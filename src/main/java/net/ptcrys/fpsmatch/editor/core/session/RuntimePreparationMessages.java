package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.Arrays;
import java.util.List;

/** Typed, translatable admission failures for saved runtime preparation. */
final class RuntimePreparationMessages {

    private RuntimePreparationMessages() {}

    static SessionRejection rejection(String name, Object target, Object... arguments) {
        List<TextContent.Argument> values = Arrays.stream(arguments).<TextContent.Argument>map(value -> new TextContent.LiteralArgument(String.valueOf(value))).toList();
        return new SessionRejection(List.of(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "preparation/" + name), String.valueOf(target),
                new TextContent.Message("editor.preparation." + name, values))));
    }
}
