package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSessionLifecycleTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.edit", List.of());

    @Test void everyPublicSurfaceRejectsAnotherThreadWithoutChangingTheDraft() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of(source(id, "before")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            var before = handle.view();
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, session::id);
                assertThrows(IllegalStateException.class, session::source);
                assertThrows(IllegalStateException.class, session::documents);
                assertThrows(IllegalStateException.class, session::unavailableDocuments);
                assertThrows(IllegalStateException.class, session::documentFailures);
                assertThrows(IllegalStateException.class, session::problems);
                assertThrows(IllegalStateException.class, session::resourceFailure);
                assertThrows(IllegalStateException.class, session::protectedMode);
                assertThrows(IllegalStateException.class, () -> session.find(id, String.class));
                assertThrows(IllegalStateException.class,
                        () -> session.create(UUID.randomUUID(), TYPE, String.class, "wrong thread"));
                assertThrows(IllegalStateException.class, session::isClosed);
                assertThrows(IllegalStateException.class, handle::id);
                assertThrows(IllegalStateException.class, handle::typeId);
                assertThrows(IllegalStateException.class, handle::valueType);
                assertThrows(IllegalStateException.class, handle::displayName);
                assertThrows(IllegalStateException.class, handle::view);
                assertThrows(IllegalStateException.class, handle::hasUnpublishedChanges);
                assertThrows(IllegalStateException.class, handle::undoLabel);
                assertThrows(IllegalStateException.class, handle::redoLabel);
                assertThrows(IllegalStateException.class,
                        () -> handle.edit(before.token(), LABEL, value -> "wrong thread"));
                assertThrows(IllegalStateException.class, () -> handle.undo(before.token()));
                assertThrows(IllegalStateException.class, () -> handle.redo(before.token()));
                assertThrows(IllegalStateException.class,
                        () -> handle.setPendingInput("field", "raw"));
                assertThrows(IllegalStateException.class, () -> handle.clearPendingInput("field"));
                assertThrows(IllegalStateException.class, session::close);
            }).get(5, TimeUnit.SECONDS);
            assertEquals(before, handle.view());
            assertFalse(session.isClosed());
        }
    }

    @Test void commandsCannotMutateOtherDocumentsCreateOrCloseButCanReadOldViews() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        try (var fixture = project(directory.resolve("project"), registry(new TestType()),
                List.of(source(a, "A"), source(b, "B"))); var session = fixture.open()) {
            var first = session.find(a, String.class).orElseThrow();
            var second = session.find(b, String.class).orElseThrow();
            var beforeA = first.view();
            var beforeB = second.view();
            List<Runnable> attempts = List.of(
                    () -> second.edit(beforeB.token(), LABEL, value -> "changed B"),
                    () -> session.create(created, TYPE, String.class, "new"), session::close,
                    () -> second.setPendingInput("field", "raw"));
            for (Runnable attempt : attempts) {
                var rejected = assertThrows(SessionRejection.class,
                        () -> first.edit(beforeA.token(), LABEL, value -> {
                            assertEquals(beforeA, first.view());
                            assertEquals(beforeB, second.view());
                            assertEquals(2, session.documents().size());
                            attempt.run();
                            return "changed A";
                        }));
                assertEquals("editor:runtime/reentrant_mutation", rejected.problems().get(0).code().toString());
                assertEquals(beforeA, first.view());
                assertEquals(beforeB, second.view());
                assertFalse(session.isClosed());
                assertFalse(session.documents().containsKey(created));
            }
            first.edit(beforeA.token(), LABEL, value -> "accepted");
            assertEquals("accepted", first.view().content());
            assertEquals(beforeB, second.view());
        }
    }

    @Test void runtimeExceptionAndErrorReleaseTheMutationGuard() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of(source(id, "before")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            var before = handle.view();
            RuntimeException failure = new IllegalStateException("plugin failure");
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> handle.edit(before.token(), LABEL, value -> { throw failure; })));
            AssertionError error = new AssertionError("plugin error");
            assertSame(error, assertThrows(AssertionError.class,
                    () -> handle.edit(before.token(), LABEL, value -> { throw error; })));
            assertEquals(before, handle.view());
            handle.edit(before.token(), LABEL, value -> "after");
            assertEquals("after", handle.view().content());
        }
    }

    @Test void allModelCallbacksRejectCrossDocumentMutationAndPreserveTheRedoBranch() throws Exception {
        TestType type = new TestType();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        try (var fixture = project(directory.resolve("callbacks"), registry(type),
                List.of(source(a, "A"), source(b, "B"))); var session = fixture.open()) {
            var first = session.find(a, String.class).orElseThrow();
            var second = session.find(b, String.class).orElseThrow();
            first.edit(first.view().token(), LABEL, value -> "A edited");
            first.undo(first.view().token());
            var beforeA = first.view();
            var beforeB = second.view();
            var directoryBefore = session.documents();
            List<Consumer<Runnable>> callbacks = List.of(
                    hook -> type.snapshotTransform = value -> { hook.run(); return value; },
                    hook -> type.validateFunction = value -> { hook.run(); return List.of(); },
                    hook -> type.encodeFunction = value -> {
                        hook.run(); return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    },
                    hook -> type.dependencyFunction = value -> { hook.run(); return NO_DEPENDENCIES; });
            List<Runnable> mutations = List.of(
                    () -> second.edit(beforeB.token(), LABEL, value -> "unexpected"),
                    () -> session.create(created, TYPE, String.class, "unexpected"), session::close);
            for (Consumer<Runnable> callback : callbacks) {
                for (Runnable mutation : mutations) {
                    callback.accept(() -> {
                        assertEquals(beforeA, first.view());
                        assertEquals(beforeB, second.view());
                        mutation.run();
                    });
                    SessionRejection failure = assertThrows(SessionRejection.class,
                            () -> first.edit(beforeA.token(), LABEL, value -> "rejected"));
                    assertEquals("editor:runtime/reentrant_mutation", failure.problems().get(0).code().toString());
                    type.snapshotTransform = java.util.function.UnaryOperator.identity();
                    type.validateFunction = value -> List.of();
                    type.encodeFunction = value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    type.dependencyFunction = value -> NO_DEPENDENCIES;
                    assertEquals(beforeA, first.view());
                    assertEquals(beforeB, second.view());
                    assertEquals(directoryBefore, session.documents());
                    assertEquals(LABEL, first.redoLabel().orElseThrow());
                    assertTrue(first.undoLabel().isEmpty());
                    assertFalse(session.isClosed());
                }
            }
            first.redo(beforeA.token());
            assertEquals("A edited", first.view().content());
            assertEquals(beforeB, second.view());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    @Test void failedDependencyEvaluationKeepsTheDraftAndHistoryAtomic() throws Exception {
        TestType type = new TestType();
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("dependency-failure"), registry(type), List.of(source(id, "A")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            handle.edit(handle.view().token(), LABEL, value -> "B");
            handle.undo(handle.view().token());
            var before = handle.view();
            IllegalArgumentException failure = new IllegalArgumentException("dependency callback failed");
            type.dependencyFunction = value -> { throw failure; };
            assertSame(failure, assertThrows(IllegalArgumentException.class,
                    () -> handle.edit(before.token(), LABEL, value -> "C")));
            assertEquals(before, handle.view());
            assertFalse(handle.hasUnpublishedChanges());
            assertEquals(LABEL, handle.redoLabel().orElseThrow());
            AssertionError error = new AssertionError("dependency callback error");
            type.dependencyFunction = value -> { throw error; };
            assertSame(error, assertThrows(AssertionError.class,
                    () -> handle.edit(before.token(), LABEL, value -> "C")));
            assertEquals(before, handle.view());
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            handle.redo(before.token());
            assertEquals("B", handle.view().content());
            assertTrue(handle.hasUnpublishedChanges());
        }
    }

    @Test void closeIsIdempotentInvalidatesHandlesAndLeavesTheBorrowedStoreOpen() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of(source(id, "before")))) {
            var session = fixture.open();
            var handle = session.find(id, String.class).orElseThrow();
            var retained = handle.view();
            session.close();
            session.close();
            assertTrue(session.isClosed());
            assertEquals("before", retained.content());
            assertThrows(IllegalStateException.class, handle::view);
            assertThrows(IllegalStateException.class, handle::hasUnpublishedChanges);
            assertThrows(IllegalStateException.class, session::source);
            assertEquals(fixture.loaded().saved(), fixture.store().load());
            try (var reopened = fixture.open()) {
                assertEquals("before", reopened.find(id, String.class).orElseThrow().view().content());
            }
        }
    }

    @Test void anAccessCanLendOnlyOneSessionAndForeignLoadedIdentityIsRefused() throws Exception {
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            assertThrows(IllegalStateException.class, fixture::open);
            LocalProjectAccess other = new LocalProjectAccess(fixture.store());
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectSession.open(other, fixture.loaded(), fixture.registry(), 8));
            assertThrows(IllegalArgumentException.class,
                    () -> ProjectSession.open(fixture.access(), other.read(), fixture.registry(), 8));
            assertFalse(session.isClosed());
        }
    }

    @Test void failedActivationReleasesTheLeaseIncludingErrors() throws Exception {
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("project"), registry(type), List.of())) {
            var missingKinds = new ExtensionRegistryBuilder(new ApiVersion(1, 0),
                    Contribution.Side.COMMON).freeze();
            assertThrows(RuntimeException.class,
                    () -> ProjectSession.open(fixture.access(), fixture.loaded(), missingKinds, 8));
            AssertionError failure = new AssertionError("descriptor error");
            TestType broken = new TestType() {
                @Override public Class<String> valueType() { throw failure; }
            };
            assertSame(failure, assertThrows(AssertionError.class,
                    () -> ProjectSession.open(fixture.access(), fixture.loaded(), registry(broken), 8)));
            try (var session = fixture.open()) {
                assertFalse(session.isClosed());
            }
        }
    }
}
