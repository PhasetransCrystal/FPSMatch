package net.ptcrys.fpsmatch.editor.core.types.objects;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;

import java.math.BigDecimal;
import java.util.List;

final class ObjectMessages {

    private ObjectMessages() {}

    static DocumentCodecException unavailable(EditorId id) {
        return error("type_unavailable", "objects", literal(String.valueOf(id)));
    }

    static DocumentCodecException node(Object target, String reason) {
        return error("invalid_node", String.valueOf(target), literal(String.valueOf(target)), literal(reason));
    }

    static DocumentCodecException wire(String reason) {
        return error("invalid_wire", "objects", literal(reason));
    }

    static void requireBudget(String name, long actual, long max) {
        if (actual > max) throw error("budget", "objects", literal(name), number(actual), number(max));
    }

    private static DocumentCodecException error(String suffix, String target, TextContent.Argument... args) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "objects/" + suffix), target,
                new TextContent.Message("editor.objects." + suffix, List.of(args)))));
    }

    private static TextContent.Argument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
