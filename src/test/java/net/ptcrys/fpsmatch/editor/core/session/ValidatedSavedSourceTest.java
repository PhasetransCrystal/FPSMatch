package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidator;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/** Q59 acceptance via public access, registry and real temporary store APIs. */
class ValidatedSavedSourceTest {
    @TempDir Path directory;
    private static final EditorId IMPORTER = EditorId.parse("synthetic:saved_source");
    private static final EditorId ASSET_TYPE = EditorId.parse("synthetic:saved_bytes");
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final UUID PROJECT = id(1), DOCUMENT = id(2), RESOURCE = id(3);
    private static final UUID ACTIVE = id(4), RETAINED = id(5), BYTE = id(6), RETAINED_BYTE = id(7);
    private static final ResourceContentValidator ACCEPT = (version, content) -> List.of();

    @Test void currentAndExplicitFixedValidatedReadsKeepAAfterLegalSaveB() throws Exception {
        try (Fixture f = fixture("fixed")) {
            LoadedProject currentA = f.access.readValidated(f.registry, () -> false);
            LoadedProject fixedA = f.access.readValidated(f.captured, f.registry, () -> false);
            SavedProject b = f.advance();
            int validations = f.validatorCalls.get();
            f.validator.set((version, content) -> { throw new AssertionError("verification reran validator"); });
            f.type.decodeTransform = value -> { throw new AssertionError("verification ran codec"); };
            for (LoadedProject source : List.of(currentA, fixedA)) {
                for (int repeat = 0; repeat < 2; repeat++) f.verify(source);
                assertEquals(f.a, source.saved());
                assertArrayEquals(bytes("document A"), source.documentBytes(DOCUMENT));
                assertEquals(f.catalog, source.resources().orElseThrow());
            }
            assertEquals(validations, f.validatorCalls.get());
            assertEquals(0, f.type.decodes);
            assertEquals(b, f.access.read().saved());
            assertEquals(b, f.store.load());
        }
    }

    @Test void onlyOriginalAccessAndExactValidatedRegistryAuthorizeSource() throws Exception {
        try (Fixture f = fixture("identity")) {
            LoadedProject source = f.validated();
            assertThrows(IllegalArgumentException.class, () ->
                    new LocalProjectAccess(f.store).verifyValidatedSource(source, f.registry, () -> false));
            ExtensionRegistry equivalent = registry(new TestType(), ACCEPT);
            assertRequired(() -> f.access.verifyValidatedSource(source, equivalent, () -> false));
            assertRequired(() -> f.verify(f.access.read()));
            assertRequired(() -> f.verify(f.access.read(f.captured)));
            assertThrows(NullPointerException.class, () -> f.access.verifyValidatedSource(null, f.registry, () -> false));
            assertThrows(NullPointerException.class, () -> f.access.verifyValidatedSource(source, null, () -> false));
            assertThrows(NullPointerException.class, () -> f.access.verifyValidatedSource(source, f.registry, null));
            f.verify(source);
        }
    }

    @Test void emptyResourceGraphStillRequiresActualValidatedRead() throws Exception {
        ExtensionRegistry registry = ProjectSessionFixtures.registry(new TestType());
        try (var f = project(directory.resolve("empty"), registry, List.of(source(DOCUMENT, "plain")))) {
            assertTrue(f.loaded().resources().orElseThrow().versions().isEmpty());
            assertRequired(() -> f.access().verifyValidatedSource(f.loaded(), registry, () -> false),
                    f.loaded().saved().state().projectId());
            LoadedProject validated = f.access().readValidated(registry, () -> false);
            f.access().verifyValidatedSource(validated, registry, () -> false);
        }
    }

    @Test void missingValidatorAndContentErrorNeverAcquireAuthorityButWarningsDo() throws Exception {
        try (Fixture f = fixture("diagnostics")) {
            ExtensionRegistry missing = registry(new TestType(), null);
            LoadedProject absent = f.access.readValidated(missing, () -> false);
            assertTrue(absent.problems().stream().anyMatch(p -> p.code().toString()
                    .equals("editor:session/resource_content_validator_unavailable")));
            assertRequired(() -> f.access.verifyValidatedSource(absent, missing, () -> false));
            Problem error = new Problem(Problem.Severity.ERROR, EditorId.parse("synthetic:invalid"),
                    RESOURCE.toString(), new TextContent.Literal("invalid fixture"));
            f.validator.set((version, content) -> List.of(error));
            LoadedProject refused = f.validated();
            assertTrue(refused.problems().contains(error));
            assertRequired(() -> f.verify(refused));
            Problem warning = new Problem(Problem.Severity.WARNING, EditorId.parse("synthetic:warning"),
                    RESOURCE.toString(), new TextContent.Literal("fixture warning"));
            f.validator.set((version, content) -> List.of(warning));
            LoadedProject accepted = f.validated();
            assertTrue(accepted.problems().contains(warning));
            f.verify(accepted);
            assertRequired(() -> f.verify(refused));
        }
    }

    @Test void unreadableResourceGraphCannotBeMistakenForValidatedEmptyGraph() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(directory.resolve("unknown"))) {
            ProjectState.Entry opaque = new ProjectState.Entry(BYTE, RESOURCES.toString(), "future:resource",
                    "future:opaque", 7, "unknown descriptor", store.stage(BlobRef.Kind.ASSET, bytes("opaque")),
                    Set.of(), Set.of());
            store.commit(null, UUID.randomUUID(), new ProjectState(PROJECT, "unknown", modules(), Map.of(),
                    Map.of(BYTE, opaque), null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            ExtensionRegistry registry = registry(new TestType(), ACCEPT);
            LoadedProject source = access.readValidated(registry, () -> false);
            assertTrue(source.resources().isEmpty());
            assertTrue(source.resourceFailure().isPresent());
            assertRequired(() -> access.verifyValidatedSource(source, registry, () -> false));
            SavedProject healthy = store.commit(source.saved().commitId(), UUID.randomUUID(),
                    new ProjectState(PROJECT, "healthy", Map.of(), Map.of(), Map.of(), null)).saved();
            LoadedProject healthySource = access.readValidated(registry, () -> false);
            access.verifyValidatedSource(healthySource, registry, () -> false);
            assertEquals(healthy, store.load());
        }
    }

    @Test void damagedAOnlyDocumentAndInactiveRetainedBytesCannotFallBackToHealthyB() throws Exception {
        try (Fixture f = fixture("corrupt")) {
            LoadedProject source = f.validated();
            SavedProject b = f.advance();
            for (ProjectState.Entry entry : List.of(f.a.state().documents().get(DOCUMENT),
                    f.catalog.byteEntries().get(RETAINED_BYTE))) {
                Path blob = f.path.resolve(entry.content().kind() == BlobRef.Kind.DOCUMENT ? "documents" : "assets")
                        .resolve(entry.content().sha256() + ".blob");
                byte[] original = Files.readAllBytes(blob);
                byte[] damaged = original.clone();
                damaged[0] ^= 1;
                Files.write(blob, damaged);
                try {
                    assertStorage(ProjectStorageException.Code.CORRUPT_CONTENT, () -> f.verify(source));
                    assertArrayEquals(damaged, Files.readAllBytes(blob), "verification must not repair bytes");
                    assertEquals(b, f.access.read().saved());
                } finally {
                    Files.write(blob, original);
                }
                f.verify(source);
            }
        }
    }

    @Test void externalCurrentChangeAndClosedOrReopenedStoresRejectOldAuthority() throws Exception {
        Path path = directory.resolve("lifetime");
        LocalProjectAccess oldAccess;
        LoadedProject oldSource;
        ExtensionRegistry registry;
        try (Fixture f = new Fixture(path)) {
            oldAccess = f.access;
            oldSource = f.validated();
            registry = f.registry;
            SavedProject b = f.advance();
            Path current = path.resolve("CURRENT");
            byte[] headB = Files.readAllBytes(current);
            Files.write(current, f.headA);
            try {
                assertStorage(ProjectStorageException.Code.CONFLICT, () -> f.verify(oldSource));
                assertArrayEquals(f.headA, Files.readAllBytes(current));
            } finally {
                Files.write(current, headB);
            }
            f.verify(oldSource);
            assertEquals(b, f.store.load());
        }
        assertStorage(ProjectStorageException.Code.CLOSED, () ->
                oldAccess.verifyValidatedSource(oldSource, registry, () -> false));
        try (LocalProjectStore reopened = LocalProjectStore.open(path)) {
            LocalProjectAccess access = new LocalProjectAccess(reopened);
            assertThrows(IllegalArgumentException.class, () ->
                    access.verifyValidatedSource(oldSource, registry, () -> false));
            assertStorage(ProjectStorageException.Code.CLOSED, () ->
                    oldAccess.verifyValidatedSource(oldSource, registry, () -> false));
            assertRequired(() -> access.verifyValidatedSource(access.read(), registry, () -> false));
            access.verifyValidatedSource(access.readValidated(registry, () -> false), registry, () -> false);
        }
    }

    @Test void firstAndLastCancellationAndSupplierFailuresReleaseGuardWithoutConsumingSource() throws Exception {
        try (Fixture f = fixture("cancellation")) {
            LoadedProject source = f.validated();
            assertThrows(ResourceValidationCancelledException.class, () ->
                    f.access.verifyValidatedSource(source, f.registry, () -> true));
            f.verify(source);
            AtomicInteger checks = new AtomicInteger();
            assertThrows(ResourceValidationCancelledException.class, () ->
                    f.access.verifyValidatedSource(source, f.registry, () -> checks.incrementAndGet() == 2));
            assertEquals(2, checks.get(), "late cancellation is observed after fixed load");
            f.verify(source);
            for (int boundary : List.of(1, 2)) {
                RuntimeException failure = new IllegalArgumentException("supplier failure " + boundary);
                checks.set(0);
                assertSame(failure, assertThrows(IllegalArgumentException.class, () ->
                        f.access.verifyValidatedSource(source, f.registry, () -> {
                            if (checks.incrementAndGet() == boundary) throw failure;
                            return false;
                        })));
                f.verify(source);
                assertEquals(f.a, f.access.read().saved());
            }
        }
    }

    @Test void cancellationAndValidatorCallbacksCannotReenterVerificationOrAccessIo() throws Exception {
        try (Fixture f = fixture("reentry")) {
            LoadedProject source = f.validated();
            f.access.verifyValidatedSource(source, f.registry, () -> {
                assertThrows(IllegalStateException.class, () -> f.verify(source));
                assertThrows(IllegalStateException.class, f.access::read);
                assertThrows(IllegalStateException.class, f.access::captureSnapshot);
                assertThrows(IllegalStateException.class, () -> f.access.read(f.captured));
                assertThrows(IllegalStateException.class, () -> f.access.readValidated(f.registry, () -> false));
                return false;
            });
            f.validator.set((version, content) -> {
                assertThrows(IllegalStateException.class, () -> f.verify(source));
                return List.of();
            });
            f.verify(f.validated());
            f.verify(source);
        }
    }

    @Test void concurrentVerificationExcludesIoAndExistingValidationExcludesVerification() throws Exception {
        try (Fixture f = fixture("concurrency")) {
            LoadedProject source = f.validated();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                for (boolean validating : List.of(false, true)) {
                    CountDownLatch entered = new CountDownLatch(1);
                    CountDownLatch release = new CountDownLatch(1);
                    AtomicInteger checks = new AtomicInteger();
                    Future<?> running = executor.submit(() -> {
                        java.util.function.BooleanSupplier wait = () -> {
                            if (checks.incrementAndGet() == 1) {
                                entered.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError(interrupted);
                                }
                            }
                            return false;
                        };
                        if (validating) f.access.readValidated(f.registry, wait);
                        else f.access.verifyValidatedSource(source, f.registry, wait);
                        return null;
                    });
                    try {
                        assertTrue(entered.await(10, TimeUnit.SECONDS), "worker entered callback");
                        assertBusy(() -> f.verify(source));
                        assertBusy(f.access::read);
                        assertBusy(f.access::captureSnapshot);
                        assertBusy(() -> f.access.readValidated(f.registry, () -> false));
                    } finally {
                        release.countDown();
                        running.get(10, TimeUnit.SECONDS);
                    }
                    f.verify(source);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "no leftover worker");
            }
        }
    }

    @Test void verificationPreservesOpenSessionLeaseDraftHistoryAndAllDomainCallbackCounts() throws Exception {
        try (Fixture f = fixture("session")) {
            LoadedProject source = f.validated();
            try (ProjectSession session = ProjectSession.open(f.access, source, f.registry, 8)) {
                var document = session.find(DOCUMENT, String.class).orElseThrow();
                document.edit(document.view().token(), new TextContent.Literal("edit"), value -> "draft");
                var before = document.view();
                int[] callbacks = callbackCounts(f);
                f.verify(source);
                f.verify(source);
                assertSame(source, session.source());
                assertEquals(before, document.view());
                assertTrue(document.hasUnpublishedChanges());
                assertArrayEquals(callbacks, callbackCounts(f));
                assertEquals(f.a, f.store.load());
                assertThrows(IllegalStateException.class, () -> ProjectSession.open(f.access, source, f.registry, 8));
                document.undo(document.view().token());
                assertEquals("document A", document.view().content());
            }
            try (ProjectSession session = ProjectSession.open(f.access, source, f.registry, 8)) {
                assertFalse(session.protectedMode());
            }
        }
    }

    private Fixture fixture(String name) throws Exception { return new Fixture(directory.resolve(name)); }

    private static final class Fixture implements AutoCloseable {
        final Path path;
        final LocalProjectStore store;
        final LocalProjectAccess access;
        final ProjectSnapshot captured;
        final SavedProject a;
        final byte[] headA;
        final TestType type = new TestType();
        final AtomicInteger validatorCalls = new AtomicInteger();
        final AtomicReference<ResourceContentValidator> validator = new AtomicReference<>(ACCEPT);
        final ExtensionRegistry registry = registry(type, (version, content) -> {
            validatorCalls.incrementAndGet();
            return validator.get().validate(version, content);
        });
        final ResourceCatalog catalog;

        Fixture(Path path) throws Exception {
            this.path = path;
            store = LocalProjectStore.create(path);
            catalog = new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, ASSET_TYPE, "resource", ACTIVE)),
                    Map.of(ACTIVE, version(ACTIVE, BYTE), RETAINED, version(RETAINED, RETAINED_BYTE)),
                    Map.of(BYTE, asset(BYTE, store.stage(BlobRef.Kind.ASSET, bytes("active A"))),
                            RETAINED_BYTE, asset(RETAINED_BYTE, store.stage(BlobRef.Kind.ASSET, bytes("retained A only")))));
            a = store.commit(null, UUID.randomUUID(), new ProjectState(PROJECT, "A", modules(),
                    Map.of(DOCUMENT, document(store.stage(BlobRef.Kind.DOCUMENT, bytes("document A")))),
                    ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalog)), null)).saved();
            access = new LocalProjectAccess(store);
            captured = access.captureSnapshot();
            headA = Files.readAllBytes(path.resolve("CURRENT"));
        }

        LoadedProject validated() throws IOException { return access.readValidated(registry, () -> false); }
        void verify(LoadedProject source) throws IOException { access.verifyValidatedSource(source, registry, () -> false); }
        SavedProject advance() throws IOException {
            return store.commit(a.commitId(), UUID.randomUUID(), new ProjectState(PROJECT, "B", modules(),
                    Map.of(DOCUMENT, document(store.stage(BlobRef.Kind.DOCUMENT, bytes("document B")))), Map.of(), null)).saved();
        }
        @Override public void close() throws IOException { store.close(); }
    }

    private static ExtensionRegistry registry(TestType type, ResourceContentValidator validator) {
        var builder = ProjectSessionFixtures.builder(type);
        builder.addModule(new ModuleSpec(RESOURCES, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), IMPORTER),
                OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
        if (validator != null) builder.add(ResourceContentValidators.KIND,
                new Contribution(new ContributionKey(ResourceContentValidators.KIND.id(), IMPORTER),
                        OWNER, Contribution.Side.COMMON, Set.of()), validator);
        return builder.freeze();
    }

    private static ResourceVersion version(UUID version, UUID entry) {
        return new ResourceVersion(version, RESOURCE, IMPORTER, "1", Map.of(), entry, Set.of(), Set.of());
    }
    private static ProjectState.Entry asset(UUID id, BlobRef blob) {
        return new ProjectState.Entry(id, OWNER.toString(), ASSET_TYPE.toString(), "synthetic:raw", 1,
                "bytes", blob, Set.of(), Set.of());
    }
    private static ProjectState.Entry document(BlobRef blob) {
        return new ProjectState.Entry(DOCUMENT, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1,
                "note", blob, Set.of(), Set.of());
    }
    private static Map<String, String> modules() { return Map.of(OWNER.toString(), BUILD, RESOURCES.toString(), "1"); }
    private static int[] callbackCounts(Fixture f) {
        return new int[]{f.validatorCalls.get(), f.type.creates, f.type.decodes, f.type.snapshots,
                f.type.validations, f.type.encodings, f.type.dependencies};
    }
    private static void assertRequired(Executable action) { assertRequired(action, PROJECT); }
    private static void assertRequired(Executable action, UUID project) {
        SessionRejection refusal = assertThrows(SessionRejection.class, action);
        assertEquals(1, refusal.problems().size());
        Problem problem = refusal.problems().get(0);
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(EditorId.parse("editor:session/saved_source_validation_required"), problem.code());
        assertEquals(project.toString(), problem.target());
        assertEquals(new TextContent.Message("editor.session.saved_source_validation_required",
                List.of(new TextContent.LiteralArgument(project.toString()))), problem.message());
    }
    private static void assertBusy(Executable action) {
        SessionRejection refusal = assertThrows(SessionRejection.class, action);
        assertEquals(List.of(EditorId.parse("editor:runtime_save/io_busy")),
                refusal.problems().stream().map(Problem::code).toList());
    }
    private static void assertStorage(ProjectStorageException.Code code, Executable action) {
        assertEquals(code, assertThrows(ProjectStorageException.class, action).code());
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static UUID id(long value) { return new UUID(0x5150353900000000L, value); }
}
