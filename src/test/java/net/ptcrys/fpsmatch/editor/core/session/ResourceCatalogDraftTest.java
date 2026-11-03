package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;

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
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Behavioral regressions for explicit resource intent and the real durable save boundary. */
class ResourceCatalogDraftTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.resource.edit", List.of());
    private static final EditorId RESOURCE_OWNER = EditorId.parse("editor:resources");
    private static final EditorId BYTE_OWNER = EditorId.parse("bytes:owner");
    private static final EditorId PRODUCER_OWNER = EditorId.parse("producer:module");
    private static final EditorId IMPORTER = EditorId.parse("plugin:importer");
    private static final EditorId ASSET_TYPE = EditorId.parse("plugin:picture");
    private static final ResourceCatalog EMPTY = new ResourceCatalog(Map.of(), Map.of(), Map.of());

    @Test void adoptionAndHistoryAreIndependentOfTheDocumentLockedToV1() throws Exception {
        try (Fixture f = fixture("history", true, 8)) {
            ResourceCatalog initial = f.resources().view().content();
            ResourceCatalog second = f.next(initial, "v2");
            f.edit(second);
            assertTrue(f.resources().view().dirty());
            assertEquals("lock", f.document().view().content());
            assertFalse(f.document().hasUnpublishedChanges());
            assertEquals(initial, f.session.source().resources().orElseThrow());
            f.resources().undo(f.resources().view().token());
            assertEquals(initial, f.resources().view().content());
            assertFalse(f.resources().view().dirty());
            f.resources().redo(f.resources().view().token());
            assertEquals(second, f.resources().view().content());
            assertEquals(LABEL, f.resources().undoLabel().orElseThrow());
            f.save(f.session.captureSave(Set.of(), true));
            assertFalse(f.resources().view().dirty());
            f.resources().undo(f.resources().view().token());
            assertTrue(f.resources().view().dirty(), "undo compares against the confirmed v2 catalog");
            f.resources().redo(f.resources().view().token());
            assertFalse(f.resources().view().dirty());
        }
    }

    @Test void resourceOnlySaveConfirmsTheCaptureAndFreshShareRetainsBothVersions() throws Exception {
        Path archive = directory.resolve("share.zip");
        UUID resource;
        UUID v1;
        UUID v2;
        ExtensionRegistry registry;
        try (Fixture f = fixture("share", true, 0)) {
            resource = f.resource;
            v1 = f.v1;
            registry = f.registry;
            ResourceCatalog second = f.next(f.resources().view().content(), "v2 pixels");
            v2 = second.resources().get(resource).currentVersion();
            f.edit(second);
            var capture = f.session.captureSave(Set.of(), true);
            f.edit(rename(second, resource, "later draft"));
            assertFalse(f.resources().view().canUndo());
            assertTrue(f.resources().view().historyTruncated());
            f.save(capture);
            assertTrue(f.resources().view().dirty(), "confirmation must not mark the later name saved");
            assertEquals("later draft", f.resources().view().content().resources().get(resource).name());
            assertEquals(v2, f.access.read().resources().orElseThrow().resources().get(resource).currentVersion());
            f.store.exportSnapshot(archive);
        }
        Path received = directory.resolve("received");
        LocalProjectStore.importSnapshot(archive, received);
        try (LocalProjectStore store = LocalProjectStore.open(received)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession reopened = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                ResourceCatalog catalog = reopened.resources().orElseThrow().view().content();
                assertEquals(v2, catalog.resources().get(resource).currentVersion());
                assertEquals("picture", catalog.resources().get(resource).name());
                assertEquals(Set.of(v1, v2), catalog.versions().keySet());
                assertEquals("v1 pixels", bytes(store, catalog.byteEntries().get(catalog.versions().get(v1).sourceEntry())));
                assertEquals("v2 pixels", bytes(store, catalog.byteEntries().get(catalog.versions().get(v2).sourceEntry())));
                assertFalse(reopened.resources().orElseThrow().view().dirty());
                assertFalse(reopened.protectedMode());
            }
        }
    }

    @Test void defaultDocumentSaveDoesNotPublishResourceDraftAndRejectsUnsavedReferences() throws Exception {
        try (Fixture f = fixture("scope", false, 8)) {
            ResourceCatalog added = f.next(EMPTY, "new resource");
            f.edit(added);
            f.document().edit(f.document().view().token(), LABEL, ignored -> "plain changed");
            f.save(f.session.captureSave(Set.of(f.document), false));
            assertTrue(f.access.read().resources().orElseThrow().resources().isEmpty());
            assertTrue(f.resources().view().dirty());
            f.document().edit(f.document().view().token(), LABEL, ignored -> "lock");
            var before = f.session.saves();
            rejection("unsaved_resources", () -> f.session.captureSave(Set.of(f.document)));
            assertEquals(before, f.session.saves());
            assertThrows(IllegalArgumentException.class, () -> f.session.captureSave(Set.of(), false));
            f.save(f.session.captureSave(Set.of(f.document), true));
            assertFalse(f.resources().view().dirty());
            assertFalse(f.document().hasUnpublishedChanges());
            assertEquals(added, f.access.read().resources().orElseThrow());
        }
    }

    @Test void oldDocumentCaptureQueuedAfterNewResourceIntentCannotRollCurrentBack() throws Exception {
        try (Fixture f = fixture("exclude-queued", true, 8)) {
            f.document().edit(f.document().view().token(), LABEL, ignored -> "lock changed");
            // This capture contains the v1 baseline and is prepared only after adopting v2.
            ProjectSaveCapture oldDocument = f.session.captureSave(Set.of(f.document));
            ResourceCatalog second = f.next(f.resources().view().content(), "v2");
            f.edit(second);
            f.session.enqueueSave(f.access.prepareValidated(f.session.captureSave(Set.of(), true), () -> false));
            f.session.enqueueSave(f.access.prepareValidated(oldDocument, () -> false));
            f.publishNext();
            f.publishNext();
            assertEquals(second, f.access.read().resources().orElseThrow());
            assertEquals("lock changed", new String(f.access.read().documentBytes(f.document), StandardCharsets.UTF_8));
            assertFalse(f.resources().view().dirty());
        }
    }

    @Test void reversePreparationRejectsTheOlderResourceIntentWithoutDisturbingAcceptedSave() throws Exception {
        try (Fixture f = fixture("reverse", true, 8)) {
            f.edit(f.next(f.resources().view().content(), "v2"));
            ProjectSaveCapture old = f.session.captureSave(Set.of(), true);
            ResourceCatalog latest = rename(f.resources().view().content(), f.resource, "latest");
            f.edit(latest);
            f.session.enqueueSave(f.access.prepareValidated(f.session.captureSave(Set.of(), true), () -> false));
            var before = f.session.saves();
            PreparedProjectSave late = f.access.prepareValidated(old, () -> false);
            rejection("stale_capture", () -> f.session.enqueueSave(late));
            assertEquals(before, f.session.saves());
            f.publishNext();
            assertEquals(latest, f.access.read().resources().orElseThrow());
            assertFalse(f.resources().view().dirty());
        }
    }

    @Test void semanticAndCategoryReuseAfterUndoCannotPoisonIdentityOrConsumeRedo() throws Exception {
        try (Fixture f = fixture("identity", false, 8)) {
            ResourceCatalog added = f.next(EMPTY, "first");
            f.edit(added);
            f.resources().undo(f.resources().view().token());
            ResourceVersion original = added.versions().get(f.v1);
            ResourceVersion changed = new ResourceVersion(original.id(), original.resourceId(), original.importer(),
                    original.importerVersion(), Map.of("changed", "semantics"), original.sourceEntry(), Set.of(), Set.of());
            ResourceCatalog changedVersion = new ResourceCatalog(added.resources(), Map.of(f.v1, changed), added.byteEntries());
            unchanged(f.resources(), "identity_conflict", ignored -> changedVersion);
            var byteEntry = added.byteEntries().values().iterator().next();
            var changedBytes = new ProjectState.Entry(byteEntry.id(), byteEntry.ownerModule(), byteEntry.typeId(),
                    byteEntry.codecId(), byteEntry.schemaVersion(), "changed byte identity", byteEntry.content(), Set.of(), Set.of());
            unchanged(f.resources(), "identity_conflict", ignored -> new ResourceCatalog(added.resources(), added.versions(),
                    Map.of(changedBytes.id(), changedBytes)));
            unchanged(f.resources(), "identity_conflict", ignored -> new ResourceCatalog(Map.of(), Map.of(),
                    Map.of(f.resource, asset(f.store, f.resource, "category reuse", BYTE_OWNER))));
            f.resources().redo(f.resources().view().token());
            assertEquals(added, f.resources().view().content());
            unchanged(f.resources(), "removal_unsupported", ignored -> EMPTY);
        }
    }

    @Test void failedDomainAndGraphCandidatesLeaveTheEntireDraftAndHistoryUntouched() throws Exception {
        try (Fixture f = fixture("validation", false, 8)) {
            ResourceCatalog valid = f.next(EMPTY, "candidate");
            ResourceVersion original = valid.versions().get(f.v1);
            ResourceVersion missingProducer = new ResourceVersion(f.v1, f.resource, EditorId.parse("absent:importer"),
                    "1", Map.of(), original.sourceEntry(), Set.of(), Set.of());
            SessionRejection producerFailure = rejectedUnchanged(f.resources(),
                    ignored -> new ResourceCatalog(valid.resources(), Map.of(f.v1, missingProducer), valid.byteEntries()));
            assertEquals("editor:session/resource_producer_unavailable", producerFailure.problems().get(0).code().toString());
            var source = valid.byteEntries().values().iterator().next();
            var missingOwner = new ProjectState.Entry(source.id(), "absent:owner", source.typeId(), source.codecId(),
                    source.schemaVersion(), source.displayName(), source.content(), Set.of(), Set.of());
            SessionRejection ownerFailure = rejectedUnchanged(f.resources(),
                    ignored -> new ResourceCatalog(valid.resources(), valid.versions(), Map.of(source.id(), missingOwner)));
            assertEquals("editor:session/module_unavailable", ownerFailure.problems().get(0).code().toString());
            SessionRejection graphFailure = rejectedUnchanged(f.resources(),
                    ignored -> new ResourceCatalog(valid.resources(), Map.of(), valid.byteEntries()));
            assertEquals("editor:resource_draft/invalid_catalog", graphFailure.problems().get(0).code().toString());
            assertInstanceOf(IllegalArgumentException.class, graphFailure.getCause());
            ResourceCatalog documentId = single(f.document, UUID.randomUUID(), null, Map.of());
            unchanged(f.resources(), "document_identity_conflict", ignored -> documentId);
            // Failed candidates must not reserve the valid candidate's IDs in the identity ledger.
            f.edit(valid);
            assertEquals(valid, f.resources().view().content());
            f.resources().undo(f.resources().view().token());
            assertEquals(EMPTY, f.resources().view().content());
            SessionRejection collision = assertThrows(SessionRejection.class,
                    () -> f.session.create(f.resource, TYPE, String.class, "collision"));
            assertEquals("editor:runtime/document_identity_conflict", collision.problems().get(0).code().toString());
        }
    }

    @Test void undoRefusesCurrentPublishedAndRetainedDocumentReferencesAtomically() throws Exception {
        try (Fixture f = fixture("references", false, 8)) {
            ResourceCatalog added = f.next(EMPTY, "resource");
            f.edit(added);
            f.document().edit(f.document().view().token(), LABEL, ignored -> "lock");
            undoRejectedUnchanged(f.resources()); // Current draft.
            ProjectSaveCapture retained = f.session.captureSave(Set.of(f.document), true);
            f.session.enqueueSave(f.access.prepareValidated(retained, () -> false));
            f.document().edit(f.document().view().token(), LABEL, ignored -> "plain");
            undoRejectedUnchanged(f.resources()); // Only the queued capture still references it.
            f.publishNext();
            undoRejectedUnchanged(f.resources()); // Only the published document still references it.
            f.save(f.session.captureSave(Set.of(f.document), true));
            f.resources().undo(f.resources().view().token());
            assertEquals(EMPTY, f.resources().view().content());
            f.save(f.session.captureSave(Set.of(), true));
            assertTrue(f.access.read().resources().orElseThrow().resources().isEmpty());
            assertTrue(f.access.read().saved().state().assets().isEmpty(), "explicit undo save removes managed descriptors and bytes");
        }
    }

    @Test void threadReentryStaleTokenAndCloseRejectWithoutPublishingPartialCommands() throws Exception {
        Fixture f = fixture("guards", false, 8);
        try (f) {
            ResourceHandle handle = f.resources();
            ResourceToken token = handle.view().token();
            CompletionException foreignThread = assertThrows(CompletionException.class,
                    () -> CompletableFuture.runAsync(handle::view).join());
            assertInstanceOf(IllegalStateException.class, foreignThread.getCause());
            ResourceView before = handle.view();
            assertThrows(SessionRejection.class, () -> handle.edit(token, LABEL, catalog -> {
                f.session.close();
                return catalog;
            }));
            assertEquals(before, handle.view());
            assertFalse(f.session.isClosed());
            f.edit(f.next(EMPTY, "accepted"));
            boolean[] invoked = {false};
            rejection("stale_token", () -> handle.edit(token, LABEL, catalog -> { invoked[0] = true; return catalog; }));
            assertFalse(invoked[0]);
            rejection("stale_token", () -> handle.undo(new ResourceToken(UUID.randomUUID(), handle.view().token().revision())));
            f.session.close();
            assertThrows(IllegalStateException.class, handle::view);
            assertThrows(IllegalStateException.class, () -> handle.edit(token, LABEL, ignored -> EMPTY));
        }
    }

    @Test void noOpPreservesRedoAndHistoryLimitIsVisible() throws Exception {
        try (Fixture f = fixture("bounded-history", true, 200)) {
            for (int i = 0; i < 130; i++) f.edit(rename(f.resources().view().content(), f.resource, "name " + i));
            assertTrue(f.resources().view().historyTruncated());
            f.resources().undo(f.resources().view().token());
            ResourceView before = f.resources().view();
            var redoLabel = f.resources().redoLabel();
            f.resources().edit(before.token(), LABEL, UnaryOperator.identity());
            assertEquals(before, f.resources().view());
            assertEquals(redoLabel, f.resources().redoLabel());
            int undos = 1;
            while (f.resources().view().canUndo()) { f.resources().undo(f.resources().view().token()); undos++; }
            assertEquals(128, undos);
        }
    }

    @Test void metadataLimitAndIdentityLedgerLimitRejectRealGraphsAtomically() {
        SessionOwner owner = new SessionOwner(UUID.randomUUID());
        ResourceHandle handle = new ResourceHandle(owner, EMPTY, 0, ignored -> {});
        ResourceCatalog below = heavyCatalog(33);
        ResourceCatalog above = heavyCatalog(36);
        assertTrue(ResourceHandle.metadataBytes(below) <= 32L * 1024 * 1024);
        assertTrue(ResourceHandle.metadataBytes(above) > 32L * 1024 * 1024);
        unchanged(handle, "metadata_budget", ignored -> above);
        handle.edit(handle.view().token(), LABEL, ignored -> below);
        assertTrue(handle.view().dirty());

        ResourceHandle ledger = new ResourceHandle(new SessionOwner(UUID.randomUUID()), EMPTY, 1, ignored -> {});
        ResourceCatalog first = manyResources(4096);
        ledger.edit(ledger.view().token(), LABEL, ignored -> first);
        ledger.undo(ledger.view().token());
        unchanged(ledger, "identity_budget", ignored -> single(UUID.randomUUID(), UUID.randomUUID(), null, Map.of()));
        ledger.redo(ledger.view().token());
        assertEquals(first, ledger.view().content());

        ResourceHandle byteLedger = new ResourceHandle(new SessionOwner(UUID.randomUUID()), EMPTY, 1, ignored -> {});
        ResourceCatalog largeFirst = heavyCatalog(18);
        byteLedger.edit(byteLedger.view().token(), LABEL, ignored -> largeFirst);
        byteLedger.undo(byteLedger.view().token());
        unchanged(byteLedger, "identity_budget", ignored -> heavyCatalog(18));
        byteLedger.redo(byteLedger.view().token());
        assertEquals(largeFirst, byteLedger.view().content());
    }

    @Test void metadataRetentionTrimsHistoryBeforeTheEntryCountLimit() {
        ResourceHandle handle = new ResourceHandle(new SessionOwner(UUID.randomUUID()), EMPTY, 128, ignored -> {});
        ResourceCatalog large = heavyCatalog(24);
        handle.edit(handle.view().token(), LABEL, ignored -> large);
        UUID resource = large.resources().keySet().iterator().next();
        handle.edit(handle.view().token(), LABEL, ignored -> rename(large, resource, "renamed"));
        assertTrue(handle.view().historyTruncated());
        handle.undo(handle.view().token());
        assertEquals(large, handle.view().content());
        assertFalse(handle.view().canUndo(), "before+after retained metadata exceeds 64 MiB after two edits");
    }

    @Test void resourceRetainedBudgetIsReleasedOnConfirmation() throws Exception {
        try (Fixture f = fixture("retained-budget", false, 1)) {
            ResourceCatalog large = heavyCatalog(24);
            f.edit(large);
            assertTrue(ResourceHandle.metadataBytes(large) > 21L * 1024 * 1024);
            f.session.enqueueSave(f.access.prepareValidated(f.session.captureSave(Set.of(), true), () -> false));
            f.session.enqueueSave(f.access.prepareValidated(f.session.captureSave(Set.of(), true), () -> false));
            PreparedProjectSave third = f.access.prepareValidated(f.session.captureSave(Set.of(), true), () -> false);
            var before = f.session.saves();
            rejection("retained_budget", () -> f.session.enqueueSave(third));
            assertEquals(before, f.session.saves());
            f.publishNext();
            f.session.enqueueSave(third);
            f.publishNext();
            f.publishNext();
            assertFalse(f.resources().view().dirty());
        }
    }

    @Test void failedResourcePreparationPreservesDraftAndAllowsTheExactCaptureToRetry() throws Exception {
        Path project = directory.resolve("resource-stage-failure");
        try (Fixture f = new Fixture(project, false, 8)) {
            ResourceCatalog imported = f.next(EMPTY, "original physical content");
            f.edit(imported);
            ProjectSaveCapture capture = f.session.captureSave(Set.of(), true);
            var saved = f.store.load();
            var queues = f.session.saves();
            var draft = f.resources().view();
            var entry = imported.byteEntries().values().iterator().next();
            Path blob = project.resolve("assets").resolve(entry.content().sha256() + ".blob");
            Files.writeString(blob, "damaged isolated test blob", StandardCharsets.UTF_8);
            assertThrows(java.io.IOException.class, () -> f.access.prepareValidated(capture, () -> false));
            assertEquals(saved, f.store.load(), "the corrupt orphan is not in CURRENT");
            assertEquals(queues, f.session.saves());
            assertEquals(draft, f.resources().view());
            Files.writeString(blob, "original physical content", StandardCharsets.UTF_8);
            f.save(capture);
            assertEquals(imported, f.access.read().resources().orElseThrow());
            assertFalse(f.resources().view().dirty());
        }
    }

    @Test void unknownResourcePublicationConfirmsOnlyItsOriginalCaptureAndRequiresFreshReopen() throws Exception {
        Path project = directory.resolve("resource-unknown");
        ExtensionRegistry registry;
        try (Fixture f = new Fixture(project, false, 8)) { registry = f.registry; }
        ResourceCatalog imported;
        try (LocalProjectStore store = RuntimeFaultStores.loseReceiptAfterPublish(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                UUID resource = UUID.randomUUID();
                UUID version = UUID.randomUUID();
                UUID bytes = UUID.randomUUID();
                var source = asset(store, bytes, "resource survives lost receipt", BYTE_OWNER);
                imported = new ResourceCatalog(
                        Map.of(resource, new LogicalResource(resource, ASSET_TYPE, "captured name", version)),
                        Map.of(version, new ResourceVersion(version, resource, IMPORTER, "1", Map.of(), bytes, Set.of(), Set.of())),
                        Map.of(bytes, source));
                ResourceHandle handle = session.resources().orElseThrow();
                handle.edit(handle.view().token(), LABEL, ignored -> imported);
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
                ResourceCatalog later = rename(imported, resource, "later name");
                handle.edit(handle.view().token(), LABEL, ignored -> later);
                session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false));
                SaveResult unknown = access.publish(job);
                assertEquals(SaveResult.Outcome.UNKNOWN, unknown.outcome());
                assertTrue(session.completeSave(job, unknown));
                assertEquals(SaveQueueView.State.UNKNOWN, session.saves().state());
                assertTrue(handle.view().dirty());
                assertTrue(session.dispatchSave().isEmpty());
                assertThrows(SessionRejection.class, () -> session.enqueueSave(access.prepareValidated(session.captureSave(Set.of(), true), () -> false)));
                assertTrue(session.completeSave(job, access.reconcile(job)));
                assertTrue(session.requiresReopen());
                assertEquals(imported, handle.savedCatalog());
                assertEquals(later, handle.view().content());
                assertTrue(handle.view().dirty());
                assertEquals(1, session.saves().queued());
                assertTrue(session.dispatchSave().isEmpty());
                assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(), true));
                assertThrows(IllegalStateException.class, () -> access.publish(job));
            }
        }
        try (LocalProjectStore store = LocalProjectStore.open(project)) {
            LocalProjectAccess access = new LocalProjectAccess(store);
            try (ProjectSession session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, 8)) {
                assertEquals(imported, session.resources().orElseThrow().view().content());
                assertFalse(session.resources().orElseThrow().view().dirty());
                assertFalse(session.requiresReopen());
            }
        }
    }

    private Fixture fixture(String name, boolean seeded, int history) throws Exception {
        return new Fixture(directory.resolve(name), seeded, history);
    }

    private static final class Fixture implements AutoCloseable {
        final UUID document = UUID.randomUUID();
        final UUID resource = UUID.randomUUID();
        final UUID v1 = UUID.randomUUID();
        final LocalProjectStore store;
        final LocalProjectAccess access;
        final ExtensionRegistry registry;
        final ProjectSession session;

        Fixture(Path path, boolean seeded, int history) throws Exception {
            TestType type = new TestType();
            type.dependencyFunction = value -> value.startsWith("lock")
                    ? new DocumentDependencies(Set.of(), Set.of(new ResourceRef(resource, v1)), Set.of()) : NO_DEPENDENCIES;
            var builder = builder(type);
            for (EditorId module : List.of(RESOURCE_OWNER, BYTE_OWNER, PRODUCER_OWNER))
                builder.addModule(new ModuleSpec(module, "1", new ApiVersion(1, 0), Set.of(), Set.of()));
            builder.add(ResourceProducers.KIND, new Contribution(new ContributionKey(ResourceProducers.KIND.id(), IMPORTER),
                    PRODUCER_OWNER, Contribution.Side.COMMON, Set.of()), new ResourceProducerSpec(Set.of("1")));
            syntheticValidator(builder, IMPORTER, PRODUCER_OWNER);
            registry = builder.freeze();
            store = LocalProjectStore.create(path);
            ResourceCatalog initial = seeded ? next(EMPTY, "v1 pixels") : EMPTY;
            var doc = new ProjectState.Entry(document, OWNER.toString(), TYPE.toString(), CODEC.toString(), 1, "note",
                    store.stage(BlobRef.Kind.DOCUMENT, (seeded ? "lock" : "plain").getBytes(StandardCharsets.UTF_8)),
                    Set.of(), seeded ? Set.of(resource, v1) : Set.of());
            store.commit(null, UUID.randomUUID(), new ProjectState(UUID.randomUUID(), "resource tests",
                    Map.of(OWNER.toString(), BUILD, RESOURCE_OWNER.toString(), "1", BYTE_OWNER.toString(), "1", PRODUCER_OWNER.toString(), "1"),
                    Map.of(document, doc), ResourceStorageBridge.stage(store, initial, Set.of()), null));
            access = new LocalProjectAccess(store);
            session = ProjectSession.open(access, access.readValidated(registry, () -> false), registry, history);
            assertFalse(session.protectedMode());
        }

        ResourceHandle resources() { return session.resources().orElseThrow(); }
        DocumentHandle<String> document() { return session.find(document, String.class).orElseThrow(); }
        void edit(ResourceCatalog catalog) { resources().edit(resources().view().token(), LABEL, ignored -> catalog); }
        ResourceCatalog next(ResourceCatalog old, String pixels) throws Exception {
            UUID version = old.resources().isEmpty() ? v1 : UUID.randomUUID();
            UUID bytes = UUID.randomUUID();
            Map<UUID, ResourceVersion> versions = new TreeMap<>(old.versions());
            versions.put(version, new ResourceVersion(version, resource, IMPORTER, "1", Map.of(), bytes, Set.of(), Set.of()));
            Map<UUID, ProjectState.Entry> entries = new TreeMap<>(old.byteEntries());
            entries.put(bytes, asset(store, bytes, pixels, BYTE_OWNER));
            return new ResourceCatalog(Map.of(resource, new LogicalResource(resource, ASSET_TYPE, "picture", version)), versions, entries);
        }
        void save(ProjectSaveCapture capture) throws Exception { session.enqueueSave(access.prepareValidated(capture, () -> false)); publishNext(); }
        void publishNext() throws Exception {
            ProjectSaveJob job = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(job, access.publish(job)));
        }
        @Override public void close() throws IOException { session.close(); store.close(); }
    }

    private static ResourceCatalog rename(ResourceCatalog old, UUID id, String name) {
        Map<UUID, LogicalResource> resources = new TreeMap<>(old.resources());
        LogicalResource logical = resources.get(id);
        resources.put(id, new LogicalResource(id, logical.type(), name, logical.currentVersion()));
        return new ResourceCatalog(resources, old.versions(), old.byteEntries());
    }

    private static ProjectState.Entry asset(LocalProjectStore store, UUID id, String bytes, EditorId owner) {
        try {
            return new ProjectState.Entry(id, owner.toString(), "plugin:bytes", "plugin:raw", 1, "pixels",
                    store.stage(BlobRef.Kind.ASSET, bytes.getBytes(StandardCharsets.UTF_8)), Set.of(), Set.of());
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static String bytes(LocalProjectStore store, ProjectState.Entry entry) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        store.copyContent(entry.content(), output);
        return output.toString(StandardCharsets.UTF_8);
    }

    private static void rejection(String suffix, org.junit.jupiter.api.function.Executable action) {
        SessionRejection rejected = assertThrows(SessionRejection.class, action);
        assertEquals("editor:resource_draft/" + suffix, rejected.problems().get(0).code().toString());
    }

    private static void unchanged(ResourceHandle handle, String code, UnaryOperator<ResourceCatalog> candidate) {
        ResourceView before = handle.view();
        var undo = handle.undoLabel();
        var redo = handle.redoLabel();
        rejection(code, () -> handle.edit(before.token(), LABEL, candidate));
        assertEquals(before, handle.view());
        assertEquals(undo, handle.undoLabel());
        assertEquals(redo, handle.redoLabel());
    }

    private static SessionRejection rejectedUnchanged(ResourceHandle handle, UnaryOperator<ResourceCatalog> candidate) {
        ResourceView before = handle.view();
        SessionRejection failure = assertThrows(SessionRejection.class, () -> handle.edit(before.token(), LABEL, candidate));
        assertEquals(before, handle.view());
        assertTrue(handle.undoLabel().isEmpty());
        assertTrue(handle.redoLabel().isEmpty());
        return failure;
    }

    private static void undoRejectedUnchanged(ResourceHandle handle) {
        ResourceView before = handle.view();
        var label = handle.undoLabel();
        rejection("reference_unavailable", () -> handle.undo(before.token()));
        assertEquals(before, handle.view());
        assertEquals(label, handle.undoLabel());
    }

    private static ResourceCatalog single(UUID resource, UUID version, UUID source, Map<String, String> settings) {
        return new ResourceCatalog(Map.of(resource, new LogicalResource(resource, ASSET_TYPE, "picture", version)),
                Map.of(version, new ResourceVersion(version, resource, IMPORTER, "1", settings, source, Set.of(), Set.of())), Map.of());
    }

    private static ResourceCatalog manyResources(int count) {
        Map<UUID, LogicalResource> resources = new HashMap<>();
        Map<UUID, ResourceVersion> versions = new HashMap<>();
        for (int i = 0; i < count; i++) {
            UUID resource = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            resources.put(resource, new LogicalResource(resource, ASSET_TYPE, "picture", version));
            versions.put(version, new ResourceVersion(version, resource, IMPORTER, "1", Map.of(), null, Set.of(), Set.of()));
        }
        return new ResourceCatalog(resources, versions, Map.of());
    }

    private static ResourceCatalog heavyCatalog(int count) {
        Map<String, String> settings = new TreeMap<>();
        for (int i = 0; i < 8; i++) settings.put("setting " + i, "x".repeat(60000));
        Map<UUID, LogicalResource> resources = new HashMap<>();
        Map<UUID, ResourceVersion> versions = new HashMap<>();
        for (int i = 0; i < count; i++) {
            UUID resource = UUID.randomUUID();
            UUID version = UUID.randomUUID();
            resources.put(resource, new LogicalResource(resource, ASSET_TYPE, "picture", version));
            versions.put(version, new ResourceVersion(version, resource, IMPORTER, "1", settings, null, Set.of(), Set.of()));
        }
        return new ResourceCatalog(resources, versions, Map.of());
    }
}
