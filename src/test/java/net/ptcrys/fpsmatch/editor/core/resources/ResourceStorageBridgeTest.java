package net.ptcrys.fpsmatch.editor.core.resources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

/** Behaviour of the resource to storage bridge: closure, identity stability and rejection. */
class ResourceStorageBridgeTest {
    private static final EditorId TEXTURE_TYPE = EditorId.parse("editor:texture");
    private static final EditorId MODEL_TYPE = EditorId.parse("editor:model");
    private static final EditorId TEXT_IMPORTER = EditorId.parse("editor:text-java2d");
    private static final EditorId MODEL_IMPORTER = EditorId.parse("editor:model-gltf");

    private static final UUID TEXTURE = id(1);
    private static final UUID TEXTURE_V1 = id(2);
    private static final UUID MODEL = id(3);
    private static final UUID MODEL_V1 = id(4);
    private static final UUID MODEL_V2 = id(5);
    private static final UUID MODEL_V0 = id(6);
    private static final UUID TEXTURE_BYTES = id(20);
    private static final UUID MODEL_BYTES = id(21);
    private static final UUID MODEL_SOURCE_V1 = id(22);
    private static final UUID MODEL_SOURCE_V2 = id(23);
    private static final UUID DOCUMENT = id(52);
    private static final UUID LOCKED_REQUEST = id(901);

    private static final String MODEL_SOURCE_V1_BODY = "mesh-source-v1";
    private static final String MODEL_SOURCE_V2_BODY = "mesh-source-v2";

    @TempDir
    Path temp;

    private record Fixture(ResourceCatalog catalog, Map<UUID, ProjectState.Entry> staged,
                           ProjectState.Entry textureEntry, ProjectState.Entry modelEntry) {
    }

    @Test
    void stageResolvesTheReachableClosureAndRoundTripsThroughSnapshot() throws IOException {
        Path project = temp.resolve("project");
        Path archive = temp.resolve("bridge.editorarchive");
        Path restored = temp.resolve("restored");

        Fixture fixture = prepare(project, archive);
        assertEquals(Set.of(TEXTURE, MODEL, TEXTURE_V1, MODEL_V2, MODEL_V1, TEXTURE_BYTES, MODEL_BYTES,
                        MODEL_SOURCE_V1, MODEL_SOURCE_V2),
                new TreeSet<>(fixture.staged().keySet()));

        ProjectState.Entry descriptor = fixture.staged().get(MODEL);
        assertEquals("editor:resources", descriptor.ownerModule());
        assertEquals("editor:resource", descriptor.typeId());
        assertEquals("editor:resource_binary", descriptor.codecId());
        assertEquals(1, descriptor.schemaVersion());
        assertEquals("lamp.gltf", descriptor.displayName());
        assertEquals(Set.of(MODEL_V2), descriptor.assetDependencies());
        assertTrue(descriptor.documentDependencies().isEmpty());

        ProjectState.Entry versionDescriptor = fixture.staged().get(MODEL_V2);
        assertEquals("editor:resource_version", versionDescriptor.typeId());
        assertEquals("editor:resource_binary", versionDescriptor.codecId());
        assertEquals(MODEL_V2.toString(), versionDescriptor.displayName());
        assertEquals(Set.of(TEXTURE_V1, MODEL_BYTES, MODEL_SOURCE_V2),
                versionDescriptor.assetDependencies());
        assertTrue(versionDescriptor.documentDependencies().isEmpty());

        // Byte content is carried over verbatim; descriptors never become byte content.
        assertEquals(fixture.modelEntry(), fixture.staged().get(MODEL_BYTES));
        assertEquals(fixture.textureEntry(), fixture.staged().get(TEXTURE_BYTES));
        // The locked version travels with the current one, but the old unreachable version does not.
        assertEquals(Set.of(TEXTURE_V1, TEXTURE_BYTES, MODEL_SOURCE_V1),
                fixture.staged().get(MODEL_V1).assetDependencies());
        assertFalse(fixture.staged().containsKey(MODEL_V0));
        assertThrows(UnsupportedOperationException.class, () -> fixture.staged().remove(MODEL));

        SavedProject imported = LocalProjectStore.importSnapshot(archive, restored);
        ResourceCatalog reloaded = readBack(restored, imported);
        assertEquals(new TreeSet<>(List.of(TEXTURE, MODEL)), new TreeSet<>(reloaded.resources().keySet()));
        assertEquals("brick.png", reloaded.resources().get(TEXTURE).name());
        assertEquals(TEXTURE_TYPE, reloaded.resources().get(TEXTURE).type());
        assertEquals(TEXTURE_V1, reloaded.resources().get(TEXTURE).currentVersion());
        assertEquals("lamp.gltf", reloaded.resources().get(MODEL).name());
        assertEquals(MODEL_TYPE, reloaded.resources().get(MODEL).type());
        assertEquals(MODEL_V2, reloaded.resources().get(MODEL).currentVersion());

        ResourceVersion recovered = reloaded.versions().get(MODEL_V2);
        assertEquals(MODEL_IMPORTER, recovered.importer());
        assertEquals("1.1.0", recovered.importerVersion());
        assertEquals(Map.of("alpha", "cutout", "lod", "2"), recovered.settings());
        assertEquals(MODEL, recovered.resourceId());
        assertEquals(Set.of(TEXTURE_V1), recovered.dependencyVersions());
        assertEquals(Set.of(MODEL_BYTES), recovered.outputEntries());
        assertEquals(fixture.modelEntry(), reloaded.byteEntries().get(MODEL_BYTES));
        assertEquals(fixture.textureEntry(), reloaded.byteEntries().get(TEXTURE_BYTES));
        assertFalse(reloaded.byteEntries().containsKey(MODEL));
        assertFalse(reloaded.versions().containsKey(MODEL_V0));

        // The document locked version survives the snapshot, and both source payloads are still the
        // exact bytes that were staged: the descriptor round trip did not rewrite asset content.
        ResourceVersion locked = reloaded.versions().get(MODEL_V1);
        assertEquals(MODEL_SOURCE_V1, locked.sourceEntry());
        assertEquals(MODEL_SOURCE_V2, recovered.sourceEntry());
        assertEquals(fixture.staged().get(MODEL_SOURCE_V1),
                imported.state().assets().get(MODEL_SOURCE_V1));
        assertEquals(MODEL_SOURCE_V1_BODY, content(restored,
                imported.state().assets().get(MODEL_SOURCE_V1).content()));
        assertEquals(MODEL_SOURCE_V2_BODY, content(restored,
                imported.state().assets().get(MODEL_SOURCE_V2).content()));
        assertEquals("pixels", content(restored, reloaded.byteEntries().get(TEXTURE_BYTES).content()));

        // Renaming keeps the resource identity and the current version; only the bytes move.
        LogicalResource renamed = new LogicalResource(MODEL, MODEL_TYPE, "lamp-final.gltf", MODEL_V2);
        ResourceCatalog updated = new ResourceCatalog(
                Map.of(MODEL, renamed, TEXTURE, reloaded.resources().get(TEXTURE)),
                reloaded.versions(), reloaded.byteEntries());
        Map<UUID, ProjectState.Entry> restaged = restage(restored, updated);
        assertEquals(MODEL, restaged.get(MODEL).id());
        assertEquals("lamp-final.gltf", restaged.get(MODEL).displayName());
        assertEquals(MODEL_V2, restaged.get(MODEL).assetDependencies().iterator().next());
        assertNotEquals(reloaded.resources().get(MODEL).name(), restaged.get(MODEL).displayName());
        assertNotEquals(fixture.staged().get(MODEL).content(), restaged.get(MODEL).content());
        // Nothing locks the old version any more, so re-staging leaves it out of the closure again.
        assertFalse(restaged.containsKey(MODEL_V1));
        assertFalse(restaged.containsKey(MODEL_SOURCE_V1));
    }

    @Test
    void twoResourcesWithTheSameNameStayDistinct() throws IOException {
        UUID first = id(31);
        UUID firstVersion = id(32);
        UUID second = id(33);
        UUID secondVersion = id(34);
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(first, new LogicalResource(first, TEXTURE_TYPE, "brick.png", firstVersion),
                        second, new LogicalResource(second, TEXTURE_TYPE, "brick.png", secondVersion)),
                Map.of(firstVersion, new ResourceVersion(firstVersion, first, TEXT_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(), Set.of()),
                        secondVersion, new ResourceVersion(secondVersion, second, TEXT_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(), Set.of())),
                Map.of());
        try (LocalProjectStore store = LocalProjectStore.create(temp.resolve("project"))) {
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, catalog, Set.of());
            assertEquals(Set.of(first, second, firstVersion, secondVersion), new TreeSet<>(staged.keySet()));
            assertEquals("brick.png", staged.get(first).displayName());
            assertEquals("brick.png", staged.get(second).displayName());
            assertNotEquals(staged.get(first).content(), staged.get(second).content());
        }
    }

    @Test
    void catalogDefensivelyCopiesCallerCollections() {
        Set<UUID> dependencies = new HashSet<>(Set.of(TEXTURE_V1));
        Map<String, String> settings = new HashMap<>(Map.of("lod", "1"));
        Map<UUID, LogicalResource> resources = new HashMap<>();
        resources.put(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V2));
        ResourceVersion version = new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "1.0.0",
                settings, null, dependencies, Set.of());
        ResourceCatalog catalog = new ResourceCatalog(resources,
                Map.of(MODEL_V2, version), Map.of());

        settings.put("lod", "9");
        dependencies.add(MODEL_BYTES);
        resources.put(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "changed.gltf", MODEL_V2));

        assertEquals(Map.of("lod", "1"), catalog.versions().get(MODEL_V2).settings());
        assertEquals(Set.of(TEXTURE_V1), catalog.versions().get(MODEL_V2).dependencyVersions());
        assertEquals("lamp.gltf", catalog.resources().get(MODEL).name());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.versions().get(MODEL_V2).settings().put("lod", "2"));
        assertThrows(UnsupportedOperationException.class, () -> catalog.resources().clear());
    }

    @Test
    void missingCurrentVersionIsRejectedBeforeStaging() throws IOException {
        Path project = committedProject("missing-current");
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V2, new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of())),
                Map.of());
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void unknownLockedReferenceIsRejectedBeforeStaging() throws IOException {
        Path project = committedProject("unknown-locked");
        assertRejectedWithoutWrites(project, simpleCatalog(), Set.of(new ResourceRef(MODEL, id(77))));
    }

    @Test
    void lockedVersionBelongingToAnotherResourceIsRejected() throws IOException {
        Path project = committedProject("misattributed-locked");
        assertRejectedWithoutWrites(project, simpleCatalog(), Set.of(new ResourceRef(MODEL, TEXTURE_V1)));
    }

    @Test
    void missingSourceEntryIsRejected() throws IOException {
        Path project = committedProject("missing-source");
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), id(88), Set.of(), Set.of())),
                Map.of());
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void versionDependencyCycleIsRejected() throws IOException {
        Path project = committedProject("version-cycle");
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(MODEL_V2), Set.of()),
                        MODEL_V2, new ResourceVersion(MODEL_V2, MODEL, MODEL_IMPORTER, "1.1.0",
                                Map.of(), null, Set.of(MODEL_V1), Set.of())),
                Map.of());
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void byteDependencyCycleIsRejected() throws IOException {
        Path project = committedProject("byte-cycle");
        UUID firstBytes = id(41);
        UUID secondBytes = id(42);
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(firstBytes))),
                Map.of(firstBytes, fabricated(firstBytes, secondBytes),
                        secondBytes, fabricated(secondBytes, firstBytes)));
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void byteEntryWithADocumentDependencyIsRejectedBeforeStaging() throws IOException {
        Path project = committedProject("document-edge");
        UUID documentId = id(51);
        // The rule is about the byte entry, not about who owns it: a foreign importer entry and an
        // entry that dresses up as a resource descriptor are both refused.
        ProjectState.Entry foreign = new ProjectState.Entry(MODEL_BYTES, "editor:text-java2d",
                "editor:texture_png", "editor:png_binary", 1, "document-backed",
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(documentId), Set.of());
        ProjectState.Entry disguised = new ProjectState.Entry(MODEL_BYTES, "editor:resources",
                "editor:resource", "editor:resource_binary", 1, "document-backed",
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(documentId), Set.of());
        for (ProjectState.Entry hostile : List.of(foreign, disguised)) {
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                    Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                            Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                    Map.of(MODEL_BYTES, hostile));
            assertRejectedWithoutWrites(project, catalog, Set.of());
        }
    }

    @Test
    void byteEntryClaimingADescriptorIdentityIsRejectedBeforeStagingAndOnRead() throws IOException {
        Path project = committedProject("descriptor-identity");
        // Byte content claiming a single part of the descriptor identity can neither be staged as byte
        // content nor read back as byte content: read treats the very same entry as a descriptor and
        // refuses its shape, so stage may never accept it.
        List<ProjectState.Entry> impostors = List.of(
                impostor("editor:resources", "editor:texture_png", "editor:png_binary"),
                impostor("editor:text-java2d", "editor:resource", "editor:png_binary"),
                impostor("editor:text-java2d", "editor:resource_version", "editor:png_binary"),
                impostor("editor:text-java2d", "editor:texture_png", "editor:resource_binary"));
        for (ProjectState.Entry entry : impostors) {
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                    Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                            Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                    Map.of(MODEL_BYTES, entry));
            assertRejectedWithoutWrites(project, catalog, Set.of());
            try (LocalProjectStore store = LocalProjectStore.open(project)) {
                assertThrows(IllegalArgumentException.class,
                        () -> ResourceStorageBridge.read(store, Map.of(MODEL_BYTES, entry)));
            }
        }
    }

    @Test
    void stageRejectsAMissingByteBlobBeforeWritingDescriptors() throws IOException {
        Path project = committedProject("missing-blob");
        // The entry is legal, only its content is absent: this is real store I/O, not illegal data,
        // and it must surface as an exception while no descriptor has been written yet.
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of(MODEL_BYTES))),
                Map.of(MODEL_BYTES, fabricated(MODEL_BYTES, null)));
        assertIoRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void stageRejectsACorruptByteBlobBeforeWritingDescriptors() throws IOException {
        Path project = committedProject("corrupt-blob");
        BlobRef content;
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            content = store.stage(BlobRef.Kind.ASSET, "pixels".getBytes(StandardCharsets.UTF_8));
        }
        // Same length, different bytes: the digest no longer matches the reference.
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
        assertIoRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void stageRejectsDescriptorsOverTheTotalBudgetBeforeWriting() throws IOException {
        Path project = committedProject("total-budget");
        // Each version stays below the per descriptor bound while the chain of eighty exceeds the
        // total descriptor budget; the measurement happens before any payload is written.
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
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void sharedIdentityAcrossTheGraphIsRejected() throws IOException {
        Path project = committedProject("shared-identity");
        ResourceCatalog catalog = new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                        Map.of(), null, Set.of(), Set.of())),
                Map.of(MODEL, fabricated(MODEL, null)));
        assertRejectedWithoutWrites(project, catalog, Set.of());
    }

    @Test
    void readRejectsDocumentContent() throws IOException {
        Path project = committedProject("read-document");
        ProjectState.Entry document = new ProjectState.Entry(id(61), "editor:documents",
                "editor:document", "editor:document_json", 1, "scene.doc",
                new BlobRef(BlobRef.Kind.DOCUMENT, "0".repeat(64), 8), Set.of(), Set.of());
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(id(61), document)));
        }
    }

    @Test
    void readRejectsUnsupportedDescriptorShape() throws IOException {
        Path project = committedProject("read-shape");
        ProjectState.Entry tampered = new ProjectState.Entry(MODEL, "editor:resources",
                "editor:resource", "editor:resource_json", 1, "lamp.gltf",
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 8), Set.of(), Set.of());
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(MODEL, tampered)));
        }
    }

    @Test
    void readRejectsCorruptDescriptorBytes() throws IOException {
        Path project = temp.resolve("read-corrupt");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            BlobRef content = store.stage(BlobRef.Kind.ASSET, "not-a-descriptor".getBytes(StandardCharsets.UTF_8));
            ProjectState.Entry corrupt = new ProjectState.Entry(MODEL, "editor:resources",
                    "editor:resource", "editor:resource_binary", 1, "lamp.gltf", content, Set.of(), Set.of());
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(MODEL, corrupt)));
        }
    }

    @Test
    void readRejectsDescriptorIdentityMismatch() throws IOException {
        Path project = temp.resolve("read-identity");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            LogicalResource other = new LogicalResource(id(71), MODEL_TYPE, "other.gltf", MODEL_V2);
            BlobRef content = store.stage(BlobRef.Kind.ASSET, DescriptorCodec.encode(other));
            ProjectState.Entry mismatched = new ProjectState.Entry(MODEL, "editor:resources",
                    "editor:resource", "editor:resource_binary", 1, "lamp.gltf", content, Set.of(), Set.of());
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(MODEL, mismatched)));
        }
    }

    @Test
    void readRejectsDanglingVersionDependency() throws IOException {
        Path project = temp.resolve("read-dangling");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ResourceVersion dangling = new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                    Map.of(), null, Set.of(id(72)), Set.of());
            LogicalResource resource = new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1);
            Map<UUID, ProjectState.Entry> entries = new java.util.LinkedHashMap<>();
            entries.put(MODEL_V1, descriptorEntry(store, MODEL_V1, "editor:resource_version",
                    DescriptorCodec.encode(dangling), Set.of(id(72))));
            entries.put(MODEL, descriptorEntry(store, MODEL, "editor:resource",
                    DescriptorCodec.encode(resource)));
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, entries));
        }
    }

    @Test
    void readRejectsAManifestWithAMissingEdge() throws IOException {
        Path project = temp.resolve("read-manifest-missing");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            LogicalResource resource = new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1);
            ResourceVersion dependent = new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                    Map.of(), null, Set.of(MODEL_V2), Set.of());
            assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.read(store,
                    descriptorMap(
                            descriptorEntry(store, MODEL_V1, "editor:resource_version",
                                    DescriptorCodec.encode(dependent)),
                            descriptorEntry(store, MODEL_V2, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V2, MODEL,
                                            MODEL_IMPORTER, "1.1.0", Map.of(), null, Set.of(), Set.of()))),
                            descriptorEntry(store, MODEL, "editor:resource",
                                    DescriptorCodec.encode(resource), Set.of(MODEL_V1)))));
            // The resource payload locks MODEL_V1 while its entry declares no edge at all.
            assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.read(store,
                    descriptorMap(
                            descriptorEntry(store, MODEL_V1, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V1, MODEL,
                                            MODEL_IMPORTER, "1.0.0", Map.of(), null, Set.of(), Set.of()))),
                            descriptorEntry(store, MODEL, "editor:resource",
                                    DescriptorCodec.encode(resource)))));
        }
    }

    @Test
    void readRejectsAManifestWithAnExtraEdge() throws IOException {
        Path project = temp.resolve("read-manifest-extra");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            LogicalResource resource = new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1);
            ResourceVersion plain = new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                    Map.of(), null, Set.of(), Set.of());
            assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.read(store,
                    descriptorMap(
                            descriptorEntry(store, MODEL_V1, "editor:resource_version",
                                    DescriptorCodec.encode(plain), Set.of(MODEL_V2)),
                            descriptorEntry(store, MODEL_V2, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V2, MODEL,
                                            MODEL_IMPORTER, "1.1.0", Map.of(), null, Set.of(), Set.of()))),
                            descriptorEntry(store, MODEL, "editor:resource",
                                    DescriptorCodec.encode(resource), Set.of(MODEL_V1)))));
            // The resource payload locks only MODEL_V1 while its entry claims a second version.
            assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.read(store,
                    descriptorMap(
                            descriptorEntry(store, MODEL_V1, "editor:resource_version",
                                    DescriptorCodec.encode(plain)),
                            descriptorEntry(store, MODEL, "editor:resource",
                                    DescriptorCodec.encode(resource), Set.of(MODEL_V1, MODEL_V2)))));
        }
    }

    @Test
    void readRejectsADescriptorThatDependsOnADocument() throws IOException {
        Path project = committedProject("read-document-edge");
        // The blob is deliberately absent: the shape check must reject the entry before any blob is
        // opened, so a missing file failure would prove the check came too late.
        ProjectState.Entry tainted = new ProjectState.Entry(MODEL, "editor:resources", "editor:resource",
                "editor:resource_binary", 1, "lamp.gltf",
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 16), Set.of(id(53)), Set.of(MODEL_V1));
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(MODEL, tainted)));
        }
    }

    @Test
    void readRejectsByteContentThatIsMissingOrCorrupt() throws IOException {
        Path missing = temp.resolve("read-missing-blob");
        try (LocalProjectStore store = LocalProjectStore.create(missing)) {
            assertThrows(IOException.class, () -> ResourceStorageBridge.read(store,
                    Map.of(MODEL_BYTES, fabricated(MODEL_BYTES, null))));
        }

        Path corrupt = committedProject("read-corrupt-blob");
        BlobRef content;
        try (LocalProjectStore store = LocalProjectStore.open(corrupt)) {
            content = store.stage(BlobRef.Kind.ASSET, "pixels".getBytes(StandardCharsets.UTF_8));
        }
        Files.writeString(corrupt.resolve("assets").resolve(content.sha256() + ".blob"),
                "pixelz", StandardCharsets.UTF_8);
        ProjectState.Entry entry = new ProjectState.Entry(MODEL_BYTES, "editor:text-java2d",
                "editor:texture_png", "editor:png_binary", 1, "byte-" + MODEL_BYTES, content,
                Set.of(), Set.of());
        try (LocalProjectStore store = LocalProjectStore.open(corrupt)) {
            assertThrows(IOException.class,
                    () -> ResourceStorageBridge.read(store, Map.of(MODEL_BYTES, entry)));
        }
    }

    @Test
    void readRejectsDescriptorsOverTheTotalBudgetBeforeOpeningAnyBlob() throws IOException {
        Path project = committedProject("read-total-budget");
        // Every entry claims the whole per descriptor budget, so the sum is over the total budget and
        // is refused before the first (in fact nonexistent) blob is opened.
        Map<UUID, ProjectState.Entry> entries = new LinkedHashMap<>();
        for (int index = 0; index < 65; index++) {
            UUID entryId = id(700 + index);
            entries.put(entryId, new ProjectState.Entry(entryId, "editor:resources", "editor:resource",
                    "editor:resource_binary", 1, "budget", new BlobRef(BlobRef.Kind.ASSET,
                    "0".repeat(64), ResourceSupport.MAX_DESCRIPTOR_BYTES), Set.of(), Set.of()));
        }
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, entries));
        }
    }

    @Test
    void readRejectsACycleBetweenUnreachableVersions() throws IOException {
        Path project = temp.resolve("read-unreachable-cycle");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            // The lock chain runs through these two versions, so only a whole catalog cycle check
            // finds them; a closure walk from the current version would never visit either.
            assertThrows(IllegalArgumentException.class, () -> ResourceStorageBridge.read(store,
                    descriptorMap(
                            descriptorEntry(store, MODEL_V1, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V1, MODEL,
                                            MODEL_IMPORTER, "1.0.0", Map.of(), null,
                                            Set.of(MODEL_V2), Set.of())), Set.of(MODEL_V2)),
                            descriptorEntry(store, MODEL_V2, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V2, MODEL,
                                            MODEL_IMPORTER, "1.1.0", Map.of(), null,
                                            Set.of(MODEL_V1), Set.of())), Set.of(MODEL_V1)),
                            descriptorEntry(store, MODEL, "editor:resource",
                                    DescriptorCodec.encode(new LogicalResource(MODEL, MODEL_TYPE,
                                            "lamp.gltf", MODEL_V0)), Set.of(MODEL_V0)),
                            descriptorEntry(store, MODEL_V0, "editor:resource_version",
                                    DescriptorCodec.encode(new ResourceVersion(MODEL_V0, MODEL,
                                            MODEL_IMPORTER, "0.9.0", Map.of(), null, Set.of(), Set.of()))))));
        }
    }

    @Test
    void readKeepsForeignAssetEntriesAsByteContent() throws IOException {
        Path project = temp.resolve("read-foreign");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState.Entry foreign = asset(store, TEXTURE_BYTES, "editor:text-java2d",
                    "editor:texture_png", "editor:png_binary", "pixels", Set.of());
            ResourceCatalog catalog = ResourceStorageBridge.read(store, Map.of(TEXTURE_BYTES, foreign));
            assertTrue(catalog.resources().isEmpty());
            assertTrue(catalog.versions().isEmpty());
            assertEquals(foreign, catalog.byteEntries().get(TEXTURE_BYTES));
        }
    }

    /** The catalog node budget must be settled on the declared sizes, before any map is copied. */
    @Test
    void catalogNodeBudgetIsCheckedOnDeclaredSizesBeforeAnyEntryIsCopied() {
        Map<UUID, LogicalResource> resources = new LinkedHashMap<>();
        for (int i = 0; i < ResourceSupport.MAX_NODES + 1; i++) {
            // The value identity deliberately disagrees with its key: a catalog that copied first would
            // report the key mismatch instead of the node budget, so the message has to be the budget.
            resources.put(id(60_000 + i), new LogicalResource(id(70_000 + i), MODEL_TYPE, "n.gltf", id(1)));
        }
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceCatalog(resources, Map.of(), Map.of()));
        assertTrue(failure.getMessage().contains("8192"), failure.getMessage());
    }

    /** The node budget is metadata only: it must reject before the shared blob is ever opened. */
    @Test
    void readRejectsTooManyEntriesBeforeOpeningAnyBlob() throws IOException {
        Path project = committedProject("read-node-budget");
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            BlobRef absent = new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4);
            Map<UUID, ProjectState.Entry> tooMany = new LinkedHashMap<>();
            for (int i = 0; i < ResourceSupport.MAX_NODES + 1; i++) {
                UUID entryId = id(50_000 + i);
                tooMany.put(entryId, new ProjectState.Entry(entryId, "editor:text-java2d",
                        "editor:texture_png", "editor:png_binary", 1, "byte-" + entryId, absent,
                        Set.of(), Set.of()));
            }
            // Every entry is a legal foreign asset entry: without the metadata bound this would fail
            // with the IOException of the missing blob instead.
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, tooMany));
            assertTrue(failure.getMessage().contains("8192"), failure.getMessage());
        }
    }

    /** The declared edge budget is metadata only, and it is the exact boundary that is allowed. */
    @Test
    void readChecksTheDeclaredEdgeBudgetBeforeOpeningAnyBlob() throws IOException {
        Path project = committedProject("read-edge-budget");
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            Map<UUID, ProjectState.Entry> atBudget = new LinkedHashMap<>();
            for (int i = 0; i < 4096; i++) {
                Set<UUID> dependencies = new TreeSet<>();
                for (int j = 0; j < 16; j++) {
                    dependencies.add(id(4_000L + i * 16L + j));
                }
                UUID versionId = id(1000 + i);
                atBudget.put(versionId,
                        fabricatedDescriptor(versionId, ResourceSupport.TYPE_VERSION, dependencies));
            }

            // Exactly 65536 declared edges: the metadata pass lets it through, so the absent blob is
            // what fails, which proves no earlier I/O produced this outcome.
            assertThrows(IOException.class, () -> ResourceStorageBridge.read(store, atBudget));

            UUID tail = id(1000 + 4095);
            Set<UUID> oneMore = new TreeSet<>(atBudget.get(tail).assetDependencies());
            oneMore.add(id(999_999));
            atBudget.put(tail, fabricatedDescriptor(tail, ResourceSupport.TYPE_VERSION, oneMore));
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.read(store, atBudget));
            assertTrue(failure.getMessage().contains("65536"), failure.getMessage());
        }
    }

    /**
     * The stage side of the same boundary: one resource, a chain of 4096 versions and 15 shared byte
     * outputs whose source entry is also an output. The union edges are 1 + 4095*16 + 15 = 65536, the
     * declared maximum, and both stage and read must accept it.
     */
    @Test
    void stageAndReadAcceptTheExactEdgeBudgetBoundary() throws IOException {
        Path project = temp.resolve("edge-boundary");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ResourceCatalog catalog = chainCatalog(store, 4096, false);
            ResourceVersion tail = catalog.versions().get(chainVersion(4095));
            assertTrue(tail.outputEntries().contains(tail.sourceEntry()));
            assertEquals(65536, declaredUnionEdges(catalog));

            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, catalog, Set.of());
            assertEquals(4112, staged.size());

            ResourceCatalog reloaded = ResourceStorageBridge.read(store, staged);
            assertEquals(4096, reloaded.versions().size());
            assertEquals(15, reloaded.byteEntries().size());
            assertEquals(chainVersion(0), reloaded.resources().get(chainResource()).currentVersion());
            ResourceVersion reloadedHead = reloaded.versions().get(chainVersion(0));
            assertEquals(chainByte(0), reloadedHead.sourceEntry());
            assertTrue(reloadedHead.outputEntries().contains(chainByte(0)));
        }
    }

    /** One real extra output edge on the tail version pushes the union to 65537 and must be refused. */
    @Test
    void stageRejectsOneRealEdgeOverTheEdgeBudget() throws IOException {
        Path project = temp.resolve("edge-over-budget");
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ResourceCatalog catalog = chainCatalog(store, 4096, true);
            assertEquals(65537, declaredUnionEdges(catalog));
            long before = fileCount(project);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.stage(store, catalog, Set.of()));
            assertTrue(failure.getMessage().contains("65536"), failure.getMessage());
            assertEquals(before, fileCount(project));
        }
    }

    private Fixture prepare(Path project, Path archive) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState.Entry textureEntry = asset(store, TEXTURE_BYTES, "editor:text-java2d",
                    "editor:texture_png", "editor:png_binary", "pixels", Set.of());
            ProjectState.Entry modelEntry = asset(store, MODEL_BYTES, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "mesh", Set.of(TEXTURE_BYTES));
            ProjectState.Entry sourceV1 = asset(store, MODEL_SOURCE_V1, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", MODEL_SOURCE_V1_BODY, Set.of());
            ProjectState.Entry sourceV2 = asset(store, MODEL_SOURCE_V2, "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", MODEL_SOURCE_V2_BODY, Set.of());
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
                                    Map.of("lod", "2", "alpha", "cutout"), MODEL_SOURCE_V2,
                                    Set.of(TEXTURE_V1), Set.of(MODEL_BYTES))),
                    Map.of(TEXTURE_BYTES, textureEntry, MODEL_BYTES, modelEntry,
                            MODEL_SOURCE_V1, sourceV1, MODEL_SOURCE_V2, sourceV2));

            // The document locks the logical resource and one older version. Only that reference pulls
            // MODEL_V1, its source and the texture into the closure; MODEL_V0 stays out.
            Map<UUID, ProjectState.Entry> document = Map.of(DOCUMENT,
                    new ProjectState.Entry(DOCUMENT, "editor:documents", "editor:document",
                            "editor:document_json", 1, "scene.doc",
                            store.stage(BlobRef.Kind.DOCUMENT,
                                    "scene".getBytes(StandardCharsets.UTF_8)),
                            Set.of(), Set.of(MODEL, MODEL_V1)));
            Map<UUID, ProjectState.Entry> staged = ResourceStorageBridge.stage(store, catalog,
                    Set.of(new ResourceRef(TEXTURE, TEXTURE_V1), new ResourceRef(MODEL, MODEL_V1)));
            // A locked reference still pulls an older version in instead of the current one.
            assertTrue(ResourceStorageBridge.stage(store, catalog, Set.of(new ResourceRef(MODEL, MODEL_V1)))
                    .containsKey(MODEL_V1));
            store.commit(null, LOCKED_REQUEST, projectState(document, Map.of(), staged));
            store.exportSnapshot(archive);
            return new Fixture(catalog, staged, textureEntry, modelEntry);
        }
    }

    private static Map<UUID, ProjectState.Entry> descriptorMap(ProjectState.Entry... entries) {
        Map<UUID, ProjectState.Entry> map = new LinkedHashMap<>();
        for (ProjectState.Entry entry : entries) {
            map.put(entry.id(), entry);
        }
        return map;
    }

    private static ProjectState.Entry descriptorEntry(LocalProjectStore store, UUID entryId,
                                                      String typeId, byte[] payload) throws IOException {
        return descriptorEntry(store, entryId, typeId, payload, Set.of());
    }

    private static ProjectState.Entry descriptorEntry(LocalProjectStore store, UUID entryId,
                                                      String typeId, byte[] payload,
                                                      Set<UUID> assetDependencies) throws IOException {
        return descriptorEntry(store, entryId, typeId, payload, assetDependencies, Set.of());
    }

    private static ProjectState.Entry descriptorEntry(LocalProjectStore store, UUID entryId,
                                                      String typeId, byte[] payload,
                                                      Set<UUID> assetDependencies,
                                                      Set<UUID> documentDependencies)
            throws IOException {
        BlobRef content = store.stage(BlobRef.Kind.ASSET, payload);
        return new ProjectState.Entry(entryId, "editor:resources", typeId,
                "editor:resource_binary", 1, entryId.toString(), content, documentDependencies,
                assetDependencies);
    }

    private static String content(Path project, BlobRef ref) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            store.copyContent(ref, out);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID entryId, String owner,
                                            String typeId, String codecId, String body,
                                            Set<UUID> assetDependencies) throws IOException {
        BlobRef content = store.stage(BlobRef.Kind.ASSET, body.getBytes(StandardCharsets.UTF_8));
        return new ProjectState.Entry(entryId, owner, typeId, codecId, 1, "byte-" + entryId,
                content, Set.of(), assetDependencies);
    }

    private static ProjectState.Entry fabricated(UUID entryId, UUID assetDependency) {
        Set<UUID> dependencies = assetDependency == null ? Set.of() : Set.of(assetDependency);
        return new ProjectState.Entry(entryId, "editor:text-java2d", "editor:texture_png",
                "editor:png_binary", 1, "byte-" + entryId,
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(), dependencies);
    }

    /** Byte content that borrows a descriptor identity while its blob does not exist at all. */
    private static ProjectState.Entry impostor(String owner, String typeId, String codecId) {
        return new ProjectState.Entry(MODEL_BYTES, owner, typeId, codecId, 1, "impostor",
                new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(), Set.of());
    }

    /** A descriptor-shaped entry whose declared dependencies are metadata only; its blob is absent. */
    private static ProjectState.Entry fabricatedDescriptor(UUID entryId, String typeId,
                                                           Set<UUID> assetDependencies) {
        return new ProjectState.Entry(entryId, ResourceSupport.OWNER, typeId, ResourceSupport.CODEC, 1,
                entryId.toString(), new BlobRef(BlobRef.Kind.ASSET, "0".repeat(64), 4), Set.of(),
                assetDependencies);
    }

    private static UUID chainResource() {
        return id(9_000_000);
    }

    /** The union edge total the catalog declares: resource edges plus every version's source/deps/outputs. */
    private static long declaredUnionEdges(ResourceCatalog catalog) {
        long declared = catalog.resources().size();
        for (ResourceVersion version : catalog.versions().values()) {
            declared += ResourceSupport.versionDependencies(version).size();
        }
        return declared;
    }

    private static UUID chainVersion(int index) {
        return id(1_000_000 + index);
    }

    private static UUID chainByte(int index) {
        return id(8_000_000 + index);
    }

    /**
     * One resource, a chain of {@code versionCount} versions and shared byte outputs, sized so the
     * union edges sit exactly on the declared maximum. Every version writes all base outputs and
     * takes one of them as its source, so source and output overlap by construction;
     * {@code oneEdgeOverBudget} adds a single real extra output to the tail version.
     */
    private static ResourceCatalog chainCatalog(LocalProjectStore store, int versionCount,
                                                boolean oneEdgeOverBudget) throws IOException {
        int baseOutputs = 15;
        Map<UUID, ProjectState.Entry> bytes = new LinkedHashMap<>();
        for (int i = 0; i < baseOutputs + (oneEdgeOverBudget ? 1 : 0); i++) {
            bytes.put(chainByte(i), asset(store, chainByte(i), "editor:model-gltf",
                    "editor:mesh_binary", "editor:gltf_binary", "shared", Set.of()));
        }
        Map<UUID, ResourceVersion> versions = new LinkedHashMap<>();
        for (int i = 0; i < versionCount; i++) {
            Set<UUID> outputs = new TreeSet<>();
            for (int j = 0; j < baseOutputs; j++) {
                outputs.add(chainByte(j));
            }
            if (oneEdgeOverBudget && i == versionCount - 1) {
                outputs.add(chainByte(baseOutputs));
            }
            Set<UUID> dependencies = i + 1 < versionCount ? Set.of(chainVersion(i + 1)) : Set.of();
            versions.put(chainVersion(i), new ResourceVersion(chainVersion(i), chainResource(),
                    MODEL_IMPORTER, "1.0.0", Map.of(), chainByte(0), dependencies, outputs));
        }
        return new ResourceCatalog(
                Map.of(chainResource(), new LogicalResource(chainResource(), MODEL_TYPE,
                        "chain.gltf", chainVersion(0))),
                versions, bytes);
    }

    private static ResourceCatalog simpleCatalog() {
        return new ResourceCatalog(
                Map.of(MODEL, new LogicalResource(MODEL, MODEL_TYPE, "lamp.gltf", MODEL_V1),
                        TEXTURE, new LogicalResource(TEXTURE, TEXTURE_TYPE, "brick.png", TEXTURE_V1)),
                Map.of(MODEL_V1, new ResourceVersion(MODEL_V1, MODEL, MODEL_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(), Set.of()),
                        TEXTURE_V1, new ResourceVersion(TEXTURE_V1, TEXTURE, TEXT_IMPORTER, "1.0.0",
                                Map.of(), null, Set.of(), Set.of())),
                Map.of());
    }

    private Path committedProject(String name) throws IOException {
        Path project = temp.resolve(name);
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            store.commit(null, LOCKED_REQUEST, projectState(Map.of(), Map.of(), Map.of()));
        }
        return project;
    }

    private void assertRejectedWithoutWrites(Path project, ResourceCatalog catalog,
                                            Set<ResourceRef> requiredRefs) throws IOException {
        long before = fileCount(project);
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ResourceStorageBridge.stage(store, catalog, requiredRefs));
        }
        assertEquals(before, fileCount(project));
    }

    /**
     * Asserts that a stage that fails with real store I/O leaves no descriptor behind: only an
     * already published blob may remain, and this fixture has no blob to publish.
     */
    private void assertIoRejectedWithoutWrites(Path project, ResourceCatalog catalog,
                                              Set<ResourceRef> requiredRefs) throws IOException {
        long before = fileCount(project);
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            assertThrows(IOException.class,
                    () -> ResourceStorageBridge.stage(store, catalog, requiredRefs));
        }
        assertEquals(before, fileCount(project));
    }

    private static ProjectState projectState(Map<UUID, ProjectState.Entry> documents,
                                             Map<UUID, ProjectState.Entry> assets,
                                             Map<UUID, ProjectState.Entry> staged) {
        Map<UUID, ProjectState.Entry> merged = new java.util.TreeMap<>(assets);
        merged.putAll(staged);
        return new ProjectState(id(900), "bridge",
                Map.of("editor:resources", "1.0.0", "editor:text-java2d", "1.0.0",
                        "editor:model-gltf", "1.1.0", "editor:documents", "1.0.0"),
                documents, merged, null);
    }

    private static ResourceCatalog readBack(Path restored, SavedProject imported) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.open(restored)) {
            return ResourceStorageBridge.read(store, imported.state().assets());
        }
    }

    private static Map<UUID, ProjectState.Entry> restage(Path restored, ResourceCatalog catalog)
            throws IOException {
        try (LocalProjectStore store = LocalProjectStore.open(restored)) {
            return ResourceStorageBridge.stage(store, catalog, Set.of());
        }
    }

    private static long fileCount(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static UUID id(long value) {
        return new UUID(0x5054430000000000L, value);
    }
}
