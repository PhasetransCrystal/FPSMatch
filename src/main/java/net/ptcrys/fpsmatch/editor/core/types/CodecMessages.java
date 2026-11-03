package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.math.BigDecimal;
import java.util.List;

/** Unresolved diagnostics for common codec boundaries. */
final class CodecMessages {

    private CodecMessages() {}

    static DocumentCodecException unsupportedCodec(EditorId id, EditorId source, EditorId current) {
        return failure("unsupported_codec", id, literal(id), literal(source), literal(current));
    }

    static DocumentCodecException unsupportedSchema(EditorId id, int schema) {
        return failure("unsupported_schema", id, literal(id),
                new TextContent.NumberArgument(BigDecimal.valueOf(schema)));
    }

    static DocumentCodecException failure(String suffix, EditorId id, TextContent.Argument... args) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                new EditorId("editor", "codec/" + suffix), id.toString(),
                new TextContent.Message("editor.codec." + suffix, List.of(args)))));
    }

    private static TextContent.LiteralArgument literal(EditorId id) {
        return new TextContent.LiteralArgument(id.toString());
    }
}
