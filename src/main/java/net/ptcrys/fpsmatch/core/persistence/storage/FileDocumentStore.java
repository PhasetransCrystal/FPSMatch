package net.ptcrys.fpsmatch.core.persistence.storage;

import net.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class FileDocumentStore implements DocumentStore {

    private static final Gson GSON = new Gson();
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path file;
    private final Path lockFile;

    public FileDocumentStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
    }

    @Override
    public <T> T transact(Function<Transaction, T> operation) {
        synchronized (LOCKS.computeIfAbsent(file, ignored -> new Object())) {
            try {
                Files.createDirectories(file.getParent());
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                        var lock = channel.lock()) {
                    JsonObject root;
                    if (Files.exists(file)) {
                        root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                        if (!root.has("version") || root.get("version").getAsInt() != 1 || !root.has("documents")) {
                            throw new DataPersistenceException("Unsupported document storage format: " + file);
                        }
                    } else {
                        root = new JsonObject();
                        root.addProperty("version", 1);
                        root.add("documents", new JsonObject());
                    }
                    JsonDocumentTransaction transaction = new JsonDocumentTransaction(root.getAsJsonObject("documents"));
                    T result = operation.apply(transaction);
                    if (transaction.dirty()) write(root);
                    return result;
                }
            } catch (IOException | com.google.gson.JsonParseException | IllegalStateException failure) {
                throw new DataPersistenceException("Document transaction failed: " + file, failure);
            }
        }
    }

    private void write(JsonObject root) throws IOException {
        Path temporary = Files.createTempFile(file.getParent(), "documents-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(GSON.toJson(root));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
