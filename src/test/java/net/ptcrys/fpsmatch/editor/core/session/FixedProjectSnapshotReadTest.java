package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceValidationCancelledException;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectSnapshot;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Q55 acceptance through actual saved local projects; no world or runtime installation. */
class FixedProjectSnapshotReadTest {
    @TempDir Path directory;
    private static final EditorId IMPORTER = EditorId.parse("synthetic:fixed_raw");
    private static final EditorId ASSET_TYPE = EditorId.parse("synthetic:fixed_bytes");
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final UUID PROJECT = id(1), DOCUMENT = id(2), OLD_DOCUMENT = id(3);
    private static final UUID RESOURCE = id(10), OLD = id(11), ACTIVE = id(12), RETAINED = id(13);
    private static final UUID BYTE = id(14), OUTPUT = id(15), RETAINED_BYTE = id(16);
    private static final UUID B_RESOURCE = id(20), B_VERSION = id(21), B_BYTE = id(22);
    private static final ResourceContentValidator ACCEPT = (version, content) -> List.of();

    @Test void capturedReadKeepsCompleteAAfterBReplacesDocumentsAndResourceGraph() throws Exception {
        try (Fixture f = fixture("fixed")) {
            for (int repeat = 0; repeat < 2; repeat++) {
                LoadedProject loaded = f.access.read(f.captured);
                assertA(f, loaded);
                assertFalse(loaded.validatedWith(f.registry));
                assertEquals(f.b, f.access.read().saved());
                assertArrayEquals(bytes("document B"), f.access.read().documentBytes(DOCUMENT));
                assertEquals(f.catalogB, f.access.read().resources().orElseThrow());
                byte[] copy = loaded.documentBytes(DOCUMENT);
                copy[0] ^= 1;
                assertArrayEquals(bytes("document A"), loaded.documentBytes(DOCUMENT));
            }
        }
    }

    @Test void fixedValidationSeesAllRetainedAVersionsAndExactRegistryIdentity() throws Exception {
        try (Fixture f = fixture("validated")) {
            List<UUID> seen = new ArrayList<>();
            f.validator.set((version, content) -> {
                seen.add(version.id());
                assertEquals(f.catalogA.versions(), content.versions());
                assertEquals(f.catalogA.byteEntries(), content.byteEntries());
                assertEquals(Map.of(RESOURCE, ASSET_TYPE), content.resourceTypes());
                for (UUID entry : content.byteEntries().keySet()) {
                    ByteArrayOutputStream copied = new ByteArrayOutputStream();
                    content.copyContent(entry, copied);
                    assertArrayEquals(f.assetBytes.get(entry), copied.toByteArray());
                }
                return List.of();
            });
            LoadedProject validated = f.access.readValidated(f.captured, f.registry, () -> false);
            assertA(f, validated);
            assertEquals(List.of(OLD, ACTIVE, RETAINED), seen);
            assertTrue(validated.validatedWith(f.registry));
            ExtensionRegistry equivalent = registry(ACCEPT, Set.of("old", "active", "retained", "B"));
            assertFalse(validated.validatedWith(equivalent));
            try (ProjectSession session = ProjectSession.open(f.access, validated, equivalent, 2)) {
                assertTrue(session.protectedMode());
                assertCode(session.problems(), "resource_content_validation_required");
            }
            try (ProjectSession session = ProjectSession.open(f.access, validated, f.registry, 2)) {
                assertFalse(session.protectedMode());
            }
            assertThrows(IllegalArgumentException.class, () -> ProjectSession.open(
                    new LocalProjectAccess(f.store), validated, f.registry, 2));
            AtomicInteger calls = new AtomicInteger();
            ExtensionRegistry omitsRetained = registry((version, content) -> {
                calls.incrementAndGet(); return List.of();
            }, Set.of("active", "B"));
            LoadedProject refused = f.access.readValidated(f.captured, omitsRetained, () -> false);
            assertCode(refused.problems(), "resource_producer_version_unavailable");
            assertEquals(0, calls.get());
            assertFalse(refused.validatedWith(omitsRetained));
        }
    }

    @Test void firstAndFinalCancellationRejectAndLeaveAccessUsable() throws Exception {
        try (Fixture f = fixture("cancel-boundaries")) {
            AtomicInteger callbacks = new AtomicInteger();
            f.validator.set((version, content) -> { callbacks.incrementAndGet(); return List.of(); });
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.readValidated(f.captured, f.registry, () -> true));
            assertEquals(0, callbacks.get());
            AtomicInteger checks = new AtomicInteger();
            assertNotNull(f.access.readValidated(f.captured, f.registry, () -> {
                checks.incrementAndGet(); return false;
            }));
            int finalCheck = checks.get();
            callbacks.set(0);
            checks.set(0);
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.readValidated(f.captured, f.registry,
                            () -> checks.incrementAndGet() == finalCheck));
            assertEquals(3, callbacks.get(), "the final cancellation happens after every validator");
            assertA(f, f.access.read(f.captured));
            assertNotNull(f.access.captureSnapshot());
            assertTrue(f.access.readValidated(f.captured, f.registry, () -> false).validatedWith(f.registry));
        }
    }

    @Test void cancellationSwallowedByCallbackRemainsLatched() throws Exception {
        try (Fixture f = fixture("cancel-latched")) {
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger calls = new AtomicInteger();
            f.validator.set((version, content) -> {
                calls.incrementAndGet();
                cancelled.set(true);
                assertThrows(ResourceValidationCancelledException.class, content::checkCancelled);
                cancelled.set(false);
                assertThrows(ResourceValidationCancelledException.class, content::checkCancelled);
                return List.of();
            });
            assertThrows(ResourceValidationCancelledException.class,
                    () -> f.access.readValidated(f.captured, f.registry, cancelled::get));
            assertEquals(1, calls.get());
            f.validator.set(ACCEPT);
            assertTrue(f.access.readValidated(f.captured, f.registry, () -> false).validatedWith(f.registry));
        }
    }

    @Test void callbackCannotReenterCapturedOrCurrentIoAndFinallyRestoresGuards() throws Exception {
        try (Fixture f = fixture("reentrant")) {
            Object lease = new Object();
            f.access.claim(lease);
            f.validator.set((version, content) -> {
                assertThrows(IllegalStateException.class, f.access::captureSnapshot);
                assertThrows(IllegalStateException.class, f.access::read);
                assertThrows(IllegalStateException.class, () -> f.access.read(f.captured));
                assertThrows(IllegalStateException.class,
                        () -> f.access.readValidated(f.captured, f.registry, () -> false));
                assertThrows(IllegalStateException.class,
                        () -> f.access.readValidated(f.registry, () -> false));
                assertThrows(IllegalStateException.class, () -> f.access.release(lease));
                assertThrows(IllegalStateException.class, () -> f.access.claim(new Object()));
                throw new IllegalArgumentException("callback failure");
            });
            assertEquals("callback failure", assertThrows(IllegalArgumentException.class,
                    () -> f.access.readValidated(f.captured, f.registry, () -> false)).getMessage());
            f.access.release(lease);
            f.access.claim(lease);
            f.access.release(lease);
            f.validator.set(ACCEPT);
            assertA(f, f.access.read(f.captured));
            assertEquals(f.b, f.access.read(f.access.captureSnapshot()).saved());
            assertTrue(f.access.readValidated(f.captured, f.registry, () -> false).validatedWith(f.registry));
        }
    }

    @Test void finalRecheckRejectsExternalCurrentChangeAndRestorationAllowsRetry() throws Exception {
        try (Fixture f = fixture("changed-current")) {
            Path current = f.path.resolve("CURRENT");
            byte[] headB = Files.readAllBytes(current);
            f.validator.set((version, content) -> {
                if (version.id().equals(RETAINED)) Files.write(current, f.headA);
                return List.of();
            });
            assertCode(ProjectStorageException.Code.CONFLICT, () ->
                    f.access.readValidated(f.captured, f.registry, () -> false));
            assertArrayEquals(f.headA, Files.readAllBytes(current), "refusal must not repair CURRENT");
            Files.write(current, headB);
            f.validator.set(ACCEPT);
            assertA(f, f.access.readValidated(f.captured, f.registry, () -> false));
            assertEquals(f.b, f.access.read().saved());
        }
    }

    @Test void finalRecheckRejectsDamagedAOnlyContentWithoutFallingBackToHealthyB() throws Exception {
        try (Fixture f = fixture("changed-old-content")) {
            Path blob = f.path.resolve("assets").resolve(
                    f.catalogA.byteEntries().get(RETAINED_BYTE).content().sha256() + ".blob");
            byte[] original = Files.readAllBytes(blob);
            byte[] damaged = original.clone();
            damaged[0] ^= 1;
            f.validator.set((version, content) -> {
                if (version.id().equals(RETAINED)) Files.write(blob, damaged);
                return List.of();
            });
            assertCode(ProjectStorageException.Code.CORRUPT_CONTENT, () ->
                    f.access.readValidated(f.captured, f.registry, () -> false));
            assertArrayEquals(damaged, Files.readAllBytes(blob));
            assertCode(ProjectStorageException.Code.CORRUPT_CONTENT, () -> f.access.read(f.captured));
            assertEquals(f.b, f.access.read().saved());
            Files.write(blob, original);
            f.validator.set(ACCEPT);
            assertA(f, f.access.readValidated(f.captured, f.registry, () -> false));
        }
    }

    @Test void foreignClosedReopenedAndNullCapabilitiesAreRejectedAtAccessBoundary() throws Exception {
        Path project = directory.resolve("issuer");
        ProjectSnapshot captured;
        LocalProjectAccess oldAccess;
        try (LocalProjectStore store = LocalProjectStore.create(project);
                LocalProjectStore foreign = LocalProjectStore.create(directory.resolve("foreign"))) {
            ProjectState state = new ProjectState(PROJECT, "same project id", Map.of(), Map.of(), Map.of(), null);
            store.commit(null, UUID.randomUUID(), state);
            foreign.commit(null, UUID.randomUUID(), state);
            oldAccess = new LocalProjectAccess(store);
            captured = oldAccess.captureSnapshot();
            LocalProjectAccess other = new LocalProjectAccess(foreign);
            assertCode(ProjectStorageException.Code.REQUEST_MISMATCH, () -> other.read(captured));
            assertCode(ProjectStorageException.Code.REQUEST_MISMATCH,
                    () -> other.readValidated(captured, registry(ACCEPT, Set.of("B")), () -> false));
            assertThrows(NullPointerException.class, () -> oldAccess.read((ProjectSnapshot) null));
            assertThrows(NullPointerException.class,
                    () -> oldAccess.readValidated(null, registry(ACCEPT, Set.of("B")), () -> false));
            assertNotNull(other.captureSnapshot());
        }
        assertCode(ProjectStorageException.Code.CLOSED, () -> oldAccess.read(captured));
        assertCode(ProjectStorageException.Code.CLOSED, oldAccess::captureSnapshot);
        try (LocalProjectStore reopened = LocalProjectStore.open(project)) {
            LocalProjectAccess access = new LocalProjectAccess(reopened);
            assertCode(ProjectStorageException.Code.REQUEST_MISMATCH, () -> access.read(captured));
            assertEquals(PROJECT, access.read(access.captureSnapshot()).saved().state().projectId());
        }
    }

    @Test void fixedUnknownAssetGraphKeepsEveryOriginalEntryAndProtectionDiagnostic() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(directory.resolve("unknown"))) {
            ProjectState.Entry first = new ProjectState.Entry(BYTE, RESOURCES.toString(), "future:resource",
                    "future:opaque", 7, "unknown descriptor", store.stage(BlobRef.Kind.ASSET, bytes("opaque A")),
                    Set.of(), Set.of(OUTPUT));
            ProjectState.Entry second = asset(OUTPUT, store.stage(BlobRef.Kind.ASSET, bytes("opaque output")));
            ProjectState.Entry doc = document(DOCUMENT, store.stage(BlobRef.Kind.DOCUMENT, bytes("opaque document")),
                    Set.of(), Set.of(BYTE, OUTPUT));
            SavedProject a = store.commit(null, UUID.randomUUID(), new ProjectState(PROJECT, "unknown A",
                    modules(), Map.of(DOCUMENT, doc), Map.of(BYTE, first, OUTPUT, second), null)).saved();
            LocalProjectAccess access = new LocalProjectAccess(store);
            ProjectSnapshot captured = access.captureSnapshot();
            SavedProject b = store.commit(a.commitId(), UUID.randomUUID(), new ProjectState(PROJECT, "B",
                    Map.of(), Map.of(), Map.of(), null)).saved();
            AtomicInteger calls = new AtomicInteger();
            ExtensionRegistry registry = registry((version, content) -> { calls.incrementAndGet(); return List.of(); }, Set.of("B"));
            for (LoadedProject loaded : List.of(access.read(captured), access.readValidated(captured, registry, () -> false))) {
                assertEquals(a, loaded.saved());
                assertEquals(Map.of(BYTE, first, OUTPUT, second), loaded.saved().state().assets());
                assertArrayEquals(bytes("opaque document"), loaded.documentBytes(DOCUMENT));
                assertTrue(loaded.resources().isEmpty());
                assertTrue(loaded.resourceFailure().isPresent());
                assertEquals(1, loaded.problems().size());
                assertEquals(Problem.Severity.ERROR, loaded.problems().get(0).severity());
                assertCode(loaded.problems(), "resource_catalog_unavailable");
                assertFalse(loaded.validatedWith(registry));
            }
            assertEquals(0, calls.get());
            assertEquals(b, access.read().saved());
        }
    }

    @Test void fixedReadKeeps64MiBDeclaredBudgetEvenWhenCurrentBIsSmall() throws Exception {
        try (LocalProjectStore store = LocalProjectStore.create(directory.resolve("budget"))) {
            long half = LocalProjectAccess.MAX_DOCUMENT_BYTES / 2;
            BlobRef shared = store.stage(BlobRef.Kind.DOCUMENT, new byte[(int) half]);
            Map<UUID, ProjectState.Entry> documents = Map.of(
                    DOCUMENT, document(DOCUMENT, shared, Set.of(), Set.of()),
                    OLD_DOCUMENT, document(OLD_DOCUMENT, shared, Set.of(), Set.of()),
                    id(4), document(id(4), shared, Set.of(), Set.of()),
                    id(5), document(id(5), shared, Set.of(), Set.of()));
            SavedProject a = store.commit(null, UUID.randomUUID(), new ProjectState(PROJECT, "large A",
                    Map.of(OWNER.toString(), BUILD), documents, Map.of(), null)).saved();
            LocalProjectAccess access = new LocalProjectAccess(store);
            ProjectSnapshot captured = access.captureSnapshot();
            SavedProject b = store.commit(a.commitId(), UUID.randomUUID(), new ProjectState(PROJECT, "small B",
                    Map.of(), Map.of(), Map.of(), null)).saved();
            for (org.junit.jupiter.api.function.Executable read : List.<org.junit.jupiter.api.function.Executable>of(
                    () -> access.read(captured),
                    () -> access.readValidated(captured, registry(ACCEPT, Set.of("B")), () -> false))) {
                SessionRejection refusal = assertThrows(SessionRejection.class, read);
                assertCode(refusal.problems(), "document_bytes_exceeded");
                TextContent.Message message = (TextContent.Message) refusal.problems().get(0).message();
                assertEquals(List.of(new TextContent.NumberArgument(java.math.BigDecimal.valueOf(4 * half)),
                        new TextContent.NumberArgument(java.math.BigDecimal.valueOf(LocalProjectAccess.MAX_DOCUMENT_BYTES))),
                        message.arguments());
            }
            assertEquals(a, store.load(captured));
            assertEquals(b, access.read().saved());
        }
    }

    private Fixture fixture(String name) throws Exception { return new Fixture(directory.resolve(name)); }

    private static final class Fixture implements AutoCloseable {
        final Path path;
        final LocalProjectStore store;
        final LocalProjectAccess access;
        final SavedProject a, b;
        final ProjectSnapshot captured;
        final byte[] headA;
        final ResourceCatalog catalogA, catalogB;
        final Map<UUID, byte[]> assetBytes = Map.of(BYTE, bytes("A source"), OUTPUT, bytes("A output"),
                RETAINED_BYTE, bytes("A inactive retained only"));
        final AtomicReference<ResourceContentValidator> validator = new AtomicReference<>(ACCEPT);
        final ExtensionRegistry registry = registry((version, content) -> validator.get().validate(version, content),
                Set.of("old", "active", "retained", "B"));

        Fixture(Path path) throws Exception {
            this.path = path;
            store = LocalProjectStore.create(path);
            Map<UUID, ProjectState.Entry> entries = new java.util.TreeMap<>();
            for (var entry : assetBytes.entrySet())
                entries.put(entry.getKey(), asset(entry.getKey(), store.stage(BlobRef.Kind.ASSET, entry.getValue())));
            catalogA = new ResourceCatalog(Map.of(RESOURCE, new LogicalResource(RESOURCE, ASSET_TYPE, "A resource", ACTIVE)),
                    Map.of(OLD, version(OLD, RESOURCE, "old", BYTE, Set.of(), Set.of()),
                            ACTIVE, version(ACTIVE, RESOURCE, "active", BYTE, Set.of(OLD), Set.of(OUTPUT)),
                            RETAINED, version(RETAINED, RESOURCE, "retained", RETAINED_BYTE, Set.of(), Set.of())), entries);
            ProjectState.Entry doc = document(DOCUMENT, store.stage(BlobRef.Kind.DOCUMENT, bytes("document A")),
                    Set.of(OLD_DOCUMENT), Set.of(RESOURCE, ACTIVE));
            ProjectState.Entry oldDoc = document(OLD_DOCUMENT, store.stage(BlobRef.Kind.DOCUMENT, bytes("A only document")),
                    Set.of(), Set.of());
            a = store.commit(null, UUID.randomUUID(), new ProjectState(PROJECT, "A",
                    modules(), Map.of(DOCUMENT, doc, OLD_DOCUMENT, oldDoc),
                    ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalogA)), null)).saved();
            access = new LocalProjectAccess(store);
            captured = access.captureSnapshot();
            headA = Files.readAllBytes(path.resolve("CURRENT"));
            catalogB = new ResourceCatalog(Map.of(B_RESOURCE, new LogicalResource(B_RESOURCE, ASSET_TYPE, "B resource", B_VERSION)),
                    Map.of(B_VERSION, version(B_VERSION, B_RESOURCE, "B", B_BYTE, Set.of(), Set.of())),
                    Map.of(B_BYTE, asset(B_BYTE, store.stage(BlobRef.Kind.ASSET, bytes("B only bytes")))));
            b = store.commit(a.commitId(), UUID.randomUUID(), new ProjectState(PROJECT, "B", modules(),
                    Map.of(DOCUMENT, document(DOCUMENT, store.stage(BlobRef.Kind.DOCUMENT, bytes("document B")), Set.of(), Set.of())),
                    ResourceStorageBridge.stage(store, ResourceStorageBridge.planAll(catalogB)), null)).saved();
        }

        @Override public void close() throws IOException { store.close(); }
    }

    private static void assertA(Fixture f, LoadedProject loaded) {
        assertEquals(f.a, loaded.saved());
        assertTrue(f.access.owns(loaded));
        assertEquals(Set.of(DOCUMENT, OLD_DOCUMENT), loaded.documentIds());
        assertArrayEquals(bytes("document A"), loaded.documentBytes(DOCUMENT));
        assertArrayEquals(bytes("A only document"), loaded.documentBytes(OLD_DOCUMENT));
        assertEquals(Set.of(OLD_DOCUMENT), loaded.saved().state().documents().get(DOCUMENT).documentDependencies());
        assertEquals(Set.of(RESOURCE, ACTIVE), loaded.saved().state().documents().get(DOCUMENT).assetDependencies());
        assertEquals(f.catalogA, loaded.resources().orElseThrow());
        assertEquals(Set.of(OLD), loaded.resources().orElseThrow().versions().get(ACTIVE).dependencyVersions());
        assertTrue(loaded.problems().isEmpty());
        assertTrue(loaded.resourceFailure().isEmpty());
    }

    private static ExtensionRegistry registry(ResourceContentValidator validator, Set<String> readable) {
        TestType type = new TestType();
        type.dependencyFunction = value -> value.equals("document A")
                ? new DocumentDependencies(Set.of(OLD_DOCUMENT), Set.of(new ResourceRef(RESOURCE, ACTIVE)), Set.of())
                : NO_DEPENDENCIES;
        var builder = ProjectSessionFixtures.builder(type);
        builder.addModule(new ModuleSpec(RESOURCES, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), IMPORTER),
                OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(readable));
        builder.add(ResourceContentValidators.KIND, new Contribution(new ContributionKey(ResourceContentValidators.KIND.id(), IMPORTER),
                OWNER, Contribution.Side.COMMON, Set.of()), validator);
        return builder.freeze();
    }

    private static ResourceVersion version(UUID id, UUID resource, String producerVersion, UUID source,
            Set<UUID> dependencies, Set<UUID> outputs) {
        return new ResourceVersion(id, resource, IMPORTER, producerVersion, Map.of("fixed", producerVersion), source,
                dependencies, outputs);
    }
    private static Map<String, String> modules() { return Map.of(OWNER.toString(), BUILD, RESOURCES.toString(), "1"); }
    private static ProjectState.Entry asset(UUID id, BlobRef blob) {
        return new ProjectState.Entry(id, OWNER.toString(), ASSET_TYPE.toString(), "synthetic:raw", 1, "bytes", blob, Set.of(), Set.of());
    }
    private static ProjectState.Entry document(UUID id, BlobRef blob, Set<UUID> documents, Set<UUID> assets) {
        return new ProjectState.Entry(id, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1, "note", blob, documents, assets);
    }
    private static void assertCode(List<Problem> problems, String suffix) {
        assertTrue(problems.stream().anyMatch(p -> p.code().equals(EditorId.parse("editor:session/" + suffix))), problems.toString());
    }
    private static void assertCode(ProjectStorageException.Code expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(ProjectStorageException.class, action).code());
    }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static UUID id(long value) { return new UUID(0x5150353500000000L, value); }
}
