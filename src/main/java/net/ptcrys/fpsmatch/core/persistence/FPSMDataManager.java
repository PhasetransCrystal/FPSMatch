package net.ptcrys.fpsmatch.core.persistence;

import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMSaveDataEvent;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLLoader;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FPSMDataManager {

    private static final ExecutorService ASYNC_EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "FPSMatch Data IO");
        thread.setDaemon(true);
        return thread;
    });
    private static final Gson GSON = new Gson();

    // 注册表会被异步保存线程与主线程同时读写，用并发容器避免竞态
    private final Map<Class<?>, DataEntry<?>> registry = new ConcurrentHashMap<>();
    private final Path levelDataPath;
    private final Path globalDataPath;

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
        File file = getSaveFolder(data);
        entry.holder.getWriter(data, fileName, overwrite).accept(file);
    }

    // 异步保存数据
    public <T> CompletableFuture<Void> saveDataAsync(T data, String fileName, boolean overwrite) {
        return CompletableFuture.runAsync(() -> saveData(data, fileName, overwrite), ASYNC_EXECUTOR);
    }

    // 同步读取数据
    public <T> T readSpecificData(Class<T> clazz, String fileName) {
        DataEntry<T> entry = getEntry(clazz);
        Path dirPath = entry.holder.isGlobal() ? globalDataPath : levelDataPath;
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
        return CompletableFuture.supplyAsync(() -> readSpecificData(clazz, fileName), ASYNC_EXECUTOR);
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
        registry.values().forEach(entry -> {
            entry.holder.getReader().accept(getSaveFolder(entry));
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

    private File getSaveFolder(DataEntry<?> entry) {
        return new File(entry.holder.isGlobal() ? globalDataPath.toString() : levelDataPath.toString(), entry.folderName);
    }
}
