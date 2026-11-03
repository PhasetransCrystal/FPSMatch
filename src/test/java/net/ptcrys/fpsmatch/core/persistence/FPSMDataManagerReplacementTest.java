package net.ptcrys.fpsmatch.core.persistence;

import com.google.gson.JsonElement;
import net.ptcrys.fpsmatch.core.persistence.SaveHolderReplacementTest.Probe;
import net.ptcrys.fpsmatch.core.persistence.SaveHolderReplacementTest.Value;
import net.ptcrys.fpsmatch.core.persistence.datafixer.DataFixer;
import net.ptcrys.fpsmatch.core.persistence.storage.DocumentStore;
import net.ptcrys.fpsmatch.core.persistence.storage.FileDocumentStore;
import net.ptcrys.fpsmatch.core.persistence.storage.JdbcDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static net.ptcrys.fpsmatch.core.persistence.SaveHolderReplacementTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FPSMDataManagerReplacementTest {
    @TempDir Path directory;

    @FunctionalInterface
    private interface Scenario {
        void run(FPSMDataManager manager, DocumentStore store, Path storeFile) throws Exception;
    }

    private void bothStores(Scenario scenario) throws Exception {
        for (boolean jdbc : new boolean[] {false, true}) {
            Path root = Files.createDirectory(directory.resolve(UUID.randomUUID().toString()));
            Path storeFile = root.resolve("documents.json");
            String url = "jdbc:h2:file:" + root.resolve("database").toAbsolutePath().toString().replace('\\', '/');
            try (DocumentStore store = jdbc
                    ? new JdbcDocumentStore(() -> DriverManager.getConnection(url))
                    : new FileDocumentStore(storeFile)) {
                FPSMDataManager manager = new FPSMDataManager(root.resolve("level"), root.resolve("global"), store, true);
                try {
                    scenario.run(manager, store, jdbc ? null : storeFile);
                } finally {
                    manager.close();
                }
            }
        }
    }

    private static String namespace(boolean global) { return "definitions/" + (global ? "global" : "level") + "/maps"; }

    private static void seed(DocumentStore store, boolean global, String key, JsonElement old) {
        store.transact(transaction -> {
            transaction.put(namespace(global), key, old);
            return null;
        });
    }

    private static JsonElement stored(DocumentStore store, boolean global, String key) {
        return store.transact(transaction -> transaction.get(namespace(global), key).orElseThrow());
    }

    @Test
    void actualDefinitionsSaveValidatesAndReplacesWithoutRuntimeDecodeForLocalAndGlobalHolders() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (boolean global : new boolean[] {false, true}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                runtime.decodeFailure = new AssertionError("save constructed old runtime value");
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                SaveHolder<Value> holder = new SaveHolder.Builder<>(runtime).withValidatedReplacement(validation)
                        .isGlobal(global).withLoadHandler(old -> fail("save called load handler")).build();
                manager.registerData(Value.class, "maps", holder);
                seed(store, global, "item", envelope(1, "old"));
                manager.saveData(CURRENT, "item", false);
                assertEquals(envelope(1, "current"), stored(store, global, "item"));
                assertEquals(1, validation.decodes);
                assertEquals(0, runtime.decodes);
                assertEquals(1, runtime.encodes);
                assertFalse(Files.exists(manager.getSaveFolder(CURRENT).toPath().resolve("item.json")));
            }
        });
    }

    @Test
    void actualDefinitionsSavePreservesDefaultCustomAndNullMergeSemantics() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (int mode = 0; mode < 3; mode++) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                AtomicInteger merges = new AtomicInteger();
                SaveHolder.Builder<Value> builder = new SaveHolder.Builder<>(runtime);
                if (mode == 1) builder.withMergeHandler((old, current) -> {
                    merges.incrementAndGet();
                    assertEquals(new Value("old"), old);
                    assertSame(CURRENT, current);
                    return new Value(old.name() + "+" + current.name());
                });
                if (mode == 2) builder.withMergeHandler(null);
                manager.registerData(Value.class, "maps", builder.build());
                seed(store, false, "item", envelope(1, "old"));
                manager.saveData(CURRENT, "item", false);
                assertEquals(envelope(1, mode == 1 ? "old+current" : "current"), stored(store, false, "item"));
                assertEquals(1, runtime.decodes);
                assertEquals(1, runtime.encodes);
                assertEquals(mode == 1 ? 1 : 0, merges.get());
            }
        });
    }

    @Test
    void actualDefinitionsSaveSkipsMergerForNullDecodedOldValueAndFallsBackForNullMergeResult() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (boolean nullDecodedOld : new boolean[] {true, false}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                runtime.nullDecodedValue = nullDecodedOld;
                AtomicInteger merges = new AtomicInteger();
                SaveHolder<Value> holder = new SaveHolder.Builder<>(runtime).withMergeHandler((old, current) -> {
                    merges.incrementAndGet();
                    if (nullDecodedOld) throw new AssertionError("database must skip merger for a null decoded value");
                    assertEquals(new Value("old"), old);
                    assertSame(CURRENT, current);
                    return null;
                }).build();
                manager.registerData(Value.class, "maps", holder);
                seed(store, false, "item", envelope(1, "old"));
                manager.saveData(CURRENT, "item", false);
                assertEquals(envelope(1, "current"), stored(store, false, "item"));
                assertEquals(1, runtime.decodes);
                assertEquals(nullDecodedOld ? 0 : 1, merges.get());
                assertEquals(1, runtime.encodes);
            }
        });
    }

    @Test
    void rejectedStructureFutureVersionAndMissingFixerKeepOldValueWithoutEncoding() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (JsonElement old : new JsonElement[] {json("{\"version\":98,\"data\":{}}"),
                    envelope(99, "old"), envelope(97, "old")}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                manager.registerData(Value.class, "maps", replacement(runtime, validation, 98));
                seed(store, false, "item", old);
                byte[] before = storeFile == null ? null : Files.readAllBytes(storeFile);
                assertThrows(RuntimeException.class, () -> manager.saveData(CURRENT, "item", false));
                assertEquals(old, stored(store, false, "item"));
                if (storeFile != null) assertArrayEquals(before, Files.readAllBytes(storeFile));
                assertEquals(0, runtime.decodes);
                assertEquals(0, runtime.encodes);
                assertEquals(old.getAsJsonObject().get("version").getAsInt() == 98 ? 1 : 0, validation.decodes);
            }
        });
    }

    @Test
    void partialValidationCannotReplaceExistingDefinition() throws Exception {
        bothStores((manager, store, storeFile) -> {
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            validation.partial = new Value("partial");
            manager.registerData(Value.class, "maps", replacement(runtime, validation, 1));
            JsonElement old = envelope(1, "old");
            seed(store, false, "item", old);
            assertThrows(RuntimeException.class, () -> manager.saveData(CURRENT, "item", false));
            assertEquals(old, stored(store, false, "item"));
            assertEquals(0, runtime.decodes);
            assertEquals(0, runtime.encodes);
        });
    }

    @Test
    void actualDefinitionsSaveMigratesBeforePureValidation() throws Exception {
        bothStores((manager, store, storeFile) -> {
            Probe<Value> runtime = new Probe<>(VALUE_CODEC);
            runtime.decodeFailure = new AssertionError("runtime decode");
            Probe<Value> validation = new Probe<>(VALUE_CODEC);
            AtomicInteger migrations = new AtomicInteger();
            DataFixer.getInstance().registerJsonFixer(Value.class, 10, old -> {
                migrations.incrementAndGet();
                assertEquals("old", old.getAsJsonObject().get("legacy").getAsString());
                return json("{\"name\":\"fixed\"}");
            });
            manager.registerData(Value.class, "maps", replacement(runtime, validation, 11));
            seed(store, false, "item", json("{\"version\":10,\"data\":{\"legacy\":\"old\"}}"));
            manager.saveData(CURRENT, "item", false);
            assertEquals(envelope(11, "current"), stored(store, false, "item"));
            assertEquals(1, migrations.get());
            assertEquals(1, validation.decodes);
            assertEquals(0, runtime.decodes);
            assertEquals(1, runtime.encodes);
        });
    }

    @Test
    void validationMigrationAndEncodeExceptionsKeepIdentityAndOldDocument() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (String stage : new String[] {"validation", "migration", "encoding"}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                IllegalArgumentException failure = new IllegalArgumentException(stage);
                if (stage.equals("validation")) validation.decodeFailure = failure;
                if (stage.equals("encoding")) runtime.encodeFailure = failure;
                DataFixer.getInstance().registerJsonFixer(Value.class, 20, old -> { throw failure; });
                manager.registerData(Value.class, "maps", replacement(runtime, validation, 21));
                JsonElement old = envelope(stage.equals("migration") ? 20 : 21, "old");
                seed(store, false, "item", old);
                byte[] before = storeFile == null ? null : Files.readAllBytes(storeFile);
                assertSame(failure, assertThrows(IllegalArgumentException.class, () -> manager.saveData(CURRENT, "item", false)));
                assertEquals(old, stored(store, false, "item"));
                if (storeFile != null) assertArrayEquals(before, Files.readAllBytes(storeFile));
                assertEquals(0, runtime.decodes);
                assertEquals(stage.equals("encoding") ? 1 : 0, runtime.encodes);
                assertEquals(stage.equals("migration") ? 0 : 1, validation.decodes);
            }
        });
    }

    @Test
    void validationMigrationAndEncodingErrorsPropagateOriginalIdentity() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (String stage : new String[] {"validation", "migration", "encoding"}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                AssertionError failure = new AssertionError("stop");
                if (stage.equals("validation")) validation.decodeFailure = failure;
                if (stage.equals("encoding")) runtime.encodeFailure = failure;
                DataFixer.getInstance().registerJsonFixer(Value.class, 30, old -> { throw failure; });
                manager.registerData(Value.class, "maps", replacement(runtime, validation, 31));
                seed(store, false, "item", envelope(stage.equals("migration") ? 30 : 31, "old"));
                assertSame(failure, assertThrows(AssertionError.class, () -> manager.saveData(CURRENT, "item", false)));
                assertEquals(0, runtime.decodes);
                assertEquals(stage.equals("encoding") ? 1 : 0, runtime.encodes);
                // Error rollback is outside the JDBC contract; only identity and pre-encode ordering are asserted.
            }
        });
    }

    @Test
    void missingRecordAndOverwriteSkipValidationButStillEncodeCurrent() throws Exception {
        bothStores((manager, store, storeFile) -> {
            for (boolean overwrite : new boolean[] {false, true}) {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                validation.decodeFailure = new AssertionError("must skip old definition");
                manager.registerData(Value.class, "maps", replacement(runtime, validation, 1));
                String key = overwrite ? "existing" : "missing";
                if (overwrite) seed(store, false, key, envelope(100, "future"));
                manager.saveData(CURRENT, key, overwrite);
                assertEquals(envelope(1, "current"), stored(store, false, key));
                assertEquals(0, validation.decodes);
                assertEquals(0, runtime.decodes);
                assertEquals(1, runtime.encodes);
            }
        });
    }

    @Test
    void fileModeManagerDelegatesToRealReplacementWriter() throws Exception {
        Path level = directory.resolve("level");
        try (DocumentStore store = new FileDocumentStore(directory.resolve("documents.json"))) {
            FPSMDataManager manager = new FPSMDataManager(level, directory.resolve("global"), store, false);
            try {
                Probe<Value> runtime = new Probe<>(VALUE_CODEC);
                Probe<Value> validation = new Probe<>(VALUE_CODEC);
                manager.registerData(Value.class, "maps", replacement(runtime, validation, 1));
                Path folder = Files.createDirectories(level.resolve("maps"));
                Path file = folder.resolve("item.json");
                Files.writeString(file, envelope(1, "old").toString());
                manager.saveData(CURRENT, "item", false);
                assertEquals(envelope(1, "current"), json(Files.readString(file)));
                assertEquals(1, validation.decodes);
                assertEquals(0, runtime.decodes);
                assertEquals(1, runtime.encodes);
            } finally {
                manager.close();
            }
        }
    }
}
