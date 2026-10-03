package net.ptcrys.fpsmatch.core.persistence;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMSaveDataEvent;
import net.ptcrys.fpsmatch.core.persistence.storage.DocumentStore;
import net.ptcrys.fpsmatch.core.persistence.storage.ExecutorShutdown;
import net.ptcrys.fpsmatch.core.persistence.storage.FileDocumentStore;
import net.ptcrys.fpsmatch.core.persistence.storage.JdbcDocumentStore;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLLoader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FPSMDataManager {

    private final ExecutorService asyncExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "FPSMatch Data IO");
        thread.setDaemon(true);
        return thread;
    });
    private static final Gson GSON = new Gson();

    // 注册表会被异步保存线程与主线程同时读写，用并发容器避免竞态
    private final Map<Class<?>, DataEntry<?>> registry = new ConcurrentHashMap<>();
    private final Path levelDataPath;
    private final Path globalDataPath;
    private final DocumentStore documentStore;
    private final boolean databaseDefinitions;
    private Boolean asyncDrained;
    private volatile boolean deferStorageClose;

    // 数据条目：存储文件夹名和SaveHolder
    private static class DataEntry<T> {

        String folderName;
        SaveHolder<T> holder;

        DataEntry(String folderName, SaveHolder<T> holder) {
            this.folderName = folderName;
            this.holder = holder;
        }
    }

    public FPSMDataManager(String levelName) {
        String fixedLevelName = PersistenceUtils.fixFileName(levelName);
        this.levelDataPath = Paths.get(FMLLoader.getGamePath().toString(), "fpsmatch", fixedLevelName);
        this.globalDataPath = ConfigManager.getGlobalDataPath();
        PersistenceUtils.ensureDirectoryExists(levelDataPath);
        PersistenceUtils.ensureDirectoryExists(globalDataPath);

        String jdbcUrl = System.getProperty("fpsmatch.storage.jdbc.url", "");
        if (jdbcUrl.startsWith("jdbc:mysql:")) {
            try {
                Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException failure) {
                throw new DataPersistenceException("MySQL JDBC driver is unavailable", failure);
            }
        }
        this.documentStore = jdbcUrl.isBlank() ? new FileDocumentStore(levelDataPath.resolve("documents.json")) : new JdbcDocumentStore(() -> java.sql.DriverManager.getConnection(jdbcUrl,
                System.getProperty("fpsmatch.storage.jdbc.user", ""),
                System.getProperty("fpsmatch.storage.jdbc.password", "")));
        this.databaseDefinitions = !jdbcUrl.isBlank() && Boolean.getBoolean("fpsmatch.storage.jdbc.definitions");

        MinecraftForge.EVENT_BUS.post(new RegisterFPSMSaveDataEvent(this));
    }

    // 注册数据类型
    public <T> void registerData(Class<T> clazz, String folderName, SaveHolder<T> holder) {
        String fixedFolderName = PersistenceUtils.fixFileName(folderName);
        holder.setHolderClass(clazz);
        registry.put(clazz, new DataEntry<>(fixedFolderName, holder));
    }

    // 同步保存数据
    @SuppressWarnings("unchecked")
    public <T> void saveData(T data, String fileName, boolean overwrite) {
        if (!registry.containsKey(data.getClass())) return;
        DataEntry<T> entry = getEntry((Class<T>) data.getClass());
        if (databaseDefinitions) {
            documentStore.transact(transaction -> {
                String namespace = namespace(entry);
                String key = PersistenceUtils.fixFileName(fileName);
                T merged = overwrite ? data : transaction.get(namespace, key)
                        .map(entry.holder::decodeFromJson).map(old -> entry.holder.mergeHandler(old, data)).orElse(data);
                transaction.put(namespace, key, entry.holder.encodeToJson(merged));
                return null;
            });
            return;
        }
        File file = getSaveFolder(data);
        entry.holder.getWriter(data, fileName, overwrite).accept(file);
    }

    /** Writes editor-managed definitions without swallowing disk failures. */
    @SuppressWarnings("unchecked")
    public <T> void saveDataAtomic(T data, String fileName) {
        DataEntry<T> entry = getEntry((Class<T>) data.getClass());
        if (databaseDefinitions) {
            saveData(data, fileName, true);
            return;
        }
        Path folder = getSaveFolder(entry).toPath();
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            Path destination = folder.resolve(PersistenceUtils.fixFileName(fileName) + "." + entry.holder.getFileType());
            temporary = Files.createTempFile(folder, "definition-", ".tmp");
            Files.writeString(temporary, GSON.toJson(entry.holder.encodeToJson(data)));
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new DataPersistenceException("Failed to save definition: " + fileName, failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {}
            }
        }
    }

    public <T> void deleteData(Class<T> type, String fileName) {
        DataEntry<T> entry = getEntry(type);
        if (databaseDefinitions) {
            documentStore.transact(transaction -> {
                transaction.delete(namespace(entry), PersistenceUtils.fixFileName(fileName));
                return null;
            });
            return;
        }
        Path destination = getSaveFolder(entry).toPath().resolve(PersistenceUtils.fixFileName(fileName) + "." + entry.holder.getFileType());
        try {
            Files.deleteIfExists(destination);
        } catch (IOException failure) {
            throw new DataPersistenceException("Failed to delete definition: " + fileName, failure);
        }
    }

    // 异步保存数据
    public <T> CompletableFuture<Void> saveDataAsync(T data, String fileName, boolean overwrite) {
        return CompletableFuture.runAsync(() -> saveData(data, fileName, overwrite), asyncExecutor);
    }

    // 同步读取数据
    public <T> T readSpecificData(Class<T> clazz, String fileName) {
        DataEntry<T> entry = getEntry(clazz);
        if (databaseDefinitions) {
            return documentStore.transact(transaction -> transaction.get(namespace(entry), PersistenceUtils.fixFileName(fileName))
                    .map(entry.holder::decodeFromJson).orElseThrow(() -> new DataPersistenceException("Missing data: " + fileName)));
        }
        Path dirPath = getSaveFolder(entry).toPath();
        Path filePath = dirPath.resolve(PersistenceUtils.fixFileName(fileName) + "." + entry.holder.getFileType());
        try {
            String content = Files.readString(filePath);
            JsonElement element = GSON.fromJson(content, JsonElement.class);
            return entry.holder.decodeFromJson(element);
        } catch (Exception e) {
            throw new DataPersistenceException("Failed to read data: " + clazz.getName(), e);
        }
    }

    // 异步读取数据
    public <T> CompletableFuture<T> readSpecificDataAsync(Class<T> clazz, String fileName) {
        return CompletableFuture.supplyAsync(() -> readSpecificData(clazz, fileName), asyncExecutor);
    }

    // 获取数据条目
    @SuppressWarnings("unchecked")
    private <T> DataEntry<T> getEntry(Class<T> clazz) {
        DataEntry<?> entry = registry.get(clazz);
        if (entry == null) {
            throw new DataPersistenceException("Data type not registered: " + clazz.getName());
        }
        return (DataEntry<T>) entry;
    }

    public void readAllData() {
        registry.values().stream().sorted(java.util.Comparator.comparingInt((DataEntry<?> entry) -> entry.holder.getLoadPriority()).reversed()).forEach(entry -> {
            PersistenceUtils.ensureDirectoryExists(getSaveFolder(entry).toPath());
            if (databaseDefinitions) {
                readDocuments(entry);
            } else {
                entry.holder.getReader().accept(getSaveFolder(entry));
            }
        });
    }

    public void saveAllData() {
        registry.values().forEach(entry -> {
            entry.holder.writeHandler().accept(this);
        });
    }

    public <T> File getSaveFolder(T savedData) {
        DataEntry<?> entry = getEntry(savedData.getClass());
        return new File(entry.holder.isGlobal() ? globalDataPath.toString() : levelDataPath.toString(), entry.folderName);
    }

    public Path getLevelDataPath() {
        return levelDataPath;
    }

    public DocumentStore getDocumentStore() {
        return documentStore;
    }

    private String namespace(DataEntry<?> entry) {
        return "definitions/" + (entry.holder.isGlobal() ? "global" : levelDataPath.getFileName()) + "/" + entry.folderName;
    }

    private <T> void readDocuments(DataEntry<T> entry) {
        Map<String, JsonElement> documents = documentStore.transact(transaction -> transaction.list(namespace(entry)));
        documents.values().forEach(json -> entry.holder.readHandler().accept(entry.holder.decodeFromJson(json)));
    }

    public void close() {
        if (drainAsyncOperations() && asyncExecutor.isTerminated() && !deferStorageClose) {
            documentStore.close();
        } else {
            FPSMatch.LOGGER.error("Persistence worker is still running; skipping storage close to avoid interrupting a transaction");
        }
    }

    public synchronized boolean drainAsyncOperations() {
        if (asyncDrained == null) {
            asyncDrained = ExecutorShutdown.drain(asyncExecutor, 30000, 5000, message -> FPSMatch.LOGGER.error("Persistence shutdown: {}", message));
        }
        return asyncDrained;
    }

    public void deferStorageClose() {
        deferStorageClose = true;
    }

    private File getSaveFolder(DataEntry<?> entry) {
        return new File(entry.holder.isGlobal() ? globalDataPath.toString() : levelDataPath.toString(), entry.folderName);
    }
}
