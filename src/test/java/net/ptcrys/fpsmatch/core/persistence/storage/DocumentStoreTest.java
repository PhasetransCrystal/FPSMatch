package net.ptcrys.fpsmatch.core.persistence.storage;

import net.ptcrys.fpsmatch.core.persistence.DataPersistenceException;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DocumentStoreTest {

    @TempDir
    Path directory;

    private List<DocumentStore> stores() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        return List.of(new FileDocumentStore(directory.resolve("documents.json")), new JdbcDocumentStore(() -> DriverManager.getConnection(url)));
    }

    @Test
    void namespaceIsolationAndRollbackMatchAcrossBackends() {
        for (DocumentStore store : stores()) {
            store.transact(transaction -> {
                transaction.put("first", "item", new JsonPrimitive("原始数据"));
                transaction.put("second", "item", new JsonPrimitive(2));
                return null;
            });
            assertThrows(IllegalArgumentException.class, () -> store.transact(transaction -> {
                transaction.put("first", "item", new JsonPrimitive("changed"));
                transaction.delete("second", "item");
                throw new IllegalArgumentException("abort");
            }));
            store.transact(transaction -> {
                assertEquals("原始数据", transaction.get("first", "item").orElseThrow().getAsString());
                assertEquals(2, transaction.get("second", "item").orElseThrow().getAsInt());
                assertEquals(1, transaction.list("first").size());
                assertTrue(transaction.get("first", "missing").isEmpty());
                return null;
            });
        }
    }

    @Test
    void concurrentTransactionsDoNotLoseUpdates() throws Exception {
        for (DocumentStore store : stores()) {
            var workers = Executors.newFixedThreadPool(4);
            try {
                List<java.util.concurrent.Future<?>> operations = new ArrayList<>();
                for (int index = 0; index < 40; index++) {
                    operations.add(workers.submit(() -> store.transact(transaction -> {
                        int count = transaction.get("counter", "count").map(value -> value.getAsInt()).orElse(0);
                        transaction.put("counter", "count", new JsonPrimitive(count + 1));
                        return null;
                    })));
                }
                for (var operation : operations) operation.get();
                int total = store.transact(transaction -> transaction.get("counter", "count").orElseThrow().getAsInt());
                assertEquals(40, total);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    void fileStorePersistsAcrossInstancesAndNeverReplacesCorruptData() throws Exception {
        Path file = directory.resolve("state.json");
        new FileDocumentStore(file).transact(transaction -> {
            transaction.put("inventory", "player", new JsonPrimitive("保存"));
            return null;
        });
        String restored = new FileDocumentStore(file).transact(transaction -> transaction.get("inventory", "player").orElseThrow().getAsString());
        assertEquals("保存", restored);
        Files.writeString(file, "corrupt");
        assertThrows(DataPersistenceException.class, () -> new FileDocumentStore(file).transact(transaction -> {
            transaction.put("inventory", "player", new JsonPrimitive("reset"));
            return null;
        }));
        assertEquals("corrupt", Files.readString(file));
    }

    @Test
    void futureSchemaIsRejected() throws Exception {
        Path file = directory.resolve("future.json");
        Files.writeString(file, "{\"version\":2,\"documents\":{}}");
        assertThrows(DataPersistenceException.class, () -> new FileDocumentStore(file).transact(transaction -> null));
    }

    @Test
    void scopesIsolateWorldsAndMigrationRequiresEmptyDestination() {
        DocumentStore shared = stores().get(1);
        DocumentStore first = new ScopedDocumentStore(shared, "world/first");
        DocumentStore second = new ScopedDocumentStore(shared, "world/second");
        first.transact(transaction -> {
            transaction.put("inventory", "player", new JsonPrimitive(50));
            return null;
        });
        boolean empty = second.transact(transaction -> transaction.get("inventory", "player").isEmpty());
        assertTrue(empty);
        assertEquals(1, DocumentMigration.copyIntoEmpty(first, second, List.of("inventory")));
        int migrated = second.transact(transaction -> transaction.get("inventory", "player").orElseThrow().getAsInt());
        assertEquals(50, migrated);
        assertThrows(DataPersistenceException.class, () -> DocumentMigration.copyIntoEmpty(first, second, List.of("inventory")));
    }

    @Test
    void distinctUnicodeAndCaseSensitiveKeysRemainDistinct() {
        for (DocumentStore store : stores()) {
            store.transact(transaction -> {
                transaction.put("definitions", "Bar", new JsonPrimitive(1));
                transaction.put("definitions", "Bär", new JsonPrimitive(2));
                transaction.put("definitions", "bar", new JsonPrimitive(3));
                assertEquals(3, transaction.list("definitions").size());
                return null;
            });
        }
    }
}
