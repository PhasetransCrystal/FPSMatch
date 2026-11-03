package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * Contract of {@link SessionRejection}.
 *
 * <p>A rejection carries the complete diagnostics of one refused operation: the producer's list is
 * copied in its original order, every element keeps its severity, code, target and unresolved
 * message, at least one ERROR is required, a technical cause is handed through unchanged, and
 * deserialization is refused because a {@link Problem} cannot survive it.</p>
 */
class SessionRejectionTest {

    private static final EditorId ACTIVATION_FAILED =
            EditorId.parse("editor:session/type_activation_failed");
    private static final EditorId UNSUPPORTED_CODEC =
            EditorId.parse("editor:session/unsupported_codec");
    private static final EditorId UNSUPPORTED_SCHEMA =
            EditorId.parse("editor:session/unsupported_schema");
    private static final String REJECTED = "q11a:rejected_type";

    @Test
    void copiesEveryProblemInProducerOrderAndRepeatsTheFirstCode() {
        List<Problem> produced = mixed();
        List<Problem> kept = List.copyOf(produced);
        SessionRejection rejection = new SessionRejection(produced);

        assertEquals(3, rejection.problems().size(), "no produced problem may be dropped");
        assertEquals(mixed(), rejection.problems(), "order, severity, code, target and message survive");
        assertSame(kept.get(0), rejection.problems().get(0), "the elements themselves are kept");
        assertSame(kept.get(1), rejection.problems().get(1));
        assertSame(kept.get(2), rejection.problems().get(2));

        assertEquals(ACTIVATION_FAILED.toString(), rejection.getMessage(),
                "getMessage() repeats the first code as a technical locator");
        assertInstanceOf(IllegalStateException.class, rejection,
                "a frozen registry is a state failure, not an argument error");

        produced.clear();
        assertEquals(mixed(), rejection.problems(), "the rejection copied the producer's list");
        assertSame(kept.get(0), rejection.problems().get(0),
                "the copied elements stay the producer's own objects");
        assertThrows(UnsupportedOperationException.class,
                () -> rejection.problems().add(mixed().get(0)), "the reported list is immutable");
        assertThrows(UnsupportedOperationException.class, () -> rejection.problems().clear());
    }

    @Test
    void anEmptyOrErrorFreeDiagnosisIsARefusedProducerInput() {
        assertThrows(NullPointerException.class, () -> new SessionRejection(null));
        assertThrows(NullPointerException.class,
                () -> new SessionRejection(Arrays.asList(mixed().get(0), null)),
                "a null element is a producer programming error");
        assertThrows(IllegalArgumentException.class, () -> new SessionRejection(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRejection(List.of(warning(UNSUPPORTED_CODEC))),
                "a warning alone cannot reject an operation");
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRejection(List.of(info(UNSUPPORTED_CODEC))),
                "an informational message alone cannot reject an operation");

        IllegalStateException cause = new IllegalStateException("codec table is not frozen");
        assertThrows(NullPointerException.class, () -> new SessionRejection(null, cause));
        assertThrows(NullPointerException.class,
                () -> new SessionRejection(Arrays.asList(mixed().get(0), null), cause));
        assertThrows(IllegalArgumentException.class, () -> new SessionRejection(List.of(), cause));
        assertThrows(IllegalArgumentException.class,
                () -> new SessionRejection(List.of(warning(UNSUPPORTED_CODEC)), cause));
    }

    @Test
    void keepsTheTechnicalCauseUnchangedAndStaysCauselessWhenNoneWasGiven() {
        IllegalStateException cause = new IllegalStateException("codec table is not frozen");
        SessionRejection rejection = new SessionRejection(mixed(), cause);

        assertSame(cause, rejection.getCause(), "the failed third-party call stays reachable as-is");
        assertEquals(ACTIVATION_FAILED.toString(), rejection.getMessage(),
                "the cause text never replaces the code locator");
        assertEquals(mixed(), rejection.problems(), "a cause adds no diagnostic of its own");

        assertNull(new SessionRejection(mixed()).getCause(),
                "a rejection this module detected itself carries no cause");
        assertNull(new SessionRejection(mixed(), null).getCause());
    }

    @Test
    void refusesDeserializationInsteadOfLosingItsDiagnostics() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(buffer)) {
            output.writeObject(new SessionRejection(mixed(), new IllegalStateException("not frozen")));
        }

        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(buffer.toByteArray()));
        InvalidObjectException refusal = assertThrows(InvalidObjectException.class, input::readObject);
        assertEquals("a serialized session rejection carries no diagnostics", refusal.getMessage());
    }

    /** A problem of the kind a producer of this module would report. */
    private static Problem problem(Problem.Severity severity, EditorId code, String key) {
        return new Problem(severity, code, REJECTED,
                new TextContent.Message(key, List.of(new TextContent.LiteralArgument(REJECTED))));
    }

    private static Problem warning(EditorId code) {
        return problem(Problem.Severity.WARNING, code, "q11a.rejection.warning");
    }

    private static Problem info(EditorId code) {
        return problem(Problem.Severity.INFO, code, "q11a.rejection.info");
    }

    /**
     * Three problems in the order a producing pass reported them: an ERROR first, a WARNING in the
     * middle and another ERROR last, so a shortened or reordered copy is visible.
     */
    private static List<Problem> mixed() {
        List<Problem> problems = new ArrayList<>();
        problems.add(problem(Problem.Severity.ERROR, ACTIVATION_FAILED, "q11a.rejection.activation"));
        problems.add(problem(Problem.Severity.WARNING, UNSUPPORTED_CODEC, "q11a.rejection.codec"));
        problems.add(problem(Problem.Severity.ERROR, UNSUPPORTED_SCHEMA, "q11a.rejection.schema"));
        return problems;
    }
}
