package net.ptcrys.fpsmatch.editor.core.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * Diagnostics contract of {@link DocumentRejection}: every state rejection carries a stable code, a
 * translation key, the current document as target and typed arguments, and a rejection caused by
 * domain validation keeps the whole problem list of that validation run.
 *
 * <p>The tests never assert a product sentence: they read both language resources of the module,
 * check that every used key exists in both with the arity the code produces, and render the observed
 * arguments with those templates. {@link DocumentRejection#getMessage()} is only asserted to be a
 * technical locator.</p>
 */
class DocumentRejectionTest {

    private static final TextContent LABEL = new TextContent.Literal("typing");

    // ------------------------------------------------------------- test model

    /** Reports whatever diagnostics the test installed, and can fail the way a buggy model would. */
    private static final class DiagnosticsModel implements DocumentModel<TextDocument> {
        private List<Problem> problems = List.of();
        private RuntimeException failure;

        void problems(List<Problem> problems) {
            this.problems = problems;
        }

        void failure(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public TextDocument snapshot(TextDocument value) {
            return new TextDocument(value.lines(), value.caret());
        }

        @Override
        public byte[] encode(TextDocument value) {
            return value.joined().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public List<Problem> validate(TextDocument value) {
            if (failure != null) {
                throw failure;
            }
            return problems;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static DocumentSession<TextDocument> newSession(UUID documentId, DiagnosticsModel model,
            int history) {
        return new DocumentSession<>(documentId, new TextDocument(List.of("a"), 0), model, history);
    }

    private static DocumentRejection rejection(Runnable action) {
        return assertThrows(DocumentRejection.class, action::run);
    }

    /** A state rejection reports exactly one problem; returns it. */
    private static Problem only(DocumentRejection rejection) {
        assertEquals(1, rejection.problems().size(),
                "a state rejection reports exactly one problem");
        return rejection.problems().get(0);
    }

    private static List<TextContent.Argument> arguments(Problem problem) {
        return ((TextContent.Message) problem.message()).arguments();
    }

    private static TextContent.Argument id(UUID value) {
        return new TextContent.LiteralArgument(value.toString());
    }

    private static TextContent.Argument text(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    /** Asserts the whole diagnostic of one rejection and records it under its code path. */
    private static void record(Map<String, Problem> observed, String name, Problem problem,
            UUID documentId, List<TextContent.Argument> arguments) {
        assertEquals(EditorId.parse("editor:document/" + name), problem.code(), name);
        assertEquals(Problem.Severity.ERROR, problem.severity(), name);
        assertEquals(documentId.toString(), problem.target(), name);
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message(), name);
        assertEquals("editor.document." + name, message.key(), name);
        assertEquals(arguments, message.arguments(), name + " argument order is part of the contract");
        assertNull(observed.put(name, problem), "duplicate scenario " + name);
    }

    /** Attempts a state change from inside an edit command and returns the refusal it produced. */
    private static DocumentRejection refusalInsideCommand(DocumentSession<TextDocument> session,
            Runnable attempt) {
        AtomicReference<DocumentRejection> refused = new AtomicReference<>();
        session.edit(session.view().token(), LABEL, document -> {
            try {
                attempt.run();
            } catch (DocumentRejection rejection) {
                refused.set(rejection);
            }
            return document;
        });
        assertNotNull(refused.get(), "the state change inside the command must be refused");
        return refused.get();
    }

    /**
     * Drives every rejection the session can produce from its own state and returns the single
     * problem of each, keyed by the path of its stable code.
     */
    private static Map<String, Problem> observedRejections() {
        Map<String, Problem> observed = new LinkedHashMap<>();
        UUID documentId = UUID.randomUUID();
        DiagnosticsModel model = new DiagnosticsModel();

        DocumentSession<TextDocument> pristine = newSession(documentId, model, 4);
        record(observed, "nothing_to_undo",
                only(rejection(() -> pristine.undo(pristine.view().token()))), documentId, List.of());
        record(observed, "nothing_to_redo",
                only(rejection(() -> pristine.redo(pristine.view().token()))), documentId, List.of());

        DocumentSession<TextDocument> branched = newSession(documentId, model, 4);
        DocumentToken branch = branched.view().token();
        UUID foreignBranch = UUID.randomUUID();
        record(observed, "wrong_branch",
                only(rejection(() -> branched.markSaved(new DocumentToken(documentId, foreignBranch, 0L)))),
                documentId, List.of(id(documentId), id(foreignBranch), number(0), id(branch.branchId()),
                        number(branch.revision())));

        DocumentSession<TextDocument> ahead = newSession(documentId, model, 4);
        DocumentToken aheadToken = ahead.view().token();
        record(observed, "future_revision",
                only(rejection(() -> ahead.markSaved(
                        new DocumentToken(documentId, aheadToken.branchId(), 5L)))),
                documentId, List.of(number(5), number(aheadToken.revision())));

        DocumentSession<TextDocument> confirmed = newSession(documentId, model, 4);
        DocumentToken beforeConfirmation = confirmed.view().token();
        confirmed.edit(beforeConfirmation, LABEL, document -> new TextDocument(List.of("b"), 0));
        confirmed.confirmSaved(confirmed.captureForSave());
        record(observed, "older_confirmation",
                only(rejection(() -> confirmed.markSaved(beforeConfirmation))), documentId,
                List.of(number(0), number(1)));

        DocumentSession<TextDocument> trimmed = newSession(documentId, model, 1);
        DocumentToken oldest = trimmed.view().token();
        trimmed.edit(oldest, LABEL, document -> new TextDocument(List.of("b"), 0));
        trimmed.edit(trimmed.view().token(), LABEL, document -> new TextDocument(List.of("c"), 0));
        record(observed, "capture_not_retained",
                only(rejection(() -> trimmed.markSaved(oldest))), documentId,
                List.of(number(0), number(2), number(1)));

        DocumentSession<TextDocument> owner = newSession(documentId, model, 4);
        DocumentSession<TextDocument> twin = newSession(documentId, model, 4);
        DocumentCapture<TextDocument> foreign = twin.captureForSave();
        record(observed, "foreign_capture",
                only(rejection(() -> owner.confirmSaved(foreign))), documentId,
                List.of(id(documentId), id(twin.view().token().branchId()), number(0)));

        DocumentSession<TextDocument> guarded = newSession(documentId, model, 4);
        record(observed, "reentrant_mutation",
                only(refusalInsideCommand(guarded, () -> guarded.undo(guarded.view().token()))),
                documentId, List.of());
        record(observed, "capture_during_mutation",
                only(refusalInsideCommand(guarded, guarded::captureForSave)), documentId, List.of());
        assertEquals(0L, guarded.view().token().revision(), "refused attempts publish nothing");

        DocumentSession<TextDocument> stale = newSession(documentId, model, 4);
        DocumentToken staleToken = stale.view().token();
        record(observed, "stale_token",
                only(rejection(() -> stale.edit(
                        new DocumentToken(documentId, staleToken.branchId(), 7L), LABEL, document -> document))),
                documentId, List.of(text("edit"), id(documentId), id(staleToken.branchId()), number(7),
                        id(staleToken.branchId()), number(staleToken.revision())));

        DocumentSession<TextDocument> limited = newSession(documentId, model, 4);
        record(observed, "pending_address_limit",
                only(rejection(() -> limited.setPendingInput("a".repeat(1025), "x"))), documentId,
                List.of(number(1025), number(1024)));
        record(observed, "pending_text_limit",
                only(rejection(() -> limited.setPendingInput("field", "x".repeat(65537)))), documentId,
                List.of(number(65537), number(65536)));
        for (int index = 0; index < 1024; index++) {
            limited.setPendingInput("field-" + index, "x");
        }
        record(observed, "pending_count_limit",
                only(rejection(() -> limited.setPendingInput("overflow", "x"))), documentId,
                List.of(number(1024), number(1024)));

        return observed;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void everyStateRejectionUsesItsOwnStableCodeKeyAndTypedArguments() {
        Set<String> expected = Set.of("nothing_to_undo", "nothing_to_redo", "wrong_branch",
                "future_revision", "older_confirmation", "capture_not_retained", "foreign_capture",
                "reentrant_mutation", "capture_during_mutation", "stale_token", "pending_address_limit",
                "pending_text_limit", "pending_count_limit");

        Map<String, Problem> observed = observedRejections();

        assertEquals(expected, observed.keySet(), "one rejection per documented state, no more");
        assertNotEquals(observed.get("nothing_to_undo").code(),
                observed.get("nothing_to_redo").code(), "distinct causes must not share a code");
        for (Problem problem : observed.values()) {
            assertTrue(problem.target().length() == 36, "the target is the current document id");
        }
    }

    @Test
    void wrongBranchAndStaleTokenNameTheForeignTokenAndTheOperation() {
        UUID documentId = UUID.randomUUID();
        DiagnosticsModel model = new DiagnosticsModel();
        DocumentSession<TextDocument> session = newSession(documentId, model, 4);
        DocumentToken current = session.view().token();

        UUID foreignDocument = UUID.randomUUID();
        Problem foreignDocumentProblem = only(rejection(
                () -> session.markSaved(new DocumentToken(foreignDocument, current.branchId(), 0L))));
        assertEquals(List.of(id(foreignDocument), id(current.branchId()), number(0), id(current.branchId()),
                number(current.revision())), arguments(foreignDocumentProblem));

        UUID foreignBranch = UUID.randomUUID();
        Problem fromUndo = only(rejection(
                () -> session.undo(new DocumentToken(documentId, foreignBranch, 0L))));
        assertEquals(List.of(text("undo"), id(documentId), id(foreignBranch), number(0),
                id(current.branchId()), number(current.revision())), arguments(fromUndo));

        Problem fromRedo = only(rejection(
                () -> session.redo(new DocumentToken(documentId, foreignBranch, 0L))));
        assertEquals(List.of(text("redo"), id(documentId), id(foreignBranch), number(0),
                id(current.branchId()), number(current.revision())), arguments(fromRedo));
    }

    @Test
    void domainValidationProblemsAreKeptCompletelyInOrderAndCopied() {
        UUID documentId = UUID.randomUUID();
        DiagnosticsModel model = new DiagnosticsModel();
        DocumentSession<TextDocument> session = newSession(documentId, model, 4);

        Problem first = new Problem(Problem.Severity.ERROR, EditorId.parse("documents:test/first"), "text",
                new TextContent.Message("documents.test.first",
                        List.of(new TextContent.NumberArgument(BigDecimal.valueOf(3)))));
        Problem middle = new Problem(Problem.Severity.WARNING, EditorId.parse("documents:test/middle"), "caret",
                new TextContent.Literal("mind the caret"));
        Problem last = new Problem(Problem.Severity.ERROR, EditorId.parse("documents:test/last"), "line/2",
                new TextContent.Message("documents.test.last",
                        List.of(new TextContent.LiteralArgument("marker"),
                                new TextContent.NestedArgument(new TextContent.Literal("nested")))));
        List<Problem> produced = new ArrayList<>(List.of(first, middle, last));
        model.problems(produced);

        DocumentRejection rejection = rejection(() -> session.edit(session.view().token(), LABEL,
                document -> new TextDocument(List.of("b"), 0)));

        assertEquals(List.of(first, middle, last), rejection.problems(),
                "the complete list is kept in the order the model reported it");
        assertEquals(Problem.Severity.WARNING, rejection.problems().get(1).severity(),
                "the warning between the two errors stays readable");
        assertEquals("documents:test/first", rejection.getMessage(),
                "getMessage is only a technical locator, never product text");

        List<Problem> kept = rejection.problems();
        produced.clear();
        produced.add(middle);
        assertEquals(3, kept.size(), "the rejection copied the producer's list");
        assertSame(kept, rejection.problems(), "the same immutable list is returned every time");
        assertThrows(UnsupportedOperationException.class, () -> kept.add(middle));
        assertThrows(UnsupportedOperationException.class, () -> kept.remove(0));
        assertEquals(0L, session.view().token().revision(), "a rejected edit publishes nothing");
        assertFalse(session.view().dirty());
    }

    @Test
    void initialValueRejectionKeepsTheWholeDiagnosticList() {
        DiagnosticsModel model = new DiagnosticsModel();
        Problem error = new Problem(Problem.Severity.ERROR, EditorId.parse("documents:test/rule"), "text",
                new TextContent.Literal("forbidden marker"));
        Problem warning = new Problem(Problem.Severity.WARNING, EditorId.parse("documents:test/hint"), "text",
                new TextContent.Literal("hint"));
        List<Problem> produced = new ArrayList<>(List.of(warning, error));
        model.problems(produced);

        DocumentRejection rejection = rejection(() -> newSession(UUID.randomUUID(), model, 4));

        assertEquals(List.of(warning, error), rejection.problems());
        produced.clear();
        assertEquals(2, rejection.problems().size(), "the constructor also copies its diagnostics");
    }

    @Test
    void aRejectionNeedsAtLeastOneErrorAndNeverInventsDiagnostics() {
        Problem warning = new Problem(Problem.Severity.WARNING, EditorId.parse("documents:test/rule"), "text",
                new TextContent.Literal("careful"));
        Problem info = new Problem(Problem.Severity.INFO, EditorId.parse("documents:test/hint"), "text",
                new TextContent.Literal("note"));
        Problem error = new Problem(Problem.Severity.ERROR, EditorId.parse("documents:test/rule"), "text",
                new TextContent.Literal("forbidden marker"));

        assertThrows(NullPointerException.class, () -> new DocumentRejection(null));
        assertThrows(IllegalArgumentException.class, () -> new DocumentRejection(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new DocumentRejection(List.of(warning)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentRejection(List.of(info, warning)));
        assertThrows(NullPointerException.class, () -> new DocumentRejection(Arrays.asList(error, null)));

        DocumentRejection mixed = new DocumentRejection(List.of(warning, error));
        assertEquals(List.of(warning, error), mixed.problems());
        assertEquals("documents:test/rule", mixed.getMessage());
    }

    @Test
    void nullInputsAndModelFailuresPropagateUnchangedInsteadOfBecomingDiagnostics() {
        UUID documentId = UUID.randomUUID();
        DiagnosticsModel model = new DiagnosticsModel();
        DocumentSession<TextDocument> session = newSession(documentId, model, 4);

        assertThrows(NullPointerException.class,
                () -> session.edit(session.view().token(), LABEL, document -> null));
        assertEquals(0L, session.view().token().revision(), "a null candidate publishes nothing");
        assertFalse(session.view().dirty());

        IllegalStateException exploded = new IllegalStateException("validate exploded");
        model.failure(exploded);
        assertSame(exploded, assertThrows(IllegalStateException.class, () -> session.edit(
                session.view().token(), LABEL, document -> new TextDocument(List.of("b"), 0))),
                "a model failure is not rewritten into a user diagnostic");
        assertEquals(0L, session.view().token().revision(), "a failed model call publishes nothing");
        assertFalse(session.view().dirty());

        model.failure(null);
        model.problems(Arrays.asList(
                new Problem(Problem.Severity.ERROR, EditorId.parse("documents:test/rule"), "text",
                        new TextContent.Literal("forbidden marker")),
                null));
        assertThrows(NullPointerException.class, () -> session.edit(session.view().token(), LABEL,
                document -> new TextDocument(List.of("b"), 0)),
                "a null problem is a programming error, not a rejection");
        assertEquals(0L, session.view().token().revision());
    }

    @Test
    void aRefusedReentrantAttemptStillReleasesTheGuard() {
        UUID documentId = UUID.randomUUID();
        DiagnosticsModel model = new DiagnosticsModel();
        DocumentSession<TextDocument> session = newSession(documentId, model, 4);

        AtomicReference<DocumentRejection> refused = new AtomicReference<>();
        session.edit(session.view().token(), LABEL, document -> {
            try {
                session.undo(session.view().token());
            } catch (DocumentRejection rejection) {
                refused.set(rejection);
            }
            return new TextDocument(List.of("b"), 0);
        });

        assertNotNull(refused.get());
        assertEquals("editor:document/reentrant_mutation", refused.get().problems().get(0).code().toString());
        assertEquals(1L, session.view().token().revision(),
                "the command that survived the refusal still published");
        assertEquals("b", session.view().content().joined());

        session.edit(session.view().token(), LABEL, document -> new TextDocument(List.of("c"), 0));
        assertEquals(2L, session.view().token().revision(), "the guard was released");
        assertEquals("c", session.view().content().joined());
    }

    @Test
    void bothLanguagesCoverEveryCodeWithMatchingPlaceholders() {
        Map<String, Problem> observed = observedRejections();
        Map<String, String> english = LangResources.load("en_us");
        Map<String, String> chinese = LangResources.load("zh_cn");

        Set<String> used = new TreeSet<>();
        for (String name : observed.keySet()) {
            used.add("editor.document." + name);
        }
        assertEquals(used, new TreeSet<>(english.keySet()),
                "English resources hold exactly the diagnostics the code produces");
        assertEquals(new TreeSet<>(english.keySet()), new TreeSet<>(chinese.keySet()),
                "both languages must cover the same keys");

        for (Map.Entry<String, Problem> entry : observed.entrySet()) {
            String key = "editor.document." + entry.getKey();
            int arity = arguments(entry.getValue()).size();
            Set<Integer> expected = new TreeSet<>();
            for (int index = 0; index < arity; index++) {
                expected.add(index);
            }
            assertFalse(english.get(key).isBlank(), key);
            assertFalse(chinese.get(key).isBlank(), key);
            assertEquals(expected, LangResources.placeholders(english.get(key)), key);
            assertEquals(expected, LangResources.placeholders(chinese.get(key)), key);
        }

        String englishCount = english.get("editor.document.pending_count_limit");
        String chineseCount = chinese.get("editor.document.pending_count_limit");
        assertTrue(englishCount.indexOf("%1$s") < englishCount.indexOf("%2$s"), englishCount);
        assertTrue(chineseCount.indexOf("%2$s") < chineseCount.indexOf("%1$s"),
                "a language may order the same arguments differently: " + chineseCount);
    }

    @Test
    void everyObservedDiagnosticRendersWithItsArgumentsInBothLanguages() {
        Map<String, String> english = LangResources.load("en_us");
        Map<String, String> chinese = LangResources.load("zh_cn");

        for (Map.Entry<String, Problem> entry : observedRejections().entrySet()) {
            String key = "editor.document." + entry.getKey();
            TextContent.Message message = (TextContent.Message) entry.getValue().message();
            for (String template : List.of(english.get(key), chinese.get(key))) {
                String rendered = LangResources.render(message, template);
                assertFalse(rendered.isBlank(), key);
                for (TextContent.Argument argument : message.arguments()) {
                    assertTrue(rendered.contains(LangResources.argumentText(argument)),
                            "rendered " + key + " lost the argument " + argument + ": " + rendered);
                }
            }
        }
    }

    @Test
    void codeAndKeyConstantsStayPairedAndComplete() throws Exception {
        Map<String, String> codes = new TreeMap<>();
        Map<String, String> keys = new TreeMap<>();
        for (Field field : Messages.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (field.getType() == EditorId.class) {
                codes.put(field.getName(), field.get(null).toString());
            } else if (field.getType() == String.class && field.getName().startsWith("KEY_")) {
                keys.put(field.getName(), (String) field.get(null));
            }
        }

        assertEquals(13, codes.size(), "one stable code per state rejection");
        assertEquals(13, keys.size(), "one translation key per state rejection");
        Set<String> paths = new TreeSet<>();
        for (Map.Entry<String, String> entry : codes.entrySet()) {
            assertTrue(entry.getValue().startsWith("editor:document/"), entry.getValue());
            String path = entry.getValue().substring("editor:document/".length());
            paths.add(path);
            assertEquals("editor.document." + path, keys.get("KEY_" + entry.getKey()),
                    "code and key of " + entry.getKey() + " must match");
        }
        assertEquals(paths, observedRejections().keySet(),
                "declared codes and the rejections the session really produces must agree");
    }
}
