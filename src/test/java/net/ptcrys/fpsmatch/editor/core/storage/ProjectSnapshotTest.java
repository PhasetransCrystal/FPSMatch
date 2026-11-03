package net.ptcrys.fpsmatch.editor.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.code;
import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.entry;
import static net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStoreTest.populated;
import static net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixed-snapshot sharing: a capability issued for one published version keeps exporting that exact
 * version after the project moved on, and only for the live instance that issued it.
 */
class ProjectSnapshotTest {
    @TempDir Path sandbox;

    /** Version A, the capability captured for A, and the later version B the store moved on to. */
    private record Versions(ProjectState first, ProjectState second, UUID firstRequest, UUID secondRequest,
                            ProjectSnapshot captured) {
    }

    /** Publishes A, captures A, then publishes B: renamed project, rewritten document, A's asset dropped. */
    private static Versions publishTwoVersions(LocalProjectStore store) throws IOException {
        UUID documentA = UUID.randomUUID();
        UUID documentB = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        BlobRef text = store.stage(BlobRef.Kind.DOCUMENT, "A 文档".getBytes(StandardCharsets.UTF_8));
        BlobRef binary = store.stage(BlobRef.Kind.DOCUMENT, new byte[]{0, (byte) 255, (byte) 192, 7});
        BlobRef font = store.stage(BlobRef.Kind.ASSET, new byte[]{9, 8, 7, 6, 5});
        ProjectState first = new ProjectState(UUID.randomUUID(), "A 港口", Map.of("test:module", "2.3.4"),
                Map.of(documentA, entry(documentA, "test:map", text, Set.of(), Set.of(asset)),
                        documentB, entry(documentB, "thirdparty:binary", binary, Set.of(documentA), Set.of(asset))),
                Map.of(asset, entry(asset, "thirdparty:font", font, Set.of(), Set.of())), null);
        UUID firstRequest = UUID.randomUUID();
        SavedProject savedFirst = store.commit(null, firstRequest, first).saved();
        ProjectSnapshot captured = store.captureSnapshot();
        assertEquals(savedFirst, captured.saved());
        assertEquals(first, captured.saved().state());

        BlobRef rewritten = store.stage(BlobRef.Kind.DOCUMENT, "B 文档".getBytes(StandardCharsets.UTF_8));
        ProjectState second = new ProjectState(first.projectId(), "B 港口", first.modules(),
                Map.of(documentA, entry(documentA, "test:map", rewritten, Set.of(), Set.of()),
                        documentB, entry(documentB, "thirdparty:binary", binary, Set.of(documentA), Set.of())),
                Map.of(), null);
        UUID secondRequest = UUID.randomUUID();
        assertEquals(second, store.commit(firstRequest, secondRequest, second).saved().state());
        return new Versions(first, second, firstRequest, secondRequest, captured);
    }

    @Test void capabilityKeepsExportingItsOwnVersionAfterTheProjectMovedOn() throws Exception {
        Path project = sandbox.resolve("project");
        Path fixed = sandbox.resolve("fixed.zip");
        Path current = sandbox.resolve("current.zip");
        Versions versions;
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            versions = publishTwoVersions(store);
            store.exportSnapshot(fixed, versions.captured());
            store.exportSnapshot(current);
            Path repeated = sandbox.resolve("fixed-again.zip");
            store.exportSnapshot(repeated, versions.captured());
            assertSameContents(fixed, repeated);
        }
        // The fixed export holds A only: its own state, its own bytes, no rewritten B content.
        Set<String> expected = new TreeSet<>();
        expected.add("snapshot.json");
        for (BlobRef ref : LocalProjectStore.contentReferences(versions.first())) {
            expected.add(ref.relativePath());
        }
        assertEquals(expected, contents(fixed).keySet());
        BlobRef assetOnlyInA = versions.first().assets().values().iterator().next().content();
        assertFalse(contents(current).containsKey(assetOnlyInA.relativePath()));

        Path copyOfA = sandbox.resolve("imported-a");
        SavedProject importedA = LocalProjectStore.importSnapshot(fixed, copyOfA);
        assertEquals(versions.first().name(), importedA.state().name());
        assertEquals(versions.first().documents(), importedA.state().documents());
        assertEquals(versions.first().assets(), importedA.state().assets());
        assertEquals(versions.first().modules(), importedA.state().modules());
        assertEquals(new ProjectState.Origin(versions.first().projectId(), versions.firstRequest()),
                importedA.state().origin());
        try (LocalProjectStore copy = LocalProjectStore.open(copyOfA)) {
            assertEquals(importedA, copy.load());
        }

        Path copyOfB = sandbox.resolve("imported-b");
        SavedProject importedB = LocalProjectStore.importSnapshot(current, copyOfB);
        assertEquals(versions.second().name(), importedB.state().name());
        assertEquals(versions.second().documents(), importedB.state().documents());
        assertTrue(importedB.state().assets().isEmpty());
        assertEquals(new ProjectState.Origin(versions.second().projectId(), versions.secondRequest()),
                importedB.state().origin());
    }

    @Test void eachCapabilityIsRepeatableAndIndependentOfLaterQueries() throws Exception {
        Path project = sandbox.resolve("project");
        Path targetA = sandbox.resolve("a-1.zip");
        Path targetA2 = sandbox.resolve("a-2.zip");
        Path targetB = sandbox.resolve("b-1.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            ProjectSnapshot capabilityA = versions.captured();
            ProjectSnapshot capabilityB = store.captureSnapshot();
            assertEquals(versions.second(), capabilityB.saved().state());
            store.exportSnapshot(targetA, capabilityA);
            store.exportSnapshot(targetB, capabilityB);
            store.exportSnapshot(targetA2, capabilityA);
            assertSameContents(targetA, targetA2);
            assertNotEquals(contents(targetA).keySet(), contents(targetB).keySet());
            // Repeated queries and copies never change what a capability certifies.
            assertEquals(versions.first(), capabilityA.saved().state());
            assertThrows(UnsupportedOperationException.class, () -> capabilityA.saved().state().documents().clear());
            assertThrows(UnsupportedOperationException.class,
                    () -> capabilityA.saved().state().modules().put("test:other", "1.0.0"));
            for (BlobRef ref : LocalProjectStore.contentReferences(versions.first())) {
                ByteArrayOutputStream copy = new ByteArrayOutputStream();
                store.copyContent(ref, copy);
                assertArrayEquals(copy.toByteArray(), contents(targetA).get(ref.relativePath()), ref.relativePath());
            }
        }
    }

    @Test void capabilityFromAnotherInstanceOrReopenedPathIsRefused() throws Exception {
        Path project = sandbox.resolve("project");
        Path foreignProject = sandbox.resolve("foreign");
        Path target = sandbox.resolve("share.zip");
        Versions versions;
        ProjectSnapshot captured;
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            versions = publishTwoVersions(store);
            captured = versions.captured();
            try (LocalProjectStore foreign = LocalProjectStore.create(foreignProject)) {
                foreign.commit(null, UUID.randomUUID(), populated(foreign));
                ProjectSnapshot foreignCapture = foreign.captureSnapshot();
                code(REQUEST_MISMATCH, () -> foreign.exportSnapshot(target, captured));
                assertFalse(Files.exists(target));
                code(REQUEST_MISMATCH, () -> store.exportSnapshot(target, foreignCapture));
                assertFalse(Files.exists(target));
            }
            // Not even correct content is a capability: only an issuer identity this store handed out counts.
            String certifiedDigest = StoreJson.readHead(Files.readAllBytes(project.resolve("CURRENT")),
                    StoreLimits.DEFAULT).manifestHash();
            ProjectSnapshot forged = new ProjectSnapshot(new Object(), store.load(), certifiedDigest);
            assertEquals(store.load(), forged.saved());
            code(REQUEST_MISMATCH, () -> store.exportSnapshot(target, forged));
            assertFalse(Files.exists(target));
            store.exportSnapshot(target, captured);
            assertTrue(Files.deleteIfExists(target));
        }
        // Reopening the same path is a new instance with its own issuer identity, never a shared one.
        try (LocalProjectStore reopened = LocalProjectStore.open(project)) {
            assertEquals(versions.second(), reopened.load().state());
            ProjectSnapshot own = reopened.captureSnapshot();
            code(REQUEST_MISMATCH, () -> reopened.exportSnapshot(target, captured));
            assertFalse(Files.exists(target));
            reopened.exportSnapshot(target, own);
            assertTrue(Files.deleteIfExists(target));
        }
    }

    @Test void closedIssuerRefusesItsOwnCapability() throws Exception {
        Path project = sandbox.resolve("project");
        Path target = sandbox.resolve("share.zip");
        LocalProjectStore issuer = LocalProjectStore.create(project);
        ProjectSnapshot captured;
        try (issuer) {
            captured = publishTwoVersions(issuer).captured();
        }
        code(CLOSED, () -> issuer.exportSnapshot(target, captured));
        code(CLOSED, issuer::captureSnapshot);
        assertFalse(Files.exists(target));
    }

    @Test void nullCapabilityIsRejected() throws Exception {
        Path project = sandbox.resolve("project");
        Path target = sandbox.resolve("share.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.commit(null, UUID.randomUUID(), populated(store));
            assertThrows(NullPointerException.class, () -> store.exportSnapshot(target, null));
            assertFalse(Files.exists(target));
            store.exportSnapshot(target);
            assertTrue(Files.deleteIfExists(target));
        }
    }

    @Test void stagedContentCannotBeCapturedOrShared() throws Exception {
        Path project = sandbox.resolve("project");
        Path target = sandbox.resolve("share.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.stage(BlobRef.Kind.DOCUMENT, new byte[]{1, 2, 3});
            code(MISSING_PROJECT, store::captureSnapshot);
            code(MISSING_PROJECT, () -> store.exportSnapshot(target));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(project.resolve("CURRENT")));
            assertNoTemporaryArchives(sandbox);
        }
    }

    @Test void corruptedCertifiedManifestFailsWithoutFallingBackToTheCurrentVersion() throws Exception {
        Path project = sandbox.resolve("project");
        Path fixed = sandbox.resolve("fixed.zip");
        Path current = sandbox.resolve("current.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            Path manifest = project.resolve("commits").resolve(versions.firstRequest() + ".json");
            Files.writeString(manifest, Files.readString(manifest) + " ");
            code(CORRUPT_CONTENT, () -> store.exportSnapshot(fixed, versions.captured()));
            assertFalse(Files.exists(fixed));
            assertNoTemporaryArchives(sandbox);
            // The damaged version is never repaired and never silently replaced by the current one.
            assertTrue(Files.readString(manifest).endsWith(" "));
            store.exportSnapshot(current);
        }
        assertEquals("B 港口", LocalProjectStore.importSnapshot(current, sandbox.resolve("copy")).state().name());
    }

    @Test void corruptedCertifiedContentFailsWithoutFallingBackToTheCurrentVersion() throws Exception {
        Path project = sandbox.resolve("project");
        Path fixed = sandbox.resolve("fixed.zip");
        Path current = sandbox.resolve("current.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            Versions versions = publishTwoVersions(store);
            BlobRef assetOnlyInA = versions.first().assets().values().iterator().next().content();
            Path blob = project.resolve(assetOnlyInA.relativePath());
            byte[] damaged = Files.readAllBytes(blob);
            damaged[0] ^= 1;
            Files.write(blob, damaged);
            code(CORRUPT_CONTENT, () -> store.exportSnapshot(fixed, versions.captured()));
            assertFalse(Files.exists(fixed));
            assertArrayEquals(damaged, Files.readAllBytes(blob));
            assertNoTemporaryArchives(sandbox);
            // B never referenced the damaged blob, so the current version still shares normally.
            store.exportSnapshot(current);
        }
    }

    @Test void externalEntryChangeAtThePublicationBoundaryIsRejected() throws Exception {
        Path foreignProject = sandbox.resolve("foreign");
        byte[] foreignHead;
        try (LocalProjectStore foreign = LocalProjectStore.create(foreignProject)) {
            foreign.commit(null, UUID.randomUUID(), populated(foreign));
            foreignHead = Files.readAllBytes(foreignProject.resolve("CURRENT"));
        }
        Path project = sandbox.resolve("project");
        Files.createDirectory(project);
        Path target = sandbox.resolve("share.zip");
        AtomicBoolean injected = new AtomicBoolean();
        try (LocalProjectStore store = new LocalProjectStore(project, StoreLimits.DEFAULT, true, stage -> {
            if (stage == LocalProjectStore.Stage.BEFORE_EXPORT_PUBLISH) {
                Files.write(project.resolve("CURRENT"), foreignHead);
                injected.set(true);
            }
        })) {
            store.commit(null, UUID.randomUUID(), populated(store));
            ProjectSnapshot captured = store.captureSnapshot();
            code(CONFLICT, () -> store.exportSnapshot(target, captured));
            assertTrue(injected.get(), "the publication boundary was never reached");
            assertFalse(Files.exists(target));
            assertNoTemporaryArchives(sandbox);
            code(CONFLICT, store::load);
            assertArrayEquals(foreignHead, Files.readAllBytes(project.resolve("CURRENT")));
        }
    }

    @Test void publicationBoundaryCheckpointDoesNotChangeThePublishedArchive() throws Exception {
        Path project = sandbox.resolve("project");
        Files.createDirectory(project);
        Path fixed = sandbox.resolve("fixed.zip");
        Path current = sandbox.resolve("current.zip");
        try (LocalProjectStore store = new LocalProjectStore(project, StoreLimits.DEFAULT, true, stage -> {
        })) {
            store.commit(null, UUID.randomUUID(), populated(store));
            ProjectSnapshot captured = store.captureSnapshot();
            store.exportSnapshot(fixed, captured);
            store.exportSnapshot(current);
        }
        assertSameContents(fixed, current);
        assertNoTemporaryArchives(sandbox);
    }

    @Test void failureAtThePublicationBoundaryLeavesNoTargetAndNoTemporaryFile() throws Exception {
        Path project = sandbox.resolve("project");
        Files.createDirectory(project);
        Path target = sandbox.resolve("share.zip");
        try (LocalProjectStore store = new LocalProjectStore(project, StoreLimits.DEFAULT, true, stage -> {
            if (stage == LocalProjectStore.Stage.BEFORE_EXPORT_PUBLISH) {
                throw new IOException("injected publication failure");
            }
        })) {
            store.commit(null, UUID.randomUUID(), populated(store));
            ProjectSnapshot captured = store.captureSnapshot();
            IOException failure = assertThrows(IOException.class, () -> store.exportSnapshot(target, captured));
            assertEquals("injected publication failure", failure.getMessage());
            assertFalse(Files.exists(target));
            assertNoTemporaryArchives(sandbox);
        }
        // A failed share leaves the published project and its content usable.
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertEquals("港口", store.load().state().name());
            store.exportSnapshot(target);
        }
    }

    @Test void fixedExportNeverReplacesAnExistingTarget() throws Exception {
        Path project = sandbox.resolve("project");
        Path target = sandbox.resolve("share.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.exportSnapshot(target, publishTwoVersions(store).captured());
        }
        byte[] published = Files.readAllBytes(target);
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            ProjectSnapshot captured = store.captureSnapshot();
            assertThrows(FileAlreadyExistsException.class, () -> store.exportSnapshot(target, captured));
        }
        assertArrayEquals(published, Files.readAllBytes(target));
        assertNoTemporaryArchives(sandbox);
    }

    @Test void snapshotExposesNoPublicConstructorAndOnlyTheCertifiedSave() {
        assertTrue(Modifier.isFinal(ProjectSnapshot.class.getModifiers()));
        for (Constructor<?> constructor : ProjectSnapshot.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()), constructor::toString);
            assertFalse(Modifier.isProtected(constructor.getModifiers()), constructor::toString);
        }
        for (Field field : ProjectSnapshot.class.getDeclaredFields()) {
            assertFalse(Modifier.isStatic(field.getModifiers()), field::toString);
        }
        for (Method method : ProjectSnapshot.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                assertEquals("saved", method.getName(), method::toString);
                assertEquals(SavedProject.class, method.getReturnType(), method::toString);
                assertEquals(0, method.getParameterCount(), method::toString);
            }
        }
    }

    @Test void separateJvmHaltAtTheExportBoundaryPublishesNoTarget() throws Exception {
        Path project = sandbox.resolve("project");
        Path target = sandbox.resolve("share.zip");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.commit(null, UUID.randomUUID(), populated(store));
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(java)) {
            java = java.resolveSibling("java");
        }
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("storage.test.classpath"),
                CrashWorker.class.getName(), "export", project.toString(), UUID.randomUUID().toString(),
                LocalProjectStore.Stage.BEFORE_EXPORT_PUBLISH.name(), target.toString())
                .redirectErrorStream(true).start();
        boolean done = process.waitFor(20, TimeUnit.SECONDS);
        if (!done) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(done, "child process did not terminate");
        assertEquals(73, process.exitValue(), () -> "unexpected child exit: " + output);
        assertFalse(Files.exists(target));
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertEquals("港口", store.load().state().name());
            store.exportSnapshot(target);
        }
        assertTrue(Files.exists(target));
    }

    private static Map<String, byte[]> contents(Path archive) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> names = zip.stream().toList();
            for (ZipEntry name : names) {
                try (InputStream input = zip.getInputStream(name)) {
                    entries.put(name.getName(), input.readAllBytes());
                }
            }
        }
        return entries;
    }

    private static void assertSameContents(Path expected, Path actual) throws IOException {
        Map<String, byte[]> left = contents(expected);
        Map<String, byte[]> right = contents(actual);
        assertEquals(left.keySet(), right.keySet());
        left.forEach((name, bytes) -> assertArrayEquals(bytes, right.get(name), name));
    }

    private static void assertNoTemporaryArchives(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().startsWith(".share-")),
                    "a temporary archive was left behind");
        }
    }
}
