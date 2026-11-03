package net.ptcrys.fpsmatch.editor.common.domain.item;

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
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/** Minimal UTF-8 and field contract layer over the shared exact JSON parser. */
final class BasicItemCodec {

    static final DocumentDependencies EMPTY_DEPENDENCIES = new DocumentDependencies(Set.of(), Set.of(), Set.of());

    private BasicItemCodec() {}

    static ExactJson.FrozenJson freeze(JsonElement value) {
        try {
            return ExactJson.freeze(value);
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonElement read(int schema, byte[] encoding) {
        if (schema != 1) throw error("unsupported_schema", "items", number(schema));
        if (encoding == null) throw field("", "utf8_bytes");
        if (encoding.length > ExactJson.MAX_UTF8_BYTES) {
            throw error("byte_budget", "items", number(encoding.length), number(ExactJson.MAX_UTF8_BYTES));
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoding)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("invalid_utf8", "items");
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') throw error("invalid_utf8", "items");
        try {
            return ExactJson.parse(text).toJson();
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static BasicItem current(JsonElement input, Map<EditorId, BasicItemType.TagMode> items) {
        JsonObject json = object(input, "", Set.of("itemId", "count", "damage"), Set.of());
        EditorId id = id(json.get("itemId"), "/itemId");
        int count = count(json.get("count"), "/count");
        JsonElement damage = json.get("damage");
        OptionalInt value = damage.isJsonNull() ? OptionalInt.empty() : OptionalInt.of(damage(damage, "/damage"));
        BasicItem item = new BasicItem(id, count, value);
        validate(item, items);
        return item;
    }

    static BasicItem legacy(JsonElement input, Map<EditorId, BasicItemType.TagMode> items) {
        JsonObject json = object(input, "", Set.of("id", "Count"), Set.of("tag"));
        EditorId id = id(json.get("id"), "/id");
        BasicItemType.TagMode mode = items.get(id);
        if (mode == null) throw field("/id", "accepted_item_id");
        int count = count(json.get("Count"), "/Count");
        int damage = 0;
        if (json.has("tag")) {
            JsonObject tag = object(json.get("tag"), "/tag", Set.of(),
                    mode == BasicItemType.TagMode.DAMAGE ? Set.of("Damage") : Set.of());
            if (tag.has("Damage")) damage = damage(tag.get("Damage"), "/tag/Damage");
        }
        return new BasicItem(id, count, mode == BasicItemType.TagMode.DAMAGE ? OptionalInt.of(damage) : OptionalInt.empty());
    }

    static void validate(BasicItem value, Map<EditorId, BasicItemType.TagMode> items) {
        BasicItemType.TagMode mode = items.get(value.itemId());
        if (mode == null) throw field("/itemId", "accepted_item_id");
        if (value.count() < 1 || value.count() > 127) throw field("/count", "count_1_127");
        if (mode == BasicItemType.TagMode.EMPTY && value.damage().isPresent()) {
            throw field("/damage", "null");
        }
        if (mode == BasicItemType.TagMode.DAMAGE && (value.damage().isEmpty() || value.damage().getAsInt() < 0)) {
            throw field("/damage", "nonnegative_int32");
        }
    }

    private static JsonObject object(JsonElement value, String pointer, Set<String> required,
                                     Set<String> optional) {
        if (value == null || !value.isJsonObject()) throw field(pointer, "object");
        JsonObject json = value.getAsJsonObject();
        for (String key : required.stream().sorted().toList()) {
            if (!json.has(key)) throw field(child(pointer, key), "required_field");
        }
        for (String key : json.keySet().stream().sorted().toList()) {
            if (!required.contains(key) && !optional.contains(key)) throw field(child(pointer, key), "known_field");
        }
        return json;
    }

    private static EditorId id(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw field(pointer, "editor_id");
        }
        try {
            return EditorId.parse(value.getAsString());
        } catch (IllegalArgumentException invalid) {
            throw field(pointer, "editor_id");
        }
    }

    private static int integer(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw field(pointer, "int32");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw field(pointer, "int32");
        }
    }

    private static int count(JsonElement value, String pointer) {
        int count = integer(value, pointer);
        if (count < 1 || count > 127) throw field(pointer, "count_1_127");
        return count;
    }

    private static int damage(JsonElement value, String pointer) {
        int damage = integer(value, pointer);
        if (damage < 0) throw field(pointer, "nonnegative_int32");
        return damage;
    }

    private static String child(String pointer, String name) {
        return pointer + "/" + name.replace("~", "~0").replace("/", "~1");
    }

    static DocumentCodecException field(String pointer, String expected) {
        return error("invalid_field", "items:" + pointer, literal(pointer), literal(expected));
    }

    private static DocumentCodecException invalidJson(JsonContractException failure) {
        return error("invalid_json", "items:" + failure.path(), literal(failure.code()), literal(failure.path()));
    }

    private static TextContent.LiteralArgument literal(String text) {
        return new TextContent.LiteralArgument(text);
    }

    private static TextContent.NumberArgument number(int value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    private static DocumentCodecException error(String suffix, String target, TextContent.Argument... arguments) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                EditorId.parse("fpsmatch:items/" + suffix), target,
                new TextContent.Message("fpsmatch.editor.items." + suffix, List.of(arguments)))));
    }
}
