package net.ptcrys.fpsmatch.editor.core.documents;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * Behaviour tests for {@link DocumentSession} against two domain-neutral immutable models.
 */
class DocumentSessionTest {

    private static final EditorId CODE = EditorId.parse("documents:test/rule");
    private static final TextContent LABEL = new TextContent.Literal("typing");

    // ---------------------------------------------------------------- models

    /** Rejects any document whose joined text contains "ERROR". */
    private static final class RejectingTextModel implements DocumentModel<TextDocument> {
        @Override
        public TextDocument snapshot(TextDocument value) {
            return new TextDocument(value.lines(), value.caret());
        }

        @Override
        public byte[] encode(TextDocument value) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(value.caret());
                for (String line : value.lines()) {
                    out.writeInt(line.length());
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return bytes.toByteArray();
        }

        @Override
        public List<Problem> validate(TextDocument value) {
            if (value.joined().contains("ERROR")) {
                return List.of(new Problem(Problem.Severity.ERROR, CODE, "text",
                        new TextContent.Literal("forbidden marker")));
            }
            return List.of();
        }
    }

    /** Counts encodings so a test can prove that reading a view never re-encodes. */
    private static final class CounterModel implements DocumentModel<CounterDocument> {
        private final AtomicInteger encodings = new AtomicInteger();

        @Override
        public CounterDocument snapshot(CounterDocument value) {
            return new CounterDocument(value.deltas(), value.note());
        }

        @Override
        public byte[] encode(CounterDocument value) {
            encodings.incrementAndGet();
            return (value.total() + "|" + value.note()).getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(CounterDocument value) {
            List<Problem> problems = new ArrayList<>();
            if (value.note().contains("ERROR")) {
                problems.add(new Problem(Problem.Severity.ERROR, CODE, "note",
                        new TextContent.Literal("bad note")));
            }
            if (value.deltas().size() > 8) {
                problems.add(new Problem(Problem.Severity.ERROR, CODE, "deltas",
                        new TextContent.Literal("too many entries")));
            }
            return problems;
        }
    }

    /**
     * A model that can attempt a session mutation from inside {@code snapshot}, {@code validate} or
     * {@code encode}. Hooks are assigned after construction, when the session already exists.
     *
     * <p>Construction runs before the guard exists, so the hook attempt counter is armed only for
     * the calls made after the harness has been wired to a session.</p>
     */
    private static final class ReentrancyHarness implements DocumentModel<TextDocument> {
        private final AtomicInteger hookAttempts = new AtomicInteger();
        private boolean armed;
        private Runnable snapshotHook;
        private Runnable validateHook;
        private Runnable encodeHook;

        void arm() {
            armed = true;
        }

        int hookAttempts() {
            return hookAttempts.get();
        }

        @Override
        public TextDocument snapshot(TextDocument value) {
            fire(snapshotHook);
            return new TextDocument(value.lines(), value.caret());
        }

        @Override
        public byte[] encode(TextDocument value) {
            fire(encodeHook);
            return value.joined().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(TextDocument value) {
            fire(validateHook);
            return List.of();
        }

        private void fire(Runnable hook) {
            if (hook != null && armed) {
                hookAttempts.incrementAndGet();
                hook.run();
            }
        }
    }

    /** Recorded outcome of one reentrant call made from inside a mutation. */
    private static final class ReentrancyOutcome {
        Throwable failure;
    }

    private static void attempt(Runnable action, ReentrancyOutcome outcome) {
        try {
            action.run();
        } catch (RuntimeException e) {
            outcome.failure = e;
        }
    }

    private static void assertRejected(ReentrancyOutcome outcome) {
        assertTrue(outcome.failure instanceof DocumentRejection, "expected DocumentRejection but got "
                + (outcome.failure == null ? "no failure" : outcome.failure));
    }

    private static UnaryOperator<TextDocument> lines(String... replacements) {
        return document -> new TextDocument(List.of(replacements), document.caret());
    }

    private static DocumentSession<TextDocument> textSession(int history) {
        return new DocumentSession<>(UUID.randomUUID(), new TextDocument(List.of("a"), 0),
                new RejectingTextModel(), history);
    }

    private static DocumentSession<CounterDocument> counterSession(int history, long... deltas) {
        List<Long> values = new ArrayList<>();
        for (long delta : deltas) {
            values.add(delta);
        }
        return new DocumentSession<>(UUID.randomUUID(), new CounterDocument(values, "draft"),
                new CounterModel(), history);
    }

    // ------------------------------------------------------------ construction

    @Test
    void rejectsInitialValueWithErrorProblem() {
        assertThrows(DocumentRejection.class, () -> new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("ERROR"), 0), new RejectingTextModel(), 4));
    }

    @Test
    void rejectsNegativeHistoryCapacityAndNullArguments() {
        TextDocument initial = new TextDocument(List.of("a"), 0);
        assertThrows(IllegalArgumentException.class, () -> new DocumentSession<>(UUID.randomUUID(),
                initial, new RejectingTextModel(), -1));
        assertThrows(NullPointerException.class,
                () -> new DocumentSession<>(UUID.randomUUID(), initial, null, 1));
        assertThrows(NullPointerException.class,
                () -> new DocumentSession<>(null, initial, new RejectingTextModel(), 1));
        assertThrows(NullPointerException.class,
                () -> new DocumentSession<>(UUID.randomUUID(), null, new RejectingTextModel(), 1));
    }

    @Test
    void initialViewIsCleanAndWithoutHistory() {
        DocumentSession<TextDocument> session = textSession(2);
        DocumentView<TextDocument> view = session.view();
        assertEquals(0L, view.token().revision());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertTrue(view.pendingInputs().isEmpty());
        assertEquals("a", view.content().joined());
    }

    // ---------------------------------------------------------- edit atomicity

    @Test
    void throwingCommandLeavesContentTokenHistoryAndDirtyUntouched() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();

        assertThrows(IllegalStateException.class, () -> session.edit(token, LABEL, document -> {
            throw new IllegalStateException("command failed");
        }));

        DocumentView<TextDocument> view = session.view();
        assertEquals(token, view.token());
        assertEquals("a", view.content().joined());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertFalse(view.dirty());
    }

    @Test
    void errorResultLeavesStateUntouched() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();

        assertThrows(DocumentRejection.class, () -> session.edit(token, LABEL, lines("ERROR")));

        DocumentView<TextDocument> view = session.view();
        assertEquals(token, view.token());
        assertEquals("a", view.content().joined());
        assertFalse(view.canUndo());
        assertFalse(view.dirty());
    }

    @Test
    void noOpEditDoesNotAddHistoryOrAdvanceRevision() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();

        session.edit(token, LABEL, lines("a"));

        DocumentView<TextDocument> view = session.view();
        assertEquals(token, view.token());
        assertFalse(view.canUndo());
        assertFalse(view.dirty());
    }

    @Test
    void successfulEditPublishesSnapshotAndClearsRedo() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        session.undo(session.view().token());
        assertTrue(session.view().canRedo());

        session.edit(session.view().token(), LABEL, lines("c"));

        DocumentView<TextDocument> view = session.view();
        assertEquals("c", view.content().joined());
        assertFalse(view.canRedo());
        assertTrue(view.canUndo());
        assertTrue(view.dirty());
        assertEquals(3L, view.token().revision());
    }

    // ------------------------------------------------------------- undo / redo

    @Test
    void undoRestoresContentAndIncrementsRevision() {
        DocumentSession<TextDocument> session = textSession(4);
        String branch = session.view().token().branchId().toString();

        session.edit(session.view().token(), LABEL, lines("b"));
        long afterEdit = session.view().token().revision();
        session.undo(session.view().token());

        DocumentView<TextDocument> view = session.view();
        assertEquals("a", view.content().joined());
        assertEquals(afterEdit + 1, view.token().revision());
        assertEquals(branch, view.token().branchId().toString());
        assertTrue(view.canRedo());
        assertFalse(view.dirty());
    }

    @Test
    void redoReappliesUndoneChangeAndIncrementsRevision() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        session.undo(session.view().token());
        long afterUndo = session.view().token().revision();

        session.redo(session.view().token());

        DocumentView<TextDocument> view = session.view();
        assertEquals("b", view.content().joined());
        assertEquals(afterUndo + 1, view.token().revision());
        assertTrue(view.canUndo());
        assertFalse(view.canRedo());
        assertTrue(view.dirty());
    }

    @Test
    void undoAndRedoRejectStaleAndForeignTokens() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken initial = session.view().token();
        session.edit(initial, LABEL, lines("b"));

        assertThrows(DocumentRejection.class, () -> session.undo(initial));
        assertThrows(DocumentRejection.class, () -> session.undo(new DocumentToken(
                initial.documentId(), UUID.randomUUID(), 1L)));
        assertThrows(DocumentRejection.class, () -> session.undo(new DocumentToken(
                UUID.randomUUID(), initial.branchId(), 1L)));
        assertThrows(DocumentRejection.class, () -> session.redo(session.view().token()));
    }

    // ------------------------------------------------------------ save capture

    @Test
    void markSavedConfirmsCaptureWithoutReplacingDraft() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken captured = session.view().token();

        session.markSaved(captured);

        DocumentView<TextDocument> view = session.view();
        assertEquals("b", view.content().joined());
        assertEquals(captured, view.token());
        assertFalse(view.dirty());
        assertTrue(view.canUndo());
    }

    @Test
    void editAfterSaveKeepsDirtyAndLateConfirmationIsHonoured() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken captured = session.view().token();
        session.markSaved(captured);
        session.edit(session.view().token(), LABEL, lines("c"));
        assertTrue(session.view().dirty());

        session.markSaved(captured);

        DocumentView<TextDocument> view = session.view();
        assertEquals("c", view.content().joined());
        assertTrue(view.dirty());
        assertNotEquals(captured, view.token());
    }

    @Test
    void repeatedConfirmationOfSameTokenIsIdempotent() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken captured = session.view().token();

        session.markSaved(captured);
        session.markSaved(captured);

        assertFalse(session.view().dirty());
    }

    @Test
    void undoAfterSaveRemainsAvailableAndRedoRestoresCleanState() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        session.markSaved(session.view().token());
        assertFalse(session.view().dirty());

        session.undo(session.view().token());
        DocumentView<TextDocument> undone = session.view();
        assertEquals(2L, undone.token().revision());
        assertTrue(undone.canRedo());

        session.redo(session.view().token());

        DocumentView<TextDocument> redone = session.view();
        assertEquals("b", redone.content().joined());
        assertFalse(redone.dirty());
    }

    @Test
    void undoPastAConfirmationIsDirtyUntilTheDraftMatchesTheSavedContentAgain() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken captured = session.view().token();
        session.markSaved(captured);
        session.undo(session.view().token());

        DocumentView<TextDocument> undone = session.view();
        assertEquals("a", undone.content().joined());
        assertTrue(undone.dirty());

        session.redo(session.view().token());

        assertFalse(session.view().dirty());
    }

    @Test
    void outOfOrderSaveConfirmationIsRejected() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken first = session.view().token();
        session.edit(session.view().token(), LABEL, lines("c"));
        DocumentToken second = session.view().token();

        session.markSaved(second);

        assertEquals("c", session.view().content().joined());
        assertThrows(DocumentRejection.class, () -> session.markSaved(first));
        assertFalse(session.view().dirty());
    }

    @Test
    void saveConfirmationRejectsUnknownBranchAndFutureRevision() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();
        session.edit(token, LABEL, lines("b"));
        DocumentToken current = session.view().token();

        assertThrows(DocumentRejection.class, () -> session.markSaved(new DocumentToken(
                current.documentId(), UUID.randomUUID(), current.revision())));
        assertThrows(DocumentRejection.class, () -> session.markSaved(new DocumentToken(
                current.documentId(), current.branchId(), current.revision() + 5)));
        assertThrows(DocumentRejection.class, () -> session.markSaved(new DocumentToken(
                UUID.randomUUID(), current.branchId(), current.revision())));
        assertTrue(session.view().dirty());
    }

    @Test
    void trimmedHistoryRejectsSaveConfirmationOfUnretainedToken() {
        DocumentSession<TextDocument> session = textSession(1);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken trimmed = session.view().token();
        session.edit(session.view().token(), LABEL, lines("c"));
        session.edit(session.view().token(), LABEL, lines("d"));

        assertEquals(3L, session.view().token().revision());
        assertThrows(DocumentRejection.class, () -> session.markSaved(trimmed));
        assertTrue(session.view().dirty());
    }

    @Test
    void zeroHistoryStillConfirmsTheCurrentRevision() {
        DocumentSession<TextDocument> session = textSession(0);
        session.edit(session.view().token(), LABEL, lines("b"));

        session.markSaved(session.view().token());

        assertFalse(session.view().dirty());
        assertFalse(session.view().canUndo());
    }

    @Test
    void boundedHistoryDropsOldestUndoEntries() {
        DocumentSession<TextDocument> session = textSession(2);
        session.edit(session.view().token(), LABEL, lines("b"));
        session.edit(session.view().token(), LABEL, lines("c"));
        session.edit(session.view().token(), LABEL, lines("d"));

        session.undo(session.view().token());
        session.undo(session.view().token());

        DocumentView<TextDocument> view = session.view();
        assertEquals("b", view.content().joined());
        assertFalse(view.canUndo());
        assertTrue(view.canRedo());
    }

    @Test
    void zeroHistoryDisablesUndoAndRedo() {
        DocumentSession<TextDocument> session = textSession(0);
        session.edit(session.view().token(), LABEL, lines("b"));

        DocumentView<TextDocument> view = session.view();
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertThrows(DocumentRejection.class, () -> session.undo(view.token()));
    }

    // --------------------------------------------------------------- baseline

    @Test
    void replaceBaselineStartsNewBranchAndInvalidatesOldTokens() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken old = session.view().token();
        session.setPendingInput("name", "half typed");

        session.replaceBaseline(new TextDocument(List.of("fresh"), 3));

        DocumentView<TextDocument> view = session.view();
        assertEquals("fresh", view.content().joined());
        assertEquals(0L, view.token().revision());
        assertNotEquals(old.branchId(), view.token().branchId());
        assertEquals(old.documentId(), view.token().documentId());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertFalse(view.dirty());
        assertEquals(Map.of("name", "half typed"), view.pendingInputs());
        assertThrows(DocumentRejection.class, () -> session.undo(old));
        assertThrows(DocumentRejection.class, () -> session.markSaved(old));
    }

    @Test
    void replaceBaselineRejectsInvalidValueAndKeepsState() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();

        assertThrows(DocumentRejection.class,
                () -> session.replaceBaseline(new TextDocument(List.of("ERROR"), 0)));
        assertThrows(NullPointerException.class, () -> session.replaceBaseline(null));

        assertEquals(token, session.view().token());
        assertEquals("a", session.view().content().joined());
    }

    // ---------------------------------------------------------------- pending

    @Test
    void pendingInputDoesNotTouchDomainDirtyOrToken() {
        DocumentSession<CounterDocument> session = counterSession(2, 5L);
        DocumentToken token = session.view().token();

        session.setPendingInput("amount", "12");

        DocumentView<CounterDocument> view = session.view();
        assertEquals(token, view.token());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());
        assertEquals(5L, view.content().total());
        assertEquals("12", view.pendingInputs().get("amount"));

        session.clearPendingInput("amount");
        assertTrue(session.view().pendingInputs().isEmpty());
        session.clearPendingInput("unknown");
        assertTrue(session.view().pendingInputs().isEmpty());
    }

    @Test
    void pendingInputIsBoundedAndValidated() {
        DocumentSession<CounterDocument> session = counterSession(2, 1L);
        for (int i = 0; i < 1024; i++) {
            session.setPendingInput("field-" + i, "v");
        }
        assertEquals(1024, session.view().pendingInputs().size());

        assertThrows(DocumentRejection.class, () -> session.setPendingInput("field-overflow", "v"));
        assertThrows(IllegalArgumentException.class,
                () -> session.setPendingInput("x".repeat(1025), "v"));
        assertThrows(IllegalArgumentException.class,
                () -> session.setPendingInput("ok", "x".repeat(65537)));
        assertEquals(1024, session.view().pendingInputs().size());

        session.setPendingInput("field-0", "replaced");
        assertEquals(1024, session.view().pendingInputs().size());
        assertEquals("replaced", session.view().pendingInputs().get("field-0"));
    }

    // -------------------------------------------------------------- isolation

    @Test
    void viewCollectionsAreImmutableCopies() {
        DocumentSession<TextDocument> session = textSession(2);
        session.setPendingInput("a", "1");
        DocumentView<TextDocument> view = session.view();

        assertThrows(UnsupportedOperationException.class, () -> view.content().lines().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> view.pendingInputs().put("a", "b"));
    }

    @Test
    void mutatingSourceValueAfterConstructionDoesNotPolluteSnapshot() {
        List<Long> source = new ArrayList<>(List.of(1L, 2L));
        CounterDocument initial = new CounterDocument(source, "draft");
        DocumentSession<CounterDocument> session =
                new DocumentSession<>(UUID.randomUUID(), initial, new CounterModel(), 2);

        source.add(100L);

        assertEquals(3L, session.view().content().total());
        assertEquals(List.of(1L, 2L), session.view().content().deltas());
    }

    @Test
    void commandReceivesAnIsolatedSnapshotNotTheLiveContent() {
        DocumentSession<TextDocument> session = textSession(2);
        TextDocument live = session.view().content();
        List<Object> observed = new ArrayList<>();

        session.edit(session.view().token(), LABEL, document -> {
            observed.add(document);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertEquals(1, observed.size());
        assertNotSame(live, observed.get(0));
        assertEquals("a", live.joined());
        assertEquals("a", ((TextDocument) observed.get(0)).joined());
    }

    @Test
    void publishedSnapshotIsDetachedFromTheCommandResult() {
        DocumentSession<CounterDocument> session = counterSession(2, 0L);
        List<CounterDocument> results = new ArrayList<>();

        session.edit(session.view().token(), LABEL, document -> {
            CounterDocument result = new CounterDocument(List.of(4L), "draft");
            results.add(result);
            return result;
        });

        assertNotSame(results.get(0), session.view().content());
        assertEquals(4L, session.view().content().total());
    }

    @Test
    void viewDoesNotReEncodeOnEveryRead() {
        CounterModel model = new CounterModel();
        DocumentSession<CounterDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new CounterDocument(List.of(1L), "draft"), model, 2);
        int afterConstruction = model.encodings.get();

        for (int i = 0; i < 25; i++) {
            session.view();
        }

        assertEquals(afterConstruction, model.encodings.get());
    }

    @Test
    void previouslyReturnedViewDoesNotChangeWithLaterEdits() {
        DocumentSession<TextDocument> session = textSession(2);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentView<TextDocument> first = session.view();

        session.edit(first.token(), LABEL, lines("c"));

        assertEquals("b", first.content().joined());
        assertEquals(1L, first.token().revision());
        assertEquals("c", session.view().content().joined());
    }

    @Test
    void invalidArgumentsAreRejectedWithoutChangingState() {
        DocumentSession<TextDocument> session = textSession(2);
        DocumentToken token = session.view().token();

        assertThrows(NullPointerException.class, () -> session.edit(null, LABEL, lines("b")));
        assertThrows(NullPointerException.class, () -> session.edit(token, null, lines("b")));
        assertThrows(NullPointerException.class, () -> session.edit(token, LABEL, null));
        assertThrows(NullPointerException.class, () -> session.setPendingInput(null, "v"));
        assertThrows(NullPointerException.class, () -> session.setPendingInput("a", null));
        assertThrows(NullPointerException.class, () -> session.clearPendingInput(null));
        assertThrows(NullPointerException.class, () -> session.markSaved(null));
        assertThrows(NullPointerException.class, () -> session.undo(null));
        assertThrows(NullPointerException.class, () -> session.redo(null));
        assertThrows(NullPointerException.class, () -> session.edit(token, LABEL, document -> null));
        assertThrows(NullPointerException.class, () -> session.replaceBaseline(null));

        DocumentView<TextDocument> view = session.view();
        assertEquals(token, view.token());
        assertEquals("a", view.content().joined());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());
        assertTrue(view.pendingInputs().isEmpty());
    }

    @Test
    void modelViolationsAreRejectedAndLeaveStateUntouched() {
        DocumentModel<TextDocument> nullSnapshot = new DocumentModel<>() {
            @Override
            public TextDocument snapshot(TextDocument value) {
                return null;
            }

            @Override
            public byte[] encode(TextDocument value) {
                return new byte[0];
            }

            @Override
            public List<Problem> validate(TextDocument value) {
                return List.of();
            }
        };
        assertThrows(NullPointerException.class, () -> new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), nullSnapshot, 1));

        DocumentModel<TextDocument> nullEncoding = new DocumentModel<>() {
            @Override
            public TextDocument snapshot(TextDocument value) {
                return new TextDocument(value.lines(), value.caret());
            }

            @Override
            public byte[] encode(TextDocument value) {
                return null;
            }

            @Override
            public List<Problem> validate(TextDocument value) {
                return List.of();
            }
        };
        assertThrows(NullPointerException.class, () -> new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), nullEncoding, 1));
    }

    @Test
    void nullEncodingFromEditResultIsRejectedWithoutChangingState() {
        DocumentModel<TextDocument> encodeOnSecondCallOnly = new DocumentModel<>() {
            private final AtomicInteger calls = new AtomicInteger();

            @Override
            public TextDocument snapshot(TextDocument value) {
                return new TextDocument(value.lines(), value.caret());
            }

            @Override
            public byte[] encode(TextDocument value) {
                if (calls.incrementAndGet() > 1) {
                    return null;
                }
                return value.joined().getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public List<Problem> validate(TextDocument value) {
                return List.of();
            }
        };
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), encodeOnSecondCallOnly, 1);
        DocumentToken token = session.view().token();

        assertThrows(NullPointerException.class, () -> session.edit(token, LABEL, lines("b")));

        assertEquals(token, session.view().token());
        assertEquals("a", session.view().content().joined());
        assertFalse(session.view().dirty());
        assertFalse(session.view().canUndo());
    }

    @Test
    void tokenRejectsNegativeRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> new DocumentToken(UUID.randomUUID(), UUID.randomUUID(), -1L));
    }

    @Test
    void warningSeverityDoesNotRejectValue() {
        DocumentModel<CounterDocument> warningOnly = new DocumentModel<>() {
            @Override
            public CounterDocument snapshot(CounterDocument value) {
                return new CounterDocument(value.deltas(), value.note());
            }

            @Override
            public byte[] encode(CounterDocument value) {
                return Objects.toString(value.total()).getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public List<Problem> validate(CounterDocument value) {
                return List.of(new Problem(Problem.Severity.WARNING, CODE, "note",
                        new TextContent.Literal("mild")));
            }
        };
        DocumentSession<CounterDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new CounterDocument(List.of(2L), "draft"), warningOnly, 1);

        session.edit(session.view().token(), LABEL,
                document -> new CounterDocument(List.of(3L), "draft"));

        assertEquals(3L, session.view().content().total());
        assertTrue(session.view().dirty());
        assertArrayEquals("3".getBytes(StandardCharsets.UTF_8),
                warningOnly.encode(session.view().content()));
    }

    // ------------------------------------------------------ mutation reentrancy

    @Test
    void commandReenteringReplaceBaselineIsRejectedAndCannotPublishInsideTheMutation() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        session.setPendingInput("name", "half trained");
        ReentrancyOutcome outcome = new ReentrancyOutcome();

        session.edit(token, LABEL, document -> {
            attempt(() -> session.replaceBaseline(new TextDocument(List.of("swap"), 7)), outcome);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(outcome);
        DocumentView<TextDocument> view = session.view();
        assertEquals(token.branchId(), view.token().branchId());
        assertEquals(1L, view.token().revision());
        assertEquals("b", view.content().joined());
        assertEquals(Map.of("name", "half trained"), view.pendingInputs());
    }

    @Test
    void commandReenteringReplaceBaselineThenThrowingLeavesNoPublishedChange() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        session.setPendingInput("name", "half typed");
        ReentrancyOutcome outcome = new ReentrancyOutcome();
        DocumentView<TextDocument> before = session.view();

        assertThrows(IllegalStateException.class, () -> session.edit(token, LABEL, document -> {
            attempt(() -> session.replaceBaseline(new TextDocument(List.of("swap"), 7)), outcome);
            throw new IllegalStateException("command failed after the reentrant attempt");
        }));

        assertRejected(outcome);
        DocumentView<TextDocument> view = session.view();
        assertEquals(before.token(), view.token());
        assertEquals(before.content().joined(), view.content().joined());
        assertEquals(before.dirty(), view.dirty());
        assertEquals(before.canUndo(), view.canUndo());
        assertEquals(before.canRedo(), view.canRedo());
        assertEquals(before.pendingInputs(), view.pendingInputs());
    }

    @Test
    void commandReenteringEditUndoRedoAndMarkSavedIsRejected() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        session.edit(session.view().token(), LABEL, lines("b"));
        session.undo(session.view().token());
        ReentrancyOutcome nestedEdit = new ReentrancyOutcome();
        ReentrancyOutcome nestedUndo = new ReentrancyOutcome();
        ReentrancyOutcome nestedRedo = new ReentrancyOutcome();
        ReentrancyOutcome nestedMarkSaved = new ReentrancyOutcome();
        ReentrancyOutcome nestedClearPending = new ReentrancyOutcome();
        session.setPendingInput("keep", "value");
        DocumentToken current = session.view().token();

        session.edit(current, LABEL, document -> {
            attempt(() -> session.edit(current, LABEL, lines("nested")), nestedEdit);
            attempt(() -> session.undo(current), nestedUndo);
            attempt(() -> session.redo(current), nestedRedo);
            attempt(() -> session.markSaved(current), nestedMarkSaved);
            attempt(() -> session.clearPendingInput("keep"), nestedClearPending);
            return new TextDocument(List.of("outer"), document.caret());
        });

        assertRejected(nestedEdit);
        assertRejected(nestedUndo);
        assertRejected(nestedRedo);
        assertRejected(nestedMarkSaved);
        assertRejected(nestedClearPending);
        DocumentView<TextDocument> view = session.view();
        assertEquals(current.revision() + 1, view.token().revision());
        assertEquals("outer", view.content().joined());
        assertEquals(Map.of("keep", "value"), view.pendingInputs());
        assertTrue(view.dirty());
    }

    @Test
    void commandCanStillReadThePublishedStateWhileMutating() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        List<DocumentView<TextDocument>> observed = new ArrayList<>();

        session.edit(token, LABEL, document -> {
            observed.add(session.view());
            return new TextDocument(List.of("b"), document.caret());
        });

        assertEquals(1, observed.size());
        assertEquals(token, observed.get(0).token());
        assertEquals("a", observed.get(0).content().joined());
        assertEquals(1L, session.view().token().revision());
        assertEquals("b", session.view().content().joined());
    }

    @Test
    void pendingInputReenteringEditIsRejectedWithoutWritingTheEntry() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        session.setPendingInput("attempt", "1");
        session.setPendingInput("attempt", "2");
        assertEquals("2", session.view().pendingInputs().get("attempt"));
        assertEquals(1, session.view().pendingInputs().size());
        ReentrancyOutcome outcome = new ReentrancyOutcome();

        session.edit(token, LABEL, document -> {
            attempt(() -> session.setPendingInput("nested", "3"), outcome);
            attempt(() -> session.setPendingInput("attempt", "overwritten"), outcome);
            attempt(() -> session.clearPendingInput("attempt"), outcome);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(outcome);
        assertEquals(Map.of("attempt", "2"), session.view().pendingInputs());
        assertEquals(1L, session.view().token().revision());
        assertEquals("b", session.view().content().joined());

        session.setPendingInput("after", "4");
        assertEquals(2, session.view().pendingInputs().size());
    }

    @Test
    void modelSnapshotCannotRenenterTheSession() {
        assertModelHookReentrancyRejected(true, false, false);
    }
    @Test
    void modelValidateCannotRenenterTheSession() {
        assertModelHookReentrancyRejected(false, true, false);
    }

    @Test
    void modelEncodeCannotRenenterTheSession() {
        assertModelHookReentrancyRejected(false, false, true);
    }

    private static void assertModelHookReentrancyRejected(boolean inSnapshot, boolean inValidate,
            boolean inEncode) {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        DocumentToken token = session.view().token();
        List<DocumentView<TextDocument>> observed = new ArrayList<>();
        ReentrancyOutcome outcome = new ReentrancyOutcome();
        Runnable hook = () -> {
            observed.add(session.view());
            attempt(() -> session.edit(token, LABEL, lines("from-hook")), outcome);
            attempt(() -> session.replaceBaseline(new TextDocument(List.of("swap"), 1)), outcome);
        };
        if (inSnapshot) {
            harness.snapshotHook = hook;
        }
        if (inValidate) {
            harness.validateHook = hook;
        }
        if (inEncode) {
            harness.encodeHook = hook;
        }
        harness.arm();

        session.edit(token, LABEL, lines("b"));

        assertTrue(outcome.failure instanceof DocumentRejection,
                "expected the reentrant edit to be rejected, got " + outcome.failure);
        assertTrue(harness.hookAttempts() >= 1);
        assertFalse(observed.isEmpty());
        assertEquals(token, observed.get(0).token());
        DocumentView<TextDocument> view = session.view();
        assertEquals(token.branchId(), view.token().branchId());
        assertEquals(1L, view.token().revision());
        assertEquals("b", view.content().joined());
        assertTrue(view.canUndo());
        assertFalse(view.canRedo());

        session.undo(view.token());

        DocumentView<TextDocument> undone = session.view();
        assertEquals(2L, undone.token().revision());
        assertEquals(token.branchId(), undone.token().branchId());
        assertEquals("a", undone.content().joined());
        assertFalse(undone.canUndo());
        assertTrue(undone.canRedo());
        assertFalse(undone.dirty());
    }

    @Test
    void guardIsReleasedAfterARejectedReentrantAttempt() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        ReentrancyOutcome outcome = new ReentrancyOutcome();

        session.edit(token, LABEL, document -> {
            attempt(() -> session.undo(token), outcome);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(outcome);

        session.edit(session.view().token(), LABEL, lines("c"));
        session.undo(session.view().token());
        session.redo(session.view().token());
        session.markSaved(session.view().token());
        session.setPendingInput("after", "guard");
        session.clearPendingInput("after");
        session.replaceBaseline(new TextDocument(List.of("fresh"), 0));

        DocumentView<TextDocument> view = session.view();
        assertEquals("fresh", view.content().joined());
        assertEquals(0L, view.token().revision());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertTrue(view.pendingInputs().isEmpty());
    }

    @Test
    void rejectedReentrantMutationDoesNotLeakGuardToAnotherSession() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> guarded = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken guardedToken = guarded.view().token();
        DocumentSession<TextDocument> other = textSession(4);
        ReentrancyOutcome outcome = new ReentrancyOutcome();

        guarded.edit(guardedToken, LABEL, document -> {
            other.edit(other.view().token(), LABEL, lines("other"));
            attempt(() -> guarded.undo(guardedToken), outcome);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(outcome);
        assertEquals("other", other.view().content().joined());
        assertEquals(1L, other.view().token().revision());
        assertEquals("b", guarded.view().content().joined());
        assertEquals(1L, guarded.view().token().revision());
    }

    @Test
    void guardTakesPrecedenceOverArgumentValidationForReentrantCalls() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        ReentrancyOutcome nullLabel = new ReentrancyOutcome();
        ReentrancyOutcome nullAddress = new ReentrancyOutcome();

        session.edit(token, LABEL, document -> {
            attempt(() -> session.edit(token, null, lines("x")), nullLabel);
            attempt(() -> session.setPendingInput(null, "x"), nullAddress);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(nullLabel);
        assertRejected(nullAddress);
        assertEquals("b", session.view().content().joined());
        assertEquals(1L, session.view().token().revision());
    }

    @Test
    void replaceBaselineInsideACommandPropagatesRejectionAndPublishesNothing() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentToken current = session.view().token();
        DocumentView<TextDocument> before = session.view();

        assertThrows(DocumentRejection.class, () -> session.edit(current, LABEL, document -> {
            session.replaceBaseline(new TextDocument(List.of("swap"), 3));
            return new TextDocument(List.of("c"), document.caret());
        }));

        DocumentView<TextDocument> view = session.view();
        assertEquals(before.token(), view.token());
        assertEquals(before.token().branchId(), view.token().branchId());
        assertEquals(before.content().joined(), view.content().joined());
        assertEquals(before.dirty(), view.dirty());
        assertTrue(view.pendingInputs().isEmpty());

        session.undo(view.token());

        assertEquals("a", session.view().content().joined());
        assertEquals(2L, session.view().token().revision());
        assertFalse(session.view().dirty());
        assertFalse(session.view().canUndo());
        assertTrue(session.view().canRedo());
    }

    @Test
    void replaceBaselineReenteringReplaceBaselineIsRejectedLikeInsideEdit() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        DocumentToken token = session.view().token();
        ReentrancyOutcome outcome = new ReentrancyOutcome();
        harness.snapshotHook = () -> attempt(
                () -> session.replaceBaseline(new TextDocument(List.of("inner"), 0)), outcome);

        session.replaceBaseline(new TextDocument(List.of("outer"), 5));

        assertRejected(outcome);
        DocumentView<TextDocument> view = session.view();
        assertEquals("outer", view.content().joined());
        assertEquals(5, view.content().caret());
        assertEquals(0L, view.token().revision());
        assertFalse(view.dirty());
    }

    @Test
    void reentrantAttemptFromAModelHookCannotPublishAndLeavesEveryFieldIntact() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        harness.arm();
        session.edit(session.view().token(), LABEL, lines("b"));
        session.setPendingInput("field", "42");
        DocumentToken current = session.view().token();
        DocumentView<TextDocument> before = session.view();
        ReentrancyOutcome outcome = new ReentrancyOutcome();
        harness.encodeHook = () -> {
            attempt(() -> session.edit(current, LABEL, lines("hooked")), outcome);
            attempt(() -> session.undo(current), outcome);
            attempt(() -> session.replaceBaseline(new TextDocument(List.of("swap"), 0)), outcome);
            attempt(() -> session.markSaved(current), outcome);
            attempt(() -> session.clearPendingInput("field"), outcome);
        };

        session.edit(current, LABEL, lines("c"));

        assertRejected(outcome);
        DocumentView<TextDocument> view = session.view();
        assertEquals(current.branchId(), view.token().branchId());
        assertEquals(before.token().revision() + 1, view.token().revision());
        assertEquals("c", view.content().joined());
        assertTrue(view.dirty());
        assertEquals(Map.of("field", "42"), view.pendingInputs());
        assertTrue(view.canUndo());
        assertFalse(view.canRedo());
        assertEquals(before.token().revision() + 1, view.token().revision());
    }

    // ------------------------------------------- independent save captures (Q02)

    @Test
    void captureForSaveHoldsTheCurrentTokenContentAndEncodingWithoutChangingState() {
        DocumentSession<TextDocument> session = textSession(4);
        session.setPendingInput("field", "42");

        DocumentCapture<TextDocument> capture = session.captureForSave();

        DocumentView<TextDocument> view = session.view();
        assertEquals(view.token(), capture.token());
        assertEquals(view.content(), capture.content());
        assertEquals("a", capture.content().joined());
        assertEquals(0L, capture.token().revision());
        // Encoding is not exposed by the view, so compare it with an identically built session.
        DocumentSession<TextDocument> twin = new DocumentSession<>(view.token().documentId(),
                new TextDocument(List.of("a"), 0), new RejectingTextModel(), 4);
        assertArrayEquals(twin.captureForSave().encoding(), capture.encoding());

        assertFalse(view.dirty());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertEquals(Map.of("field", "42"), view.pendingInputs());
    }

    @Test
    void captureForSaveDoesNotReEncode() {
        CounterModel model = new CounterModel();
        DocumentSession<CounterDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new CounterDocument(List.of(1L), "draft"), model, 4);
        int afterConstruction = model.encodings.get();

        DocumentCapture<CounterDocument> capture = session.captureForSave();

        assertEquals(afterConstruction, model.encodings.get(),
                "capturing for a save must not encode again");
        assertEquals("draft", capture.content().note());
        assertEquals(0L, capture.token().revision());
        assertEquals(List.of(1L), capture.content().deltas());
        session.view();
        assertEquals(afterConstruction, model.encodings.get());
    }

    @Test
    void captureEncodingIsCopiedOnConstructionAndOnEveryRead() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentCapture<TextDocument> capture = session.captureForSave();
        byte[] expected = capture.encoding();

        byte[] read = capture.encoding();
        read[0] ^= 0xFF;
        assertArrayEquals(expected, capture.encoding(),
                "mutating a returned array must not change the capture");

        byte[] source = {1, 2, 3};
        DocumentCapture<TextDocument> builtByHand = new DocumentCapture<>(new Object(),
                new DocumentToken(UUID.randomUUID(), UUID.randomUUID(), 0L),
                new TextDocument(List.of("a"), 0), source);
        source[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, builtByHand.encoding(),
                "mutating the source array must not change the capture");
    }

    @Test
    void confirmingACaptureOutsideTheHistoryWindowKeepsADivergedDraftDirty() {
        DocumentSession<TextDocument> session = textSession(0);
        DocumentCapture<TextDocument> captureA = session.captureForSave();

        session.edit(session.view().token(), LABEL, lines("b"));
        session.edit(session.view().token(), LABEL, lines("c"));

        DocumentToken tokenAtC = session.view().token();
        assertEquals(2L, tokenAtC.revision());
        assertTrue(session.view().dirty());
        assertThrows(DocumentRejection.class, () -> session.undo(tokenAtC),
                "a zero-length history must still refuse undo");
        assertThrows(DocumentRejection.class, () -> session.markSaved(captureA.token()),
                "the captured revision has been trimmed out of the retained window");

        session.confirmSaved(captureA);

        DocumentView<TextDocument> view = session.view();
        assertEquals("c", view.content().joined());
        assertTrue(view.dirty(), "confirming an older capture must not clean a diverged draft");
        assertEquals(2L, view.token().revision());
        assertFalse(view.canUndo());
        assertFalse(view.canRedo());
        assertEquals(0L, captureA.token().revision());
    }

    @Test
    void undoBackToTheConfirmedCaptureBecomesClean() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentCapture<TextDocument> captureA = session.captureForSave();
        session.edit(session.view().token(), LABEL, lines("b"));
        session.edit(session.view().token(), LABEL, lines("c"));

        session.confirmSaved(captureA);
        assertTrue(session.view().dirty());

        session.undo(session.view().token());
        DocumentView<TextDocument> atB = session.view();
        assertEquals("b", atB.content().joined());
        assertTrue(atB.dirty());

        session.undo(atB.token());
        DocumentView<TextDocument> atA = session.view();
        assertEquals("a", atA.content().joined());
        assertEquals(4L, atA.token().revision());
        assertFalse(atA.dirty(), "undoing back onto the confirmed capture is clean again");
    }

    @Test
    void confirmSavedIsIdempotentAndRefusesAnOlderCapture() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentCapture<TextDocument> captureA = session.captureForSave();
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentCapture<TextDocument> captureB = session.captureForSave();

        session.confirmSaved(captureB);
        assertFalse(session.view().dirty());
        session.confirmSaved(captureB);
        assertFalse(session.view().dirty(), "repeating the same capture is idempotent");
        assertEquals(1L, session.view().token().revision());

        DocumentView<TextDocument> before = session.view();
        assertThrows(DocumentRejection.class, () -> session.confirmSaved(captureA),
                "a capture older than the last confirmation is refused");

        DocumentView<TextDocument> after = session.view();
        assertEquals(before.token(), after.token());
        assertEquals(before.content(), after.content());
        assertEquals(before.dirty(), after.dirty());
        assertEquals("b", after.content().joined());
    }

    @Test
    void confirmSavedRefusesACaptureOfAnotherSessionEvenWithTheSameDocumentId() {
        UUID documentId = UUID.randomUUID();
        DocumentSession<TextDocument> first = new DocumentSession<>(documentId,
                new TextDocument(List.of("a"), 0), new RejectingTextModel(), 4);
        DocumentSession<TextDocument> second = new DocumentSession<>(documentId,
                new TextDocument(List.of("a"), 0), new RejectingTextModel(), 4);

        DocumentCapture<TextDocument> fromSecond = second.captureForSave();
        assertEquals(documentId, fromSecond.token().documentId());
        assertEquals(0L, fromSecond.token().revision());
        assertThrows(DocumentRejection.class, () -> first.confirmSaved(fromSecond),
                "the issuing session identity, not the token, decides");

        DocumentCapture<TextDocument> assembled = new DocumentCapture<>(new Object(),
                first.view().token(), new TextDocument(List.of("a"), 0), new byte[] {1});
        assertThrows(DocumentRejection.class, () -> first.confirmSaved(assembled));

        DocumentView<TextDocument> view = first.view();
        assertEquals(0L, view.token().revision());
        assertEquals("a", view.content().joined());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());

        first.confirmSaved(first.captureForSave());
        assertFalse(first.view().dirty());
    }

    @Test
    void confirmSavedRefusesACaptureOfAReplacedBranch() {
        DocumentSession<TextDocument> session = textSession(4);
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentCapture<TextDocument> oldBranch = session.captureForSave();

        session.replaceBaseline(new TextDocument(List.of("z"), 0));
        assertThrows(DocumentRejection.class, () -> session.confirmSaved(oldBranch));

        DocumentView<TextDocument> view = session.view();
        assertEquals("z", view.content().joined());
        assertEquals(0L, view.token().revision());
        assertFalse(view.dirty());
        assertFalse(view.canUndo());

        DocumentCapture<TextDocument> fresh = session.captureForSave();
        session.edit(session.view().token(), LABEL, lines("y"));
        session.confirmSaved(fresh);
        assertTrue(session.view().dirty(), "the new branch baseline is the fresh capture");
    }

    @Test
    void confirmSavedLeavesDraftRevisionHistoryAndPendingInputAlone() {
        DocumentSession<TextDocument> session = textSession(4);
        session.setPendingInput("field", "42");
        DocumentCapture<TextDocument> captureA = session.captureForSave();
        session.edit(session.view().token(), LABEL, lines("b"));
        DocumentCapture<TextDocument> captureB = session.captureForSave();

        DocumentView<TextDocument> before = session.view();
        session.confirmSaved(captureA);
        DocumentView<TextDocument> after = session.view();

        assertEquals(before.token(), after.token());
        assertEquals(before.content(), after.content());
        assertEquals(before.canUndo(), after.canUndo());
        assertEquals(before.canRedo(), after.canRedo());
        assertEquals(1L, after.token().revision());
        assertEquals("b", after.content().joined());
        assertTrue(after.dirty());
        assertTrue(after.canUndo());
        assertEquals(Map.of("field", "42"), after.pendingInputs());

        session.confirmSaved(captureB);
        assertFalse(session.view().dirty());
        assertEquals(before.token(), session.view().token());
        assertTrue(session.view().canUndo());
    }

    @Test
    void captureForSaveInsideACommandIsRefusedAndTheCommandStillPublishes() {
        DocumentSession<TextDocument> session = textSession(4);
        DocumentToken token = session.view().token();
        ReentrancyOutcome outcome = new ReentrancyOutcome();

        session.edit(token, LABEL, document -> {
            attempt(session::captureForSave, outcome);
            return new TextDocument(List.of("b"), document.caret());
        });

        assertRejected(outcome);
        DocumentView<TextDocument> view = session.view();
        assertEquals(1L, view.token().revision());
        assertEquals("b", view.content().joined());
        assertTrue(view.canUndo());

        session.confirmSaved(session.captureForSave());
        assertFalse(session.view().dirty());
    }

    @Test
    void captureForSaveInsideAModelCallbackIsRefusedAndChangesNothing() {
        ReentrancyHarness harness = new ReentrancyHarness();
        DocumentSession<TextDocument> session = new DocumentSession<>(UUID.randomUUID(),
                new TextDocument(List.of("a"), 0), harness, 4);
        DocumentToken token = session.view().token();
        ReentrancyOutcome outcome = new ReentrancyOutcome();
        harness.encodeHook = () -> attempt(session::captureForSave, outcome);
        harness.arm();

        session.edit(token, LABEL, lines("b"));

        assertRejected(outcome);
        assertTrue(harness.hookAttempts() >= 1);
        DocumentView<TextDocument> view = session.view();
        assertEquals(1L, view.token().revision());
        assertEquals("b", view.content().joined());
        assertTrue(view.dirty());
        assertTrue(view.canUndo());
        assertFalse(view.canRedo());
    }

    // ------------------------------------------------ history labels (Q02)

    @Test
    void undoLabelAndRedoLabelReportTheRecordedCommands() {
        TextContent typing = new TextContent.Literal("typing");
        TextContent deleting = new TextContent.Literal("deleting");
        TextContent renaming = new TextContent.Literal("renaming");
        DocumentSession<TextDocument> session = textSession(4);
        assertTrue(session.undoLabel().isEmpty());
        assertTrue(session.redoLabel().isEmpty());

        session.edit(session.view().token(), typing, lines("b"));
        assertEquals(Optional.of(typing), session.undoLabel());
        assertTrue(session.redoLabel().isEmpty());

        session.edit(session.view().token(), deleting, lines("c"));
        assertEquals(Optional.of(deleting), session.undoLabel());
        assertEquals(2L, session.view().token().revision());

        session.edit(session.view().token(), renaming,
                document -> new TextDocument(document.lines(), document.caret()));
        assertEquals(Optional.of(deleting), session.undoLabel(),
                "a command that encodes identically adds no label");
        assertEquals(2L, session.view().token().revision());

        session.undo(session.view().token());
        assertEquals(Optional.of(typing), session.undoLabel());
        assertEquals(Optional.of(deleting), session.redoLabel());

        session.redo(session.view().token());
        assertEquals(Optional.of(deleting), session.undoLabel());
        assertTrue(session.redoLabel().isEmpty());
    }

    @Test
    void labelsSurviveSavesFailuresAndUndoRedo() {
        TextContent typing = new TextContent.Literal("typing");
        TextContent fixing = new TextContent.Literal("fixing");
        TextContent boom = new TextContent.Literal("boom");
        TextContent bad = new TextContent.Literal("bad");
        DocumentSession<TextDocument> session = textSession(4);

        session.edit(session.view().token(), typing, lines("b"));
        session.edit(session.view().token(), fixing, lines("c"));
        assertEquals(Optional.of(fixing), session.undoLabel());

        session.markSaved(session.view().token());
        assertEquals(Optional.of(fixing), session.undoLabel());
        assertTrue(session.redoLabel().isEmpty());
        assertFalse(session.view().dirty());

        session.confirmSaved(session.captureForSave());
        assertEquals(Optional.of(fixing), session.undoLabel());

        assertThrows(IllegalStateException.class, () -> session.edit(session.view().token(), boom,
                document -> {
                    throw new IllegalStateException("command failed");
                }));
        assertEquals(Optional.of(fixing), session.undoLabel());

        assertThrows(DocumentRejection.class,
                () -> session.edit(session.view().token(), bad, lines("ERROR")));
        assertEquals(Optional.of(fixing), session.undoLabel());

        session.undo(session.view().token());
        assertEquals(Optional.of(typing), session.undoLabel());
        assertEquals(Optional.of(fixing), session.redoLabel());

        session.redo(session.view().token());
        assertEquals(Optional.of(fixing), session.undoLabel());
    }

    @Test
    void trimmingForkingAndReplacingTheBaselineDropTheLabelsTheyDiscard() {
        TextContent first = new TextContent.Literal("first");
        TextContent second = new TextContent.Literal("second");
        TextContent third = new TextContent.Literal("third");
        DocumentSession<TextDocument> session = textSession(1);

        session.edit(session.view().token(), first, lines("b"));
        session.edit(session.view().token(), second, lines("c"));
        assertEquals(Optional.of(second), session.undoLabel());

        session.undo(session.view().token());
        assertTrue(session.undoLabel().isEmpty(), "the trimmed entry took its label with it");
        assertEquals(Optional.of(second), session.redoLabel());
        assertFalse(session.view().canUndo());

        session.redo(session.view().token());
        assertEquals(Optional.of(second), session.undoLabel());

        session.undo(session.view().token());
        session.edit(session.view().token(), third, lines("d"));
        assertEquals(Optional.of(third), session.undoLabel());
        assertTrue(session.redoLabel().isEmpty(), "a new edit after an undo clears redo labels");

        session.replaceBaseline(new TextDocument(List.of("z"), 0));
        assertTrue(session.undoLabel().isEmpty());
        assertTrue(session.redoLabel().isEmpty());
    }
}
