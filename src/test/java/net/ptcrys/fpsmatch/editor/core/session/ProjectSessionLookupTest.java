package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSessionLookupTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Literal("Lookup edit");

    @Test void currentLookupSeesNewHandlesWithoutChangingEarlierDirectorySnapshots() throws Exception {
        UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
        try (var fixture = project(directory.resolve("created"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            var empty = session.documents();
            assertTrue(session.find(firstId).isEmpty());
            var first = session.create(firstId, TYPE, String.class, "First");
            var firstOnly = session.documents();
            var second = session.createWithValue(secondId, TYPE, String.class, "Second", "seed");
            assertSame(first, session.find(firstId).orElseThrow());
            assertSame(second, session.find(secondId).orElseThrow());
            assertSame(second, session.find(secondId, String.class).orElseThrow());
            assertEquals("seed", session.find(secondId).orElseThrow().view().content());
            assertTrue(empty.isEmpty());
            assertEquals(java.util.Set.of(firstId), firstOnly.keySet());
            assertSame(first, firstOnly.get(firstId));
            assertThrows(UnsupportedOperationException.class, () -> firstOnly.put(secondId, second));
            assertEquals(java.util.Set.of(firstId, secondId), session.documents().keySet());
        }
    }

    @Test void unavailableAndAbsentDocumentsBothReturnEmptyWithoutLosingDiagnostics() throws Exception {
        UUID unavailable = UUID.randomUUID();
        try (var fixture = project(directory.resolve("unavailable"), registry(null),
                List.of(source(unavailable, "kept on disk"))); var session = fixture.open()) {
            assertTrue(session.find(unavailable).isEmpty());
            assertTrue(session.find(UUID.randomUUID()).isEmpty());
            assertTrue(session.unavailableDocuments().contains(unavailable));
            assertTrue(session.documentFailures().containsKey(unavailable));
            assertTrue(session.protectedMode());
        }
    }

    @Test void lookupChecksOwnerAndClosedStateBeforeItsNonNullArgument() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("owner"), registry(new TestType()), List.of(source(id, "before")));
             var session = fixture.open()) {
            var before = session.find(id).orElseThrow().view();
            assertThrows(NullPointerException.class, () -> session.find(null));
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, () -> session.find(id));
                assertThrows(IllegalStateException.class, () -> session.find(null));
            }).get(5, TimeUnit.SECONDS);
            assertEquals(before, session.find(id).orElseThrow().view());
            session.close();
            assertThrows(IllegalStateException.class, () -> session.find(id));
            assertThrows(IllegalStateException.class, () -> session.find(null));
        }
    }

    @Test void lookupIsReadableInsideAnEditButDoesNotPermitNestedMutation() throws Exception {
        UUID id = UUID.randomUUID(), nested = UUID.randomUUID();
        try (var fixture = project(directory.resolve("callback"), registry(new TestType()), List.of(source(id, "before")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            var before = handle.view();
            handle.edit(before.token(), LABEL, value -> {
                assertSame(handle, session.find(id).orElseThrow());
                assertEquals(before, session.find(id).orElseThrow().view());
                var rejected = assertThrows(SessionRejection.class,
                        () -> session.create(nested, TYPE, String.class, "Nested"));
                assertEquals("editor:runtime/reentrant_mutation", rejected.problems().get(0).code().toString());
                return "after";
            });
            assertEquals("after", session.find(id).orElseThrow().view().content());
            assertTrue(session.find(nested).isEmpty());
        }
    }

    @Test void reopeningTheSameSavedIdReturnsANewHandleAndInvalidatesTheOldOne() throws Exception {
        UUID id = UUID.randomUUID();
        try (var fixture = project(directory.resolve("reopen"), registry(new TestType()), List.of(source(id, "saved")))) {
            DocumentHandle<String> old;
            try (var session = fixture.open()) {
                old = session.find(id, String.class).orElseThrow();
                old.edit(old.view().token(), LABEL, value -> "discarded");
                old.setPendingInput("field", "raw");
                assertSame(old, session.find(id).orElseThrow());
            }
            assertThrows(IllegalStateException.class, old::view);
            try (var reopened = fixture.open()) {
                var current = reopened.find(id).orElseThrow();
                assertNotSame(old, current);
                assertEquals("saved", current.view().content());
                assertTrue(current.view().pendingInputs().isEmpty());
                assertFalse(current.view().canUndo());
            }
        }
    }
}
