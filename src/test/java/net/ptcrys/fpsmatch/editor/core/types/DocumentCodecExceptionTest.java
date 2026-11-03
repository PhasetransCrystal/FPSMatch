package net.ptcrys.fpsmatch.editor.core.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/** Contract of {@link DocumentCodecException}: original order, immutability, refusal to deserialize. */
class DocumentCodecExceptionTest {

    private static Problem problem(Problem.Severity severity, String code, String key) {
        return new Problem(severity, EditorId.parse(code), "sample:note",
                new TextContent.Message(key, List.of(new TextContent.LiteralArgument("detail"))));
    }

    private static List<Problem> mixed() {
        return List.of(
                problem(Problem.Severity.ERROR, "sample:documents/damaged_encoding", "sample.documents.damaged"),
                problem(Problem.Severity.WARNING, "sample:documents/partial", "sample.documents.partial"),
                problem(Problem.Severity.ERROR, "sample:documents/missing_block", "sample.documents.missing"));
    }

    @Test
    void everyProblemIsKeptInTheOriginalOrderAndCopied() {
        List<Problem> reported = new ArrayList<>(mixed());
        DocumentCodecException failure = new DocumentCodecException(reported);

        assertEquals(mixed(), failure.problems());
        assertEquals("sample:documents/damaged_encoding", failure.getMessage());
        assertEquals(3, failure.problems().size());

        // The producer's list is neither aliased nor consulted again.
        reported.clear();
        assertEquals(mixed(), failure.problems());
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void theProblemListIsImmutable() {
        DocumentCodecException failure = new DocumentCodecException(mixed());

        assertThrows(UnsupportedOperationException.class,
                () -> failure.problems().add(problem(Problem.Severity.ERROR, "sample:documents/x", "k")));
        assertThrows(UnsupportedOperationException.class, () -> failure.problems().clear());
    }

    @Test
    void nullEmptyAndNonErrorInputsAreRejected() {
        assertThrows(NullPointerException.class, () -> new DocumentCodecException(null));

        List<Problem> withNull = new ArrayList<>(mixed());
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new DocumentCodecException(withNull));

        assertThrows(IllegalArgumentException.class, () -> new DocumentCodecException(List.of()));

        List<Problem> withoutError = List.of(
                problem(Problem.Severity.WARNING, "sample:documents/partial", "sample.documents.partial"),
                problem(Problem.Severity.INFO, "sample:documents/note", "sample.documents.note"));
        assertThrows(IllegalArgumentException.class, () -> new DocumentCodecException(withoutError));
    }

    @Test
    void deserializationIsRefusedBecauseProblemsAreNotSerializable() throws Exception {
        DocumentCodecException failure = new DocumentCodecException(mixed());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(failure);
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(InvalidObjectException.class, in::readObject);
        }
    }

    @Test
    void theTechnicalMessageIsOnlyTheFirstCode() {
        DocumentCodecException failure = new DocumentCodecException(List.of(
                problem(Problem.Severity.ERROR, "thirdparty:codec/unreadable_schema", "thirdparty.unreadable")));

        assertEquals("thirdparty:codec/unreadable_schema", failure.getMessage());
        assertTrue(failure.problems().get(0).message() instanceof TextContent.Message);
    }
}
