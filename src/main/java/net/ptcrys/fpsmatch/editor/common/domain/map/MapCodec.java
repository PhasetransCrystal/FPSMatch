package net.ptcrys.fpsmatch.editor.common.domain.map;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Package-private mechanics; no parser, world, registry or asynchronous state is retained. */
final class MapCodec {

    static final DocumentDependencies EMPTY_DEPENDENCIES = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    private MapCodec() {}

    static ExactJson.FrozenJson freeze(JsonElement value) {
        try {
            return ExactJson.freeze(value);
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonElement read(int sourceSchema, byte[] encoding) {
        if (sourceSchema != 1) {
            throw error("unsupported_schema", "map_values", numberArgument(sourceSchema));
        }
        if (encoding == null) throw field("", "utf8_bytes");
        if (encoding.length > ExactJson.MAX_UTF8_BYTES) {
            throw error("byte_budget", "map_values", numberArgument(encoding.length), numberArgument(ExactJson.MAX_UTF8_BYTES));
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoding)).toString();
        } catch (CharacterCodingException failure) {
            throw error("invalid_utf8", "map_values");
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            throw error("invalid_utf8", "map_values");
        }
        try {
            return ExactJson.parse(text).toJson();
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonObject object(JsonElement value, String pointer, List<String> fields) {
        if (value == null || !value.isJsonObject()) throw field(pointer, "object");
        JsonObject object = value.getAsJsonObject();
        for (String name : fields) {
            if (!object.has(name)) throw field(child(pointer, name), "required_field");
        }
        // Sorted traversal fixes the first unknown-field error independently of JSON member order.
        for (String name : object.keySet().stream().sorted().toList()) {
            if (!fields.contains(name)) throw field(child(pointer, name), "known_field");
        }
        return object;
    }

    static String text(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw field(pointer, "string");
        String text = value.getAsString();
        if (text.isBlank() || text.length() > 512) throw field(pointer, "nonempty_utf8_512");
        try {
            int size = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(text)).remaining();
            if (size == 0 || size > 512) throw field(pointer, "nonempty_utf8_512");
        } catch (CharacterCodingException invalid) {
            throw field(pointer, "utf8_string");
        }
        return text;
    }

    static int integerValue(JsonElement value, String pointer) {
        return integer(value, pointer);
    }

    private static BigDecimal decimal(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw field(pointer, "number");
        }
        // Only the exact private tree returned by ExactJson reaches this conversion.
        return value.getAsBigDecimal();
    }

    private static int integer(JsonElement value, String pointer) {
        BigDecimal decimal = decimal(value, pointer);
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException failure) {
            throw field(pointer, "int32");
        }
    }

    private static String child(String pointer, String name) {
        return pointer + "/" + name.replace("~", "~0").replace("/", "~1");
    }

    private static DocumentCodecException invalidJson(JsonContractException failure) {
        return error("invalid_json", "map_values:" + failure.path(), literal(failure.code()), literal(failure.path()));
    }

    static DocumentCodecException field(String pointer, String expected) {
        return error("invalid_field", "map_values:" + pointer, literal(pointer), literal(expected));
    }

    private static TextContent.LiteralArgument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.NumberArgument numberArgument(int value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    private static DocumentCodecException error(String suffix, String target, TextContent.Argument... arguments) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR, EditorId.parse("fpsmatch:map_values/" + suffix), target, new TextContent.Message("fpsmatch.editor.map_values." + suffix, List.of(arguments)))));
    }
}
