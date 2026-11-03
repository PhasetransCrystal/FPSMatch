package net.ptcrys.fpsmatch.editor.common.ui.persistence;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProviders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static net.ptcrys.fpsmatch.editor.common.ui.persistence.ProjectWorldStore.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectWorldStoreTest {
    @TempDir Path temporary;
    private final FileIdentityProvider identities = System.getProperty("os.name").startsWith("Windows")
            ? new WindowsFileIdentityProvider() : FileIdentityProviders.jdk();
    private record Fixture(Path project, Path saves, Path local, UUID id, ProjectWorldStore store,
                           ProjectWorldStore.World world) { }
    private Fixture fixture() throws IOException {
        Path project = Files.createDirectory(temporary.resolve("project"));
        Files.writeString(project.resolve("project-data"), "project remains untouched");
        Path saves = Files.createDirectory(temporary.resolve("saves"));
        createSave(saves.resolve("original"));
        Path local = temporary.resolve("host-local");
        var store = new ProjectWorldStore(local, identities);
        return new Fixture(project, saves, local, UUID.randomUUID(), store, store.inspect(saves, "original", "Original world"));
    }
    private static Path createSave(Path root) throws IOException {
        Files.createDirectory(root);
        Files.writeString(root.resolve("level.dat"), "test save metadata");
        Files.createDirectory(root.resolve("region"));
        Files.writeString(root.resolve("region/chunk"), "preserved world bytes");
        return root;
    }
    private static Path bucket(Fixture f) throws IOException {
        try (var entries = Files.list(f.local())) { return entries.findFirst().orElseThrow(); }
    }
    private static Map<String, String> tree(Path root) throws IOException {
        var result = new TreeMap<String, String>();
        try (var entries = Files.walk(root)) {
            for (Path path : entries.toList()) {
                result.put(root.relativize(path).toString(), Files.isDirectory(path) ? "directory" : HexFormat.of().formatHex(Files.readAllBytes(path)));
            }
        }
        return result;
    }
    private static void code(ProjectWorldStore.Code expected, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(expected, assertThrows(ProjectWorldStore.BindingException.class, operation).code());
    }

    @Test void firstBindingIsIdempotentPermanentAndDoesNotWriteProjectOrWorld() throws IOException {
        Fixture f = fixture();
        Map<String, String> project = tree(f.project()), world = tree(f.world().root());
        assertTrue(f.store().read(f.project(), f.id()).isEmpty());
        assertFalse(Files.exists(f.local()));
        var binding = f.store().bind(f.project(), f.id(), f.world());
        byte[] original = Files.readAllBytes(bucket(f).resolve("binding.json"));
        assertEquals(binding, f.store().bind(f.project(), f.id(), f.world()));
        assertEquals(binding, new ProjectWorldStore(f.local(), identities).read(f.project(), f.id()).orElseThrow());
        assertArrayEquals(original, Files.readAllBytes(bucket(f).resolve("binding.json")));
        assertEquals(project, tree(f.project()));
        assertEquals(world, tree(f.world().root()));
    }

    @Test void anotherSaveCannotReplaceTheFirstBinding() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        createSave(f.saves().resolve("other"));
        var other = f.store().inspect(f.saves(), "other", "Other");
        code(ALREADY_BOUND, () -> f.store().bind(f.project(), f.id(), other));
        code(ALREADY_BOUND, () -> f.store().relocate(f.project(), f.id(), other));
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
    }

    @Test void replacementAtTheSamePathIsRejectedButTheOriginalCanBeLocated() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        Files.move(f.world().root(), f.saves().resolve("moved-original"));
        createSave(f.world().root());
        code(CHANGED, () -> f.store().verify(f.world()));
        code(ALREADY_BOUND, () -> f.store().locateOriginal(f.project(), f.id(), f.saves(), "original", "Replacement"));
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
        var restored = f.store().locateOriginal(f.project(), f.id(), f.saves(), "moved-original", "Located original");
        assertEquals(f.world().identity(), restored.world().identity());
        assertEquals(f.saves().resolve("moved-original"), restored.world().root());
        f.store().verify(restored.world());
    }

    @Test void missingSaveRetainsAReadableCompleteOriginalBinding() throws IOException {
        Fixture f = fixture();
        var original = f.store().bind(f.project(), f.id(), f.world());
        Files.move(f.world().root(), temporary.resolve("outside-saves"));
        assertThrows(NoSuchFileException.class, () -> f.store().verify(f.world()));
        assertEquals(original, f.store().read(f.project(), f.id()).orElseThrow());
        assertEquals(original, f.store().readHistory(f.project(), f.id()).orElseThrow().original());
    }

    @Test void relocationKeepsOriginalBytesAndAnAuthenticatedCompleteHistory() throws IOException {
        Fixture f = fixture();
        var original = f.store().bind(f.project(), f.id(), f.world());
        Path record = bucket(f).resolve("binding.json");
        byte[] originalBytes = Files.readAllBytes(record);
        Map<String, String> world = tree(f.world().root());
        Files.move(f.world().root(), f.saves().resolve("relocated"));
        var first = f.store().locateOriginal(f.project(), f.id(), f.saves(), "relocated", "First move");
        assertEquals(first, f.store().locateOriginal(f.project(), f.id(), f.saves(), "relocated", "Repeated choice"));
        Files.move(first.world().root(), f.saves().resolve("relocated-again"));
        var second = f.store().locateOriginal(f.project(), f.id(), f.saves(), "relocated-again", "Second move");
        var reopened = new ProjectWorldStore(f.local(), identities);
        var history = reopened.readHistory(f.project(), f.id()).orElseThrow();
        assertEquals(original, history.original());
        assertEquals(List.of(first, second), history.relocations());
        assertEquals(second, reopened.read(f.project(), f.id()).orElseThrow());
        assertArrayEquals(originalBytes, Files.readAllBytes(record));
        assertEquals(world, tree(second.world().root()));
    }

    @Test void copiedWorldDoesNotCountAsTheOriginalPhysicalSave() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        Path copy = createSave(f.saves().resolve("copy"));
        assertEquals(tree(f.world().root()), tree(copy));
        code(ALREADY_BOUND, () -> f.store().locateOriginal(f.project(), f.id(), f.saves(), "copy", "Same bytes"));
        assertTrue(f.store().readHistory(f.project(), f.id()).orElseThrow().relocations().isEmpty());
    }

    @Test void locateOriginalCannotCreateAFirstBinding() throws IOException {
        Fixture f = fixture();
        code(MISSING, () -> f.store().locateOriginal(f.project(), f.id(), f.saves(), "original", "Original"));
        assertFalse(Files.exists(f.local()));
    }

    @Test void callerCannotForgeTheOriginalIdentityOnAnotherDirectory() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        createSave(f.saves().resolve("other"));
        var forged = new ProjectWorldStore.World(f.saves(), "other", "Forged", f.world().identity());
        code(CHANGED, () -> f.store().relocate(f.project(), f.id(), forged));
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
        assertTrue(f.store().readHistory(f.project(), f.id()).orElseThrow().relocations().isEmpty());
    }

    @Test void movedProjectKeepsItsBindingButANewPhysicalCopyMayBindIndependently() throws IOException {
        Fixture f = fixture();
        var original = f.store().bind(f.project(), f.id(), f.world());
        Path moved = Files.move(f.project(), temporary.resolve("moved-project"));
        var read = f.store().read(moved, f.id()).orElseThrow();
        assertEquals(moved, read.project());
        assertEquals(original.projectIdentity(), read.projectIdentity());
        assertEquals(original.world(), read.world());
        createSave(f.saves().resolve("other"));
        var other = f.store().inspect(f.saves(), "other", "Other");
        code(ALREADY_BOUND, () -> f.store().bind(moved, f.id(), other));
        Path copy = Files.createDirectory(temporary.resolve("project-copy"));
        assertTrue(f.store().read(copy, f.id()).isEmpty());
        assertEquals(other, f.store().bind(copy, f.id(), other).world());
    }

    @Test void localStoreCannotBePlacedInsideProjectOrSave() throws IOException {
        Fixture f = fixture();
        Path insideProject = f.project().resolve("bindings"), insideWorld = f.world().root().resolve("bindings");
        code(INVALID, () -> new ProjectWorldStore(insideProject, identities).bind(f.project(), f.id(), f.world()));
        code(INVALID, () -> new ProjectWorldStore(insideWorld, identities).bind(f.project(), f.id(), f.world()));
        assertFalse(Files.exists(insideProject));
        assertFalse(Files.exists(insideWorld));
    }

    @Test void inaccessibleStoreIsNeverReportedAsAnUnboundProject() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        var denied = new ProjectWorldStore(f.local(), identities, denyAttributes(f.local()));
        assertThrows(AccessDeniedException.class, () -> denied.read(f.project(), f.id()));
        assertThrows(AccessDeniedException.class, () -> denied.bind(f.project(), f.id(), f.world()));
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
    }

    @Test void inaccessibleRecordAndSaveMetadataRemainAccessFailures() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        var deniedRecord = new ProjectWorldStore(f.local(), identities, denyAttributes(bucket(f).resolve("binding.json")));
        assertThrows(AccessDeniedException.class, () -> deniedRecord.read(f.project(), f.id()));
        var deniedWorld = new ProjectWorldStore(f.local(), identities, denyAttributes(f.world().root().resolve("level.dat")));
        assertThrows(AccessDeniedException.class, () -> deniedWorld.verify(f.world()));
    }

    @Test void aFileAtTheStorePathIsInvalidRatherThanMissing() throws IOException {
        Fixture f = fixture();
        Files.writeString(f.local(), "foreign file");
        code(INVALID, () -> f.store().read(f.project(), f.id()));
        assertEquals("foreign file", Files.readString(f.local()));
    }

    private static ProjectWorldStore.Operations denyAttributes(Path denied) {
        return new ProjectWorldStore.Operations() {
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                if (path.equals(denied)) throw new AccessDeniedException(path.toString());
                return super.attributes(path);
            }
        };
    }

    @Test void concurrentDifferentFirstBindingsHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture();
        createSave(f.saves().resolve("other"));
        var other = f.store().inspect(f.saves(), "other", "Other");
        List<Object> results = concurrentBind(f, f.world(), other);
        assertEquals(1, results.stream().filter(ProjectWorldStore.Binding.class::isInstance).count());
        var loser = (ProjectWorldStore.BindingException) results.stream().filter(ProjectWorldStore.BindingException.class::isInstance).findFirst().orElseThrow();
        assertEquals(ALREADY_BOUND, loser.code());
        var winner = (ProjectWorldStore.Binding) results.stream().filter(ProjectWorldStore.Binding.class::isInstance).findFirst().orElseThrow();
        assertEquals(winner, f.store().read(f.project(), f.id()).orElseThrow());
    }

    @Test void concurrentSameFirstBindingIsIdempotentForBothCallers() throws Exception {
        Fixture f = fixture();
        List<Object> results = concurrentBind(f, f.world(), f.world());
        assertInstanceOf(ProjectWorldStore.Binding.class, results.get(0));
        assertEquals(results.get(0), results.get(1));
        assertEquals(results.get(0), f.store().read(f.project(), f.id()).orElseThrow());
    }

    private List<Object> concurrentBind(Fixture f, ProjectWorldStore.World first, ProjectWorldStore.World second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var operations = new ProjectWorldStore.Operations() {
            @Override void publish(Path target, Path source) throws IOException {
                try { barrier.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
                catch (BrokenBarrierException | TimeoutException failure) { throw new IOException(failure); }
                super.publish(target, source);
            }
        };
        var store = new ProjectWorldStore(f.local(), identities, operations);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> bindResult(store, f, first));
            var two = executor.submit(() -> bindResult(store, f, second));
            return List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private static Object bindResult(ProjectWorldStore store, Fixture f, ProjectWorldStore.World world) {
        try { return store.bind(f.project(), f.id(), world); }
        catch (IOException failure) { return failure; }
    }

    @Test void publicationThatSucceedsThenThrowsIsUnknownAndCanBeReconciledReadOnly() throws IOException {
        Fixture f = fixture();
        var uncertain = new ProjectWorldStore(f.local(), identities, new ProjectWorldStore.Operations() {
            @Override void publish(Path target, Path source) throws IOException {
                super.publish(target, source);
                throw new IOException("publication reply lost");
            }
        });
        code(UNKNOWN, () -> uncertain.bind(f.project(), f.id(), f.world()));
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
        assertEquals(0, f.store().readHistory(f.project(), f.id()).orElseThrow().relocations().size());
    }

    @Test void postPublicationWorldAccessFailureIsUnknownWhileTheBindingRemainsReadable() throws IOException {
        Fixture f = fixture();
        AtomicBoolean published = new AtomicBoolean();
        var uncertain = new ProjectWorldStore(f.local(), identities, new ProjectWorldStore.Operations() {
            @Override void publish(Path target, Path source) throws IOException {
                super.publish(target, source); published.set(true);
            }
            @Override BasicFileAttributes attributes(Path path) throws IOException {
                if (published.get() && path.equals(f.world().root())) throw new AccessDeniedException(path.toString());
                return super.attributes(path);
            }
        });
        code(UNKNOWN, () -> uncertain.bind(f.project(), f.id(), f.world()));
        assertEquals(f.world(), uncertain.read(f.project(), f.id()).orElseThrow().world());
    }

    @Test void prePublicationFailureIsNotUnknownAndDoesNotPublishAnAssociation() throws IOException {
        Fixture f = fixture();
        var rejected = new ProjectWorldStore(f.local(), path -> {
            if (path.getFileName() != null && path.getFileName().toString().endsWith(".tmp")) throw new AccessDeniedException(path.toString());
            return identities.identity(path);
        });
        assertThrows(AccessDeniedException.class, () -> rejected.bind(f.project(), f.id(), f.world()));
        assertTrue(f.store().read(f.project(), f.id()).isEmpty());
    }

    @Test void cleanupFailureDoesNotReplaceTheUnknownPublicationCause() throws IOException {
        Fixture f = fixture();
        var uncertain = new ProjectWorldStore(f.local(), identities, new ProjectWorldStore.Operations() {
            @Override void publish(Path target, Path source) throws IOException {
                super.publish(target, source); throw new IOException("publication reply lost");
            }
            @Override void delete(Path path) throws IOException { throw new IOException("cleanup denied"); }
        });
        var failure = assertThrows(ProjectWorldStore.BindingException.class, () -> uncertain.bind(f.project(), f.id(), f.world()));
        assertEquals(UNKNOWN, failure.code());
        assertEquals("publication reply lost", failure.getCause().getMessage());
        assertEquals("cleanup denied", failure.getCause().getSuppressed()[0].getMessage());
        assertEquals(f.world(), f.store().read(f.project(), f.id()).orElseThrow().world());
    }

    @Test void uncertainRelocationRetainsBothCompleteOriginalAndCurrentRecords() throws IOException {
        Fixture f = fixture();
        var original = f.store().bind(f.project(), f.id(), f.world());
        Files.move(f.world().root(), f.saves().resolve("moved"));
        var uncertain = new ProjectWorldStore(f.local(), identities, new ProjectWorldStore.Operations() {
            @Override void publish(Path target, Path source) throws IOException {
                super.publish(target, source); throw new IOException("relocation reply lost");
            }
        });
        code(UNKNOWN, () -> uncertain.locateOriginal(f.project(), f.id(), f.saves(), "moved", "Moved"));
        var history = f.store().readHistory(f.project(), f.id()).orElseThrow();
        assertEquals(original, history.original());
        assertEquals(1, history.relocations().size());
        assertEquals(f.saves().resolve("moved"), history.current().world().root());
        assertEquals(original.world().identity(), history.current().world().identity());
    }

    private void relocateTwice(Fixture f) throws IOException {
        f.store().bind(f.project(), f.id(), f.world());
        Files.move(f.world().root(), f.saves().resolve("first"));
        f.store().locateOriginal(f.project(), f.id(), f.saves(), "first", "First");
        Files.move(f.saves().resolve("first"), f.saves().resolve("second"));
        f.store().locateOriginal(f.project(), f.id(), f.saves(), "second", "Second");
    }

    @Test void missingPredecessorNeverFallsBackToAnApparentlyRecoveredOldBinding() throws IOException {
        Fixture f = fixture(); relocateTwice(f);
        Path first = bucket(f).resolve("relocation-00000001.json");
        Files.move(first, temporary.resolve("retained-first-record"));
        code(INVALID, () -> f.store().read(f.project(), f.id()));
        code(INVALID, () -> f.store().bind(f.project(), f.id(), f.store().inspect(f.saves(), "second", "Second")));
    }

    @Test void changedPredecessorHashAndExtraBranchAreRejected() throws IOException {
        Fixture f = fixture(); relocateTwice(f);
        Path second = bucket(f).resolve("relocation-00000002.json");
        String valid = Files.readString(second);
        var json = JsonParser.parseString(valid).getAsJsonObject();
        json.addProperty("previous", "0".repeat(64));
        Files.writeString(second, json.toString());
        code(INVALID, () -> f.store().read(f.project(), f.id()));
        Files.writeString(second, valid);
        Files.writeString(bucket(f).resolve("relocation-00000004.json"), valid);
        code(INVALID, () -> f.store().read(f.project(), f.id()));
    }

    @Test void absentOriginalAndOversizedRecordsAreRejectedInsteadOfResettingTheBinding() throws IOException {
        Fixture f = fixture(); relocateTwice(f);
        Path first = bucket(f).resolve("binding.json");
        byte[] original = Files.readAllBytes(first);
        Path retained = Files.move(first, temporary.resolve("retained-original"));
        code(INVALID, () -> f.store().read(f.project(), f.id()));
        Files.move(retained, first);
        Files.writeString(first, "x".repeat(16385));
        code(INVALID, () -> f.store().read(f.project(), f.id()));
        Files.write(first, original);
        assertEquals(f.saves().resolve("second"), f.store().read(f.project(), f.id()).orElseThrow().world().root());
    }

    @Test void excessiveHistoryFilesAreRejectedBeforeUnboundedScanning() throws IOException {
        Fixture f = fixture();
        f.store().bind(f.project(), f.id(), f.world());
        Path bucket = bucket(f);
        for (int revision = 1; revision <= 65; revision++) {
            Files.writeString(bucket.resolve(String.format(Locale.ROOT, "relocation-%08d.json", revision)), "{}");
        }
        code(INVALID, () -> f.store().read(f.project(), f.id()));
    }
}
