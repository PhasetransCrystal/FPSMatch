package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.project.SaveQueueView;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the public owner -> I/O -> owner save protocol against real stores and registries. */
class ProjectSessionSaveTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.edit", List.of());

    @Test void queuedDocumentsUseTheLatestConfirmedManifestAndConfirmOnlyTheirCapturedRevision() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Thread owner = Thread.currentThread();
        TestType type = ownerOnlyType(owner);
        Path path = directory.resolve("ordered");
        try (var fixture = project(path, registry(type), List.of(source(a, "A0"), source(b, "B0")));
             var session = fixture.open()) {
            var first = session.find(a, String.class).orElseThrow();
            var second = session.find(b, String.class).orElseThrow();
            first.edit(first.view().token(), LABEL, value -> "A1");
            int callbacks = callbacks(type);
            var captureA = session.captureSave(Set.of(a));
            assertEquals(callbacks, callbacks(type), "capture must reuse validated encodings and dependencies");
            first.edit(first.view().token(), LABEL, value -> "A2");
            second.edit(second.view().token(), LABEL, value -> "B1");
            var captureB = session.captureSave(Set.of(b));
            callbacks = callbacks(type);
            var preparedA = io(owner, () -> fixture.access().prepare(captureA));
            var preparedB = io(owner, () -> fixture.access().prepare(captureB));
            assertEquals(callbacks, callbacks(type));
            UUID requestA = session.enqueueSave(preparedA);
            UUID requestB = session.enqueueSave(preparedB);
            assertEquals(2, session.saves().queued());
            var jobA = nextJob(session);
            assertEquals(requestA, jobA.attempt().requestId());
            var resultA = io(owner, () -> fixture.access().publish(jobA));
            assertEquals(SaveResult.Outcome.CONFIRMED, resultA.outcome());
            assertTrue(session.completeSave(jobA, resultA));
            assertEquals("A1", stored(fixture.access().read(), a));
            assertTrue(first.hasUnpublishedChanges(), "A2 was never captured");
            assertEquals("A2", first.view().content());
            var jobB = nextJob(session);
            assertEquals(requestB, jobB.attempt().requestId());
            assertEquals(requestA, jobB.attempt().expectedCommitId());
            assertEquals(resultA.saved().state().documents().get(a), jobB.attempt().candidate().documents().get(a));
            var resultB = io(owner, () -> fixture.access().publish(jobB));
            assertTrue(session.completeSave(jobB, resultB));
            assertFalse(second.hasUnpublishedChanges());
            assertTrue(first.hasUnpublishedChanges());
            assertEquals(SaveQueueView.State.READY, session.saves().state());
            assertEquals(callbacks, callbacks(type), "prepare, publish and completion must not run plugins");
            assertEquals("A1", stored(fixture.access().read(), a));
            assertEquals("B1", stored(fixture.access().read(), b));
        }
        try (var store = LocalProjectStore.open(path)) {
            var access = new LocalProjectAccess(store);
            var loaded = io(owner, access::read);
            try (var reopened = ProjectSession.open(access, loaded, registry(new TestType()), 8)) {
                assertEquals("A1", reopened.find(a, String.class).orElseThrow().view().content());
                assertEquals("B1", reopened.find(b, String.class).orElseThrow().view().content());
                assertFalse(reopened.find(a, String.class).orElseThrow().hasUnpublishedChanges());
                assertFalse(reopened.find(b, String.class).orElseThrow().hasUnpublishedChanges());
            }
        }
    }

    @Test void aNewDocumentBecomesCleanOnlyAfterConfirmationAndUndoUsesThePublishedBaseline() throws Exception {
        UUID id = UUID.randomUUID();
        Path path = directory.resolve("new-document");
        try (var fixture = project(path, registry(new TestType()), List.of()); var session = fixture.open()) {
            var handle = session.create(id, TYPE, String.class, "new document");
            handle.edit(handle.view().token(), LABEL, value -> "published");
            var prepared = fixture.access().prepare(session.captureSave(Set.of(id)));
            assertFalse(fixture.store().load().state().documents().containsKey(id));
            session.enqueueSave(prepared);
            var job = nextJob(session);
            var result = fixture.access().publish(job);
            assertTrue(handle.hasUnpublishedChanges(), "disk publication alone does not confirm the draft");
            assertTrue(session.completeSave(job, result));
            assertFalse(handle.hasUnpublishedChanges());
            assertFalse(handle.view().dirty());
            handle.undo(handle.view().token());
            assertEquals("new", handle.view().content());
            assertTrue(handle.hasUnpublishedChanges());
            assertTrue(handle.view().dirty());
            handle.redo(handle.view().token());
            assertEquals("published", handle.view().content());
            assertFalse(handle.hasUnpublishedChanges());
        }
        try (var store = LocalProjectStore.open(path)) {
            var access = new LocalProjectAccess(store);
            try (var reopened = ProjectSession.open(access, access.read(), registry(new TestType()), 8)) {
                assertEquals("published", reopened.find(id, String.class).orElseThrow().view().content());
                assertFalse(reopened.find(id, String.class).orElseThrow().hasUnpublishedChanges());
            }
        }
    }

    @Test void historyZeroDoesNotInvalidateAnInFlightCapture() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("no-history"), registry(new TestType()), List.of(source(id, "A")));
             var session = ProjectSession.open(fixture.access(), fixture.loaded(), fixture.registry(), 0)) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "B");
            var captured = session.captureSave(Set.of(id));
            handle.edit(handle.view().token(), LABEL, value -> "C");
            assertFalse(handle.view().canUndo());
            session.enqueueSave(fixture.access().prepare(captured));
            var job = nextJob(session);
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            assertEquals("B", stored(fixture.access().read(), id));
            assertEquals("C", handle.view().content());
            assertTrue(handle.hasUnpublishedChanges());
            handle.edit(handle.view().token(), LABEL, value -> "B");
            assertFalse(handle.hasUnpublishedChanges());
        }
    }

    @Test void aLostPublishReplyKeepsTheOriginalJobAndRequiresReopenAfterReconciliation() throws Exception {
        UUID id = UUID.randomUUID();
        Thread owner = Thread.currentThread();
        TestType type = ownerOnlyType(owner);
        Path path = directory.resolve("lost-reply");
        try (var fixture = project(path, registry(type), List.of(source(id, "A"))); var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "B");
            var capture = session.captureSave(Set.of(id));
            session.enqueueSave(io(owner, () -> fixture.access().prepare(capture)));
            var job = nextJob(session);
            var actual = io(owner, () -> fixture.access().publish(job));
            assertEquals(SaveResult.Outcome.CONFIRMED, actual.outcome());
            // Publication occurred, but no authoritative reply reached the owner.
            assertTrue(session.completeSave(job, new SaveResult(SaveResult.Outcome.UNKNOWN, null, null, List.of())));
            assertEquals(SaveQueueView.State.UNKNOWN, session.saves().state());
            assertTrue(session.dispatchSave().isEmpty());
            assertTrue(handle.hasUnpublishedChanges());
            var pending = session.captureSave(Set.of(id));
            var prepared = io(owner, () -> fixture.access().prepare(pending));
            assertCode("queue_unavailable", assertThrows(SessionRejection.class, () -> session.enqueueSave(prepared)));
            int callbacks = callbacks(type);
            var reconciled = io(owner, () -> fixture.access().reconcile(job));
            assertEquals(SaveResult.Outcome.CONFIRMED, reconciled.outcome());
            assertEquals(actual.saved(), reconciled.saved());
            assertTrue(session.completeSave(job, reconciled));
            assertFalse(handle.hasUnpublishedChanges());
            assertTrue(session.requiresReopen());
            assertTrue(session.dispatchSave().isEmpty());
            assertCode("queue_unavailable", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(id))));
            assertCode("queue_unavailable", assertThrows(SessionRejection.class, () -> session.enqueueSave(prepared)));
            assertEquals(callbacks, callbacks(type));
            var confirmed = fixture.store().load();
            assertThrows(IllegalStateException.class, () -> fixture.access().publish(job));
            assertThrows(IllegalStateException.class, () -> fixture.access().reconcile(job));
            assertEquals(confirmed, fixture.store().load());
        }
        try (var store = LocalProjectStore.open(path)) {
            var access = new LocalProjectAccess(store);
            try (var reopened = ProjectSession.open(access, access.read(), registry(new TestType()), 8)) {
                assertEquals("B", reopened.find(id, String.class).orElseThrow().view().content());
                assertFalse(reopened.requiresReopen());
            }
        }
    }

    @Test void foreignClosedAndCompletedJobsCannotWriteCurrent() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("job-lifetime"), registry(new TestType()), List.of(source(id, "A")))) {
            var session = fixture.open();
            try {
                var handle = session.find(id, String.class).orElseThrow();
                handle.edit(handle.view().token(), LABEL, value -> "B");
                var capture = session.captureSave(Set.of(id));
                var other = new LocalProjectAccess(fixture.store());
                var original = fixture.store().load();
                assertThrows(IllegalArgumentException.class, () -> other.prepare(capture));
                var prepared = fixture.access().prepare(capture);
                assertThrows(IllegalStateException.class, () -> fixture.access().prepare(capture));
                session.enqueueSave(prepared);
                assertThrows(IllegalStateException.class, () -> session.enqueueSave(prepared));
                var job = nextJob(session);
                assertThrows(IllegalArgumentException.class, () -> other.publish(job));
                assertThrows(IllegalArgumentException.class, () -> other.reconcile(job));
                assertEquals(original, fixture.store().load());
                var result = fixture.access().publish(job);
                assertTrue(session.completeSave(job, result));
                var confirmed = fixture.store().load();
                assertFalse(session.completeSave(job, result));
                assertThrows(IllegalStateException.class, () -> fixture.access().publish(job));
                assertThrows(IllegalStateException.class, () -> fixture.access().reconcile(job));
                assertEquals(confirmed, fixture.store().load());
                handle.edit(handle.view().token(), LABEL, value -> "C");
                var unprepared = session.captureSave(Set.of(id));
                session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
                var closedJob = nextJob(session);
                session.close();
                assertThrows(IllegalStateException.class, () -> fixture.access().prepare(unprepared));
                assertThrows(IllegalStateException.class, () -> fixture.access().publish(closedJob));
                assertThrows(IllegalStateException.class, () -> fixture.access().reconcile(closedJob));
                assertThrows(IllegalStateException.class, () -> session.completeSave(closedJob, result));
                assertEquals(confirmed, fixture.store().load());
            } finally {
                session.close();
            }
            try (var reopened = ProjectSession.open(fixture.access(), fixture.access().read(), fixture.registry(), 8)) {
                assertEquals("B", reopened.find(id, String.class).orElseThrow().view().content());
            }
        }
    }

    @Test void pendingInputAndUnselectedNewOrDirtyDependenciesRequireExplicitScope() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TestType type = new TestType();
        type.dependencyFunction = value -> value.startsWith("ref")
                ? new DocumentDependencies(Set.of(b), Set.of(), Set.of()) : NO_DEPENDENCIES;
        try (var fixture = project(directory.resolve("scope"), registry(type), List.of(source(a, "A")));
             var session = fixture.open()) {
            var first = session.find(a, String.class).orElseThrow();
            var second = session.create(b, TYPE, String.class, "dependency");
            first.setPendingInput("field", "unfinished");
            var before = first.view();
            assertCode("pending_input", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(a))));
            assertEquals(before, first.view());
            first.clearPendingInput("field");
            first.edit(first.view().token(), LABEL, value -> "ref-new");
            assertCode("unsaved_dependency", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(a))));
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(a, b))));
            var job = nextJob(session);
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            second.edit(second.view().token(), LABEL, value -> "dirty dependency");
            assertCode("unsaved_dependency", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(a))));
            var confirmed = fixture.store().load();
            assertEquals(0, session.saves().queued());
            assertEquals(confirmed, session.saves().confirmed());
        }
    }

    @Test void aConflictAllowsOnlyReconciliationAndPreservesTheNewCurrent() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("conflict"), registry(new TestType()), List.of(source(id, "A")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "B");
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var job = nextJob(session);
            // A distinct real transaction advances CURRENT before the captured attempt is published.
            var baseline = fixture.store().load();
            fixture.store().commit(baseline.commitId(), UUID.randomUUID(), baseline.state());
            var replacement = fixture.store().load();
            var conflict = fixture.access().publish(job);
            assertEquals(SaveResult.Outcome.CONFLICT, conflict.outcome());
            assertTrue(session.completeSave(job, conflict));
            assertTrue(session.requiresReopen());
            assertEquals(SaveQueueView.State.RECOVERY, session.saves().state());
            assertTrue(handle.hasUnpublishedChanges());
            assertThrows(IllegalStateException.class, () -> fixture.access().publish(job));
            assertTrue(session.dispatchSave().isEmpty());
            assertCode("queue_unavailable", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(id))));
            var reconciled = fixture.access().reconcile(job);
            assertEquals(SaveResult.Outcome.UNKNOWN, reconciled.outcome(), "the rejected request has no receipt");
            assertFalse(session.completeSave(job, reconciled), "recovery cannot revive the queue");
            assertEquals(replacement, fixture.store().load());
        }
    }

    @Test void aLateOldPreparationCannotReverseANewerAcceptedIntent() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("late-prepare"), registry(new TestType()), List.of(source(id, "A")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "B");
            var old = session.captureSave(Set.of(id));
            handle.edit(handle.view().token(), LABEL, value -> "C");
            UUID newest = session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var state = session.saves();
            var late = fixture.access().prepare(old);
            assertCode("stale_capture", assertThrows(SessionRejection.class, () -> session.enqueueSave(late)));
            assertEquals(state, session.saves());
            assertEquals("A", stored(fixture.access().read(), id));
            var job = nextJob(session);
            assertEquals(newest, job.attempt().requestId());
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            assertEquals("C", stored(fixture.access().read(), id));
            assertFalse(handle.hasUnpublishedChanges());
        }
    }

    @Test void missingSavedModulesBlockCaptureWithoutChangingTheStoredProject() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("protected"), registry(new TestType()), List.of(source(id, "A")),
                Map.of(OWNER.toString(), BUILD, "missing:module", "1")); var session = fixture.open()) {
            assertTrue(session.protectedMode());
            assertCode("protected_project", assertThrows(SessionRejection.class, () -> session.captureSave(Set.of(id))));
            assertEquals(fixture.loaded().saved(), fixture.store().load());
            assertEquals(0, session.saves().queued());
        }
    }

    private static ProjectSaveJob nextJob(ProjectSession session) {
        var dispatch = session.dispatchSave().orElseThrow();
        assertTrue(dispatch.problems().isEmpty(), dispatch.problems().toString());
        return dispatch.job().orElseThrow();
    }

    private static void assertCode(String suffix, SessionRejection rejection) {
        assertEquals("editor:runtime_save/" + suffix, rejection.problems().get(0).code().toString());
    }

    private static String stored(LoadedProject loaded, UUID id) {
        return new String(loaded.documentBytes(id), StandardCharsets.UTF_8);
    }

    private static int callbacks(TestType type) {
        return type.creates + type.decodes + type.snapshots + type.validations + type.encodings + type.dependencies;
    }

    private static TestType ownerOnlyType(Thread owner) {
        return new TestType() {
            @Override public String create() { assertSame(owner, Thread.currentThread()); return super.create(); }
            @Override public String decode(int schema, byte[] bytes) {
                assertSame(owner, Thread.currentThread()); return super.decode(schema, bytes);
            }
            @Override public String snapshot(String value) {
                assertSame(owner, Thread.currentThread()); return super.snapshot(value);
            }
            @Override public List<net.ptcrys.fpsmatch.editor.api.Problem> validate(String value) {
                assertSame(owner, Thread.currentThread()); return super.validate(value);
            }
            @Override public byte[] encode(String value) {
                assertSame(owner, Thread.currentThread()); return super.encode(value);
            }
            @Override public DocumentDependencies dependencies(String value) {
                assertSame(owner, Thread.currentThread()); return super.dependencies(value);
            }
        };
    }

    @FunctionalInterface private interface IoCall<T> { T run() throws Exception; }

    private static <T> T io(Thread owner, IoCall<T> call) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            assertNotSame(owner, Thread.currentThread(), "I/O must actually execute on a different thread");
            try {
                return call.run();
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }).get(10, TimeUnit.SECONDS);
    }
}
