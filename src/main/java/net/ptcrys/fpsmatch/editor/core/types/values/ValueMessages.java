package net.ptcrys.fpsmatch.editor.core.types.values;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;

import java.math.BigDecimal;
import java.util.List;

/** Typed diagnostics; product wording is supplied by the document-types language resources. */
final class ValueMessages {

    private ValueMessages() {}

    static Problem typeActivationFailed(EditorId typeId) {
        return problem("type_activation_failed", typeId, literal(typeId));
    }

    static DocumentCodecException valueBytes(EditorId typeId, long actual, long limit) {
        return new DocumentCodecException(List.of(problem("value_bytes", typeId,
                literal(typeId), number(actual), number(limit))));
    }

    static DocumentCodecException valueDependencies(EditorId typeId, long actual, long limit) {
        return new DocumentCodecException(List.of(problem("value_dependencies", typeId,
                literal(typeId), number(actual), number(limit))));
    }

    private static Problem problem(String suffix, EditorId typeId,
                                   TextContent.Argument... arguments) {
        return new Problem(Problem.Severity.ERROR, new EditorId("editor", "codec/" + suffix),
                typeId.toString(), new TextContent.Message("editor.codec." + suffix,
                        List.of(arguments)));
    }

    private static TextContent.Argument literal(EditorId id) {
        return new TextContent.LiteralArgument(id.toString());
    }

    private static TextContent.Argument number(long value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }
}
