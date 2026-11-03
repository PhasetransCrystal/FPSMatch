package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentPublicationStateTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.edit", List.of());

    @Test void editingBackToStoredBytesIsPublishedEvenWhenTheInitialDecodedValueDiffers() throws Exception {
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        type.decodeTransform = String::toUpperCase;
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(id, "a")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            handle.edit(handle.view().token(), LABEL, value -> "a");
            assertTrue(handle.view().dirty());
            assertFalse(handle.hasUnpublishedChanges());
            handle.undo(handle.view().token());
            assertTrue(handle.hasUnpublishedChanges());
            handle.redo(handle.view().token());
            assertFalse(handle.hasUnpublishedChanges());
            assertArrayEquals(new byte[]{'a'}, fixture.access().read().documentBytes(id));
        }
    }

    @Test void equalBytesWithDifferentDependenciesProtectTheProjectAndRemainUnpublished() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        TestType type = new TestType();
        type.dependencyFunction = value -> value.equals("A")
                ? new DocumentDependencies(Set.of(b), Set.of(), Set.of()) : NO_DEPENDENCIES;
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(a, "A"), source(b, "B")));
             var session = fixture.open()) {
            assertTrue(session.protectedMode());
            assertTrue(session.find(a, String.class).orElseThrow().hasUnpublishedChanges());
            assertFalse(session.find(b, String.class).orElseThrow().hasUnpublishedChanges());
            assertTrue(session.source().saved().state().documents().get(a).documentDependencies().isEmpty());
        }
    }

    @Test void failedDependencyCollectionIsAtomicAndDoesNotPoisonTheGuard() throws Exception {
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        RuntimeException failure = new IllegalArgumentException("dependency failure");
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(id, "before")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            var before = handle.view();
            type.dependencyFunction = value -> { throw failure; };
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> handle.edit(before.token(), LABEL, value -> "after")));
            assertEquals(before, handle.view());
            assertFalse(handle.hasUnpublishedChanges());
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            handle.edit(before.token(), LABEL, value -> "after");
            assertTrue(handle.hasUnpublishedChanges());
        }
    }

    @Test void readingViewsDoesNotInvokeDocumentCallbacks() throws Exception {
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(id, "before")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            int[] before = counts(type);
            for (int n = 0; n < 20; n++) {
                handle.view(); handle.hasUnpublishedChanges(); handle.undoLabel(); handle.redoLabel();
                session.documents(); session.problems(); session.protectedMode();
            }
            assertArrayEquals(before, counts(type));
        }
    }

    @Test void aNewDocumentRemainsUnpublishedAfterUndoingToItsInitialValue() throws Exception {
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            var handle = session.create(UUID.randomUUID(), TYPE, String.class, "new");
            handle.edit(handle.view().token(), LABEL, value -> "changed");
            handle.undo(handle.view().token());
            assertEquals("new", handle.view().content());
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            assertTrue(fixture.store().load().state().documents().isEmpty());
        }
    }

    @Test void dependencyCallbackCannotMutateTheEncodingReturnedByThePreviousCallback() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] shared = new byte[]{'a'};
        TestType type = new TestType();
        type.encodeFunction = value -> { shared[0] = (byte) value.charAt(0); return shared; };
        type.dependencyFunction = value -> { shared[0] = 'x'; return NO_DEPENDENCIES; };
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(id, "a")));
             var session = fixture.open()) {
            var handle = session.find(id, String.class).orElseThrow();
            assertEquals('x', shared[0]);
            assertFalse(handle.hasUnpublishedChanges());
            handle.edit(handle.view().token(), LABEL, value -> "b");
            assertEquals("b", handle.view().content());
            assertTrue(handle.hasUnpublishedChanges());
            handle.edit(handle.view().token(), LABEL, value -> "a");
            assertFalse(handle.hasUnpublishedChanges());
        }
    }

    private static int[] counts(TestType type) {
        return new int[]{type.creates, type.decodes, type.snapshots, type.validations,
                type.encodings, type.dependencies};
    }
}
