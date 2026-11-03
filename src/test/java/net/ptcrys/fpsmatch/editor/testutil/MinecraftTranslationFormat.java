package net.ptcrys.fpsmatch.editor.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.ptcrys.fpsmatch.editor.api.TextContent;

/**
 * Test-only rendering of the shipped diagnostic templates using Minecraft 1.20.1's %s, %n$s
 * and %% syntax. Explicit indices are one-based and do not advance the implicit argument cursor.
 *
 * <p>The native renderer accepts unused arguments and can fall back to an unformatted template.
 * These contract checks deliberately reject both: every typed argument must be referenced and an
 * invalid template must fail the test. The diagnostic template contract also rejects legacy braces,
 * including mixed %s/{n} templates, instead of invoking Forge's separate MessageFormat fallback.</p>
 */
public final class MinecraftTranslationFormat {
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    private MinecraftTranslationFormat() { }

    /** Zero-based argument identities, independent of translated word order or repeated references. */
    public static Set<Integer> placeholders(String template) {
        return parse(template).indices();
    }

    /** Renders a template without a language catalog; nested literal text remains supported. */
    public static String render(TextContent.Message message, String template) {
        return render(message, template, Map.of());
    }

    /** Resolves nested translations through the same language as the containing message. */
    public static String rendered(Map<String, String> language, TextContent text) {
        if (text instanceof TextContent.Literal literal) return literal.text();
        TextContent.Message message = (TextContent.Message) text;
        String template = language.get(message.key());
        assertNotNull(template, "template for " + message.key());
        return render(message, template, language);
    }

    /** The native component bridge passes numbers as BigDecimal and renders them with toString(). */
    public static String argumentText(TextContent.Argument argument) {
        return argumentText(argument, Map.of());
    }

    private static String render(TextContent.Message message, String template, Map<String, String> language) {
        Parsed parsed = parse(template);
        Set<Integer> expected = new TreeSet<>();
        for (int index = 0; index < message.arguments().size(); index++) expected.add(index);
        assertEquals(expected, parsed.indices(),
                "complete argument indices of " + message.key() + " in " + template);
        StringBuilder result = new StringBuilder();
        for (Part part : parsed.parts()) {
            if (part.text() != null) result.append(part.text());
            else result.append(argumentText(message.arguments().get(part.index()), language));
        }
        // Never parse substituted arguments again: a literal may itself contain %, braces or $.
        return result.toString();
    }

    private static String argumentText(TextContent.Argument argument, Map<String, String> language) {
        if (argument instanceof TextContent.LiteralArgument literal) return literal.value();
        if (argument instanceof TextContent.NumberArgument number) return number.value().toString();
        if (argument instanceof TextContent.NestedArgument nested) return rendered(language, nested.value());
        throw new IllegalArgumentException("unknown argument: " + argument);
    }

    private static Parsed parse(String template) {
        assertNotNull(template, "translation template");
        assertFalse(template.contains("{") || template.contains("}"),
                "legacy or mixed placeholder syntax: " + template);
        Matcher matcher = FORMAT.matcher(template);
        List<Part> parts = new ArrayList<>();
        Set<Integer> indices = new TreeSet<>();
        int cursor = 0;
        int implicitIndex = 0;
        while (matcher.find()) {
            addLiteral(parts, template.substring(cursor, matcher.start()), template);
            String token = matcher.group();
            if (token.equals("%%")) {
                parts.add(new Part("%", -1));
            } else {
                assertEquals("s", matcher.group(2), "unsupported format " + token + " in " + template);
                int index;
                if (matcher.group(1) == null) {
                    index = implicitIndex++;
                } else {
                    try {
                        index = Integer.parseInt(matcher.group(1)) - 1;
                    } catch (NumberFormatException failure) {
                        throw new AssertionError("invalid argument index in " + token, failure);
                    }
                    assertFalse(index < 0, "argument index must be one-based: " + token);
                }
                indices.add(index);
                parts.add(new Part(null, index));
            }
            cursor = matcher.end();
        }
        addLiteral(parts, template.substring(cursor), template);
        return new Parsed(List.copyOf(parts), Collections.unmodifiableSet(indices));
    }

    private static void addLiteral(List<Part> parts, String text, String template) {
        assertFalse(text.contains("%"), "stray or malformed percent format in " + template);
        if (!text.isEmpty()) parts.add(new Part(text, -1));
    }

    private record Part(String text, int index) { }
    private record Parsed(List<Part> parts, Set<Integer> indices) { }
}
