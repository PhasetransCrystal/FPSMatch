package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.LogicalResource;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducerSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceStorageBridge;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceVersion;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import net.ptcrys.fpsmatch.editor.core.storage.RuntimeFaultStores;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real disk regressions across the document/resource/storage boundary. */
class ProjectSaveResourcesTest {
    @TempDir Path directory;
    private static final TextContent EDIT = new TextContent.Message("test.edit", List.of());
    private static final EditorId BYTE_OWNER = EditorId.parse("bytes:owner");
    private static final EditorId PRODUCER_OWNER = EditorId.parse("producer:module");

    @Test void savingAnotherDocumentPreservesLockedOldBytesInAnIndependentShare() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID resource = UUID.randomUUID();
        UUID oldVersion = UUID.randomUUID();
        UUID currentVersion = UUID.randomUUID();
        UUID oldBytes = UUID.randomUUID();
        UUID newBytes = UUID.randomUUID();
        EditorId importer = EditorId.parse("plugin:importer");
        EditorId assetType = EditorId.parse("plugin:picture");
        EditorId resourceOwner = EditorId.parse("editor:resources");
        TestType type = new TestType();
        type.dependencyFunction = value -> value.startsWith("locked")
                ? new DocumentDependencies(Set.of(), Set.of(new ResourceRef(resource, oldVersion)), Set.of())
                : NO_DEPENDENCIES;
        var builder = builder(type);
        builder.addModule(new ModuleSpec(resourceOwner, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(BYTE_OWNER, "8.1", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.addModule(new ModuleSpec(PRODUCER_OWNER, "9.2", new ApiVersion(1, 0), Set.of(), Set.of()));
        builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), importer),
                PRODUCER_OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("old", "new")));
        syntheticValidator(builder, importer, PRODUCER_OWNER);
        ExtensionRegistry registry = builder.freeze();
        Path source = directory.resolve("source");
        Path archive = directory.resolve("share.zip");
        try (LocalProjectStore store = LocalProjectStore.create(source)) {
            ProjectState.Entry oldEntry = asset(store, oldBytes, "old pixels");
            ProjectState.Entry newEntry = asset(store, newBytes, "new pixels");
            ResourceCatalog catalog = new ResourceCatalog(
                    Map.of(resource, new LogicalResource(resource, assetType, "picture", currentVersion)),
                    Map.of(oldVersion, new ResourceVersion(oldVersion, resource, importer, "old", Map.of(), oldBytes, Set.of(), Set.of()),
                            currentVersion, new ResourceVersion(currentVersion, resource, importer, "new", Map.of(), newBytes, Set.of(), Set.of())),
                    Map.of(oldBytes, oldEntry, newBytes, newEntry));
            Map<UUID, ProjectState.Entry> assets = ResourceStorageBridge.stage(store, catalog, Set.of(new ResourceRef(resource, oldVersion)));
            ProjectState.Entry first = document(store, a, "locked", Set.of(resource, oldVersion));
            ProjectState.Entry second = document(store, b, "before", Set.of());
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "resource share",
                    Map.of(OWNER.toString(), BUILD, resourceOwner.toString(), "1", BYTE_OWNER.toString(), "8.1",
                            PRODUCER_OWNER.toString(), "9.2"), Map.of(a, first, b, second), assets, null));
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 2)) {
                assertFalse(session.protectedMode());
                var unselected = session.find(a, String.class).orElseThrow();
                // A's current draft has dropped its reference, but its saved version still needs v1.
                unselected.edit(unselected.view().token(), EDIT, value -> "unpublished without reference");
                var selected = session.find(b, String.class).orElseThrow();
                selected.edit(selected.view().token(), EDIT, value -> "after");
                ProjectSaveCapture capture = session.captureSave(Set.of(b));
                selected.edit(selected.view().token(), EDIT, value -> "later draft");
                // A real missing/corrupt source refuses preparation without publishing; restoring
                // the exact bytes permits retrying this same capture rather than recapturing B.
                var beforePreparation = store.load();
                Path changedBlob = source.resolve("assets").resolve(newEntry.content().sha256() + ".blob");
                Files.writeString(changedBlob, "corruption", StandardCharsets.UTF_8);
                assertThrows(java.io.IOException.class, () -> access.prepareValidated(capture, () -> false));
                Files.writeString(changedBlob, "new pixels", StandardCharsets.UTF_8);
                assertEquals(beforePreparation, store.load());
                session.enqueueSave(access.prepareValidated(capture, () -> false));
                ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
                assertTrue(session.completeSave(job, access.publish(job)));
                assertTrue(unselected.hasUnpublishedChanges());
                assertEquals("later draft", selected.view().content());
                assertTrue(selected.hasUnpublishedChanges());
                assertTrue(session.saves().confirmed().state().assets().containsKey(oldVersion));
                assertEquals("8.1", session.saves().confirmed().state().modules().get(BYTE_OWNER.toString()));
                assertEquals("9.2", session.saves().confirmed().state().modules().get(PRODUCER_OWNER.toString()));
            }
            store.exportSnapshot(archive);
        }
        // A separate store reads the imported directory and authenticates the received bytes directly.
        Path received = directory.resolve("received");
        LocalProjectStore.importSnapshot(archive, received);
        try (LocalProjectStore store = LocalProjectStore.open(received)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 2)) {
                assertFalse(session.protectedMode());
                assertEquals("locked", session.find(a, String.class).orElseThrow().view().content());
                assertEquals("after", session.find(b, String.class).orElseThrow().view().content());
                ResourceCatalog catalog = session.source().resources().orElseThrow();
                assertEquals(currentVersion, catalog.resources().get(resource).currentVersion());
                assertEquals(oldBytes, catalog.versions().get(oldVersion).sourceEntry());
                ByteArrayOutputStream sink = new ByteArrayOutputStream();
                store.copyContent(catalog.byteEntries().get(oldBytes).content(), sink);
                assertEquals("old pixels", sink.toString(StandardCharsets.UTF_8));
                sink.reset();
                store.copyContent(catalog.byteEntries().get(newBytes).content(), sink);
                assertEquals("new pixels", sink.toString(StandardCharsets.UTF_8));
            }
            var missingProducerBuilder = builder(type);
            missingProducerBuilder.addModule(new ModuleSpec(resourceOwner, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
            missingProducerBuilder.addModule(new ModuleSpec(BYTE_OWNER, "8.1", new ApiVersion(1, 0), Set.of(), Set.of()));
            try (ProjectSession protectedSession = ProjectSession.open(access, access.read(), missingProducerBuilder.freeze(), 2)) {
                assertTrue(protectedSession.protectedMode());
                assertThrows(SessionRejection.class, () -> protectedSession.captureSave(Set.of(b)));
                assertEquals("after", protectedSession.find(b, String.class).orElseThrow().view().content());
                assertEquals("9.2", protectedSession.source().saved().state().modules().get(PRODUCER_OWNER.toString()));
            }
        }
    }

    @Test void closeAndCompletionRefuseWhileActualPublicationIsBlockedInStorage() throws Exception {
        UUID id = UUID.randomUUID();
        Path project = directory.resolve("busy");
        var registry = registry(new TestType());
        try (var fixture = project(project, registry, List.of(source(id, "before")))) {
            assertEquals(1, fixture.loaded().documentIds().size());
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var store = RuntimeFaultStores.pauseBeforePublish(project, entered, release)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            ProjectSaveJob job;
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 1)) {
                var handle = session.find(id, String.class).orElseThrow();
                handle.edit(handle.view().token(), EDIT, value -> "after");
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(id)), () -> false));
                job = session.dispatchSave().orElseThrow().job().orElseThrow();
                CompletableFuture<net.ptcrys.fpsmatch.editor.core.project.SaveResult> result = CompletableFuture.supplyAsync(() -> access.publish(job));
                try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                SessionRejection busy = assertThrows(SessionRejection.class, session::close);
                assertEquals("editor:runtime_save/io_busy", busy.problems().get(0).code().toString());
                assertFalse(session.isClosed());
                assertThrows(SessionRejection.class, () -> session.completeSave(job,
                        new net.ptcrys.fpsmatch.editor.core.project.SaveResult(net.ptcrys.fpsmatch.editor.core.project.SaveResult.Outcome.UNKNOWN, null, null, List.of())));
                } finally {
                    release.countDown();
                    result.get(6, TimeUnit.SECONDS); // Join before try-with-resources closes owner/session/store.
                }
                assertTrue(session.completeSave(job, result.get(1, TimeUnit.SECONDS)));
                assertFalse(handle.hasUnpublishedChanges());
            }
            assertThrows(IllegalStateException.class, () -> access.publish(job));
        }
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID id, String value) throws Exception {
        return new ProjectState.Entry(id, BYTE_OWNER.toString(), "plugin:bytes", "plugin:raw", 1, "pixels",
                store.stage(BlobRef.Kind.ASSET, value.getBytes(StandardCharsets.UTF_8)), Set.of(), Set.of());
    }

    private static ProjectState.Entry document(LocalProjectStore store, UUID id, String value, Set<UUID> assets) throws Exception {
        return new ProjectState.Entry(id, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1, "note",
                store.stage(BlobRef.Kind.DOCUMENT, value.getBytes(StandardCharsets.UTF_8)), Set.of(), assets);
    }
}
