package net.ptcrys.fpsmatch.editor.common.domain.spatial;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Package-private mechanics; no parser, world, registry or asynchronous state is retained. */
final class SpatialCodec {

    static final DocumentDependencies EMPTY_DEPENDENCIES = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    private SpatialCodec() {}

    static BigDecimal coordinate(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (!Double.isFinite(value.doubleValue())) {
            throw new IllegalArgumentException(name + " must convert to finite double");
        }
        return normalized(value);
    }

    static BigDecimal angle(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (!Float.isFinite(value.floatValue())) {
            throw new IllegalArgumentException(name + " must convert to finite float");
        }
        return normalized(value);
    }

    private static BigDecimal normalized(BigDecimal value) {
        // Never expand a large exponent with toPlainString, and never replace exact finite underflow.
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    static JsonObject pointJson(EditorId dimension, BigDecimal x, BigDecimal y, BigDecimal z,
                                BigDecimal yaw, BigDecimal pitch) {
        JsonObject root = new JsonObject();
        root.addProperty("dimension", dimension.toString());
        JsonArray position = new JsonArray();
        position.add(x);
        position.add(y);
        position.add(z);
        root.add("position", position);
        root.addProperty("yaw", yaw);
        root.addProperty("pitch", pitch);
        return root;
    }

    static JsonObject areaJson(EditorId dimension, BoxCorners corners) {
        JsonObject root = new JsonObject();
        root.addProperty("dimension", dimension.toString());
        JsonArray values = new JsonArray();
        values.add(cornerJson(corners.first()));
        values.add(cornerJson(corners.second()));
        root.add("corners", values);
        return root;
    }

    private static JsonArray cornerJson(IntPosition corner) {
        JsonArray values = new JsonArray();
        values.add(corner.x());
        values.add(corner.y());
        values.add(corner.z());
        return values;
    }

    static ExactJson.FrozenJson freeze(JsonElement value) {
        try {
            return ExactJson.freeze(value);
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonElement read(int sourceSchema, byte[] encoding) {
        if (sourceSchema != 1) {
            throw error("unsupported_schema", "spatial", numberArgument(sourceSchema));
        }
        if (encoding == null) throw field("", "utf8_bytes");
        if (encoding.length > ExactJson.MAX_UTF8_BYTES) {
            throw error("byte_budget", "spatial", numberArgument(encoding.length),
                    numberArgument(ExactJson.MAX_UTF8_BYTES));
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoding)).toString();
        } catch (CharacterCodingException failure) {
            throw error("invalid_utf8", "spatial");
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            throw error("invalid_utf8", "spatial");
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

    static JsonArray array(JsonElement value, String pointer, int length) {
        if (value == null || !value.isJsonArray()) throw field(pointer, "array_" + length);
        JsonArray array = value.getAsJsonArray();
        if (array.size() != length) throw field(pointer, "array_" + length);
        return array;
    }

    static EditorId dimension(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw field("/dimension", "editor_id");
        }
        try {
            return EditorId.parse(value.getAsString());
        } catch (IllegalArgumentException failure) {
            throw field("/dimension", "editor_id");
        }
    }

    static BigDecimal number(JsonElement value, String pointer, boolean angle) {
        BigDecimal number = decimal(value, pointer);
        if (angle ? !Float.isFinite(number.floatValue()) : !Double.isFinite(number.doubleValue())) {
            throw field(pointer, angle ? "finite_float" : "finite_double");
        }
        return number;
    }

    private static BigDecimal decimal(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw field(pointer, "number");
        }
        // Only the exact private tree returned by ExactJson reaches this conversion.
        return value.getAsBigDecimal();
    }

    static IntPosition corner(JsonElement value, String pointer) {
        JsonArray values = array(value, pointer, 3);
        return new IntPosition(integer(values.get(0), pointer + "/0"),
                integer(values.get(1), pointer + "/1"), integer(values.get(2), pointer + "/2"));
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
        return error("invalid_json", "spatial:" + failure.path(),
                literal(failure.code()), literal(failure.path()));
    }

    private static DocumentCodecException field(String pointer, String expected) {
        return error("invalid_field", "spatial:" + pointer, literal(pointer), literal(expected));
    }

    private static TextContent.LiteralArgument literal(String value) {
        return new TextContent.LiteralArgument(value);
    }

    private static TextContent.NumberArgument numberArgument(int value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    private static DocumentCodecException error(String suffix, String target,
                                                TextContent.Argument... arguments) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                EditorId.parse("fpsmatch:spatial/" + suffix), target,
                new TextContent.Message("fpsmatch.editor.spatial." + suffix, List.of(arguments)))));
    }
}
