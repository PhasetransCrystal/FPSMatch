package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceContentValidators;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;

class ProjectSessionResourcesTest {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final EditorId SUPPLIER = EditorId.parse("supplier:module");
    private static final EditorId RESOURCES = EditorId.parse("editor:resources");
    private static final EditorId IMPORTER = EditorId.parse("example:importer");
    private static final EditorId RESOURCE_TYPE = EditorId.parse("example:asset");

    private static final UUID PROJECT = id(1);
    private static final UUID RESOURCE = id(2);
    private static final UUID VERSION_V1 = id(3);
    private static final UUID VERSION_V2 = id(4);
    private static final UUID SOURCE = id(5);
    private static final byte[] SOURCE_BYTES = "original source bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temp;

    @Test
    void everyStoredVersionMustHaveAReadableProducerAndOriginalAssetsSurvive() throws Exception {
        Path project = temp.resolve("all-stored-versions");
        StoredAsset original = createProject(project);

        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            LoadedProject loaded = access.read();
            ResourceCatalog catalog = loaded.resources().orElseThrow();
            assertEquals(VERSION_V2, catalog.resources().get(RESOURCE).currentVersion());
            assertEquals(Set.of(VERSION_V1, VERSION_V2), catalog.versions().keySet());

            ExtensionRegistry newOnly = registry(Set.of("new"));
            ContributionKey producerKey = new ContributionKey(ResourceProducers.KIND.id(), IMPORTER);
            assertEquals(producerKey, ResourceProducerCatalog.activate(newOnly)
                    .requireContribution(catalog.versions().get(VERSION_V2)));

            try (ProjectSession session = ProjectSession.open(access, access.readValidated(newOnly, () -> false), newOnly, 8)) {
                assertTrue(session.protectedMode());
                SessionRejection failure = assertInstanceOf(SessionRejection.class,
                        session.resourceFailure().orElseThrow());
                assertEquals(1, failure.problems().size());
                Problem problem = failure.problems().get(0);
                assertEquals(EditorId.parse("editor:session/resource_producer_version_unavailable"),
                        problem.code());
                assertEquals(VERSION_V1.toString(), problem.target());
                assertEquals(original.entry(), session.source().saved().state().assets().get(SOURCE));
                assertArrayEquals(original.bytes(), read(store, original.entry()));
                SessionRejection collision = assertThrows(SessionRejection.class,
                        () -> session.create(SOURCE, ProjectSessionFixtures.TYPE, String.class, "replace asset"));
                assertEquals("editor:runtime/document_identity_conflict",
                        collision.problems().get(0).code().toString());
                assertTrue(session.documents().isEmpty());
                assertEquals(loaded.saved(), store.load());
            }

            ExtensionRegistry complete = registry(Set.of("old", "new"));
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(complete, () -> false),
                    complete, 8)) {
                assertFalse(session.protectedMode());
                assertTrue(session.problems().isEmpty());
                assertTrue(session.resourceFailure().isEmpty());
            }
        }
    }

    private static StoredAsset createProject(Path project) throws IOException {
        try (LocalProjectStore store = LocalProjectStore.create(project)) {
            ProjectState.Entry source = new ProjectState.Entry(SOURCE, SUPPLIER.toString(),
                    "example:source_asset", "example:raw_bytes", 1, "source.bin",
                    store.stage(BlobRef.Kind.ASSET, SOURCE_BYTES), Set.of(), Set.of());
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(RESOURCE, new LogicalResource(RESOURCE, RESOURCE_TYPE,
                            "resource.bin", VERSION_V2)),
                    Map.of(VERSION_V1, version(VERSION_V1, "old"),
                            VERSION_V2, version(VERSION_V2, "new")),
                    Map.of(SOURCE, source));
            Map<UUID, ProjectState.Entry> assets = ResourceStorageBridge.stage(store, catalog,
                    Set.of(new ResourceRef(RESOURCE, VERSION_V1)));
            ProjectState state = new ProjectState(PROJECT, "resources",
                    Map.of(SUPPLIER.toString(), "4.2", RESOURCES.toString(), "1"),
                    Map.of(), assets, null);
            store.commit(null, id(6), state);
            return new StoredAsset(source, SOURCE_BYTES.clone());
        }
    }

    private static ResourceVersion version(UUID id, String algorithmVersion) {
        return new ResourceVersion(id, RESOURCE, IMPORTER, algorithmVersion,
                Map.of(), SOURCE, Set.of(), Set.of());
    }

    private static ExtensionRegistry registry(Set<String> readableVersions) {
        ExtensionRegistryBuilder builder = new ExtensionRegistryBuilder(API, Contribution.Side.COMMON);
        builder.define(DocumentTypes.KIND);
        builder.define(ResourceProducers.KIND);
        builder.define(ResourceContentValidators.KIND);
        builder.addModule(module(SUPPLIER, "4.2"));
        builder.addModule(module(RESOURCES, "1"));
        ContributionKey key = new ContributionKey(ResourceProducers.KIND.id(), IMPORTER);
        builder.add(ResourceProducers.KIND,
                new Contribution(key, SUPPLIER, Contribution.Side.COMMON, Set.of()),
                new ResourceProducerSpec(readableVersions));
        ProjectSessionFixtures.syntheticValidator(builder, IMPORTER, SUPPLIER);
        return builder.freeze();
    }

    private static ModuleSpec module(EditorId id, String version) {
        return new ModuleSpec(id, version, API, Set.of(), Set.of());
    }

    private static byte[] read(LocalProjectStore store, ProjectState.Entry entry) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.copyContent(entry.content(), output);
        return output.toByteArray();
    }

    private static UUID id(long value) {
        return new UUID(0x5150530000000000L, value);
    }

    private record StoredAsset(ProjectState.Entry entry, byte[] bytes) {
    }
}
