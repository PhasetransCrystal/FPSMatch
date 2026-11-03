package net.ptcrys.fpsmatch.editor.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

/** Verifies the frozen save diagnostic factories against both real language resources. */
final class SaveMessagesTest {
    private static final Map<String, Set<Integer>> PLACEHOLDERS = Map.of(
            "editor.runtime_save.protected_project", Set.of(0),
            "editor.runtime_save.pending_input", Set.of(0),
            "editor.runtime_save.unsaved_dependency", Set.of(0, 1),
            "editor.runtime_save.stale_capture", Set.of(0),
            "editor.runtime_save.capture_bytes_exceeded", Set.of(0, 1),
            "editor.runtime_save.retained_bytes_exceeded", Set.of(0, 1),
            "editor.runtime_save.io_busy", Set.of(),
            "editor.runtime_save.queue_unavailable", Set.of(0));

    @Test
    void factoriesKeepFrozenCodesKeysTargetsAndTypedArguments() {
        UUID project = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();

        assertLiteralDiagnostic(SaveMessages.protectedProject(project), "protected_project",
                project.toString(), project.toString());
        assertLiteralDiagnostic(SaveMessages.pendingInput(document), "pending_input",
                document.toString(), document.toString());
        assertLiteralDiagnostic(SaveMessages.unsavedDependency(document, dependency),
                "unsaved_dependency", document.toString(), document.toString(),
                dependency.toString());
        assertLiteralDiagnostic(SaveMessages.staleCapture(document), "stale_capture",
                document.toString(), document.toString());
        assertNumberDiagnostic(SaveMessages.captureBytes(67_108_865L, 67_108_864L),
                "capture_bytes_exceeded", 67_108_865L, 67_108_864L);
        assertNumberDiagnostic(SaveMessages.retainedBytes(134_217_729L, 134_217_728L),
                "retained_bytes_exceeded", 134_217_729L, 134_217_728L);
        assertLiteralDiagnostic(SaveMessages.ioBusy(), "io_busy", SaveMessages.TARGET);
        assertLiteralDiagnostic(SaveMessages.queueUnavailable("closed"), "queue_unavailable",
                SaveMessages.TARGET, "closed");
    }

    @Test
    void languageFilesDeclareEveryFrozenKeyWithCompletePlaceholders() {
        for (String language : List.of("en_us", "zh_cn")) {
            Map<String, String> entries = loadSaveLang(language);
            assertEquals(PLACEHOLDERS.keySet(), entries.keySet(), language);
            for (Map.Entry<String, Set<Integer>> expected : PLACEHOLDERS.entrySet()) {
                String template = entries.get(expected.getKey());
                assertFalse(template.isBlank(), "blank " + language + " sentence for "
                        + expected.getKey());
                assertEquals(expected.getValue(), LangResources.placeholders(template),
                        language + " placeholders for " + expected.getKey());
            }
        }
    }

    @Test
    void nullArgumentsRemainProgrammingErrors() {
        UUID id = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> SaveMessages.protectedProject(null));
        assertThrows(NullPointerException.class, () -> SaveMessages.pendingInput(null));
        assertThrows(NullPointerException.class, () -> SaveMessages.unsavedDependency(null, id));
        assertThrows(NullPointerException.class, () -> SaveMessages.unsavedDependency(id, null));
        assertThrows(NullPointerException.class, () -> SaveMessages.staleCapture(null));
        assertThrows(NullPointerException.class, () -> SaveMessages.queueUnavailable(null));
    }

    private static void assertLiteralDiagnostic(SessionRejection rejection, String suffix,
            String target, String... arguments) {
        TextContent.Message message = assertDiagnostic(rejection, suffix, target);
        assertEquals(arguments.length, message.arguments().size(), message.key());
        for (int index = 0; index < arguments.length; index++) {
            TextContent.LiteralArgument argument =
                    assertInstanceOf(TextContent.LiteralArgument.class,
                            message.arguments().get(index), "argument " + index + " of "
                                    + message.key());
            assertEquals(arguments[index], argument.value(), "argument " + index);
        }
    }

    private static void assertNumberDiagnostic(SessionRejection rejection, String suffix,
            long... arguments) {
        TextContent.Message message = assertDiagnostic(rejection, suffix, SaveMessages.TARGET);
        assertEquals(arguments.length, message.arguments().size(), message.key());
        for (int index = 0; index < arguments.length; index++) {
            TextContent.NumberArgument argument =
                    assertInstanceOf(TextContent.NumberArgument.class,
                            message.arguments().get(index), "argument " + index + " of "
                                    + message.key());
            assertEquals(BigDecimal.valueOf(arguments[index]), argument.value(),
                    "argument " + index);
        }
    }

    private static TextContent.Message assertDiagnostic(SessionRejection rejection, String suffix,
            String target) {
        assertNull(rejection.getCause());
        assertEquals(1, rejection.problems().size());
        Problem problem = rejection.problems().get(0);
        EditorId code = new EditorId("editor", "runtime_save/" + suffix);
        assertEquals(code.toString(), rejection.getMessage());
        assertEquals(Problem.Severity.ERROR, problem.severity());
        assertEquals(code, problem.code());
        assertEquals(target, problem.target());
        TextContent.Message message = assertInstanceOf(TextContent.Message.class, problem.message());
        assertEquals("editor.runtime_save." + suffix, message.key());
        return message;
    }

    private static Map<String, String> loadSaveLang(String language) {
        String resource = "/assets/fpsmatch/lang/" + language + ".json";
        try (InputStream stream = SaveMessagesTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "language resource is missing: " + resource);
            Map<String, String> entries = new TreeMap<>(LangResources.parse(
                    new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource));
            entries.keySet().removeIf(key -> !key.startsWith("editor.runtime_save."));
            return Map.copyOf(entries);
        } catch (IOException failure) {
            throw new UncheckedIOException("cannot read " + resource, failure);
        }
    }
}
