package net.ptcrys.fpsmatch.editor.testutil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import org.junit.jupiter.api.Test;

class MinecraftTranslationFormatTest {
    @Test void implicitAndExplicitIndicesMatchMinecraftAndMayRepeatWithoutConsumingExtraArguments() {
        var message = message(literal("first"), literal("second"));
        assertEquals("first / second", MinecraftTranslationFormat.render(message, "%s / %s"));
        assertEquals("second / first / second",
                MinecraftTranslationFormat.render(message, "%2$s / %1$s / %2$s"));
        assertEquals("[%] second / first / second / first",
                MinecraftTranslationFormat.render(message, "[%%] %2$s / %s / %s / %1$s"));
        assertEquals(Set.of(0, 1), MinecraftTranslationFormat.placeholders("%2$s / %s / %s / %1$s"));
        assertEquals(Set.of(0), MinecraftTranslationFormat.placeholders("%01$s"));
    }

    @Test void completeIndexSetIsRequiredEvenWhenTheNumberOfDistinctIndicesMatches() {
        var twoArguments = message(literal("first"), literal("second"));
        for (String template : List.of("%1$s %3$s", "%2$s %3$s", "%1$s", "%1$s %1$s",
                "%s %s %s", "no parameters")) {
            assertThrows(AssertionError.class, () -> MinecraftTranslationFormat.render(twoArguments, template), template);
        }
        assertThrows(AssertionError.class, () -> MinecraftTranslationFormat.render(message(), "%s"));
        assertEquals("100% complete", MinecraftTranslationFormat.render(message(), "100%% complete"));
        assertEquals(Set.of(), MinecraftTranslationFormat.placeholders("100%% complete"));
    }

    @Test void invalidPercentFormatsAndLegacyOrMixedBracesAreRejected() {
        for (String template : List.of("%", "unfinished %", "%d", "%f", "%n", "%1$S", "%0$s",
                "%999999999999999999999$s", "%1$%", "%1$", "%<s", "%.2s", "% s",
                "%s {1}", "{0}", "unmatched {", "unmatched }")) {
            assertThrows(AssertionError.class, () -> MinecraftTranslationFormat.placeholders(template), template);
        }
    }

    @Test void substitutedLiteralContentIsNeverParsedAsAnotherPlaceholder() {
        String raw = "%2$s {1} $2 \\ path";
        var message = message(literal(raw), literal("second"));
        assertEquals(raw + " | second | " + raw,
                MinecraftTranslationFormat.render(message, "%1$s | %2$s | %1$s"));
    }

    @Test void typedNumbersAndNestedComponentsRenderLikeTheProductionBridge() {
        Map<String, String> language = Map.of("test.message", "%3$s / %2$s / %1$s",
                "test.child", "child %1$s");
        var nested = new TextContent.Message("test.child", List.of(literal("translated")));
        var message = message(literal("identity"), new TextContent.NumberArgument(new BigDecimal("1E+3")),
                new TextContent.NestedArgument(nested));
        assertEquals("child translated / 1E+3 / identity", MinecraftTranslationFormat.rendered(language, message));
        assertEquals("raw {0} %s", MinecraftTranslationFormat.render(
                message(new TextContent.NestedArgument(new TextContent.Literal("raw {0} %s"))), "%s"));
    }

    @Test void missingNestedTranslationsAndIncompleteNestedArgumentsCannotMasqueradeAsRenderedText() {
        var nested = new TextContent.Message("test.child", List.of(literal("first"), literal("second")));
        var message = message(new TextContent.NestedArgument(nested));
        assertThrows(AssertionError.class, () -> MinecraftTranslationFormat.rendered(
                Map.of("test.message", "%s"), message));
        assertThrows(AssertionError.class, () -> MinecraftTranslationFormat.rendered(
                Map.of("test.message", "%s", "test.child", "%1$s"), message));
    }

    private static TextContent.Message message(TextContent.Argument... arguments) {
        return new TextContent.Message("test.message", List.of(arguments));
    }

    private static TextContent.LiteralArgument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }
}
