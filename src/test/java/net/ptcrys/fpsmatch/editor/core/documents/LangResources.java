package net.ptcrys.fpsmatch.editor.core.documents;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.testutil.MinecraftTranslationFormat;

/**
 * Reads the original mod assets and projects this diagnostic namespace for the tests.
 *
 * <p>A small strict reader is enough here: the files are flat objects of string to string, and
 * strictness - duplicate keys, stray braces, unexpected values, trailing content - is exactly what
 * the tests need. The complete assets table is parsed before the frozen diagnostic namespace is selected.</p>
 */
final class LangResources {

    private LangResources() { }

    static Map<String, String> load(String language) {
        String resource = "/assets/fpsmatch/lang/" + language + ".json";
        try (InputStream stream = LangResources.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "language resource is missing from the module: " + resource);
            Map<String, String> entries = parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), resource);
            entries = new LinkedHashMap<>(entries);
            entries.keySet().removeIf(key -> !key.startsWith("editor.document."));
            return Collections.unmodifiableMap(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the positional indices {@code template} uses, refusing anything else. */
    static Set<Integer> placeholders(String template) {
        return MinecraftTranslationFormat.placeholders(template);
    }

    /** Substitutes every placeholder with the matching argument, so a blank rendering is visible. */
    static String render(TextContent.Message message, String template) {
        return MinecraftTranslationFormat.render(message, template);
    }

    /** The text a typed argument contributes to a rendered sentence. */
    static String argumentText(TextContent.Argument argument) {
        return MinecraftTranslationFormat.argumentText(argument);
    }

    private static Map<String, String> parse(String text, String resource) {
        Map<String, String> entries = new LinkedHashMap<>();
        int[] cursor = {0};
        skipWhitespace(text, cursor);
        expect(text, cursor, '{');
        skipWhitespace(text, cursor);
        if (peek(text, cursor) == '}') {
            cursor[0]++;
        } else {
            while (true) {
                skipWhitespace(text, cursor);
                String key = readString(text, cursor);
                skipWhitespace(text, cursor);
                expect(text, cursor, ':');
                skipWhitespace(text, cursor);
                String value = readString(text, cursor);
                if (entries.put(key, value) != null) {
                    throw new IllegalStateException("duplicate key " + key + " in " + resource);
                }
                skipWhitespace(text, cursor);
                char next = peek(text, cursor);
                cursor[0]++;
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw new IllegalStateException("unexpected '" + next + "' in " + resource);
                }
            }
        }
        skipWhitespace(text, cursor);
        if (cursor[0] != text.length()) {
            throw new IllegalStateException("trailing content in " + resource);
        }
        return Collections.unmodifiableMap(entries);
    }

    private static String readString(String text, int[] cursor) {
        expect(text, cursor, '"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (cursor[0] >= text.length()) {
                throw new IllegalStateException("unterminated string in " + text);
            }
            char current = text.charAt(cursor[0]++);
            if (current == '"') {
                return value.toString();
            }
            if (current != '\\') {
                value.append(current);
                continue;
            }
            char escape = text.charAt(cursor[0]++);
            switch (escape) {
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    value.append((char) Integer.parseInt(
                            text.substring(cursor[0], cursor[0] + 4), 16));
                    cursor[0] += 4;
                }
                default -> throw new IllegalStateException("invalid escape \\" + escape);
            }
        }
    }

    private static void skipWhitespace(String text, int[] cursor) {
        while (cursor[0] < text.length() && Character.isWhitespace(text.charAt(cursor[0]))) {
            cursor[0]++;
        }
    }

    private static char peek(String text, int[] cursor) {
        if (cursor[0] >= text.length()) {
            throw new IllegalStateException("unexpected end of input");
        }
        return text.charAt(cursor[0]);
    }

    private static void expect(String text, int[] cursor, char expected) {
        if (peek(text, cursor) != expected) {
            throw new IllegalStateException("expected '" + expected + "' at " + cursor[0]);
        }
        cursor[0]++;
    }
}
