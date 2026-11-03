package net.ptcrys.fpsmatch.editor.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

/**
 * The fixed resource selection plan: it resolves the closure without touching a store, and the store
 * bridge stages exactly the selection a plan lists instead of traversing the catalog a second time.
 */
class ResourcePlanTest {
    private static final EditorId TEXTURE_TYPE = EditorId.parse("editor:texture");
    private static final EditorId MODEL_TYPE = EditorId.parse("editor:model");
    private static final EditorId TEXT_IMPORTER = EditorId.parse("editor:text-java2d");
    private static final EditorId MODEL_IMPORTER = EditorId.parse("editor:model-gltf");

    private static final UUID TEXTURE = id(1);
    private static final UUID TEXTURE_V1 = id(2);
    private static final UUID MODEL = id(3);
    private static final UUID MODEL_V0 = id(4);
    private static final UUID MODEL_V1 = id(5);
    private static final UUID MODEL_V2 = id(6);
    private static final UUID EXTRA_RESOURCE = id(7);
    private static final UUID EXTRA_VERSION = id(8);

    private static final UUID TEXTURE_BYTES = id(20);
    private static final UUID MODEL_BYTES = id(21);
    private static final UUID MODEL_SOURCE_V1 = id(22);
    private static final UUID MODEL_SOURCE_V2 = id(23);
    private static final UUID DOCUMENT = id(40);
    private static final UUID REQUEST = id(50);

    @TempDir
    Path temp;

    @Test
    void planSelectsTheCurrentClosureAndALockedOldVersion() throws IOException {
        Path project = temp.resolve("plan-closure");
        Fixture fixture = prepare(project);

        ResourcePlan locked = ResourceStorageBridge.plan(fixture.catalog(),
                Set.of(new ResourceRef(MODEL, MODEL_V1)));
        assertEquals(new TreeSet<>(Set.of(TEXTURE, MODEL)), new TreeSet<>(locked.resources().keySet()));
        assertEquals(new TreeSet<>(Set.of(TEXTURE_V1, MODEL_V1, MODEL_V2)),
                new TreeSet<>(locked.versions().keySet()));
        assertEquals(new TreeSet<>(Set.of(TEXTURE_BYTES, MODEL_BYTES, MODEL_SOURCE_V1, MODEL_SOURCE_V2)),
                new TreeSet<>(locked.byteEntries().keySet()));
        assertFalse(locked.versions().containsKey(MODEL_V0), "an unreachable old version must stay out");
        // A locked old version keeps the dependencies the stored manifest will declare.
        assertEquals(Set.of(TEXTURE_V1, TEXTURE_BYTES, MODEL_SOURCE_V1),
                ResourceSupport.versionDependencies(locked.versions().get(MODEL_V1)));

        // The plan lists the immutable descriptions themselves, never copies of the payloads.
        assertSame(fixture.catalog().resources().get(MODEL), locked.resources().get(MODEL));
        assertSame(fixture.catalog().versions().get(MODEL_V1), locked.versions().get(MODEL_V1));
        assertSame(fixture.modelEntry(), locked.byteEntries().get(MODEL_BYTES));
        assertSortedAndImmutable(locked.resources());
        assertSortedAndImmutable(locked.versions());
        assertSortedAndImmutable(locked.byteEntries());

        ResourcePlan current = ResourceStorageBridge.plan(fixture.catalog(), Set.of());
        assertEquals(new TreeSet<>(Set.of(TEXTURE_V1, MODEL_V2)), new TreeSet<>(current.versions().keySet()));
        assertEquals(new TreeSet<>(Set.of(TEXTURE_BYTES, MODEL_BYTES, MODEL_SOURCE_V2)),
                new TreeSet<>(current.byteEntries().keySet()));
    }

    @Test
    void stageWritesThePlannedSelectionAndTheOldOverloadAgrees() throws IOException {
        Path project = temp.resolve("plan-stage");
        Fixture fixture = prepare(project);
        Set<ResourceRef> refs = Set.of(new ResourceRef(MODEL, MODEL_V1));
        ResourcePlan plan = ResourceStorageBridge.plan(fixture.catalog(), refs);

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, plan);
            Set<UUID> planned = new TreeSet<>(plan.resources().keySet());
            planned.addAll(plan.versions().keySet());
            planned.addAll(plan.byteEntries().keySet());
            assertEquals(planned, new TreeSet<>(staged.keySet()));
            assertFalse(staged.containsKey(MODEL_V0));

            // Descriptors carry the planned descriptions: size, identity and dependency union.
            ProjectState.Entry version = staged.get(MODEL_V1);
            assertEquals("editor:resources", version.ownerModule());
            assertEquals("editor:resource_version", version.typeId());
            assertEquals("editor:resource_binary", version.codecId());
            assertEquals(1, version.schemaVersion());
            assertEquals(Set.of(TEXTURE_V1, TEXTURE_BYTES, MODEL_SOURCE_V1), version.assetDependencies());
            assertEquals("lamp.gltf", staged.get(MODEL).displayName());
            assertEquals(Set.of(MODEL_V2), staged.get(MODEL).assetDependencies());
            assertSame(fixture.modelEntry(), staged.get(MODEL_BYTES));
            assertSame(fixture.textureEntry(), staged.get(TEXTURE_BYTES));

            // The old overload keeps its signature and behaviour: same catalog and lock set, same map.
            assertEquals(staged, ResourceStorageBridge.stage(store, fixture.catalog(), refs));
            assertThrows(UnsupportedOperationException.class, () -> staged.clear());
        }
    }

    @Test
    void stageNeverReinfersLocksAndAPlanIsAFixedSnapshot() throws IOException {
        Path project = temp.resolve("plan-snapshot");
        Fixture fixture = prepare(project);
        Map<UUID, LogicalResource> callerResources = new LinkedHashMap<>(fixture.catalog().resources());
        ResourceCatalog catalog = new ResourceCatalog(callerResources, fixture.catalog().versions(),
                fixture.catalog().byteEntries());
        Set<ResourceRef> refs = new LinkedHashSet<>();
        refs.add(new ResourceRef(MODEL, MODEL_V1));

        ResourcePlan plan = ResourceStorageBridge.plan(catalog, refs);

        // The caller keeps its own maps and sets: renaming, adding a resource or dropping the lock
        // afterwards can neither move the issued plan nor the selection staged from it.
        callerResources.put(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "renamed.gltf", MODEL_V2));
        callerResources.put(EXTRA_RESOURCE, new LogicalResource(EXTRA_RESOURCE, TEXTURE_TYPE,
                "extra.png", EXTRA_VERSION));
        refs.clear();
        refs.add(new ResourceRef(MODEL, MODEL_V0));

        assertEquals("lamp.gltf", plan.resources().get(MODEL).name());
        assertFalse(plan.resources().containsKey(EXTRA_RESOURCE));
        assertTrue(plan.versions().containsKey(MODEL_V1));
        assertFalse(plan.versions().containsKey(MODEL_V0));

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, plan);
            assertEquals("lamp.gltf", staged.get(MODEL).displayName());
            assertFalse(staged.containsKey(EXTRA_RESOURCE));
            assertTrue(staged.containsKey(MODEL_V1));
            assertFalse(staged.containsKey(MODEL_V0));

            // A lock added after planning reaches a later plan only, and staging it follows that plan.
            ResourcePlan later = ResourceStorageBridge.plan(catalog, refs);
            assertEquals(new TreeSet<>(Set.of(TEXTURE_V1, MODEL_V0, MODEL_V2)),
                    new TreeSet<>(later.versions().keySet()));
            assertTrue(ResourceStorageBridge.stage(store, later).containsKey(MODEL_V0));
        }
    }

    @Test
    void planRejectsInvalidGraphsWithoutTouchingAnyStore() throws IOException {
        Path project = committedProject("plan-reject");
        long before = fileCount(project);
        ResourceCatalog simple = simpleCatalog();

        assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.plan(simple,
                Set.of(new ResourceRef(MODEL, id(77)))));
        assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.plan(simple,
                Set.of(new ResourceRef(MODEL, TEXTURE_V1))));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceStorageBridge.plan(missingCurrentVersion(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.plan(cyclic(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceStorageBridge.plan(impostorByteEntry(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ResourceStorageBridge.plan(documentDependentByteEntry(), Set.of()));

        assertEquals(before, fileCount(project));
    }

    @Test
    void planNeedsNoStoreSoAMissingSelectedBlobOnlyFailsAtStage() throws IOException {
        Path project = committedProject("plan-missing-blob");
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                Map.of(MODEL_BYTES, fabricated(MODEL_BYTES)));

        // The selected blob was never published: the plan still resolves, because it has no store.
        ResourcePlan plan = ResourceStorageBridge.plan(catalog, Set.of());
        assertEquals(new TreeSet<>(Set.of(MODEL_BYTES)), new TreeSet<>(plan.byteEntries().keySet()));

        long before = fileCount(project);
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IOException.class, () -> ResourceStorageBridge.stage(store, plan));
        }
        // Verification ran before any descriptor was encoded or written.
        assertEquals(before, fileCount(project));
    }

    @Test
    void stageRejectsACorruptSelectedBlobBeforePublishingDescriptors() throws IOException {
        Path project = committedProject("plan-corrupt-blob");
        BlobRef content;
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            content = store.stage(BlobRef.Kind.ASSET, "pixels".getBytes(StandardCharsets.UTF_8));
        }
        // Same length, different bytes: the digest no longer matches the selected reference.
        Files.writeString(project.resolve("assets").resolve(content.sha256() + ".blob"),
                "pixelz", StandardCharsets.UTF_8);
        ProjectState.Entry entry = new ProjectState.Entry(MODEL_BYTES, "editor:text-java2d",
                "editor:texture_png", "editor:png_binary", 1, "byte-" + MODEL_BYTES, content,
                Set.of(), Set.of());
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                Map.of(MODEL_BYTES, entry));

        ResourcePlan plan = ResourceStorageBridge.plan(catalog, Set.of());
        long before = fileCount(project);
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IOException.class, () -> ResourceStorageBridge.stage(store, plan));
        }
        assertEquals(before, fileCount(project));
    }

    @Test
    void everySelectedBlobIsVerifiedBeforeAnyDescriptorIsStaged() throws IOException {
        for (boolean corrupt : List.of(false, true)) {
            Path project = committedProject(corrupt ? "later-corrupt" : "later-missing");
            ProjectState.Entry good;
            ProjectState.Entry bad;
            try (LocalProjectStore store = LocalProjectStore.open(project)) {
                good = asset(store, TEXTURE_BYTES, "editor:text-java2d", "editor:texture_png",
                        "editor:png_binary", "good pixels", Set.of());
                bad = corrupt ? asset(store, MODEL_BYTES, "editor:model-gltf", "editor:mesh_binary",
                        "editor:gltf_binary", "mesh", Set.of()) : fabricated(MODEL_BYTES);
            }
            if (corrupt) {
                Files.writeString(project.resolve("assets").resolve(bad.content().sha256() + ".blob"),
                        "MESH", StandardCharsets.UTF_8);
            }
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "two blobs", MODEL_V1)),
                    Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1",
                            Map.of(), null, Set.of(), Set.of(TEXTURE_BYTES, MODEL_BYTES))),
                    Map.of(TEXTURE_BYTES, good, MODEL_BYTES, bad));
            ResourcePlan plan = ResourceStorageBridge.plan(catalog, Set.of());
            assertEquals(List.of(TEXTURE_BYTES, MODEL_BYTES),
                    new ArrayList<>(plan.byteEntries().keySet()));
            long before = fileCount(project);
            try (LocalProjectStore store = LocalProjectStore.open(project)) {
                assertThrows(IOException.class, () -> ResourceStorageBridge.stage(store, plan));
            }
            assertEquals(before, fileCount(project), "a valid first blob must not trigger writes");
        }
    }

    @Test
    void anUnselectedOldVersionsMissingBlobIsNeitherVerifiedNorStaged() throws IOException {
        Path project = committedProject("unselected-missing");
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            ProjectState.Entry good = asset(store, MODEL_BYTES, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "current mesh", Set.of());
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp", MODEL_V2)),
                    Map.of(MODEL_V0, new ResourceVersion(MODEL_V0, MODEL, MODEL_IMPORTER, "0",
                                    Map.of(), null, Set.of(), Set.of(MODEL_SOURCE_V1)),
                            MODEL_V2, new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "2",
                                    Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                    Map.of(MODEL_SOURCE_V1, fabricated(MODEL_SOURCE_V1), MODEL_BYTES, good));
            ResourcePlan current = ResourceStorageBridge.plan(catalog, Set.of());
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, current);
            assertEquals(Set.of(MODEL, MODEL_V2, MODEL_BYTES), staged.keySet());
            ResourcePlan locked = ResourceStorageBridge.plan(catalog,
                    Set.of(new ResourceRef(MODEL, MODEL_V0)));
            assertThrows(IOException.class, () -> ResourceStorageBridge.stage(store, locked));
        }
    }

    @Test
    void planMeasuresDescriptorsAndRejectsTheTotalBudgetWithoutWriting() throws IOException {
        Path project = committedProject("plan-total-budget");
        // Every version stays below the per descriptor bound while the chain of eighty exceeds the
        // cumulative descriptor budget, so the plan refuses it before a store is ever opened.
        Map<String, String> settings = new LinkedHashMap<>();
        for (int index = 0; index < 15; index++) {
            settings.put("k" + index, "a".repeat(60000));
        }
        Map<UUID, ResourceVersion> versions = new LinkedHashMap<>();
        for (int index = 1; index <= 80; index++) {
            UUID versionId = id(600 + index);
            UUID next = index == 80 ? null : id(600 + index + 1);
            versions.put(versionId, new ResourceVersion(versionId, MODEL, MODEL_IMPORTER, "1.0.0",
                    settings, null, next == null ? Set.of() : Set.of(next), Set.of()));
        }
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", id(601))),
                versions, Map.of());

        long before = fileCount(project);
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> ResourceStorageBridge.plan(catalog, Set.of()));
        assertTrue(rejected.getMessage().contains("total budget"), rejected.getMessage());
        assertEquals(before, fileCount(project));
    }

    @Test
    void nullArgumentsAreProgrammingErrors() throws IOException {
        Path project = committedProject("plan-null");
        ResourceCatalog catalog = simpleCatalog();
        ResourcePlan plan = ResourceStorageBridge.plan(catalog, Set.of());

        assertThrows(NullPointerException.class, () -> ResourceStorageBridge.plan(null, Set.of()));
        assertThrows(NullPointerException.class, () -> ResourceStorageBridge.plan(catalog, null));
        assertThrows(NullPointerException.class, () -> ResourceStorageBridge.plan(catalog,
                Set.of((ResourceRef) null)));
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(NullPointerException.class, () -> ResourceStorageBridge.stage(store, (ResourcePlan) null));
            assertThrows(NullPointerException.class, () -> ResourceStorageBridge.stage(null, plan));
            assertThrows(NullPointerException.class,
                    () -> ResourceStorageBridge.stage(store, catalog, null));
            assertThrows(NullPointerException.class,
                    () -> ResourceStorageBridge.stage(null, catalog, Set.of()));
        }
    }

    /** A published project whose assets already hold every byte the fixtures reach. */
    private Fixture prepare(Path project) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState.Entry textureEntry = asset(store, TEXTURE_BYTES, "editor:text-java2d",
                    "editor:texture_png", "editor:png_binary", "pixels", Set.of());
            ProjectState.Entry modelEntry = asset(store, MODEL_BYTES, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "mesh", Set.of());
            ProjectState.Entry sourceV1 = asset(store, MODEL_SOURCE_V1, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "source-one", Set.of());
            ProjectState.Entry sourceV2 = asset(store, MODEL_SOURCE_V2, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "source-two", Set.of());
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(TEXTURE, new LogicalResource(TEXTURE, TEXTURE_TYPE, "brick.png", TEXTURE_V1),
                            MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V2)),
                    Map.of(TEXTURE_V1, new ResourceVersion(TEXTURE_V1, TEXTURE, TEXT_IMPORTER, "1.0.0",
                                    Map.of("srgb", "true"), TEXTURE_BYTES, Set.of(), Set.of()),
                            MODEL_V0, new ResourceVersion(MODEL_V0, MODEL, MODEL_IMPORTER, "0.9.0",
                                    Map.of(), null, Set.of(), Set.of()),
                            MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                                    Map.of(), MODEL_SOURCE_V1, Set.of(TEXTURE_V1), Set.of(TEXTURE_BYTES)),
                            MODEL_V2, new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "1.1.0",
                                    Map.of("lod", "2"), MODEL_SOURCE_V2, Set.of(TEXTURE_V1),
                                    Set.of(MODEL_BYTES))),
                    Map.of(TEXTURE_BYTES, textureEntry, MODEL_BYTES, modelEntry,
                            MODEL_SOURCE_V1, sourceV1, MODEL_SOURCE_V2, sourceV2));

            // The committed state carries the whole current closure plus the locked old version, so
            // every blob a fixture selects already exists while staging still re-verifies it.
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, catalog,
                    Set.of(new ResourceRef(MODEL, MODEL_V1)));
            Map<UUID, ProjectState.Entry> document = Map.of(DOCUMENT,
                    new ProjectState.Entry(DOCUMENT, "editor:documents", "editor:document",
                            "editor:document_json", 1, "scene.doc",
                            store.stage(BlobRef.Kind.DOCUMENT,
                                    "scene".getBytes(StandardCharsets.UTF_8)),
                            Set.of(), Set.of(MODEL, MODEL_V1)));
            store.commit(null, REQUEST, state(document, staged));
            return new Fixture(catalog, textureEntry, modelEntry);
        }
    }

    private Path committedProject(String name) throws IOException {
        Path project = temp.resolve(name);
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.commit(null, REQUEST, state(Map.of(), Map.of()));
        }
        return project;
    }

    private static ResourceCatalog simpleCatalog() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of())),
                Map.of());
    }

    /** A resource whose current version was never put in the catalog. */
    private static ResourceCatalog missingCurrentVersion() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V2)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of())),
                Map.of());
    }

    /** Two versions that depend on each other, so no root can resolve. */
    private static ResourceCatalog cyclic() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(MODEL_V2), Set.of()),
                        MODEL_V2, new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "1.1.0",
                                Map.of(), null, Set.of(MODEL_V1), Set.of())),
                Map.of());
    }

    /** Byte content that claims the descriptor identity the bridge owns. */
    private static ResourceCatalog impostorByteEntry() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                Map.of(MODEL_BYTES, new ProjectState.Entry(MODEL_BYTES, "editor:resources",
                        "editor:resource", "editor:resource_binary", 1, "impostor",
                        new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(), Set.of())));
    }

    /** Byte content that also depends on a document, which the resource bridge cannot publish. */
    private static ResourceCatalog documentDependentByteEntry() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                Map.of(MODEL_BYTES, new ProjectState.Entry(MODEL_BYTES, "editor:text-java2d",
                        "editor:texture_png", "editor:png_binary", 1, "byte-" + MODEL_BYTES,
                        new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(DOCUMENT),
                        Set.of())));
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID entryId, String owner,
                                            String typeId, String codecId, String body,
                                            Set<UUID> assetDependencies) throws IOException {
        BlobRef content = store.stage(BlobRef.Kind.ASSET, body.getBytes(StandardCharsets.UTF_8));
        return new ProjectState.Entry(entryId, owner, typeId, codecId, 1, "byte-" + entryId,
                content, Set.of(), assetDependencies);
    }

    /** An asset entry whose blob was never published at all. */
    private static ProjectState.Entry fabricated(UUID entryId) {
        return new ProjectState.Entry(entryId, "editor:text-java2d", "editor:texture_png",
                "editor:png_binary", 1, "byte-" + entryId,
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(), Set.of());
    }

    private static ProjectState state(Map<UUID, ProjectState.Entry> documents,
                                      Map<UUID, ProjectState.Entry> assets) {
        return new ProjectState(id(900), "bridge",
                Map.of("editor:resources", "1.0.0", "editor:text-java2d", "1.0.0",
                        "editor:model-gltf", "1.1.0", "editor:documents", "1.0.0"),
                documents, assets, null);
    }

    /** A plan map is identity-sorted and immutable, so a caller can read it but never edit it. */
    private static void assertSortedAndImmutable(Map<UUID, ?> map) {
        List<UUID> keys = new ArrayList<>(map.keySet());
        assertEquals(new ArrayList<>(new TreeSet<>(keys)), keys);
        assertThrows(UnsupportedOperationException.class, map::clear);
    }

    private static long fileCount(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private static UUID id(long value) {
        return new UUID(0x5054430000000000L, value);
    }

    /** The fixture catalog with the byte entries the store already published. */
    private record Fixture(ResourceCatalog catalog, ProjectState.Entry textureEntry,
                           ProjectState.Entry modelEntry) {
    }
}
