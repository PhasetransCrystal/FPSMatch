package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectArchiveTest {
    @TempDir Path sandbox;

    private Path createShare() throws Exception {
        Path archive = sandbox.resolve("project.zip");
        Path source = sandbox.resolve("source");
        try (LocalProjectStore store = LocalProjectStore.create(source)) {
            ProjectState state = LocalProjectStoreTest.populated(store);
            UUID first = UUID.randomUUID();
            store.commit(null, first, state);
            store.commit(first, UUID.randomUUID(), LocalProjectStoreTest.rename(state, "current"));
            Files.writeString(source.resolve(".editor-personal"), "private settings");
            store.exportSnapshot(archive);
        }
        return archive;
    }

    @Test void shareIncludesOnlySelectedSnapshotAndDependenciesAndImportsAsSeparateCopy() throws Exception {
        Path archive = createShare();
        SavedProject source;
        try (LocalProjectStore store = LocalProjectStore.open(sandbox.resolve("source"))) {
            source = store.load();
        }
        Map<String, byte[]> contents = contents(archive);
        assertEquals(LocalProjectStore.contentReferences(source.state()).size() + 1, contents.size());
        assertTrue(contents.containsKey("snapshot.json"));
        assertFalse(contents.containsKey("CURRENT"));
        assertFalse(contents.containsKey(".editor-personal"));
        SavedProject imported = LocalProjectStore.importSnapshot(archive, sandbox.resolve("copy"));
        assertEquals(source.state().projectId(), imported.state().projectId());
        assertEquals(source.state().documents(), imported.state().documents());
        assertEquals(source.state().assets(), imported.state().assets());
        assertEquals(source.state().modules(), imported.state().modules());
        assertNotEquals(source.commitId(), imported.commitId());
        assertNull(imported.parentCommitId());
        assertEquals(new ProjectState.Origin(source.state().projectId(), source.commitId()), imported.state().origin());
        try (LocalProjectStore original = LocalProjectStore.open(sandbox.resolve("source"));
             LocalProjectStore copy = LocalProjectStore.open(sandbox.resolve("copy"))) {
            assertEquals(source, original.load());
            assertEquals(imported, copy.load());
            assertEquals(LocalProjectStore.RequestStatus.UNKNOWN, copy.lookup(source.commitId()).status());
        }
    }

    @Test void importingNeverOverwritesAnExistingProjectDirectory() throws Exception {
        Path archive = createShare();
        Path target = sandbox.resolve("existing");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "keep");
        assertThrows(FileAlreadyExistsException.class, () -> LocalProjectStore.importSnapshot(archive, target));
        assertEquals("keep", Files.readString(target.resolve("keep.txt")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside.txt", "C:/outside.txt", "assets/../outside.txt", "assets\\outside", "/absolute", "assets/"})
    void unexpectedEntryPathsAreRejectedBeforeDestinationCreation(String name) throws Exception {
        Path archive = sandbox.resolve("bad.zip");
        writeZip(archive, Map.of(name, new byte[]{1}));
        Path target = sandbox.resolve("copy");
        LocalProjectStoreTest.code(INVALID_PATH, () -> LocalProjectStore.importSnapshot(archive, target));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(sandbox.resolve("outside.txt")));
    }

    @Test void missingOrUndeclaredBlobDoesNotProduceAProject() throws Exception {
        Path archive = createShare();
        Map<String, byte[]> contents = contents(archive);
        String blob = contents.keySet().stream().filter(path -> path.endsWith(".blob")).findFirst().orElseThrow();
        byte[] removed = contents.remove(blob);
        Path missing = sandbox.resolve("missing.zip");
        writeZip(missing, contents);
        Path target = sandbox.resolve("missing-copy");
        LocalProjectStoreTest.code(CORRUPT_CONTENT, () -> LocalProjectStore.importSnapshot(missing, target));
        assertFalse(Files.exists(target));
        contents.put(blob, removed);
        contents.put("assets/" + "0".repeat(64) + ".blob", new byte[0]);
        Path extra = sandbox.resolve("extra.zip");
        writeZip(extra, contents);
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> LocalProjectStore.importSnapshot(extra, sandbox.resolve("extra-copy")));
        assertFalse(Files.exists(sandbox.resolve("extra-copy")));
    }

    @Test void changedBlobBytesCannotPublishAnImportedCurrent() throws Exception {
        Path archive = createShare();
        Map<String, byte[]> contents = contents(archive);
        String blob = contents.keySet().stream().filter(path -> path.endsWith(".blob")).findFirst().orElseThrow();
        contents.get(blob)[0] ^= 1;
        Path changed = sandbox.resolve("changed.zip");
        writeZip(changed, contents);
        Path target = sandbox.resolve("copy");
        LocalProjectStoreTest.code(CORRUPT_CONTENT, () -> LocalProjectStore.importSnapshot(changed, target));
        assertFalse(Files.exists(target.resolve("CURRENT")));
        assertTrue(Files.exists(archive));
        try (LocalProjectStore source = LocalProjectStore.open(sandbox.resolve("source"))) {
            assertEquals("current", source.load().state().name());
        }
    }

    @Test void duplicateZipEntryIsRejected() throws Exception {
        Path zip = sandbox.resolve("duplicates.zip");
        byte[] metadata = StoreJson.manifest(new SavedProject(UUID.randomUUID(), null,
                LocalProjectStoreTest.empty("A")), 4096);
        writeZip(zip, Map.of("snapshot.json", metadata, "snapshoX.json", metadata));
        byte[] bytes = Files.readAllBytes(zip);
        byte[] needle = "snapshoX.json".getBytes(StandardCharsets.US_ASCII);
        byte[] replacement = "snapshot.json".getBytes(StandardCharsets.US_ASCII);
        int replaced = 0;
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            if (java.util.Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                System.arraycopy(replacement, 0, bytes, i, replacement.length);
                replaced++;
            }
        }
        assertEquals(2, replaced);
        Files.write(zip, bytes);
        LocalProjectStoreTest.code(INVALID_FORMAT, () -> LocalProjectStore.importSnapshot(zip, sandbox.resolve("copy")));
        assertFalse(Files.exists(sandbox.resolve("copy")));
    }

    @Test void centralDirectorySizeCannotHideAdditionalExpandedContent() throws Exception {
        Path original = createShare();
        Map<String, byte[]> contents = contents(original);
        String blob = contents.keySet().stream().filter(path -> path.endsWith(".blob")).findFirst().orElseThrow();
        int expectedSize = contents.get(blob).length;
        contents.put(blob, java.util.Arrays.copyOf(contents.get(blob), expectedSize + 10));
        Path archive = sandbox.resolve("false-size.zip");
        writeZip(archive, contents);
        byte[] bytes = Files.readAllBytes(archive);
        java.nio.ByteBuffer directory = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int patched = 0;
        for (int i = 0; i <= bytes.length - 46; i++) {
            if (directory.getInt(i) == 0x02014b50) {
                int nameLength = Short.toUnsignedInt(directory.getShort(i + 28));
                if (i + 46 + nameLength <= bytes.length
                        && new String(bytes, i + 46, nameLength, StandardCharsets.UTF_8).equals(blob)) {
                    directory.putInt(i + 24, expectedSize);
                    patched++;
                }
            }
        }
        assertEquals(1, patched, "the archive must actually declare a false size");
        Files.write(archive, bytes);
        Path target = sandbox.resolve("copy");
        LocalProjectStoreTest.code(LIMIT_EXCEEDED, () -> LocalProjectStore.importSnapshot(archive, target));
        assertFalse(Files.exists(target.resolve("CURRENT")));
    }

    @Test void expandedPackageBudgetIsCheckedBeforeDestinationCreation() throws Exception {
        Path archive = createShare();
        StoreLimits limits = new StoreLimits(4096, 1, 1, 10, 10, 2);
        LocalProjectStoreTest.code(LIMIT_EXCEEDED,
                () -> LocalProjectStore.importSnapshot(archive, sandbox.resolve("copy"), limits));
        assertFalse(Files.exists(sandbox.resolve("copy")));
    }

    @Test void shareExportCannotOverwriteCurrentOrAnExistingFile() throws Exception {
        Path path = sandbox.resolve("source");
        try (LocalProjectStore store = LocalProjectStore.create(path)) {
            UUID request = UUID.randomUUID();
            store.commit(null, request, LocalProjectStoreTest.empty("A"));
            LocalProjectStoreTest.code(INVALID_PATH, () -> store.exportSnapshot(path.resolve("CURRENT")));
            Path existing = sandbox.resolve("existing.zip");
            Files.writeString(existing, "keep");
            assertThrows(FileAlreadyExistsException.class, () -> store.exportSnapshot(existing));
            assertEquals("keep", Files.readString(existing));
            assertEquals(request, store.load().commitId());
        }
    }

    @Test void competingSharePublicationsCannotReplaceTheFirstCompleteOutput() throws Exception {
        Path first = sandbox.resolve("first.tmp");
        Path second = sandbox.resolve("second.tmp");
        Path output = sandbox.resolve("share.zip");
        Files.writeString(first, "first complete package");
        Files.writeString(second, "second complete package");
        var start = new java.util.concurrent.CyclicBarrier(2);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> publishCompetitor(start, first, output));
            var b = executor.submit(() -> publishCompetitor(start, second, output));
            boolean firstWon = a.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean secondWon = b.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon, "exactly one publication must succeed");
            assertEquals(Files.readString(firstWon ? first : second), Files.readString(output));
            Files.delete(first);
            Files.delete(second);
            assertEquals(firstWon ? "first complete package" : "second complete package", Files.readString(output));
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean publishCompetitor(java.util.concurrent.CyclicBarrier start, Path source, Path output)
            throws Exception {
        start.await(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            ProjectArchive.publishNew(source, output);
            return true;
        } catch (FileAlreadyExistsException expected) {
            return false;
        }
    }

    private static Map<String, byte[]> contents(Path archive) throws IOException {
        Map<String, byte[]> result = new HashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (var input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return result;
    }

    private static void writeZip(Path path, Map<String, byte[]> contents) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (var entry : contents.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
    }
}
