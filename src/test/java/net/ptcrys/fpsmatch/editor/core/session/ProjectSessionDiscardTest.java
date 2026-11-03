package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentRejection;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSessionDiscardTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.edit", List.of());

    @Test void discardUsesTheLatestConfirmedCaptureAndIgnoresRejectedPublication() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("confirmed"), registry(new TestType()),
                List.of(source(id, "opened"))); var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "first save");
            save(fixture, session, id);
            handle.edit(handle.view().token(), LABEL, value -> "latest save");
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var latest = nextJob(session);
            handle.edit(handle.view().token(), LABEL, value -> "edited after capture");
            assertTrue(session.completeSave(latest, fixture.access().publish(latest)));
            assertTrue(handle.hasUnpublishedChanges());

            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var rejected = nextJob(session);
            assertTrue(session.completeSave(rejected,
                    new SaveResult(SaveResult.Outcome.REJECTED, null, null, List.of())));
            handle.setPendingInput("name", "unfinished");
            var oldToken = handle.view().token();
            var saved = fixture.store().load();

            session.discardDocumentChanges(id);

            assertEquals("latest save", handle.view().content());
            assertFalse(handle.view().dirty());
            assertFalse(handle.hasUnpublishedChanges());
            assertFalse(handle.view().canUndo());
            assertFalse(handle.view().canRedo());
            assertTrue(handle.undoLabel().isEmpty());
            assertTrue(handle.redoLabel().isEmpty());
            assertTrue(handle.view().pendingInputs().isEmpty());
            assertNotEquals(oldToken.branchId(), handle.view().token().branchId());
            assertEquals(0, handle.view().token().revision());
            assertThrows(DocumentRejection.class,
                    () -> handle.edit(oldToken, LABEL, value -> "stale command"));
            assertEquals(saved, fixture.store().load());
            assertEquals("opened", new String(session.source().documentBytes(id), StandardCharsets.UTF_8));
        }
    }

    @Test void confirmedContentSurvivesHistoryTrimmingEvenWhenHistoryIsDisabled() throws Exception {
        for (int history : List.of(0, 1)) {
            UUID id = UUID.randomUUID();
            try (var fixture = project(directory.resolve("trim-" + history), registry(new TestType()),
                    List.of(source(id, "opened")));
                 var session = ProjectSession.open(fixture.access(), fixture.loaded(), fixture.registry(), history)) {
                var handle = session.find(id, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "captured");
                session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
                var job = nextJob(session);
                for (int edit = 0; edit < 4; edit++) {
                    String value = "later " + edit;
                    handle.edit(handle.view().token(), LABEL, ignored -> value);
                }
                assertTrue(session.completeSave(job, fixture.access().publish(job)));
                session.discardDocumentChanges(id);
                assertEquals("captured", handle.view().content());
                assertFalse(handle.hasUnpublishedChanges());
                assertFalse(handle.view().canUndo());
            }
        }
    }

    @Test void newAndImportedDocumentsKeepTheirInitialContentIdentityAndUnpublishedState() throws Exception {
        UUID createdId = UUID.randomUUID();
        UUID importedId = UUID.randomUUID();
        try (var fixture = project(directory.resolve("unpublished"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            var created = session.create(createdId, TYPE, String.class, "created");
            var imported = session.createWithValue(importedId, TYPE, String.class, "imported", "converted source");
            var handles = session.documents();
            for (var handle : List.of(created, imported)) {
                String initial = handle.view().content();
                handle.edit(handle.view().token(), LABEL, value -> "discard this edit");
                var oldCapture = handle.capture();
                handle.setPendingInput("name", "incomplete");
                session.discardDocumentChanges(handle.id());
                assertThrows(DocumentRejection.class, oldCapture::confirm);
                assertEquals(initial, handle.view().content());
                assertTrue(handle.hasUnpublishedChanges());
                assertFalse(handle.view().dirty());
                assertFalse(handle.view().canUndo());
                assertTrue(handle.view().pendingInputs().isEmpty());
                handle.edit(handle.view().token(), LABEL, value -> "another edit");
                session.discardDocumentChanges(handle.id());
                assertEquals(initial, handle.view().content());
                assertTrue(handle.hasUnpublishedChanges());
            }
            assertEquals(handles, session.documents());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
            assertTrue(fixture.store().load().state().documents().isEmpty());
        }
    }

    @Test void discardingOneDocumentPreservesOtherDraftsRawHistoryAndDirtyResources() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (var fixture = project(directory.resolve("isolated"), registry(new TestType()),
                List.of(source(a, "A"), source(b, "B"))); var session = fixture.open()) {
            var first = session.find(a, String.class).orElseThrow();
            var second = session.find(b, String.class).orElseThrow();
            first.edit(first.view().token(), LABEL, value -> "A draft");
            first.setPendingInput("name", "A raw");
            second.edit(second.view().token(), LABEL, value -> "B draft");
            second.setPendingInput("name", "B raw");
            var resources = session.resources().orElseThrow();
            UUID asset = UUID.randomUUID();
            var entry = new ProjectState.Entry(asset, OWNER.toString(), "example:bytes", CODEC.toString(),
                    1, "draft asset", fixture.store().stage(BlobRef.Kind.ASSET, new byte[] {1, 2, 3}),
                    Set.of(), Set.of());
            resources.edit(resources.view().token(), LABEL, catalog ->
                    new ResourceCatalog(catalog.resources(), catalog.versions(), Map.of(asset, entry)));
            assertTrue(resources.view().dirty());
            var otherBefore = second.view();
            var resourcesBefore = resources.view();
            var savedBefore = fixture.store().load();

            session.discardDocumentChanges(a);

            assertEquals("A", first.view().content());
            assertTrue(first.view().pendingInputs().isEmpty());
            assertEquals(otherBefore, second.view());
            assertTrue(second.hasUnpublishedChanges());
            assertEquals(LABEL, second.undoLabel().orElseThrow());
            assertEquals(resourcesBefore, resources.view());
            assertEquals(savedBefore, fixture.store().load());
        }
    }

    @Test void callbackFailuresPreserveContentTokensRawInputAndBothHistoryDirections() throws Exception {
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("atomic"), registry(type), List.of(source(id, "saved")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "draft");
            handle.edit(handle.view().token(), LABEL, value -> "redo target");
            handle.undo(handle.view().token());
            handle.setPendingInput("name", "uncommitted");
            var before = handle.view();
            var queueBefore = session.saves();
            RuntimeException failure = new IllegalArgumentException("callback failed");
            List<Consumer<Runnable>> callbacks = List.of(
                    hook -> type.snapshotTransform = value -> { hook.run(); return value; },
                    hook -> type.validateFunction = value -> { hook.run(); return List.of(); },
                    hook -> type.encodeFunction = value -> { hook.run(); return value.getBytes(StandardCharsets.UTF_8); },
                    hook -> type.dependencyFunction = value -> { hook.run(); return NO_DEPENDENCIES; });
            for (Consumer<Runnable> callback : callbacks) {
                callback.accept(() -> {
                    assertEquals(before, handle.view());
                    throw failure;
                });
                assertSame(failure, assertThrows(RuntimeException.class, () -> session.discardDocumentChanges(id)));
                resetCallbacks(type);
                assertEquals(before, handle.view());
                assertEquals(LABEL, handle.undoLabel().orElseThrow());
                assertEquals(LABEL, handle.redoLabel().orElseThrow());
                assertEquals(queueBefore, session.saves());
            }
            type.validateFunction = value -> List.of(new Problem(Problem.Severity.ERROR, TYPE, "name", LABEL));
            assertThrows(DocumentRejection.class, () -> session.discardDocumentChanges(id));
            assertEquals(before, handle.view());
            resetCallbacks(type);
            type.snapshotTransform = value -> { session.discardDocumentChanges(id); return value; };
            assertThrows(SessionRejection.class, () -> session.discardDocumentChanges(id));
            assertEquals(before, handle.view());
            resetCallbacks(type);

            session.discardDocumentChanges(id);
            assertEquals("saved", handle.view().content());
            assertFalse(handle.view().canUndo());
            assertFalse(handle.view().canRedo());
            assertTrue(handle.view().pendingInputs().isEmpty());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    @Test void anyQueuedPublishingOrUnknownSaveAndReopenRequirementRejectDiscard() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (var fixture = project(directory.resolve("busy"), registry(new TestType()),
                List.of(source(a, "A"), source(b, "B"))); var session = fixture.open()) {
            var target = session.find(a, String.class).orElseThrow();
            var other = session.find(b, String.class).orElseThrow();
            target.edit(target.view().token(), LABEL, value -> "draft");
            target.setPendingInput("name", "raw");
            other.edit(other.view().token(), LABEL, value -> "other save");
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(b))));
            assertEquals(SaveQueueView.State.READY, session.saves().state());
            assertEquals(1, session.saves().queued());
            assertDiscardRejectedWithoutChanges(session, target, "queue_unavailable");
            var job = nextJob(session);
            assertEquals(SaveQueueView.State.PUBLISHING, session.saves().state());
            assertDiscardRejectedWithoutChanges(session, target, "queue_unavailable");
            var actual = fixture.access().publish(job);
            assertTrue(session.completeSave(job, new SaveResult(SaveResult.Outcome.UNKNOWN, null, null, List.of())));
            assertEquals(SaveQueueView.State.UNKNOWN, session.saves().state());
            assertDiscardRejectedWithoutChanges(session, target, "queue_unavailable");
            assertTrue(session.completeSave(job, actual));
            assertTrue(session.requiresReopen());
            assertDiscardRejectedWithoutChanges(session, target, "queue_unavailable");
            assertEquals("draft", target.view().content());
        }
    }

    @Test void protectedProjectRejectsDiscardWithoutLosingReadableDrafts() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("protected"), registry(new TestType()),
                List.of(source(id, "saved")), Map.of(OWNER.toString(), BUILD, "missing:module", "1"));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "draft");
            handle.setPendingInput("name", "raw");
            assertTrue(session.protectedMode());
            assertDiscardRejectedWithoutChanges(session, handle, "protected_project");
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    @Test void oldBranchCapturesCannotEnqueueOrConfirmAndTheNewBranchCanSaveAtRevisionZero() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("branch"), registry(new TestType()),
                List.of(source(id, "opened"))); var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "first edit");
            handle.edit(handle.view().token(), LABEL, value -> "confirmed");
            save(fixture, session, id);
            handle.edit(handle.view().token(), LABEL, value -> "stale draft");
            var latePreparation = session.captureSave(Set.of(id));
            var oldConfirmation = handle.capture();
            session.discardDocumentChanges(id);
            assertEquals(0, handle.view().token().revision());
            var restored = handle.view();
            var prepared = fixture.access().prepare(latePreparation);
            var rejection = assertThrows(SessionRejection.class, () -> session.enqueueSave(prepared));
            assertEquals("editor:runtime_save/stale_capture", rejection.problems().get(0).code().toString());
            assertThrows(DocumentRejection.class, oldConfirmation::confirm);
            assertEquals(restored, handle.view());
            assertFalse(handle.hasUnpublishedChanges());
            assertEquals(0, session.saves().queued());

            handle.edit(handle.view().token(), LABEL, value -> "new branch edit");
            session.discardDocumentChanges(id);
            assertEquals("confirmed", handle.view().content(), "failed old confirmation cannot replace the baseline");
            save(fixture, session, id);
            assertEquals(0, handle.view().token().revision());
            handle.edit(handle.view().token(), LABEL, value -> "new branch save");
            save(fixture, session, id);
            handle.edit(handle.view().token(), LABEL, value -> "later draft");
            session.discardDocumentChanges(id);
            assertEquals("new branch save", handle.view().content());
            assertFalse(handle.hasUnpublishedChanges());
        }
    }

    private static void assertDiscardRejectedWithoutChanges(ProjectSession session,
                                                            DocumentHandle<String> handle, String code) {
        var before = handle.view();
        var queue = session.saves();
        var rejection = assertThrows(SessionRejection.class, () -> session.discardDocumentChanges(handle.id()));
        assertEquals("editor:runtime_save/" + code, rejection.problems().get(0).code().toString());
        assertEquals(before, handle.view());
        assertEquals(queue, session.saves());
    }

    private static void resetCallbacks(TestType type) {
        type.snapshotTransform = UnaryOperator.identity();
        type.validateFunction = value -> List.of();
        type.encodeFunction = value -> value.getBytes(StandardCharsets.UTF_8);
        type.dependencyFunction = value -> NO_DEPENDENCIES;
    }

    private static void save(Fixture fixture, ProjectSession session, UUID id) throws Exception {
        session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
        var job = nextJob(session);
        assertTrue(session.completeSave(job, fixture.access().publish(job)));
    }

    private static ProjectSaveJob nextJob(ProjectSession session) {
        var dispatch = session.dispatchSave().orElseThrow();
        assertTrue(dispatch.problems().isEmpty(), dispatch.problems().toString());
        return dispatch.job().orElseThrow();
    }
}
