package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalProjectStoreTest {
    @TempDir Path sandbox;

    static ProjectState empty(String name) {
        return new ProjectState(UUID.randomUUID(), name, Map.of(), Map.of(), Map.of(), null);
    }

    static ProjectState rename(ProjectState state, String name) {
        return new ProjectState(state.projectId(), name, state.modules(), state.documents(), state.assets(), state.origin());
    }

    static ProjectState.Entry entry(UUID id, String type, BlobRef blob, Set<UUID> docs, Set<UUID> assets) {
        return new ProjectState.Entry(id, "test:module", type, "test:opaque", 1, "中文😀", blob, docs, assets);
    }

    static ProjectState populated(LocalProjectStore store) throws IOException {
        UUID docA = UUID.randomUUID();
        UUID docB = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        BlobRef text = store.stage(BlobRef.Kind.DOCUMENT, "{\"plugin\":\"unknown\",\"n\":1.234567890123456789}".getBytes(StandardCharsets.UTF_8));
        BlobRef binary = store.stage(BlobRef.Kind.DOCUMENT, new byte[]{0, (byte) 255, (byte) 192, 7});
        BlobRef font = store.stage(BlobRef.Kind.ASSET, new byte[]{9, 8, 7, 6, 5});
        return new ProjectState(UUID.randomUUID(), "港口", Map.of("test:module", "2.3.4"),
                Map.of(docA, entry(docA, "test:map", text, Set.of(), Set.of(asset)),
                        docB, entry(docB, "thirdparty:binary", binary, Set.of(docA), Set.of(asset))),
                Map.of(asset, entry(asset, "thirdparty:font", font, Set.of(), Set.of())), null);
    }

    static void code(ProjectStorageException.Code code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ProjectStorageException.class, operation).code());
    }

    @Test void opaqueDocumentsAssetsAndStableIdentitiesSurviveReopen() throws Exception {
        Path path = sandbox.resolve("project");
        ProjectState state;
        UUID request = UUID.randomUUID();
        Map<BlobRef, byte[]> content = new HashMap<>();
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            state = populated(store);
            for (BlobRef blob : LocalProjectStore.contentReferences(state)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                store.copyContent(blob, out);
                content.put(blob, out.toByteArray());
            }
            assertEquals(state, store.commit(null, request, state).saved().state());
        }
        try (LocalProjectStore store = LocalProjectStore.open(path)) {
            assertEquals(state, store.load().state());
            assertEquals(request, store.load().commitId());
            for (var entry : content.entrySet()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                store.copyContent(entry.getKey(), out);
                assertArrayEquals(entry.getValue(), out.toByteArray());
            }
        }
    }

    @Test void retryReturnsOriginalReceiptAfterAnotherSaveAndRejectsDifferentContent() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"))) {
            ProjectState a = empty("A");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.commit(null, first, a);
            store.commit(first, second, rename(a, "B"));
            LocalProjectStore.CommitResult replay = store.commit(null, first, a);
            assertTrue(replay.replayed());
            assertEquals(first, replay.saved().commitId());
            assertEquals(second, replay.currentCommitId());
            assertEquals("B", store.load().state().name());
            code(REQUEST_MISMATCH, () -> store.commit(null, first, rename(a, "different")));
            code(REQUEST_MISMATCH, () -> store.commit(second, second, rename(a, "different")));
            assertEquals(first, store.lookup(first).saved().commitId());
        }
    }

    @Test void wrongBaselineCannotPublishEvenIdenticalState() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"))) {
            ProjectState state = empty("A");
            UUID id = UUID.randomUUID();
            store.commit(null, id, state);
            code(CONFLICT, () -> store.commit(null, UUID.randomUUID(), state));
            assertEquals(id, store.load().commitId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrReplacedCommitDirectoryRejectsPublicationWithItsCause(boolean replaceWithFile) throws Exception {
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            Path commits = path.resolve("commits");
            Files.delete(commits);
            if (replaceWithFile) Files.writeString(commits, "external replacement");
            ProjectStorageException failure = assertThrows(ProjectStorageException.class,
                    () -> store.commit(null, UUID.randomUUID(), empty("A")));
            assertEquals(INVALID_PATH, failure.code());
            assertTrue(failure.getMessage().contains(commits.toString()));
            if (replaceWithFile) {
                assertNull(failure.getCause());
                assertEquals("external replacement", Files.readString(commits));
            } else {
                assertInstanceOf(java.nio.file.NoSuchFileException.class, failure.getCause());
                assertFalse(Files.exists(commits));
            }
            assertFalse(Files.exists(path.resolve("CURRENT")));
        }
    }

    @Test void publishedSnapshotDoesNotChangeWhenCallerContinuesEditing() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"))) {
            ProjectState a = empty("A");
            ProjectState b = rename(a, "B");
            store.commit(null, UUID.randomUUID(), a);
            assertNotEquals(b, store.load().state());
            assertEquals("A", store.load().state().name());
        }
    }

    @Test void receiptWindowExpiryIsUnknownAndOldBaselineStillRejectsWrite() throws Exception {
        StoreLimits limits = new StoreLimits(4096, 1024, 4096, 10, 20, 1);
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"), limits)) {
            ProjectState a = empty("A");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.commit(null, first, a);
            store.commit(first, second, rename(a, "B"));
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, store.lookup(first).status());
            code(CONFLICT, () -> store.commit(null, first, a));
            assertEquals(second, store.load().commitId());
        }
    }

    @Test void duplicateWriterIsRejectedInSameJvmAndAnotherProcess() throws Exception {
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            store.commit(null, UUID.randomUUID(), empty("A"));
            code(BUSY, () -> LocalProjectStore.open(path));
            assertEquals(31, worker("lock", path, UUID.randomUUID(), "unused"));
        }
        try (LocalProjectStore reopened = LocalProjectStore.open(path)) {
            assertEquals("A", reopened.load().state().name());
        }
    }

    @Test void externalHeadReplacementIsNotAdoptedOrOverwritten() throws Exception {
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            ProjectState a = empty("A");
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            store.commit(null, first, a);
            byte[] old = Files.readAllBytes(path.resolve("CURRENT"));
            store.commit(first, second, rename(a, "B"));
            Files.write(path.resolve("CURRENT"), old);
            code(CONFLICT, store::load);
            code(CONFLICT, () -> store.commit(second, UUID.randomUUID(), rename(a, "C")));
            assertArrayEquals(old, Files.readAllBytes(path.resolve("CURRENT")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANIFEST_READY", "BEFORE_PUBLISH", "AFTER_PUBLISH"})
    void separateJvmHaltKeepsOneCompletePublishedSnapshot(String stage) throws Exception {
        Path path = sandbox.resolve("project");
        UUID baseline = UUID.randomUUID();
        ProjectState prior;
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            prior = populated(store);
            store.commit(null, baseline, prior);
        }
        UUID request = UUID.randomUUID();
        assertEquals(73, worker("crash", path, request, stage));
        try (LocalProjectStore store = LocalProjectStore.open(path)) {
            if (stage.equals("AFTER_PUBLISH")) {
                assertEquals(request, store.load().commitId());
                assertEquals("next", store.load().state().name());
                assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, store.lookup(request).status());
            } else {
                assertEquals(baseline, store.load().commitId());
                assertEquals("港口", store.load().state().name());
                assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, store.lookup(request).status());
            }
            LocalProjectStore.CommitResult retried = store.commit(baseline, request, rename(prior, "next"));
            assertEquals(stage.equals("AFTER_PUBLISH"), retried.replayed());
            assertEquals(request, store.load().commitId());
            assertEquals("next", store.load().state().name());
        }
    }

    @Test void failedResultAfterPublicationCanBeQueriedAndReplayedAfterReopen() throws Exception {
        Path path = sandbox.resolve("project");
        ProjectState first = empty("A");
        UUID old = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            store.commit(null, old, first);
        }
        try (LocalProjectStore store = new LocalProjectStore(path, StoreLimits.DEFAULT, false, stage -> {
            if (stage == LocalProjectStore.Stage.AFTER_PUBLISH) {
                throw new IOException("injected result delivery failure");
            }
        })) {
            code(RESULT_UNKNOWN, () -> store.commit(old, next, rename(first, "B")));
            assertEquals(LocalProjectStore.RequestStatus.PUBLISHED, store.lookup(next).status());
        }
        try (LocalProjectStore store = LocalProjectStore.open(path)) {
            assertTrue(store.commit(old, next, rename(first, "B")).replayed());
            assertEquals(next, store.load().commitId());
        }
    }

    @Test void malformedCurrentDoesNotFallBackToAnOlderOrUnpublishedManifest() throws Exception {
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            store.commit(null, UUID.randomUUID(), empty("A"));
        }
        Files.writeString(path.resolve("CURRENT"), "{broken");
        code(INVALID_FORMAT, () -> LocalProjectStore.open(path));
        assertEquals("{broken", Files.readString(path.resolve("CURRENT")));
    }

    @Test void corruptedBlobAndManifestAreDetectedWithoutRepairingOriginals() throws Exception {
        Path path = sandbox.resolve("project");
        ProjectState state;
        UUID commit = UUID.randomUUID();
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            state = populated(store);
            store.commit(null, commit, state);
        }
        BlobRef ref = state.assets().values().iterator().next().content();
        Path blob = path.resolve(ref.relativePath());
        byte[] original = Files.readAllBytes(blob);
        byte[] changed = original.clone();
        changed[0] ^= 1;
        Files.write(blob, changed);
        code(CORRUPT_CONTENT, () -> LocalProjectStore.open(path));
        assertArrayEquals(changed, Files.readAllBytes(blob));
        Files.write(blob, original);
        Path manifest = path.resolve("commits").resolve(commit + ".json");
        Files.writeString(manifest, Files.readString(manifest) + " ");
        code(CORRUPT_CONTENT, () -> LocalProjectStore.open(path));
    }

    @Test void incompleteFirstSaveIsNotSilentlyPromoted() throws Exception {
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            store.stage(BlobRef.Kind.DOCUMENT, new byte[]{1});
        }
        code(MISSING_PROJECT, () -> LocalProjectStore.open(path));
        assertFalse(Files.exists(path.resolve("CURRENT")));
    }

    @Test void missingDependencyAndMissingModuleAreRejectedWithoutPublishing() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"))) {
            UUID doc = UUID.randomUUID();
            BlobRef ref = store.stage(BlobRef.Kind.DOCUMENT, new byte[]{1});
            ProjectState.Entry entry = entry(doc, "test:map", ref, Set.of(), Set.of(UUID.randomUUID()));
            ProjectState missing = new ProjectState(UUID.randomUUID(), "A", Map.of("test:module", "1"),
                    Map.of(doc, entry), Map.of(), null);
            code(INVALID_FORMAT, () -> store.commit(null, UUID.randomUUID(), missing));
            ProjectState noModule = new ProjectState(missing.projectId(), "A", Map.of(),
                    Map.of(doc, entry(doc, "test:map", ref, Set.of(), Set.of())), Map.of(), null);
            code(INVALID_FORMAT, () -> store.commit(null, UUID.randomUUID(), noModule));
            code(MISSING_PROJECT, store::load);
        }
    }

    @Test void streamAndMetadataBudgetsDoNotChangePublishedContent() throws Exception {
        StoreLimits limits = new StoreLimits(2048, 8, 8, 4, 4, 2);
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path, limits)) {
            UUID id = UUID.randomUUID();
            ProjectState state = empty("A");
            store.commit(null, id, state);
            code(LIMIT_EXCEEDED, () -> store.stage(BlobRef.Kind.ASSET, new byte[9]));
            code(LIMIT_EXCEEDED, () -> store.commit(id, UUID.randomUUID(), rename(state, "文".repeat(4096))));
            assertEquals(id, store.load().commitId());
            try (var files = Files.list(path.resolve(".pending"))) {
                assertEquals(0, files.count());
            }
        }
    }

    @Test void projectAggregateBudgetCountsUniqueBlobVersions() throws Exception {
        StoreLimits limits = new StoreLimits(8192, 8, 8, 4, 4, 2);
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"), limits)) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            BlobRef first = store.stage(BlobRef.Kind.DOCUMENT, new byte[5]);
            BlobRef second = store.stage(BlobRef.Kind.DOCUMENT, new byte[]{1, 2, 3, 4});
            ProjectState state = new ProjectState(UUID.randomUUID(), "A", Map.of("test:module", "1"),
                    Map.of(a, entry(a, "test:a", first, Set.of(), Set.of()),
                            b, entry(b, "test:b", second, Set.of(), Set.of())), Map.of(), null);
            code(LIMIT_EXCEEDED, () -> store.commit(null, UUID.randomUUID(), state));
            ProjectState shared = new ProjectState(state.projectId(), "A", state.modules(),
                    Map.of(a, entry(a, "test:a", first, Set.of(), Set.of()),
                            b, entry(b, "test:b", first, Set.of(), Set.of())), Map.of(), null);
            assertEquals(shared, store.commit(null, UUID.randomUUID(), shared).saved().state());
        }
    }

    @Test void largerConfiguredBudgetsCannotPublishMetadataBeyondReaderStructureBudget() throws Exception {
        StoreLimits limits = new StoreLimits(32 * 1024 * 1024, 1, 1, 25000, 0, 2);
        Path path = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(path, limits)) {
            ProjectState baseline = empty("A");
            UUID saved = UUID.randomUUID();
            store.commit(null, saved, baseline);
            BlobRef ref = store.stage(BlobRef.Kind.DOCUMENT, new byte[0]);
            Map<UUID, ProjectState.Entry> documents = new HashMap<>();
            for (int i = 0; i < 22000; i++) {
                UUID id = new UUID(0, i);
                documents.put(id, entry(id, "test:map", ref, Set.of(), Set.of()));
            }
            ProjectState oversized = new ProjectState(baseline.projectId(), "B", Map.of("test:module", "1"),
                    documents, Map.of(), null);
            UUID request = UUID.randomUUID();
            code(LIMIT_EXCEEDED, () -> store.commit(saved, request, oversized));
            assertFalse(Files.exists(path.resolve("commits").resolve(request + ".json")));
            assertEquals(saved, store.load().commitId());
        }
        try (LocalProjectStore store = LocalProjectStore.open(path, limits)) {
            assertEquals("A", store.load().state().name());
        }
    }

    @Test void callerCollectionsAreCopiedAndReturnedCollectionsAreImmutable() {
        Map<String, String> modules = new HashMap<>();
        modules.put("test:module", "1");
        ProjectState state = new ProjectState(UUID.randomUUID(), "A", modules, Map.of(), Map.of(), null);
        modules.clear();
        assertEquals("1", state.modules().get("test:module"));
        assertThrows(UnsupportedOperationException.class, () -> state.modules().clear());
        assertThrows(IllegalArgumentException.class, () -> empty("\ud800"));
    }

    @Test void zeroLengthReadsCannotBypassStreamBudget() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"),
                new StoreLimits(4096, 2, 2, 4, 4, 2))) {
            InputStream input = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                @Override public int read(byte[] buffer, int offset, int length) {
                    return 0;
                }
            };
            code(LIMIT_EXCEEDED, () -> store.stage(BlobRef.Kind.DOCUMENT, input));
        }
    }

    @Test void closedWriterRejectsFurtherOperations() throws Exception {
        LocalProjectStore store = LocalProjectStore.create(sandbox.resolve("project"));
        store.close();
        store.close();
        code(CLOSED, store::load);
        code(CLOSED, () -> store.stage(BlobRef.Kind.ASSET, new byte[0]));
    }

    private static int worker(String operation, Path path, UUID request, String stage) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) {
            java = java.resolveSibling("java");
        }
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("storage.test.classpath"),
                CrashWorker.class.getName(), operation, path.toString(), request.toString(), stage)
                .redirectErrorStream(true).start();
        boolean done = process.waitFor(20, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
        }
        assertTrue(done, "child process did not terminate");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int result = process.exitValue();
        assertTrue(result == 73 || result == 31, () -> "unexpected child exit " + result + ": " + output);
        return result;
    }
}
