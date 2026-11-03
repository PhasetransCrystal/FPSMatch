package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.code;
import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.entry;
import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.populated;
import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectSnapshotReadTest {
    @TempDir Path sandbox;

    private record Versions(SavedProject first, SavedProject second, ProjectSnapshot captured,
                            Map<BlobRef, byte[]> firstBytes) {
    }

    private static Versions publishTwoVersions(LocalProjectStore store) throws IOException {
        ProjectState first = populated(store);
        SavedProject savedFirst = store.commit(null, UUID.randomUUID(), first).saved();
        ProjectSnapshot captured = store.captureSnapshot();
        Map<BlobRef, byte[]> firstBytes = new HashMap<>();
        for (BlobRef ref : LocalProjectStore.contentReferences(first)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            store.copyContent(ref, output);
            firstBytes.put(ref, output.toByteArray());
        }
        UUID document = first.documents().keySet().iterator().next();
        BlobRef rewritten = store.stage(BlobRef.Kind.DOCUMENT, "B document".getBytes(StandardCharsets.UTF_8));
        ProjectState second = new ProjectState(first.projectId(), "B", Map.of("test:module", "3.0.0"),
                Map.of(document, entry(document, "test:map", rewritten, Set.of(), Set.of())), Map.of(), null);
        SavedProject savedSecond = store.commit(savedFirst.commitId(), UUID.randomUUID(), second).saved();
        return new Versions(savedFirst, savedSecond, captured, firstBytes);
    }

    @Test void capturedVersionKeepsItsFullGraphAndBytesAfterLaterSave() throws Exception {
        Path project = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            Map<String, byte[]> disk = diskContents(project);
            ProjectSnapshot capturedB = store.captureSnapshot();
            for (int attempt = 0; attempt < 2; attempt++) {
                SavedProject fixed = store.load(versions.captured());
                assertEquals(versions.first(), fixed);
                assertEquals(2, fixed.state().documents().size());
                UUID asset = fixed.state().assets().keySet().iterator().next();
                assertTrue(fixed.state().documents().values().stream()
                        .allMatch(document -> document.assetDependencies().contains(asset)));
                assertTrue(fixed.state().documents().values().stream()
                        .anyMatch(document -> !document.documentDependencies().isEmpty()));
                for (Map.Entry<BlobRef, byte[]> content : versions.firstBytes().entrySet()) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    store.copyContent(content.getKey(), output);
                    assertArrayEquals(content.getValue(), output.toByteArray());
                }
                assertEquals(versions.second(), store.load());
                assertEquals(versions.second(), store.load(capturedB));
                assertTrue(store.load().state().assets().isEmpty());
            }
            assertDiskContents(disk, project);
        }
    }

    @Test void identicalSavedProjectInAnotherStoreCannotConsumeTheCapability() throws Exception {
        try (LocalProjectStore issuer = LocalProjectStore.create(sandbox.resolve("issuer"));
             LocalProjectStore copy = LocalProjectStore.create(sandbox.resolve("copy"))) {
            Versions versions = publishTwoVersions(issuer);
            for (Map.Entry<BlobRef, byte[]> content : versions.firstBytes().entrySet()) {
                assertEquals(content.getKey(), copy.stage(content.getKey().kind(), content.getValue()));
            }
            SavedProject first = versions.first();
            assertEquals(first, copy.commit(null, first.commitId(), first.state()).saved());
            ProjectSnapshot copyCapture = copy.captureSnapshot();
            code(REQUEST_MISMATCH, () -> copy.load(versions.captured()));
            code(REQUEST_MISMATCH, () -> issuer.load(copyCapture));
            assertEquals(first, copy.load(copyCapture));
            assertEquals(first, issuer.load(versions.captured()));
        }
    }

    @Test void reopenedPathCannotUseTheClosedIssuersCapability() throws Exception {
        Path project = sandbox.resolve("project");
        LocalProjectStore issuer = LocalProjectStore.create(project);
        Versions versions;
        try (issuer) {
            versions = publishTwoVersions(issuer);
        }
        code(CLOSED, () -> issuer.load(versions.captured()));
        code(CLOSED, () -> issuer.load((ProjectSnapshot) null));
        try (LocalProjectStore reopened = LocalProjectStore.open(project)) {
            code(REQUEST_MISMATCH, () -> reopened.load(versions.captured()));
            assertEquals(versions.second(), reopened.load(reopened.captureSnapshot()));
        }
    }

    @Test void nullAndUnpublishedContentDoNotProvideACapability() throws Exception {
        Path project = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.stage(BlobRef.Kind.DOCUMENT, new byte[]{1, 2, 3});
            Map<String, byte[]> staged = diskContents(project);
            code(MISSING_PROJECT, store::captureSnapshot);
            assertThrows(NullPointerException.class, () -> store.load((ProjectSnapshot) null));
            assertFalse(Files.exists(project.resolve("CURRENT")));
            assertDiskContents(staged, project);
            Versions versions = publishTwoVersions(store);
            assertThrows(NullPointerException.class, () -> store.load((ProjectSnapshot) null));
            assertEquals(versions.first(), store.load(versions.captured()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void damagedOldManifestIsRefusedWhileCurrentVersionStaysHealthy(boolean missing) throws Exception {
        Path project = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            Path manifest = project.resolve("commits").resolve(versions.first().commitId() + ".json");
            if (missing) {
                Files.delete(manifest);
            } else {
                Files.writeString(manifest, Files.readString(manifest) + " ");
            }
            Map<String, byte[]> damaged = diskContents(project);
            code(missing ? INVALID_PATH : CORRUPT_CONTENT, () -> store.load(versions.captured()));
            assertEquals(versions.second(), store.load());
            assertEquals(versions.second(), store.load(store.captureSnapshot()));
            assertDiskContents(damaged, project);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void damagedOldAssetIsRefusedWhileCurrentVersionStaysHealthy(boolean missing) throws Exception {
        Path project = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            BlobRef oldAsset = versions.first().state().assets().values().iterator().next().content();
            Path blob = project.resolve(oldAsset.relativePath());
            if (missing) {
                Files.delete(blob);
            } else {
                byte[] bytes = Files.readAllBytes(blob);
                bytes[0] ^= 1;
                Files.write(blob, bytes);
            }
            Map<String, byte[]> damaged = diskContents(project);
            code(missing ? INVALID_PATH : CORRUPT_CONTENT, () -> store.load(versions.captured()));
            assertEquals(versions.second(), store.load());
            assertEquals(versions.second(), store.load(store.captureSnapshot()));
            assertDiskContents(damaged, project);
        }
    }

    @Test void validExternalHeadChangeIsRefusedWithoutAdoptingOrRewritingIt() throws Exception {
        Path project = sandbox.resolve("project");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState first = populated(store);
            SavedProject savedFirst = store.commit(null, UUID.randomUUID(), first).saved();
            ProjectSnapshot captured = store.captureSnapshot();
            byte[] firstHead = Files.readAllBytes(project.resolve("CURRENT"));
            ProjectState second = LocalProjectStoreTest.rename(first, "B");
            store.commit(savedFirst.commitId(), UUID.randomUUID(), second);
            Files.write(project.resolve("CURRENT"), firstHead);
            Map<String, byte[]> changed = diskContents(project);
            code(CONFLICT, () -> store.load(captured));
            code(CONFLICT, store::load);
            assertDiskContents(changed, project);
        }
        // A valid old entry remains readable after an explicit reopen; refusal did not damage it.
        try (LocalProjectStore reopened = LocalProjectStore.open(project)) {
            assertEquals("港口", reopened.load().state().name());
        }
    }

    private static Map<String, byte[]> diskContents(Path project) throws IOException {
        Map<String, byte[]> contents = new TreeMap<>();
        try (var paths = Files.walk(project)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().equals(".editor.lock")) {
                    contents.put(project.relativize(path).toString(), Files.readAllBytes(path));
                }
            }
        }
        return contents;
    }

    private static void assertDiskContents(Map<String, byte[]> expected, Path project) throws IOException {
        Map<String, byte[]> actual = diskContents(project);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }
}
