package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.codec.JsonContractException;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemContext;
import net.ptcrys.fpsmatch.editor.common.domain.map.legacy.LegacyItemResourceKey;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.types.DocumentDependencies;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/** Minimal strict UTF-8, fields and references over the shared exact JSON parser. */
final class WeaponCodec {

    private WeaponCodec() {}

    static ExactJson.FrozenJson freeze(JsonElement value) {
        try {
            return ExactJson.freeze(value);
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonElement read(int schema, byte[] encoding) {
        if (schema != 1) throw error("unsupported_schema", "weapons", number(schema));
        if (encoding == null) throw field("", "utf8_bytes");
        if (encoding.length > ExactJson.MAX_UTF8_BYTES) {
            throw error("byte_budget", "weapons", number(encoding.length), number(ExactJson.MAX_UTF8_BYTES));
        }
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoding)).toString();
        } catch (CharacterCodingException invalid) {
            throw error("invalid_utf8", "weapons");
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') throw error("invalid_utf8", "weapons");
        try {
            return ExactJson.parse(text).toJson();
        } catch (JsonContractException failure) {
            throw invalidJson(failure);
        }
    }

    static JsonObject object(JsonElement value, String pointer, Set<String> required, Set<String> optional) {
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

    static String string(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw field(pointer, "string");
        }
        return value.getAsString();
    }

    static EditorId id(JsonElement value, String pointer) {
        String text = string(value, pointer);
        try {
            return EditorId.parse(text);
        } catch (IllegalArgumentException invalid) {
            throw field(pointer, "editor_id");
        }
    }

    static int integer(JsonElement value, String pointer) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw field(pointer, "int32");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException invalid) {
            throw field(pointer, "int32");
        }
    }

    static int count(JsonElement value, String pointer) {
        int result = integer(value, pointer);
        if (result < 1 || result > 127) throw field(pointer, "count_1_127");
        return result;
    }

    static int nonnegative(JsonElement value, String pointer) {
        int result = integer(value, pointer);
        if (result < 0) throw field(pointer, "nonnegative_int32");
        return result;
    }

    static boolean bool(JsonElement value, String pointer, boolean legacy) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }
        if (legacy) {
            int result = integer(value, pointer);
            if (result == 0 || result == 1) return result == 1;
        }
        throw field(pointer, legacy ? "boolean_or_integer_0_1" : "boolean");
    }

    static TaczGun.FireMode fireMode(JsonElement value, String pointer) {
        String name = string(value, pointer);
        try {
            return TaczGun.FireMode.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            throw field(pointer, "AUTO_SEMI_BURST");
        }
    }

    static Optional<EditorId> optionalId(JsonElement value, String pointer) {
        return value.isJsonNull() ? Optional.empty() : Optional.of(id(value, pointer));
    }

    static OptionalInt optionalInt(JsonElement value, String pointer) {
        return value.isJsonNull() ? OptionalInt.empty() : OptionalInt.of(nonnegative(value, pointer));
    }

    static ResourceRef ref(JsonElement value, String pointer) {
        JsonObject json = object(value, pointer, Set.of("resourceId", "versionId"), Set.of());
        return new ResourceRef(uuid(json.get("resourceId"), child(pointer, "resourceId")),
                uuid(json.get("versionId"), child(pointer, "versionId")));
    }

    static Optional<ResourceRef> optionalRef(JsonElement value, String pointer) {
        return value.isJsonNull() ? Optional.empty() : Optional.of(ref(value, pointer));
    }

    private static UUID uuid(JsonElement value, String pointer) {
        String text = string(value, pointer);
        final UUID result;
        try {
            result = UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw field(pointer, "canonical_uuid");
        }
        if (!result.toString().equals(text)) throw field(pointer, "canonical_uuid");
        return result;
    }

    static JsonObject ref(ResourceRef value) {
        JsonObject json = new JsonObject();
        json.addProperty("resourceId", value.resourceId().toString());
        json.addProperty("versionId", value.versionId().toString());
        return json;
    }

    static JsonElement ref(Optional<ResourceRef> value) {
        return value.<JsonElement>map(WeaponCodec::ref).orElse(JsonNull.INSTANCE);
    }

    static ResourceRef binding(LegacyItemContext context, EditorId provider, EditorId kind,
                               EditorId importer, EditorId asset, String pointer) {
        LegacyItemContext.Binding binding = context.find(new LegacyItemResourceKey(provider, kind, asset))
                .orElseThrow(() -> field(pointer, "explicit_resource_binding"));
        if (!binding.resourceType().equals(kind) || !binding.importer().equals(importer) || !binding.importerVersion().equals("1") || !asset.toString().equals(binding.settings().get("assetId"))) {
            throw field(pointer, "matching_resource_metadata");
        }
        return binding.reference();
    }

    static DocumentDependencies dependencies(ResourceRef primary, Optional<ResourceRef> display) {
        Set<ResourceRef> refs = new HashSet<>();
        refs.add(primary);
        display.ifPresent(refs::add);
        return new DocumentDependencies(Set.of(), refs, Set.of());
    }

    static void displayCoupling(boolean required, Optional<ResourceRef> reference) {
        if (required != reference.isPresent()) throw field("/displayResource", "matching_display_reference");
    }

    private static String child(String pointer, String name) {
        return pointer + "/" + name.replace("~", "~0").replace("/", "~1");
    }

    static DocumentCodecException field(String pointer, String expected) {
        return error("invalid_field", "weapons:" + pointer, literal(pointer), literal(expected));
    }

    private static DocumentCodecException invalidJson(JsonContractException failure) {
        return error("invalid_json", "weapons:" + failure.path(), literal(failure.code()), literal(failure.path()));
    }

    private static TextContent.LiteralArgument literal(String text) {
        return new TextContent.LiteralArgument(text);
    }

    private static TextContent.NumberArgument number(int value) {
        return new TextContent.NumberArgument(BigDecimal.valueOf(value));
    }

    private static DocumentCodecException error(String suffix, String target, TextContent.Argument... arguments) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                EditorId.parse("fpsmatch:weapons/" + suffix), target,
                new TextContent.Message("fpsmatch.editor.weapons." + suffix, List.of(arguments)))));
    }
}
