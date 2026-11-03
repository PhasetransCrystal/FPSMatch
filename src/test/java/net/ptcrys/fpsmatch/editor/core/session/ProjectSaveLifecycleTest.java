package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.project.SaveResult;
import net.ptcrys.fpsmatch.editor.core.storage.RuntimeFaultStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Owner and job concurrency boundaries, using real storage and no constructed runtime capabilities. */
class ProjectSaveLifecycleTest {
    @TempDir Path directory;
    private static final TextContent EDIT = new TextContent.Message("test.edit", List.of());

    @Test void saveOwnerSurfacesRejectAnotherThreadWithoutConsumingThePreparedIntent() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("owner"), registry(new TestType()), List.of(source(id, "A")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), EDIT, value -> "B");
            var firstCapture = session.captureSave(Set.of(id));
            session.enqueueSave(fixture.access().prepare(firstCapture));
            var job = session.dispatchSave().orElseThrow().job().orElseThrow();
            handle.edit(handle.view().token(), EDIT, value -> "C");
            var nextCapture = session.captureSave(Set.of(id));
            var prepared = fixture.access().prepare(nextCapture);
            var beforeQueue = session.saves();
            var beforeDraft = handle.view();
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, session::saves);
                assertThrows(IllegalStateException.class, session::requiresReopen);
                assertThrows(IllegalStateException.class, () -> session.captureSave(Set.of(id)));
                assertThrows(IllegalStateException.class, () -> session.enqueueSave(prepared));
                assertThrows(IllegalStateException.class, session::dispatchSave);
                assertThrows(IllegalStateException.class, () -> session.completeSave(job, unknown()));
            }).get(5, TimeUnit.SECONDS);
            assertEquals(beforeQueue, session.saves());
            assertEquals(beforeDraft, handle.view());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
            assertFalse(session.requiresReopen());
            // A wrong-thread enqueue must not consume the capture or mark its revision as queued.
            session.enqueueSave(prepared);
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            assertTrue(handle.hasUnpublishedChanges());
            var nextJob = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(nextJob, fixture.access().publish(nextJob)));
            assertFalse(handle.hasUnpublishedChanges());
            assertEquals("C", new String(fixture.access().read().documentBytes(id), java.nio.charset.StandardCharsets.UTF_8));
            assertDoesNotThrow(session::close);
            assertThrows(IllegalStateException.class, session::saves);
            assertThrows(IllegalStateException.class, session::requiresReopen);
            assertThrows(IllegalStateException.class, () -> session.captureSave(Set.of(id)));
            assertThrows(IllegalStateException.class, () -> session.enqueueSave(prepared));
            assertThrows(IllegalStateException.class, session::dispatchSave);
            assertThrows(IllegalStateException.class, () -> session.completeSave(nextJob, unknown()));
        }
    }

    @Test void pluginCallbacksCannotReenterAnySaveMutationOrDiscardTheRedoBranch() throws Exception {
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("callbacks"), registry(type), List.of(source(id, "A")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var job = session.dispatchSave().orElseThrow().job().orElseThrow();
            var prepared = fixture.access().prepare(session.captureSave(Set.of(id)));
            handle.edit(handle.view().token(), EDIT, value -> "B");
            handle.undo(handle.view().token());
            var beforeDraft = handle.view();
            var beforeQueue = session.saves();
            List<Runnable> mutations = List.of(
                    () -> session.captureSave(Set.of(id)),
                    () -> session.enqueueSave(prepared),
                    () -> session.dispatchSave(),
                    () -> session.completeSave(job, unknown()));
            List<Consumer<Runnable>> callbacks = List.of(
                    hook -> type.snapshotTransform = value -> { hook.run(); return value; },
                    hook -> type.dependencyFunction = value -> { hook.run(); return NO_DEPENDENCIES; });
            for (Consumer<Runnable> callback : callbacks) {
                for (Runnable mutation : mutations) {
                    callback.accept(() -> {
                        assertEquals(beforeDraft, handle.view());
                        assertEquals(beforeQueue, session.saves());
                        mutation.run();
                    });
                    SessionRejection rejected = assertThrows(SessionRejection.class,
                            () -> handle.edit(beforeDraft.token(), EDIT, value -> "rejected"));
                    assertEquals("editor:runtime/reentrant_mutation", rejected.problems().get(0).code().toString());
                    type.snapshotTransform = java.util.function.UnaryOperator.identity();
                    type.dependencyFunction = value -> NO_DEPENDENCIES;
                    assertEquals(beforeDraft, handle.view());
                    assertEquals(beforeQueue, session.saves());
                    assertTrue(handle.undoLabel().isEmpty());
                    assertEquals(EDIT, handle.redoLabel().orElseThrow());
                }
            }
            // The forbidden callback enqueue did not consume this prepared intent.
            session.enqueueSave(prepared);
            assertTrue(session.completeSave(job, fixture.access().publish(job)));
            var second = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(second, fixture.access().publish(second)));
            handle.redo(handle.view().token());
            assertEquals("B", handle.view().content());
            session.enqueueSave(fixture.access().prepare(session.captureSave(Set.of(id))));
            var finalJob = session.dispatchSave().orElseThrow().job().orElseThrow();
            assertTrue(session.completeSave(finalJob, fixture.access().publish(finalJob)));
            assertFalse(handle.hasUnpublishedChanges());
            assertEquals("B", new String(fixture.access().read().documentBytes(id), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void runningPublicationRejectsConcurrentJobUseAndOwnerCompletionWithoutBlocking() throws Exception {
        UUID id = UUID.randomUUID();
        Path path = directory.resolve("job-concurrency");
        TestType type = new TestType();
        var registry = registry(type);
        try (var fixture = project(path, registry, List.of(source(id, "A")))) {
            assertEquals(1, fixture.loaded().documentIds().size());
        }
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var store = RuntimeFaultStores.pauseBeforePublish(path, entered, release)) {
            var access = new LocalProjectAccess(store);
            try (var session = ProjectSession.open(access, access.read(), registry, 2)) {
                var handle = session.find(id, String.class).orElseThrow();
                handle.edit(handle.view().token(), EDIT, value -> "B");
                var capture = session.captureSave(Set.of(id));
                Thread owner = Thread.currentThread();
                int callbacks = callbacks(type);
                var prepared = CompletableFuture.supplyAsync(() -> {
                    assertNotSame(owner, Thread.currentThread());
                    try { return access.prepare(capture); }
                    catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
                }).get(5, TimeUnit.SECONDS);
                session.enqueueSave(prepared);
                var job = session.dispatchSave().orElseThrow().job().orElseThrow();
                var beforeQueue = session.saves();
                var beforeDraft = handle.view();
                CompletableFuture<SaveResult> publishing = CompletableFuture.supplyAsync(() -> {
                    assertNotSame(owner, Thread.currentThread());
                    return access.publish(job);
                });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS), "real storage must enter the publication checkpoint");
                    // Non-preemptive timing retains the actual owner thread. The checkpoint itself
                    // has a five-second bound, so a monitor regression cannot leave this test stuck.
                    assertTimeout(Duration.ofSeconds(1), () -> {
                        assertThrows(IllegalStateException.class, () -> access.publish(job));
                        assertThrows(IllegalStateException.class, () -> access.reconcile(job));
                        assertBusy(assertThrows(SessionRejection.class, session::close));
                        assertBusy(assertThrows(SessionRejection.class, () -> session.completeSave(job, unknown())));
                    });
                    assertFalse(session.isClosed());
                    assertEquals(beforeQueue, session.saves());
                    assertEquals(beforeDraft, handle.view());
                } finally {
                    release.countDown();
                    publishing.get(6, TimeUnit.SECONDS); // Finish I/O before session/store cleanup.
                }
                SaveResult result = publishing.get(1, TimeUnit.SECONDS);
                assertEquals(SaveResult.Outcome.CONFIRMED, result.outcome());
                assertTrue(session.completeSave(job, result));
                assertFalse(handle.hasUnpublishedChanges());
                assertEquals(callbacks, callbacks(type), "prepare/publish/complete must not invoke plugins");
                assertEquals(result.saved(), store.load());
            }
        }
    }

    private static SaveResult unknown() {
        return new SaveResult(SaveResult.Outcome.UNKNOWN, null, null, List.of());
    }

    private static void assertBusy(SessionRejection rejected) {
        assertEquals("editor:runtime_save/io_busy", rejected.problems().get(0).code().toString());
    }

    private static int callbacks(TestType type) {
        return type.creates + type.decodes + type.snapshots + type.validations + type.encodings + type.dependencies;
    }
}
