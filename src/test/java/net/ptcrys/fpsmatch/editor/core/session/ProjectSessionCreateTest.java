package net.ptcrys.fpsmatch.editor.core.session;

import static net.ptcrys.fpsmatch.editor.core.session.ProjectSessionFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSessionCreateTest {
    @TempDir Path directory;
    private static final TextContent LABEL = new TextContent.Message("test.edit", List.of());

    @Test void creatingEditingAndUndoingAreDraftOnlyAndPendingInputIsIndependent() throws Exception {
        try (var fixture = project(directory.resolve("project"), registry(new TestType()), List.of());
             var session = fixture.open()) {
            var handle = session.create(UUID.randomUUID(), TYPE, String.class, "My note");
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            handle.setPendingInput("field", "unfinished");
            var oldView = handle.view();
            handle.edit(handle.view().token(), LABEL, value -> "edited");
            assertEquals(LABEL, handle.undoLabel().orElseThrow());
            handle.undo(handle.view().token());
            assertEquals("new", handle.view().content());
            assertEquals(LABEL, handle.redoLabel().orElseThrow());
            handle.redo(handle.view().token());
            assertEquals("edited", handle.view().content());
            handle.clearPendingInput("field");
            assertEquals("unfinished", oldView.pendingInputs().get("field"));
            assertTrue(handle.view().pendingInputs().isEmpty());
            assertTrue(fixture.store().load().state().documents().isEmpty());
        }
    }

    @Test void typeAndClassFailuresOccurBeforeCreateCallbacks() throws Exception {
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("project"), registry(type), List.of()); var session = fixture.open()) {
            assertThrows(IllegalArgumentException.class,
                    () -> session.create(UUID.randomUUID(), TYPE, Integer.class, "wrong class"));
            assertThrows(SessionRejection.class,
                    () -> session.create(UUID.randomUUID(), EditorId.parse("absent:type"), String.class, "unknown"));
            assertEquals(0, type.creates);
            assertTrue(session.documents().isEmpty());
        }
    }

    @Test void twoRegisteredTypesKeepTheirOwnContentHistoryAndPendingInput() throws Exception {
        EditorId otherType = EditorId.parse("example:other_note");
        TestType other = new TestType();
        other.initial = "other default";
        var builder = builder(new TestType());
        builder.add(DocumentTypes.KIND, new Contribution(new ContributionKey(DocumentTypes.KIND.id(), otherType),
                OWNER, Contribution.Side.COMMON, java.util.Set.of()), other);
        UUID stored = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        try (var fixture = project(directory.resolve("two-types"), builder.freeze(), List.of(source(stored, "stored")));
             var session = fixture.open()) {
            var first = session.find(stored, String.class).orElseThrow();
            var second = session.create(created, otherType, String.class, "other");
            assertEquals(TYPE, first.typeId());
            assertEquals(otherType, second.typeId());
            second.setPendingInput("field", "raw second");
            first.edit(first.view().token(), LABEL, value -> "first edit");
            second.edit(second.view().token(), LABEL, value -> "second edit");
            var editedSecond = second.view();
            first.undo(first.view().token());
            assertEquals("stored", first.view().content());
            assertEquals(editedSecond, second.view());
            first.redo(first.view().token());
            var editedFirst = first.view();
            second.undo(second.view().token());
            assertEquals("other default", second.view().content());
            assertTrue(second.hasUnpublishedChanges());
            second.redo(second.view().token());
            assertEquals("second edit", second.view().content());
            assertEquals("raw second", second.view().pendingInputs().get("field"));
            assertEquals(editedFirst, first.view());
            assertTrue(first.view().pendingInputs().isEmpty());
            assertEquals(1, other.creates);
            assertEquals(0, other.decodes);
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    @Test void missingNestedContributionRejectsCreationWithoutReservingIdentity() throws Exception {
        TestType type = new TestType();
        UUID id = UUID.randomUUID();
        ContributionKey missing = new ContributionKey(DocumentTypes.KIND.id(), EditorId.parse("absent:nested"));
        try (var fixture = project(directory.resolve("create-dependencies"), registry(type), List.of());
             var session = fixture.open()) {
            type.dependencyFunction = value -> new net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies(
                    java.util.Set.of(), java.util.Set.of(), java.util.Set.of(missing));
            SessionRejection failure = assertThrows(SessionRejection.class,
                    () -> session.create(id, TYPE, String.class, "new"));
            assertEquals("editor:session/contribution_unavailable", failure.problems().get(0).code().toString());
            assertTrue(session.documents().isEmpty());
            type.dependencyFunction = value -> NO_DEPENDENCIES;
            var handle = session.create(id, TYPE, String.class, "retry");
            assertEquals(id, handle.id());
            assertFalse(handle.view().dirty());
            assertTrue(handle.hasUnpublishedChanges());
            assertTrue(handle.undoLabel().isEmpty());
            assertEquals(fixture.loaded().saved(), fixture.store().load());
        }
    }

    @Test void failedInitializationDoesNotReserveIdentityOrChangeOtherDrafts() throws Exception {
        UUID existing = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(existing, "before")));
             var session = fixture.open()) {
            var before = session.find(existing, String.class).orElseThrow().view();
            RuntimeException failure = new IllegalArgumentException("failed encoding");
            type.encodeFunction = value -> { throw failure; };
            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> session.create(id, TYPE, String.class, "new")));
            assertEquals(1, session.documents().size());
            assertEquals(before, session.find(existing, String.class).orElseThrow().view());
            type.encodeFunction = value -> value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(id, session.create(id, TYPE, String.class, "retry").id());
        }
    }

    @Test void duplicateKnownAndUnavailableDocumentsCannotBeRecreated() throws Exception {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        TestType type = new TestType();
        var unavailable = new SourceDocument(unknown, OWNER.toString(), "absent:type", CODEC.toString(),
                1, "unknown", new byte[]{1, 2, 3}, java.util.Set.of(), java.util.Set.of());
        try (var fixture = project(directory.resolve("project"), registry(type), List.of(source(known, "known"), unavailable));
             var session = fixture.open()) {
            for (UUID id : List.of(known, unknown)) {
                assertThrows(SessionRejection.class, () -> session.create(id, TYPE, String.class, "replace"));
            }
            assertEquals(0, type.creates);
            assertArrayEquals(new byte[]{1, 2, 3}, session.source().documentBytes(unknown));
            assertThrows(IllegalArgumentException.class, () -> session.find(known, Integer.class));
            assertTrue(session.find(unknown, Integer.class).isEmpty());
        }
    }

    @Test void namesUseBoundedValidUtf16AndDirectoryReadsAreSortedImmutableSnapshots() throws Exception {
        TestType type = new TestType();
        try (var fixture = project(directory.resolve("project"), registry(type), List.of()); var session = fixture.open()) {
            var oldDirectory = session.documents();
            for (String name : List.of("x".repeat(4097), "\uD800", "\uDC00", "x\uD800y")) {
                assertThrows(IllegalArgumentException.class,
                        () -> session.create(UUID.randomUUID(), TYPE, String.class, name));
            }
            assertEquals(0, type.creates);
            UUID first = new UUID(0, 1);
            UUID second = new UUID(0, 2);
            session.create(second, TYPE, String.class, "x".repeat(4094) + "\uD83D\uDE00");
            session.create(first, TYPE, String.class, "");
            assertTrue(oldDirectory.isEmpty());
            assertEquals(List.of(first, second), new ArrayList<>(session.documents().keySet()));
            assertThrows(UnsupportedOperationException.class, () -> session.documents().clear());
            assertEquals(4096, session.find(second, String.class).orElseThrow().displayName().length());
        }
    }
}
