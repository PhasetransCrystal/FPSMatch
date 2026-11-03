package net.ptcrys.fpsmatch.core;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeMapLeaseExceptionTest {
    @Test
    void everyReasonCarriesAnUnresolvedErrorAndTechnicalMessageOnly() {
        for (RuntimeMapLeaseException.Reason reason : RuntimeMapLeaseException.Reason.values()) {
            String key = reason.name().toLowerCase(Locale.ROOT);
            RuntimeMapLeaseException failure = RuntimeMapLeaseException.error(reason);
            Problem expected = new Problem(Problem.Severity.ERROR,
                    new EditorId("editor", "runtime_map/" + key), "runtime_map",
                    new TextContent.Message("editor.runtime_map." + key, List.of()));
            assertEquals(expected, failure.problem());
            assertEquals("editor:runtime_map/" + key, failure.getMessage());
        }
    }

    @Test
    void deserializationCannotProduceAnExceptionWithoutItsProblem() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.CLOSED));
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(InvalidObjectException.class, input::readObject);
        }
    }
}
