package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Orientation;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.common.model.map.MapDefinition;
import net.ptcrys.fpsmatch.editor.common.model.map.RegionDefinition;
import net.ptcrys.fpsmatch.editor.common.model.map.ResourceRevisionRef;
import net.ptcrys.fpsmatch.editor.common.model.map.SpawnDefinition;
import net.ptcrys.fpsmatch.editor.common.model.map.TeamDefinition;
import net.ptcrys.fpsmatch.editor.common.model.map.WireExtras;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R06.1b2 严格解码的正式契约测试：{@code MapDefinitionCodec.decode(String)} 与
 * {@code MapDefinitionCodec.decode(ExactJson.FrozenJson)} 两个入口。
 *
 * <p>本类的每一条期望都由本文件独立构造：wire 文本用本文件自己的 canonical 写入器（键按
 * {@link String#compareTo} 升序、数字用给定的十进制拼写、字符串用 P02 的转义表）生成，涉及
 * P02 认证本身的期望用 {@link ExactJson#parse(String)} 作为独立 oracle 取原异常对象，typed 期
 * 望用模型记录的公开构造器直接给定。任何期望都不是先跑生产 encode 再抄回来的。</p>
 *
 * <p>分组是真实 JUnit tag：{@code small-heap} 承载小输入与拒绝画像，{@code large-heap} 承载
 * 最大合法边界、超限认证与 1000 条互异 wire。两组都必须零 skip、零 abort，且发现数固定。</p>
 */
class MapDefinitionDecodeContractTest {

    // ------------------------------------------------------------------ 预给的合法值

    private static final String DEF_ID = "11111111-1111-1111-1111-111111111111";

    private static final String WORLD_ID = "22222222-2222-2222-2222-222222222222";

    private static final String TEAM_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final String SPAWN_ONE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private static final String REGION_ONE = "dddddddd-dddd-dddd-dddd-dddddddddddd";

    private static final String RESOURCE_ID = "33333333-3333-3333-3333-333333333333";

    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String DIMENSION = "minecraft:overworld";

    private static final String MODE = "fpsmatch:conquest";

    private static final String REGION_KIND = "fpsmatch:spawn_area";

    private static final String RESOURCE_KIND = "fpsmatch:model";

    /**
     * 最小合法 wire 的 canonical 文本，逐字预给。它同时是下面 {@link #minimalRoot()} 的独立文本
     * 锚点：两者必须逐字相同，因此写入器本身也被这一条钉住。
     */
    private static final String MINIMAL_WIRE =
            "{\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},\"second\":{\"x\":16,\"y\":256,"
            + "\"z\":16}},\"description\":\"line1\\nline2\",\"dimensionId\":\"minecraft:overworld\","
            + "\"displayName\":\"Dust\",\"extensions\":null,\"id\":\"" + DEF_ID + "\",\"modeId\":\""
            + MODE + "\",\"regions\":[{\"config\":null,\"corners\":{\"first\":{\"x\":4,\"y\":0,"
            + "\"z\":4},\"second\":{\"x\":0,\"y\":0,\"z\":0}},\"extensions\":{\"note\":\"x\"},"
            + "\"id\":\"" + REGION_ONE + "\",\"kindId\":\"" + REGION_KIND + "\",\"name\":\"zone\"}],"
            + "\"resources\":[{\"digest\":\"" + DIGEST + "\",\"kindId\":\"" + RESOURCE_KIND
            + "\",\"resourceId\":\"" + RESOURCE_ID + "\",\"revision\":7}],\"rules\":{\"limit\":5},"
            + "\"schemaVersion\":1,\"spawns\":[{\"dimensionId\":\"" + DIMENSION
            + "\",\"extensions\":null,\"id\":\"" + SPAWN_ONE + "\",\"name\":\"s1\","
            + "\"orientation\":{\"pitch\":-12.25,\"yaw\":45.5},\"position\":{\"x\":1.5,\"y\":0,"
            + "\"z\":2.25},\"teamId\":\"" + TEAM_A + "\"}],\"teams\":[{\"config\":{\"score\":7},"
            + "\"extensions\":null,\"id\":\"" + TEAM_A + "\",\"name\":\"Red\"}],\"worldId\":\""
            + WORLD_ID + "\"}";

    /** 预给的最小合法 wire 的 UTF-8 字节数与 SHA-256，由 JDK 独立算得。 */
    private static final int MINIMAL_WIRE_UTF8_BYTES = 1091;

    private static final String MINIMAL_WIRE_SHA256 =
            "e294f9ca850435908bf6eaecafe1e5a017c52365bc369c00e61f84d6564b223e";

    // ------------------------------------------------------------------ 独立 canonical 写入器

    /** 用本文件自己的规则写出 canonical 文本：键升序、数组保序、数字用给定拼写。 */
    private static String canonical(JsonElement value) {
        final StringBuilder text = new StringBuilder();
        writeCanonical(text, value);
        return text.toString();
    }

    private static void writeCanonical(StringBuilder text, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            text.append("null");
            return;
        }
        if (value.isJsonObject()) {
            final JsonObject object = value.getAsJsonObject();
            final List<String> keys = new ArrayList<>(object.size());
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                keys.add(entry.getKey());
            }
            keys.sort(String::compareTo);
            text.append('{');
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                writeString(text, keys.get(i));
                text.append(':');
                writeCanonical(text, object.get(keys.get(i)));
            }
            text.append('}');
            return;
        }
        if (value.isJsonArray()) {
            final JsonArray array = value.getAsJsonArray();
            text.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    text.append(',');
                }
                writeCanonical(text, array.get(i));
            }
            text.append(']');
            return;
        }
        final JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            text.append(primitive.getAsBoolean() ? "true" : "false");
            return;
        }
        if (primitive.isString()) {
            writeString(text, primitive.getAsString());
            return;
        }
        final Number number = primitive.getAsNumber();
        if (number instanceof BigDecimal) {
            text.append(((BigDecimal) number).toPlainString());
            return;
        }
        if (number instanceof Integer || number instanceof Long) {
            text.append(number.toString());
            return;
        }
        throw new IllegalArgumentException(
                "本文件的 fixture 只使用整数或直写的十进制数字: " + number.getClass().getName());
    }

    private static void writeString(StringBuilder text, String value) {
        text.append('"');
        for (int i = 0; i < value.length(); i++) {
            final char character = value.charAt(i);
            switch (character) {
                case '"':
                    text.append("\\\"");
                    break;
                case '\\':
                    text.append("\\\\");
                    break;
                case '\b':
                    text.append("\\b");
                    break;
                case '\t':
                    text.append("\\t");
                    break;
                case '\n':
                    text.append("\\n");
                    break;
                case '\f':
                    text.append("\\f");
                    break;
                case '\r':
                    text.append("\\r");
                    break;
                default:
                    if (character <= 0x1F) {
                        text.append(String.format("\\u%04x", Integer.valueOf(character)));
                    } else {
                        text.append(character);
                    }
            }
        }
        text.append('"');
    }

    private static String sha256(String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte one : hash) {
                hex.append(String.format("%02x", Integer.valueOf(one & 0xFF)));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ------------------------------------------------------------------ 树构造助手

    private static JsonObject obj(Object... pairs) {
        final JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            object.add((String) pairs[i], (JsonElement) pairs[i + 1]);
        }
        return object;
    }

    private static JsonArray arr(JsonElement... items) {
        final JsonArray array = new JsonArray();
        for (JsonElement item : items) {
            array.add(item);
        }
        return array;
    }

    private static JsonPrimitive str(String value) {
        return new JsonPrimitive(value);
    }

    private static JsonPrimitive num(int value) {
        return new JsonPrimitive(Integer.valueOf(value));
    }

    private static JsonPrimitive num(long value) {
        return new JsonPrimitive(Long.valueOf(value));
    }

    private static JsonPrimitive dec(String plain) {
        return new JsonPrimitive(new BigDecimal(plain));
    }

    private static JsonObject tri(int x, int y, int z) {
        return obj("x", num(x), "y", num(y), "z", num(z));
    }

    private static JsonObject dec3(String x, String y, String z) {
        return obj("x", dec(x), "y", dec(y), "z", dec(z));
    }

    private static JsonObject look(String yaw, String pitch) {
        return obj("yaw", dec(yaw), "pitch", dec(pitch));
    }

    private static JsonObject box(JsonObject first, JsonObject second) {
        return obj("first", first, "second", second);
    }

    private static JsonObject team(String id, String name, JsonElement config, JsonElement extensions) {
        return obj("id", str(id), "name", str(name), "config", config, "extensions", extensions);
    }

    private static JsonObject spawn(String id, String name, String teamId, String dimensionId,
            JsonObject position, JsonObject orientation, JsonElement extensions) {
        return obj("id", str(id), "name", str(name), "teamId", str(teamId),
                "dimensionId", str(dimensionId), "position", position, "orientation", orientation,
                "extensions", extensions);
    }

    private static JsonObject region(String id, String name, String kindId, JsonObject corners,
            JsonElement config, JsonElement extensions) {
        return obj("id", str(id), "name", str(name), "kindId", str(kindId), "corners", corners,
                "config", config, "extensions", extensions);
    }

    private static JsonObject resource(String kindId, String resourceId, long revision, String digest) {
        return obj("kindId", str(kindId), "resourceId", str(resourceId), "revision", num(revision),
                "digest", str(digest));
    }

    /** 最小合法 payload：一个 team、一个 spawn、一个 region、一个 resource，全部成员合法。 */
    private static JsonObject minimalRoot() {
        return obj(
                "schemaVersion", num(1),
                "id", str(DEF_ID),
                "worldId", str(WORLD_ID),
                "modeId", str(MODE),
                "dimensionId", str(DIMENSION),
                "displayName", str("Dust"),
                "description", str("line1\nline2"),
                "boundary", box(tri(0, 0, 0), tri(16, 256, 16)),
                "teams", arr(team(TEAM_A, "Red", obj("score", num(7)), JsonNull.INSTANCE)),
                "spawns", arr(spawn(SPAWN_ONE, "s1", TEAM_A, DIMENSION, dec3("1.5", "0", "2.25"),
                        look("45.5", "-12.25"), JsonNull.INSTANCE)),
                "regions", arr(region(REGION_ONE, "zone", REGION_KIND, box(tri(4, 0, 4), tri(0, 0, 0)),
                        JsonNull.INSTANCE, obj("note", str("x")))),
                "rules", obj("limit", num(5)),
                "resources", arr(resource(RESOURCE_KIND, RESOURCE_ID, 7L, DIGEST)),
                "extensions", JsonNull.INSTANCE);
    }

    /** 与 {@link #minimalRoot()} 逐字对应的 typed 期望，全部由公开构造器独立给定。 */
    private static MapDefinition minimalDefinition() {
        // 十三个未知成员槽在无未知成员时共享同一个 {}；而 rules/config/extensions 是另一类成员：
        // wire 写 null 时它们各自冻成独立的 "null"，绝不与未知成员的空容器合并。
        final ExactJson.FrozenJson empty = ExactJson.parse("{}");
        final ExactJson.FrozenJson wireNull = ExactJson.parse("null");
        return new MapDefinition(
                new Ids.DefinitionId(UUID.fromString(DEF_ID)),
                new Ids.WorldId(UUID.fromString(WORLD_ID)),
                MODE,
                DIMENSION,
                "Dust",
                "line1\nline2",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(16, 256, 16)),
                List.of(new TeamDefinition(new Ids.TeamId(UUID.fromString(TEAM_A)), "Red",
                        ExactJson.parse("{\"score\":7}"), wireNull, new WireExtras.TeamExtras(empty))),
                List.of(new SpawnDefinition(new Ids.ObjectId(UUID.fromString(SPAWN_ONE)), "s1",
                        new Ids.TeamId(UUID.fromString(TEAM_A)), DIMENSION,
                        new Position(1.5, 0.0, 2.25), new Orientation(45.5f, -12.25f), wireNull,
                        new WireExtras.SpawnExtras(empty, empty, empty))),
                List.of(new RegionDefinition(new Ids.ObjectId(UUID.fromString(REGION_ONE)), "zone",
                        REGION_KIND, new BoxCorners(new IntPosition(4, 0, 4), new IntPosition(0, 0, 0)),
                        wireNull, ExactJson.parse("{\"note\":\"x\"}"),
                        new WireExtras.RegionExtras(empty, empty, empty, empty))),
                ExactJson.parse("{\"limit\":5}"),
                List.of(new ResourceRevisionRef(RESOURCE_KIND, UUID.fromString(RESOURCE_ID), 7L, DIGEST,
                        new WireExtras.ResourceExtras(empty))),
                wireNull,
                new WireExtras.MapExtras(empty, empty, empty, empty));
    }

    /** 一条 canonical wire 文本由本文件自己的写入器生成，绝不由生产 encode 生成。 */
    private static String wire(JsonObject root) {
        return canonical(root);
    }

    /** 取一份可变副本，供逐条精确定位地改动单个成员而不改动别的字节。 */
    private static JsonObject copy(JsonObject root) {
        return root.deepCopy();
    }

    // ------------------------------------------------------------------ 断言助手

    private static SchemaException rejectedByText(String text) {
        return assertThrows(SchemaException.class, () -> MapDefinitionCodec.decode(text));
    }

    private static SchemaException rejectedByFrozen(String certifiedText) {
        final ExactJson.FrozenJson payload = ExactJson.parse(certifiedText);
        return assertThrows(SchemaException.class, () -> MapDefinitionCodec.decode(payload));
    }

    private static void assertCodeAndPath(SchemaException failure, String code, String pointer) {
        assertEquals(code, failure.code(), "错误码");
        assertEquals(pointer, failure.path().pointer(), "FieldPath pointer");
        assertEquals(FieldPath.fromPointer(pointer), failure.path(), "FieldPath segments");
    }

    /** 两个入口必须给出同一个码与同一条完整路径；cause 按给定类型判定，null 表示必须无 cause。 */
    private static void assertBothEntries(String text, String code, String pointer,
            Class<? extends Throwable> causeType) {
        final SchemaException fromText = rejectedByText(text);
        final SchemaException fromFrozen = rejectedByFrozen(text);
        assertCodeAndPath(fromText, code, pointer);
        assertCodeAndPath(fromFrozen, code, pointer);
        if (causeType == null) {
            assertNull(fromText.getCause(), "String 入口在主动抛出时不得携带 cause");
            assertNull(fromFrozen.getCause(), "Frozen 入口在主动抛出时不得携带 cause");
            return;
        }
        assertInstanceOf(causeType, fromText.getCause(), "String 入口 cause 类型");
        assertInstanceOf(causeType, fromFrozen.getCause(), "Frozen 入口 cause 类型");
        assertFalse(fromText.getCause() instanceof SchemaException,
                "cause 不得是另一个 SchemaException");
        assertFalse(fromFrozen.getCause() instanceof SchemaException,
                "cause 不得是另一个 SchemaException");
    }

    private static Executable decodeText(String text) {
        return () -> MapDefinitionCodec.decode(text);
    }

    private static Executable decodeFrozenOf(String text) {
        return () -> MapDefinitionCodec.decode(ExactJson.parse(text));
    }

    // ------------------------------------------------------------------ 入口契约

    @Test
    @Tag("small-heap")
    @DisplayName("两个入口对 Java null 都抛普通 IAE，且不是 SchemaException")
    void javaNullIsRejectedByBothEntriesAsPlainIllegalArgument() {
        final IllegalArgumentException fromText = assertThrows(IllegalArgumentException.class,
                () -> MapDefinitionCodec.decode((String) null));
        assertEquals("strictJson must not be null", fromText.getMessage());
        assertFalse(fromText instanceof SchemaException, "null 文本不是 schema 错误");

        final IllegalArgumentException fromFrozen = assertThrows(IllegalArgumentException.class,
                () -> MapDefinitionCodec.decode((ExactJson.FrozenJson) null));
        assertEquals("payload must not be null", fromFrozen.getMessage());
        assertFalse(fromFrozen instanceof SchemaException, "null payload 不是 schema 错误");
    }

    static List<Arguments> rootShapeCases() {
        return List.of(
                Arguments.of("[]", "the payload root must be a JSON object, not []"),
                Arguments.of("null", "the payload root must be a JSON object, not null"),
                Arguments.of("7", "the payload root must be a JSON object, not 7"),
                Arguments.of("true", "the payload root must be a JSON object, not true"),
                Arguments.of("\"x\"", "the payload root must be a JSON object, not \"x\""));
    }

    @ParameterizedTest
    @Tag("small-heap")
    @MethodSource("rootShapeCases")
    @DisplayName("根不是 JSON 对象时两入口都在根路径报 WRONG_TYPE 且无 cause")
    void rootNonObjectShapesAreRejectedAtTheRoot(String text, String message) {
        assertBothEntries(text, SchemaException.CODE_WRONG_TYPE, "", null);
        assertEquals(message, rejectedByText(text).getMessage());
        assertEquals(message, rejectedByFrozen(text).getMessage());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("P02 认证失败由 String 入口原样定位并保留同一份认证诊断")
    void textEntryPreservesTheCertificationFailureAsTheSameCause() {
        final String text = "{\"a\":1,\"a\":2}";
        // 独立 oracle：同一段文本自己走一次 P02，取它的码、路径与诊断文本。
        final JsonContractException original = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(text));
        try {
            MapDefinitionCodec.decode(text);
        } catch (SchemaException failure) {
            assertCodeAndPath(failure, original.code(), original.path());
            final JsonContractException cause = assertInstanceOf(JsonContractException.class,
                    failure.getCause(), "cause 必须是 P02 的认证异常");
            assertEquals(original.code(), cause.code(), "cause 的错误码");
            assertEquals(original.path(), cause.path(), "cause 的路径");
            assertEquals(original.getMessage(), cause.getMessage(), "cause 的诊断文本");
            assertEquals("payload text was rejected by the strict JSON certification: "
                    + original.getMessage(), failure.getMessage(), "包装文本");
            return;
        }
        throw new AssertionError("非法文本必须被 String 入口拒绝");
    }

    @Test
    @Tag("small-heap")
    @DisplayName("最小合法 wire 的两个入口都得到独立预给的 typed 定义")
    void minimalWireDecodesToTheIndependentlyGivenDefinition() {
        final MapDefinition expected = minimalDefinition();
        assertEquals(expected, MapDefinitionCodec.decode(MINIMAL_WIRE));
        assertEquals(expected, MapDefinitionCodec.decode(ExactJson.parse(MINIMAL_WIRE)));
    }

    @Test
    @Tag("small-heap")
    @DisplayName("最小 wire 与写入器、预给文本、UTF-8 字节数与 SHA-256 完全一致")
    void minimalWireTextIsTheGivenCanonicalText() {
        final String built = wire(minimalRoot());
        assertEquals(MINIMAL_WIRE, built);
        assertEquals(MINIMAL_WIRE_UTF8_BYTES, built.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(MINIMAL_WIRE_SHA256, sha256(built));
    }

    @Test
    @Tag("small-heap")
    @DisplayName("最小 wire 无未知成员时十三个槽共享同一个空 Frozen 实例")
    void minimalWireSharesOneEmptyInstanceAcrossAllThirteenSlots() {
        final MapDefinition decoded = MapDefinitionCodec.decode(MINIMAL_WIRE);
        final ExactJson.FrozenJson empty = decoded.wireExtras().members();
        assertEquals("{}", empty.canonical(), "空槽必须是一个冻结的 {}");
        assertEquals("null", decoded.extensions().canonical(), "wire 的 null 另冻成一份独立的 null");
        assertNotSame(empty, decoded.extensions(), "任意形状成员不得与未知成员容器共用实例");
        assertEquals("null", decoded.teams().get(0).extensions().canonical());
        assertNotSame(empty, decoded.teams().get(0).extensions());
        final WireExtras.MapExtras map = decoded.wireExtras();
        assertSame(empty, map.members());
        assertSame(empty, map.boundaryMembers());
        assertSame(empty, map.boundaryFirstMembers());
        assertSame(empty, map.boundarySecondMembers());
        final TeamDefinition team = decoded.teams().get(0);
        assertSame(empty, team.wireExtras().members());
        final SpawnDefinition spawn = decoded.spawns().get(0);
        assertSame(empty, spawn.wireExtras().members());
        assertSame(empty, spawn.wireExtras().positionMembers());
        assertSame(empty, spawn.wireExtras().orientationMembers());
        final RegionDefinition region = decoded.regions().get(0);
        assertSame(empty, region.wireExtras().members());
        assertSame(empty, region.wireExtras().cornersMembers());
        assertSame(empty, region.wireExtras().cornersFirstMembers());
        assertSame(empty, region.wireExtras().cornersSecondMembers());
        assertSame(empty, decoded.resources().get(0).wireExtras().members());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("最小 wire 解码后重新 encode 回到逐字相同的 canonical 文本")
    void minimalWireEncodesBackToTheGivenCanonicalText() {
        final MapDefinition decoded = MapDefinitionCodec.decode(MINIMAL_WIRE);
        assertEquals(MINIMAL_WIRE, MapDefinitionCodec.encode(decoded).canonical());
    }

    // ------------------------------------------------------------------ 有实质内容的第二份 wire

    private static JsonPrimitive bool(boolean value) {
        return new JsonPrimitive(Boolean.valueOf(value));
    }

    /** 独立冻结助手：用 P02 自己的 freeze 把本文件写出的树冻成 expected 槽，不经生产解码器。 */
    private static ExactJson.FrozenJson frozen(JsonElement value) {
        return ExactJson.freeze(value);
    }

    /** 每个 owner 都带未知成员、且任意形状成员都换成有内容形状的合法 wire。 */
    private static JsonObject substantiveRoot() {
        final JsonObject root = minimalRoot();
        final JsonObject boundary = root.getAsJsonObject("boundary");
        final JsonObject team = root.getAsJsonArray("teams").get(0).getAsJsonObject();
        final JsonObject spawn = root.getAsJsonArray("spawns").get(0).getAsJsonObject();
        final JsonObject region = root.getAsJsonArray("regions").get(0).getAsJsonObject();
        final JsonObject corners = region.getAsJsonObject("corners");

        root.add("rootExtra", obj("deep", arr(num(1), obj("k", JsonNull.INSTANCE))));
        boundary.add("boundaryExtra", str("b"));
        boundary.getAsJsonObject("first").add("firstExtra", arr(num(1), num(2), num(3)));
        boundary.getAsJsonObject("second").add("secondExtra", bool(true));
        team.add("teamExtra", obj("nested", arr()));
        spawn.add("spawnExtra", JsonNull.INSTANCE);
        spawn.getAsJsonObject("position").add("positionExtra", obj("p", num(4)));
        spawn.getAsJsonObject("orientation").add("orientationExtra", str("o"));
        region.add("regionExtra", arr(str("r")));
        corners.add("cornersExtra", str("c"));
        corners.getAsJsonObject("first").add("cornerFirstExtra", num(9));
        corners.getAsJsonObject("second").add("cornerSecondExtra", arr(num(0)));
        root.getAsJsonArray("resources").get(0).getAsJsonObject()
                .add("resourceExtra", obj("q", bool(false)));

        root.add("rules", obj("limit", num(5), "flags", arr(bool(true), obj("deep", str("d")))));
        root.add("extensions", obj("version", num(2)));
        team.add("config", obj("score", num(7), "tags", arr(str("a"), str("b"))));
        team.add("extensions", arr(obj("e", num(1))));
        spawn.add("extensions", str("spawn-extra"));
        region.add("config", obj("note", str("x"), "list", arr(num(1), num(2))));
        region.add("extensions", obj("z", JsonNull.INSTANCE));
        return root;
    }

    /** 与 {@link #substantiveRoot()} 逐字对应的 typed 期望：标量沿用最小定义，各槽独立给定。 */
    private static MapDefinition substantiveDefinition() {
        final MapDefinition base = minimalDefinition();
        return new MapDefinition(
                base.id(),
                base.worldId(),
                base.modeId(),
                base.dimensionId(),
                base.displayName(),
                base.description(),
                base.boundary(),
                List.of(new TeamDefinition(base.teams().get(0).id(), "Red",
                        frozen(obj("score", num(7), "tags", arr(str("a"), str("b")))),
                        frozen(arr(obj("e", num(1)))),
                        new WireExtras.TeamExtras(frozen(obj("teamExtra", obj("nested", arr())))))),
                List.of(new SpawnDefinition(base.spawns().get(0).id(), "s1",
                        base.spawns().get(0).teamId(), DIMENSION, base.spawns().get(0).position(),
                        base.spawns().get(0).orientation(), frozen(str("spawn-extra")),
                        new WireExtras.SpawnExtras(
                                frozen(obj("spawnExtra", JsonNull.INSTANCE)),
                                frozen(obj("positionExtra", obj("p", num(4)))),
                                frozen(obj("orientationExtra", str("o")))))),
                List.of(new RegionDefinition(base.regions().get(0).id(), "zone", REGION_KIND,
                        base.regions().get(0).corners(),
                        frozen(obj("note", str("x"), "list", arr(num(1), num(2)))),
                        frozen(obj("z", JsonNull.INSTANCE)),
                        new WireExtras.RegionExtras(
                                frozen(obj("regionExtra", arr(str("r")))),
                                frozen(obj("cornersExtra", str("c"))),
                                frozen(obj("cornerFirstExtra", num(9))),
                                frozen(obj("cornerSecondExtra", arr(num(0))))))),
                frozen(obj("limit", num(5), "flags", arr(bool(true), obj("deep", str("d"))))),
                List.of(new ResourceRevisionRef(RESOURCE_KIND, UUID.fromString(RESOURCE_ID), 7L, DIGEST,
                        new WireExtras.ResourceExtras(
                                frozen(obj("resourceExtra", obj("q", bool(false))))))),
                frozen(obj("version", num(2))),
                new WireExtras.MapExtras(
                        frozen(obj("rootExtra", obj("deep", arr(num(1), obj("k", JsonNull.INSTANCE))))),
                        frozen(obj("boundaryExtra", str("b"))),
                        frozen(obj("firstExtra", arr(num(1), num(2), num(3)))),
                        frozen(obj("secondExtra", bool(true)))));
    }

    @Test
    @Tag("small-heap")
    @DisplayName("有实质内容的 wire 的两个入口都得到独立预给的 typed 定义并逐字重编码")
    void substantiveWireDecodesToTheIndependentlyGivenDefinition() {
        final String text = wire(substantiveRoot());
        assertEquals(substantiveDefinition(), MapDefinitionCodec.decode(text));
        assertEquals(substantiveDefinition(), MapDefinitionCodec.decode(ExactJson.parse(text)));
        final String reencoded = MapDefinitionCodec.encode(MapDefinitionCodec.decode(text)).canonical();
        assertEquals(text, reencoded, "重编码回到本文件写入器给出的同一份文本");
        assertEquals(ExactJson.parse(text).canonical(), reencoded, "与 P02 的 canonical 文本一致");
    }

    @Test
    @Tag("small-heap")
    @DisplayName("十三个未知成员槽各自冻成独立子树，内容逐字等于写入器给出的文本")
    void substantiveWireFillsEveryUnknownSlotWithItsOwnInstance() {
        final MapDefinition decoded = MapDefinitionCodec.decode(wire(substantiveRoot()));
        final WireExtras.MapExtras map = decoded.wireExtras();
        assertEquals(wire(obj("rootExtra", obj("deep", arr(num(1), obj("k", JsonNull.INSTANCE))))),
                map.members().canonical());
        assertEquals(wire(obj("boundaryExtra", str("b"))), map.boundaryMembers().canonical());
        assertEquals(wire(obj("firstExtra", arr(num(1), num(2), num(3)))),
                map.boundaryFirstMembers().canonical());
        assertEquals(wire(obj("secondExtra", bool(true))), map.boundarySecondMembers().canonical());
        final TeamDefinition team = decoded.teams().get(0);
        assertEquals(wire(obj("teamExtra", obj("nested", arr()))), team.wireExtras().members().canonical());
        final SpawnDefinition spawn = decoded.spawns().get(0);
        assertEquals(wire(obj("spawnExtra", JsonNull.INSTANCE)), spawn.wireExtras().members().canonical());
        assertEquals(wire(obj("positionExtra", obj("p", num(4)))),
                spawn.wireExtras().positionMembers().canonical());
        assertEquals(wire(obj("orientationExtra", str("o"))),
                spawn.wireExtras().orientationMembers().canonical());
        final RegionDefinition region = decoded.regions().get(0);
        assertEquals(wire(obj("regionExtra", arr(str("r")))), region.wireExtras().members().canonical());
        assertEquals(wire(obj("cornersExtra", str("c"))), region.wireExtras().cornersMembers().canonical());
        assertEquals(wire(obj("cornerFirstExtra", num(9))),
                region.wireExtras().cornersFirstMembers().canonical());
        assertEquals(wire(obj("cornerSecondExtra", arr(num(0)))),
                region.wireExtras().cornersSecondMembers().canonical());
        assertEquals(wire(obj("resourceExtra", obj("q", bool(false)))),
                decoded.resources().get(0).wireExtras().members().canonical());
        assertNotSame(map.members(), map.boundaryMembers(), "有内容的槽不得共用实例");
        assertNotSame(team.wireExtras().members(), spawn.wireExtras().members());
        assertNotSame(region.wireExtras().cornersMembers(), region.wireExtras().members());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("未知成员里的超范围小数与超长整数保持精确十进制，不量化成 double")
    void unknownMembersKeepTheirExactDecimalValue() {
        final JsonObject root = minimalRoot();
        root.add("exact", dec("0.10000000000000001"));
        root.add("huge", dec("123456789012345678901234567890"));
        root.add("tiny", dec("0.000000000000000000001"));
        final String text = wire(root);
        final MapDefinition decoded = MapDefinitionCodec.decode(text);
        final JsonObject members = decoded.wireExtras().members().toJson().getAsJsonObject();
        assertEquals("0.10000000000000001",
                members.get("exact").getAsJsonPrimitive().getAsBigDecimal().toPlainString());
        assertEquals("123456789012345678901234567890",
                members.get("huge").getAsJsonPrimitive().getAsBigDecimal().toPlainString());
        assertEquals("0.000000000000000000001",
                members.get("tiny").getAsJsonPrimitive().getAsBigDecimal().toPlainString());
        assertEquals(frozen(obj("exact", dec("0.10000000000000001"), "huge",
                dec("123456789012345678901234567890"), "tiny", dec("0.000000000000000000001"))),
                decoded.wireExtras().members(), "未知槽必须等于 P02 独立冻结出的同一棵树");
        assertEquals(ExactJson.parse(text).canonical(),
                MapDefinitionCodec.encode(decoded).canonical(), "重编码不得改写这些数字");
    }

    @Test
    @Tag("small-heap")
    @DisplayName("保留名不跨 owner 抢成员：别的 owner 的保留名在这里是未知成员")
    void reservedNamesAreNotStolenAcrossOwners() {
        final JsonObject root = minimalRoot();
        root.getAsJsonArray("teams").get(0).getAsJsonObject().add("position", obj("x", num(1)));
        root.getAsJsonArray("spawns").get(0).getAsJsonObject().add("corners", obj("a", num(2)));
        root.getAsJsonArray("regions").get(0).getAsJsonObject().add("teamId", str(TEAM_A));
        final String text = wire(root);
        final MapDefinition decoded = MapDefinitionCodec.decode(text);
        assertEquals(wire(obj("position", obj("x", num(1)))),
                decoded.teams().get(0).wireExtras().members().canonical());
        assertEquals(wire(obj("corners", obj("a", num(2)))),
                decoded.spawns().get(0).wireExtras().members().canonical());
        assertEquals(wire(obj("teamId", str(TEAM_A))),
                decoded.regions().get(0).wireExtras().members().canonical());
        assertEquals(new Position(1.5, 0.0, 2.25), decoded.spawns().get(0).position(),
                "spawn 的 position 仍由保留成员给出");
        assertEquals(new BoxCorners(new IntPosition(4, 0, 4), new IntPosition(0, 0, 0)),
                decoded.regions().get(0).corners(), "region 的 corners 仍由保留成员给出");
        assertEquals(text, MapDefinitionCodec.encode(decoded).canonical());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("多个成员同时缺失时，报出冻结顺序里最靠前的那个缺失成员")
    void firstMissingMemberIsTheFirstOneInFrozenWireOrder() {
        final JsonObject root = minimalRoot();
        root.remove("id");
        root.remove("worldId");
        root.remove("modeId");
        assertBothEntries(wire(root), SchemaException.CODE_MISSING_FIELD, "/id", null);

        final JsonObject second = minimalRoot();
        second.remove("boundary");
        second.remove("teams");
        assertBothEntries(wire(second), SchemaException.CODE_MISSING_FIELD, "/boundary", null);

        final JsonObject third = minimalRoot();
        final JsonObject spawn = third.getAsJsonArray("spawns").get(0).getAsJsonObject();
        spawn.remove("name");
        spawn.remove("teamId");
        assertBothEntries(wire(third), SchemaException.CODE_MISSING_FIELD, "/spawns/0/name", null);

        final JsonObject fourth = minimalRoot();
        final JsonObject resource = fourth.getAsJsonArray("resources").get(0).getAsJsonObject();
        resource.remove("kindId");
        resource.remove("digest");
        assertBothEntries(wire(fourth), SchemaException.CODE_MISSING_FIELD,
                "/resources/0/kindId", null);
    }

    // ------------------------------------------------------------------ 成员表、形状表与 pointer 改写

    /** required 成员在 wire 上的写法类别，只用来挑出确定非法的形状。 */
    private enum MemberKind {
        STRING, OBJECT, ARRAY, NUMBER, ANY
    }

    private record Member(String pointer, MemberKind kind) {
        String key() {
            return pointer.substring(pointer.lastIndexOf('/') + 1);
        }
    }

    private record Owner(String pointer, List<Member> members) {
    }

    /** owner 的成员按「键, 类别」交替给出，pointer 由 owner 前缀补全。 */
    private static Owner owner(String pointer, Object... keysAndKinds) {
        final List<Member> members = new ArrayList<>();
        for (int i = 0; i < keysAndKinds.length; i += 2) {
            members.add(new Member(pointer + "/" + keysAndKinds[i], (MemberKind) keysAndKinds[i + 1]));
        }
        return new Owner(pointer, List.copyOf(members));
    }

    /** 十一个 owner 的 required 成员，逐条按 PAYLOAD-CODEC 的写法在本文件独立列出。 */
    private static List<Owner> owners() {
        return List.of(
                owner("",
                        "schemaVersion", MemberKind.NUMBER,
                        "id", MemberKind.STRING,
                        "worldId", MemberKind.STRING,
                        "modeId", MemberKind.STRING,
                        "dimensionId", MemberKind.STRING,
                        "displayName", MemberKind.STRING,
                        "description", MemberKind.STRING,
                        "boundary", MemberKind.OBJECT,
                        "teams", MemberKind.ARRAY,
                        "spawns", MemberKind.ARRAY,
                        "regions", MemberKind.ARRAY,
                        "rules", MemberKind.ANY,
                        "resources", MemberKind.ARRAY,
                        "extensions", MemberKind.ANY),
                owner("/boundary", "first", MemberKind.OBJECT, "second", MemberKind.OBJECT),
                owner("/boundary/first", "x", MemberKind.NUMBER, "y", MemberKind.NUMBER,
                        "z", MemberKind.NUMBER),
                owner("/boundary/second", "x", MemberKind.NUMBER, "y", MemberKind.NUMBER,
                        "z", MemberKind.NUMBER),
                owner("/teams/0", "id", MemberKind.STRING, "name", MemberKind.STRING,
                        "config", MemberKind.ANY, "extensions", MemberKind.ANY),
                owner("/spawns/0", "id", MemberKind.STRING, "name", MemberKind.STRING,
                        "teamId", MemberKind.STRING, "dimensionId", MemberKind.STRING,
                        "position", MemberKind.OBJECT, "orientation", MemberKind.OBJECT,
                        "extensions", MemberKind.ANY),
                owner("/spawns/0/position", "x", MemberKind.NUMBER, "y", MemberKind.NUMBER,
                        "z", MemberKind.NUMBER),
                owner("/spawns/0/orientation", "yaw", MemberKind.NUMBER, "pitch", MemberKind.NUMBER),
                owner("/regions/0", "id", MemberKind.STRING, "name", MemberKind.STRING,
                        "kindId", MemberKind.STRING, "corners", MemberKind.OBJECT,
                        "config", MemberKind.ANY, "extensions", MemberKind.ANY),
                owner("/regions/0/corners", "first", MemberKind.OBJECT, "second", MemberKind.OBJECT),
                owner("/regions/0/corners/first", "x", MemberKind.NUMBER, "y", MemberKind.NUMBER,
                        "z", MemberKind.NUMBER),
                owner("/regions/0/corners/second", "x", MemberKind.NUMBER, "y", MemberKind.NUMBER,
                        "z", MemberKind.NUMBER),
                owner("/resources/0", "kindId", MemberKind.STRING, "resourceId", MemberKind.STRING,
                        "revision", MemberKind.NUMBER, "digest", MemberKind.STRING));
    }

    /** 按本文件写出的 pointer 找到节点：数字段走数组下标，其余走对象成员。 */
    private static JsonElement nodeAt(JsonObject root, String pointer) {
        JsonElement current = root;
        if (!pointer.isEmpty()) {
            for (String segment : pointer.substring(1).split("/")) {
                current = current.isJsonObject()
                        ? current.getAsJsonObject().get(segment)
                        : current.getAsJsonArray().get(Integer.parseInt(segment));
            }
        }
        return current;
    }

    /** 把 pointer 指向的节点整体换成 value：末段是数组下标时按下标替换。 */
    private static JsonObject withNode(JsonObject root, String pointer, JsonElement value) {
        final int slash = pointer.lastIndexOf('/');
        final JsonElement parent = nodeAt(root, pointer.substring(0, slash));
        final String last = pointer.substring(slash + 1);
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(last, value);
        } else {
            parent.getAsJsonArray().set(Integer.parseInt(last), value);
        }
        return root;
    }

    private static JsonObject withNode(String pointer, JsonElement value) {
        return withNode(minimalRoot(), pointer, value);
    }

    private static JsonObject withoutMember(String pointer) {
        final JsonObject root = minimalRoot();
        final int slash = pointer.lastIndexOf('/');
        nodeAt(root, pointer.substring(0, slash)).getAsJsonObject()
                .remove(pointer.substring(slash + 1));
        return root;
    }

    private static JsonElement wrongShape(MemberKind kind) {
        return switch (kind) {
            case STRING, OBJECT -> num(7);
            case NUMBER -> str("7");
            case ARRAY -> obj("unexpected", num(1));
            case ANY -> throw new IllegalStateException("任意形状成员不参与判型表");
        };
    }

    /** 与生产 shape() 独立重写的形状词。 */
    private static String shapeWord(JsonElement value) {
        if (value.isJsonNull()) {
            return "JSON null";
        }
        if (value.isJsonObject()) {
            return "a JSON object";
        }
        if (value.isJsonArray()) {
            return "a JSON array";
        }
        if (value.getAsJsonPrimitive().isBoolean()) {
            return "a JSON boolean";
        }
        if (value.getAsJsonPrimitive().isString()) {
            return "a JSON string";
        }
        return "a JSON number";
    }

    private static boolean holdsPointer(List<String> pointers, String pointer) {
        return pointers.contains(pointer);
    }

    private static boolean disjoint(List<String> left, List<String> right) {
        for (String pointer : left) {
            if (holdsPointer(right, pointer)) {
                return false;
            }
        }
        return true;
    }

    static List<Arguments> requiredMemberCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (Owner owner : owners()) {
            for (Member member : owner.members()) {
                cases.add(Arguments.of(member.pointer(), member.kind()));
            }
        }
        return cases;
    }

    static List<Arguments> wrongShapeCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (Owner owner : owners()) {
            for (Member member : owner.members()) {
                if (member.kind() == MemberKind.ANY) {
                    continue;
                }
                for (JsonElement shape : List.of(JsonNull.INSTANCE, wrongShape(member.kind()))) {
                    cases.add(Arguments.of(member.pointer(), member.kind(), shape));
                }
            }
        }
        return cases;
    }

    static List<Arguments> arrayItemCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String pointer : List.of("/teams/0", "/spawns/0", "/regions/0", "/resources/0")) {
            for (JsonElement shape : List.of(JsonNull.INSTANCE, num(7), str("x"), arr(num(1)))) {
                cases.add(Arguments.of(pointer, shape));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "缺失 {0} → MISSING_FIELD")
    @Tag("small-heap")
    @MethodSource("requiredMemberCases")
    void everyRequiredMemberDeletionIsReportedAtItsOwnPath(String pointer, MemberKind kind) {
        final String text = wire(withoutMember(pointer));
        assertBothEntries(text, SchemaException.CODE_MISSING_FIELD, pointer, null);
        final String message = "the required member is absent from the wire object";
        assertEquals(message, rejectedByText(text).getMessage());
        assertEquals(message, rejectedByFrozen(text).getMessage());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("成员表覆盖冻结契约的全部 56 个 required 成员，其中 7 个是任意形状")
    void memberTableMatchesTheFrozenContractSize() {
        final List<Member> all = new ArrayList<>();
        final List<Member> typed = new ArrayList<>();
        for (Owner owner : owners()) {
            all.addAll(owner.members());
            for (Member member : owner.members()) {
                if (member.kind() != MemberKind.ANY) {
                    typed.add(member);
                }
            }
        }
        assertEquals(56, all.size(), "十二个 owner 的 required 成员总数");
        assertEquals(49, typed.size(), "其中参与判型表的成员数");
        assertEquals(7, all.size() - typed.size(), "任意形状成员数");
        final Set<String> anyPointers = new HashSet<>();
        for (Member member : all) {
            if (member.kind() == MemberKind.ANY) {
                anyPointers.add(member.pointer());
            }
        }
        assertEquals(Set.of("/rules", "/extensions", "/teams/0/config", "/teams/0/extensions",
                "/spawns/0/extensions", "/regions/0/config", "/regions/0/extensions"), anyPointers);
        assertTrue(all.containsAll(typed));
        final Set<String> pointers = new HashSet<>();
        for (Member member : all) {
            assertTrue(pointers.add(member.pointer()), "pointer 不得重复：" + member.pointer());
        }
    }

    @ParameterizedTest(name = "{0} 写成非法形状 → WRONG_TYPE")
    @Tag("small-heap")
    @MethodSource("wrongShapeCases")
    void everyRequiredMemberWrongShapeIsRejectedAtItsOwnPath(String pointer, MemberKind kind,
            JsonElement shape) {
        final String text = wire(withNode(pointer, shape));
        assertBothEntries(text, SchemaException.CODE_WRONG_TYPE, pointer, null);
        final String expected = switch (kind) {
            case STRING -> "the member must be a JSON string, not " + shapeWord(shape);
            case OBJECT -> "the member must be a JSON object, not " + shapeWord(shape);
            case ARRAY -> "the member must be a JSON array, not " + shapeWord(shape);
            case NUMBER -> pointer.substring(pointer.lastIndexOf('/') + 1)
                    + " must be a JSON number, not " + shapeWord(shape);
            case ANY -> throw new IllegalStateException("任意形状成员不参与判型表");
        };
        assertEquals(expected, rejectedByText(text).getMessage());
        assertEquals(expected, rejectedByFrozen(text).getMessage());
    }

    @ParameterizedTest(name = "{0} 的数组项写成 {1} → WRONG_TYPE")
    @Tag("small-heap")
    @MethodSource("arrayItemCases")
    void arrayItemsMustBeObjects(String pointer, JsonElement shape) {
        final String text = wire(withNode(pointer, shape));
        assertBothEntries(text, SchemaException.CODE_WRONG_TYPE, pointer, null);
        final String expected = "the array item must be a JSON object, not " + shapeWord(shape);
        assertEquals(expected, rejectedByText(text).getMessage());
        assertEquals(expected, rejectedByFrozen(text).getMessage());
    }

    // ------------------------------------------------------------------ 七个 UUID 读位

    /** 位置名、报错所在 pointer、为保持载荷合法必须一起改写的 pointer、本文件给出的合法规范值。 */
    private record UuidPosition(String name, String readPointer, List<String> writePointers,
            String legal) {
    }

    private static List<UuidPosition> uuidPositions() {
        return List.of(
                new UuidPosition("根 id", "/id", List.of("/id"),
                        "4444aa44-4444-4444-4444-444444444444"),
                new UuidPosition("根 worldId", "/worldId", List.of("/worldId"),
                        "5555bb55-5555-5555-5555-555555555555"),
                new UuidPosition("team id", "/teams/0/id",
                        List.of("/teams/0/id", "/spawns/0/teamId"),
                        "cccccccc-cccc-cccc-cccc-cccccccccccc"),
                new UuidPosition("spawn id", "/spawns/0/id", List.of("/spawns/0/id"),
                        "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"),
                new UuidPosition("spawn teamId", "/spawns/0/teamId",
                        List.of("/teams/0/id", "/spawns/0/teamId"),
                        "cccccccc-cccc-cccc-cccc-cccccccccccc"),
                new UuidPosition("region id", "/regions/0/id", List.of("/regions/0/id"),
                        "ffffffff-ffff-ffff-ffff-ffffffffffff"),
                new UuidPosition("resource resourceId", "/resources/0/resourceId",
                        List.of("/resources/0/resourceId"),
                        "6666cc66-6666-6666-6666-666666666666"));
    }

    private static UUID readTyped(MapDefinition decoded, String name) {
        return switch (name) {
            case "根 id" -> decoded.id().value();
            case "根 worldId" -> decoded.worldId().value();
            case "team id" -> decoded.teams().get(0).id().value();
            case "spawn id" -> decoded.spawns().get(0).id().value();
            case "spawn teamId" -> decoded.spawns().get(0).teamId().value();
            case "region id" -> decoded.regions().get(0).id().value();
            case "resource resourceId" -> decoded.resources().get(0).resourceId();
            default -> throw new IllegalStateException("未知的 UUID 读位：" + name);
        };
    }

    private static String hex4(char c) {
        final String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
        return "0000".substring(hex.length()) + hex;
    }

    /** 独立重算大写违规的内层消息：第一个大写十六进制字母的位置。 */
    private static String upperInnerMessage(String upper) {
        for (int i = 0; i < upper.length(); i++) {
            final char c = upper.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                continue;
            }
            if (c >= 'A' && c <= 'F') {
                return "a UUID must match [0-9a-f] but has '" + c + "' (U+" + hex4(c)
                        + ") at index " + i;
            }
        }
        throw new IllegalStateException("本用例选的值没有大写十六进制字母：" + upper);
    }

    private static final String REJECTED_PREFIX = "the wire value is rejected at this path: ";

    static List<Arguments> uuidAcceptCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (UuidPosition position : uuidPositions()) {
            cases.add(Arguments.of(position.name(), position.legal(), position.writePointers()));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 的合法规范值被两个入口读成同一个 UUID")
    @Tag("small-heap")
    @MethodSource("uuidAcceptCases")
    void everyUuidPositionAcceptsALegalCanonicalValue(String name, String legal,
            List<String> writePointers) {
        final JsonObject root = minimalRoot();
        for (String pointer : writePointers) {
            withNode(root, pointer, str(legal));
        }
        final String text = wire(root);
        final MapDefinition fromText = MapDefinitionCodec.decode(text);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(ExactJson.parse(text));
        assertEquals(UUID.fromString(legal), readTyped(fromText, name));
        assertEquals(UUID.fromString(legal), readTyped(fromFrozen, name));
        for (UuidPosition other : uuidPositions()) {
            if (other.name().equals(name) || !disjoint(other.writePointers(), writePointers)) {
                continue;
            }
            assertEquals(readTyped(minimalDefinition(), other.name()), readTyped(fromText, other.name()),
                    other.name() + " 不得被这一处改写牵动");
        }
        assertEquals(text, MapDefinitionCodec.encode(fromFrozen).canonical());
    }

    static List<Arguments> uuidRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (UuidPosition position : uuidPositions()) {
            final String pointer = position.readPointer();
            final String upper = position.legal().toUpperCase(java.util.Locale.ROOT);
            cases.add(Arguments.of(position.name() + " 大写", pointer, str(upper),
                    SchemaException.CODE_INVALID_VALUE, IllegalArgumentException.class,
                    REJECTED_PREFIX + upperInnerMessage(upper)));
            cases.add(Arguments.of(position.name() + " 缩写", pointer, str("1111"),
                    SchemaException.CODE_INVALID_VALUE, IllegalArgumentException.class,
                    REJECTED_PREFIX + "a UUID must be exactly 36 characters, got 4"));
            cases.add(Arguments.of(position.name() + " 非十六进制", pointer,
                    str("gggggggg-gggg-gggg-gggg-gggggggggggg"),
                    SchemaException.CODE_INVALID_VALUE, IllegalArgumentException.class,
                    REJECTED_PREFIX
                            + "a UUID must match [0-9a-f] but has 'g' (U+0067) at index 0"));
            cases.add(Arguments.of(position.name() + " 丢连字符", pointer,
                    str("111111111111111111111111111111111111"),
                    SchemaException.CODE_INVALID_VALUE, IllegalArgumentException.class,
                    REJECTED_PREFIX + "a UUID must hold '-' at index 8 but has '1'"));
            cases.add(Arguments.of(position.name() + " 显式 null", pointer, JsonNull.INSTANCE,
                    SchemaException.CODE_WRONG_TYPE, null,
                    "the member must be a JSON string, not JSON null"));
            cases.add(Arguments.of(position.name() + " 数字", pointer, num(7),
                    SchemaException.CODE_WRONG_TYPE, null,
                    "the member must be a JSON string, not a JSON number"));
            cases.add(Arguments.of(position.name() + " 布尔", pointer, bool(true),
                    SchemaException.CODE_WRONG_TYPE, null,
                    "the member must be a JSON string, not a JSON boolean"));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} → {3}")
    @Tag("small-heap")
    @MethodSource("uuidRejectCases")
    void everyUuidPositionRejectsNonCanonicalValues(String name, String pointer, JsonElement shape,
            String code, Class<? extends Throwable> causeType, String message) {
        final String text = wire(withNode(pointer, shape));
        assertBothEntries(text, code, pointer, causeType);
        assertEquals(message, rejectedByText(text).getMessage(), name);
        assertEquals(message, rejectedByFrozen(text).getMessage(), name);
        if (causeType == null) {
            assertNull(rejectedByText(text).getCause(), name);
            assertNull(rejectedByFrozen(text).getCause(), name);
        } else {
            final Throwable fromText = rejectedByText(text).getCause();
            assertEquals(causeType, fromText.getClass(), name + " 必须保原 IllegalArgumentException");
            assertEquals(fromText.getMessage(), rejectedByFrozen(text).getCause().getMessage(), name);
        }
    }

    // ------------------------------------------------------------------ 主报告的三条反例与陷阱守卫

    @Test
    @Tag("small-heap")
    @DisplayName("反例一：其余成员全部合法时，大写 id 必须 INVALID_VALUE(/id)，小写同一载荷必须通过")
    void uppercaseIdOnAnOtherwiseLegalPayloadIsRejected() {
        final String upper = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA";
        final String lower = upper.toLowerCase(java.util.Locale.ROOT);
        final MapDefinition accepted = MapDefinitionCodec.decode(wire(withNode("/id", str(lower))));
        assertEquals(UUID.fromString(lower), accepted.id().value(), "小写同一载荷必须被接受");

        final String text = wire(withNode("/id", str(upper)));
        assertBothEntries(text, SchemaException.CODE_INVALID_VALUE, "/id",
                IllegalArgumentException.class);
        final String inner = "a UUID must match [0-9a-f] but has 'A' (U+0041) at index 0";
        assertEquals(REJECTED_PREFIX + inner, rejectedByText(text).getMessage());
        assertEquals(REJECTED_PREFIX + inner, rejectedByFrozen(text).getMessage());
        assertEquals(inner, rejectedByText(text).getCause().getMessage());
        assertNotEquals(SchemaException.CODE_WRONG_TYPE, rejectedByText(text).code(),
                "形状合法、只有大小写非法：不得报成 WRONG_TYPE");
    }

    @Test
    @Tag("small-heap")
    @DisplayName("陷阱守卫：生产 Ids.parse 接受大写并归一化，解码器绝不走它")
    void uppercaseTypedIdsAreAcceptedByIdsParseButRejectedByTheCodec() {
        final String upperDefinition = "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA";
        final String upperTeam = "CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC";
        assertEquals(UUID.fromString(upperDefinition), Ids.DefinitionId.parse(upperDefinition).value());
        assertEquals(UUID.fromString(upperTeam), Ids.TeamId.parse(upperTeam).value());
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                Ids.DefinitionId.parse(upperDefinition).value().toString(),
                "Ids.parse 会把大写归一化成小写规范值");
        assertEquals("cccccccc-cccc-cccc-cccc-cccccccccccc",
                Ids.TeamId.parse(upperTeam).value().toString());

        assertBothEntries(wire(withNode("/id", str(upperDefinition))),
                SchemaException.CODE_INVALID_VALUE, "/id", IllegalArgumentException.class);
        final JsonObject teamRoot = minimalRoot();
        withNode(teamRoot, "/teams/0/id", str(upperTeam));
        withNode(teamRoot, "/spawns/0/teamId", str(upperTeam));
        assertBothEntries(wire(teamRoot), SchemaException.CODE_INVALID_VALUE, "/teams/0/id",
                IllegalArgumentException.class);
    }

    @Test
    @Tag("small-heap")
    @DisplayName("反例二：id 显式为 null 必须 WRONG_TYPE(/id) 且 cause 为空，不得是 INVALID_VALUE")
    void explicitNullIdIsWrongTypeWithoutCause() {
        final String text = wire(withNode("/id", JsonNull.INSTANCE));
        assertBothEntries(text, SchemaException.CODE_WRONG_TYPE, "/id", null);
        final SchemaException fromText = rejectedByText(text);
        assertEquals("the member must be a JSON string, not JSON null", fromText.getMessage());
        assertNotEquals(SchemaException.CODE_INVALID_VALUE, fromText.code());
        assertNull(fromText.getCause(), "判型失败不得带上任何内部异常");
        assertNull(rejectedByFrozen(text).getCause());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("反例三：合法单资源载荷里 resourceId 为 null 必须 WRONG_TYPE 且不被重包装")
    void nullResourceIdInAnOtherwiseLegalPayloadIsNotRewrapped() {
        final String text = wire(withNode("/resources/0/resourceId", JsonNull.INSTANCE));
        assertBothEntries(text, SchemaException.CODE_WRONG_TYPE, "/resources/0/resourceId", null);
        final SchemaException fromText = rejectedByText(text);
        assertEquals("the member must be a JSON string, not JSON null", fromText.getMessage());
        assertEquals("/resources/0/resourceId", fromText.path().pointer());
        assertNull(fromText.getCause());
        assertNotEquals(SchemaException.CODE_INVALID_VALUE, fromText.code());
    }

    // ------------------------------------------------------------------ 任意形状槽

    private record AnySlot(String name, String pointer) {
    }

    private static List<AnySlot> anySlots() {
        return List.of(
                new AnySlot("rules", "/rules"),
                new AnySlot("extensions", "/extensions"),
                new AnySlot("team config", "/teams/0/config"),
                new AnySlot("team extensions", "/teams/0/extensions"),
                new AnySlot("spawn extensions", "/spawns/0/extensions"),
                new AnySlot("region config", "/regions/0/config"),
                new AnySlot("region extensions", "/regions/0/extensions"));
    }

    private static ExactJson.FrozenJson readAnySlot(MapDefinition decoded, String name) {
        return switch (name) {
            case "rules" -> decoded.rules();
            case "extensions" -> decoded.extensions();
            case "team config" -> decoded.teams().get(0).config();
            case "team extensions" -> decoded.teams().get(0).extensions();
            case "spawn extensions" -> decoded.spawns().get(0).extensions();
            case "region config" -> decoded.regions().get(0).config();
            case "region extensions" -> decoded.regions().get(0).extensions();
            default -> throw new IllegalStateException("未知的任意形状槽：" + name);
        };
    }

    /** 该槽所属 owner 的未知成员容器：本用例里它只可能是共享空实例。 */
    private static ExactJson.FrozenJson ownerExtrasOf(MapDefinition decoded, String name) {
        return switch (name) {
            case "rules", "extensions" -> decoded.wireExtras().members();
            case "team config", "team extensions" -> decoded.teams().get(0).wireExtras().members();
            case "spawn extensions" -> decoded.spawns().get(0).wireExtras().members();
            case "region config", "region extensions" -> decoded.regions().get(0).wireExtras().members();
            default -> throw new IllegalStateException("未知的任意形状槽：" + name);
        };
    }

    static List<Arguments> anyShapeCases() {
        final List<JsonElement> shapes = List.of(
                JsonNull.INSTANCE,
                num(0),
                str(""),
                str("文字"),
                bool(true),
                arr(num(1), JsonNull.INSTANCE),
                obj(),
                obj("k", obj("nested", arr(str("x")))));
        final List<Arguments> cases = new ArrayList<>();
        for (AnySlot slot : anySlots()) {
            for (JsonElement shape : shapes) {
                cases.add(Arguments.of(slot.name(), slot.pointer(), shape));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 写成任意形状都合法")
    @Tag("small-heap")
    @MethodSource("anyShapeCases")
    void everyAnyShapeSlotAcceptsEveryJsonShape(String name, String pointer, JsonElement shape) {
        final String text = wire(withNode(pointer, shape));
        final MapDefinition fromText = MapDefinitionCodec.decode(text);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(ExactJson.parse(text));
        assertEquals(frozen(shape), readAnySlot(fromText, name), name);
        assertEquals(frozen(shape), readAnySlot(fromFrozen, name), name);
        assertNotSame(ownerExtrasOf(fromText, name), readAnySlot(fromText, name),
                "任意形状成员不得与未知成员容器共用实例：" + name);
        assertEquals(ExactJson.parse(text).canonical(),
                MapDefinitionCodec.encode(fromText).canonical(), "任意形状槽必须逐字重编码");
    }

    // ------------------------------------------------------------------ 数值边界的独立 oracle

    /** 五十位十进制字面量：位数远超 int/long/double/float，且超出最短拼写阈值。 */
    private static final String FIFTY_DIGITS = "12345678901234567890123456789012345678901234567890";

    /** 与生产无关的 JDK oracle：给定十进制字面量的 valueExact 失败消息，绝不手抄异常文本。 */
    private static String jdkExactFailure(String plain, boolean asInt) {
        final BigDecimal exact = new BigDecimal(plain);
        try {
            if (asInt) {
                exact.intValueExact();
            } else {
                exact.longValueExact();
            }
        } catch (ArithmeticException failure) {
            return failure.getMessage();
        }
        throw new IllegalStateException("该字面量在范围内，不能当作越界用例：" + plain);
    }

    /** 一条 wire 的两个入口都必须能解，且 re-encode 逐字等于 P02 对同一文本的 canonical。 */
    private static MapDefinition assertDecodedAndReencodes(String text) {
        final ExactJson.FrozenJson payload = ExactJson.parse(text);
        final MapDefinition fromText = MapDefinitionCodec.decode(text);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(payload);
        assertEquals(payload.canonical(), MapDefinitionCodec.encode(fromText).canonical(),
                "String 入口 re-encode 必须等于 P02 canonical");
        assertEquals(payload.canonical(), MapDefinitionCodec.encode(fromFrozen).canonical(),
                "Frozen 入口 re-encode 必须等于 P02 canonical");
        return fromText;
    }

    /**
     * 把一个成员写成给定的原始数字字面量：绕过本文件的写入器，保留指数与超长位数拼写，
     * 让 P02（而不是本文件）决定它的 canonical。
     */
    private static String wireWithLiteral(String pointer, String literal) {
        final String placeholder = "\"__RAW_NUMBER__\"";
        final String text = wire(withNode(pointer, str("__RAW_NUMBER__")));
        final int first = text.indexOf(placeholder);
        assertTrue(first >= 0, "占位符必须出现在 wire 里：" + pointer);
        assertEquals(first, text.lastIndexOf(placeholder), "占位符必须唯一：" + pointer);
        return text.replace(placeholder, literal);
    }

    /** 两个入口必须给出同一码、同一路径、同一完整消息，cause 精确到具体类型。 */
    private static void assertBothEntriesWithMessage(String text, String code, String pointer,
            Class<? extends Throwable> causeType, String message) {
        assertBothEntries(text, code, pointer, causeType);
        assertEquals(message, rejectedByText(text).getMessage(), "String 入口消息");
        assertEquals(message, rejectedByFrozen(text).getMessage(), "Frozen 入口消息");
        if (causeType != null) {
            assertEquals(causeType, rejectedByText(text).getCause().getClass(), "cause 精确类型");
            assertEquals(rejectedByText(text).getCause().getMessage(),
                    rejectedByFrozen(text).getCause().getMessage(), "两个入口的 cause 消息必须一致");
        }
    }

    /** 一个数值分量槽：种类 + 所属三元组的绝对指针 + 分量名。 */
    record ScalarSlot(String kind, String owner, String key) {
        String pointer() {
            return owner + "/" + key;
        }
    }

    private static final List<String> INT_OWNERS = List.of("/boundary/first", "/boundary/second",
            "/regions/0/corners/first", "/regions/0/corners/second");

    static List<ScalarSlot> intSlots() {
        final List<ScalarSlot> slots = new ArrayList<>();
        for (String owner : INT_OWNERS) {
            for (String key : List.of("x", "y", "z")) {
                slots.add(new ScalarSlot("int", owner, key));
            }
        }
        return slots;
    }

    static List<ScalarSlot> decimalSlots() {
        final List<ScalarSlot> slots = new ArrayList<>();
        for (String key : List.of("x", "y", "z")) {
            slots.add(new ScalarSlot("double", "/spawns/0/position", key));
        }
        return slots;
    }

    static List<ScalarSlot> floatSlots() {
        return List.of(new ScalarSlot("float", "/spawns/0/orientation", "yaw"),
                new ScalarSlot("float", "/spawns/0/orientation", "pitch"));
    }

    private static IntPosition intPositionAt(MapDefinition definition, String owner) {
        return switch (owner) {
            case "/boundary/first" -> definition.boundary().first();
            case "/boundary/second" -> definition.boundary().second();
            case "/regions/0/corners/first" -> definition.regions().get(0).corners().first();
            case "/regions/0/corners/second" -> definition.regions().get(0).corners().second();
            default -> throw new IllegalStateException("未知整数三元组：" + owner);
        };
    }

    private static int intComponent(IntPosition position, String key) {
        return switch (key) {
            case "x" -> position.x();
            case "y" -> position.y();
            case "z" -> position.z();
            default -> throw new IllegalStateException("未知分量：" + key);
        };
    }

    /** 用 JDK 自己的 toString 读回运行期值：绝不用本文件的写入器造期望值。 */
    private static String scalarText(MapDefinition definition, ScalarSlot slot) {
        if ("int".equals(slot.kind())) {
            return Integer.toString(intComponent(intPositionAt(definition, slot.owner()), slot.key()));
        }
        if ("double".equals(slot.kind())) {
            final Position position = definition.spawns().get(0).position();
            final double value = switch (slot.key()) {
                case "x" -> position.x();
                case "y" -> position.y();
                case "z" -> position.z();
                default -> throw new IllegalStateException("未知分量：" + slot.key());
            };
            return Double.toString(value);
        }
        if ("float".equals(slot.kind())) {
            final Orientation orientation = definition.spawns().get(0).orientation();
            final float value = switch (slot.key()) {
                case "yaw" -> orientation.yaw();
                case "pitch" -> orientation.pitch();
                default -> throw new IllegalStateException("未知分量：" + slot.key());
            };
            return Float.toString(value);
        }
        throw new IllegalStateException("未知标量种类：" + slot.kind());
    }

    // ------------------------------------------------------ int 边界的接受与真实 ArithmeticException

    static List<Arguments> intAcceptCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : intSlots()) {
            for (String literal : List.of("-2147483648", "-1", "0", "2147483647")) {
                cases.add(Arguments.of(slot, literal,
                        Integer.toString(new BigDecimal(literal).intValueExact())));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 接受 int 边界字面量 {1}")
    @Tag("small-heap")
    @MethodSource("intAcceptCases")
    void intComponentsAcceptEveryIntBoundaryLiteral(ScalarSlot slot, String literal, String expected) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertEquals(expected, scalarText(assertDecodedAndReencodes(text), slot),
                slot.pointer() + " 的运行期值必须是 " + expected);
    }

    static List<Arguments> intRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : intSlots()) {
            for (String literal : List.of("2147483648", "-2147483649", "1.5", FIFTY_DIGITS)) {
                cases.add(Arguments.of(slot, literal, jdkExactFailure(literal, true)));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 拒绝越界或非整数 {1}")
    @Tag("small-heap")
    @MethodSource("intRejectCases")
    void intComponentsRejectOutOfRangeAndFractionalLiterals(ScalarSlot slot, String literal,
            String jdkMessage) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, slot.pointer(),
                ArithmeticException.class,
                slot.key() + " must be a mathematical integer inside the int range: " + jdkMessage);
    }

    // ------------------------------------------------------ double / float 的精确往返

    static List<Arguments> decimalAcceptCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : decimalSlots()) {
            for (String literal : List.of("0", "-1.5", "1.5", "0.1", "4.9E-324",
                    "2.2250738585072014E-308", "1.7976931348623157E308")) {
                cases.add(Arguments.of(slot, literal,
                        Double.toString(new BigDecimal(literal).doubleValue())));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 接受可精确往返的 double 字面量 {1}")
    @Tag("small-heap")
    @MethodSource("decimalAcceptCases")
    void doubleComponentsAcceptExactlyRepresentableLiterals(ScalarSlot slot, String literal,
            String expected) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertEquals(expected, scalarText(assertDecodedAndReencodes(text), slot),
                slot.pointer() + " 的运行期值必须是 " + expected);
    }

    static List<Arguments> decimalRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : decimalSlots()) {
            for (String literal : List.of("0.10000000000000001", "1E-400", "1E+400",
                    "1E+1000000", FIFTY_DIGITS)) {
                cases.add(Arguments.of(slot, literal));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 拒绝不可精确往返或无穷的 double 字面量 {1}")
    @Tag("small-heap")
    @MethodSource("decimalRejectCases")
    void doubleComponentsRejectInexactOrInfiniteLiterals(ScalarSlot slot, String literal) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, slot.pointer(), null,
                slot.key() + " must be an exactly representable finite double");
    }

    static List<Arguments> floatAcceptCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : floatSlots()) {
            for (String literal : List.of("0", "0.1", "1.4E-45", "3.4028235E38")) {
                cases.add(Arguments.of(slot, literal,
                        Float.toString(new BigDecimal(literal).floatValue())));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 接受可精确往返的 float 字面量 {1}")
    @Tag("small-heap")
    @MethodSource("floatAcceptCases")
    void floatComponentsAcceptExactlyRepresentableLiterals(ScalarSlot slot, String literal,
            String expected) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertEquals(expected, scalarText(assertDecodedAndReencodes(text), slot),
                slot.pointer() + " 的运行期值必须是 " + expected);
    }

    static List<Arguments> floatRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (ScalarSlot slot : floatSlots()) {
            for (String literal : List.of("0.10000000000000001", "1E-50", "3.5E+38",
                    "1E+1000000", FIFTY_DIGITS)) {
                cases.add(Arguments.of(slot, literal));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 拒绝不可精确往返或无穷的 float 字面量 {1}")
    @Tag("small-heap")
    @MethodSource("floatRejectCases")
    void floatComponentsRejectInexactOrInfiniteLiterals(ScalarSlot slot, String literal) {
        final String text = wireWithLiteral(slot.pointer(), literal);
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, slot.pointer(), null,
                slot.key() + " must be an exactly representable finite float");
    }

    // ------------------------------------------------------ schemaVersion 的唯一合法取值

    static List<Arguments> schemaVersionRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String literal : List.of("0", "-1", "2", "2147483647")) {
            cases.add(Arguments.of(literal, SchemaException.CODE_UNSUPPORTED_SCHEMA, null,
                    "schemaVersion must be exactly 1 but was "
                            + new BigDecimal(literal).intValueExact()));
        }
        for (String literal : List.of("1.5", "2147483648", "-2147483649")) {
            cases.add(Arguments.of(literal, SchemaException.CODE_INVALID_VALUE,
                    ArithmeticException.class,
                    "schemaVersion must be a mathematical integer inside the int range: "
                            + jdkExactFailure(literal, true)));
        }
        return cases;
    }

    @ParameterizedTest(name = "schemaVersion={0} 被拒为 {1}")
    @Tag("small-heap")
    @MethodSource("schemaVersionRejectCases")
    void schemaVersionMustBeExactlyOne(String literal, String code,
            Class<? extends Throwable> causeType, String message) {
        final String text = wireWithLiteral("/schemaVersion", literal);
        assertBothEntriesWithMessage(text, code, "/schemaVersion", causeType, message);
    }

    static List<Arguments> schemaVersionEquivalentCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String literal : List.of("1", "1.0", "1.00", "1E0", "10E-1")) {
            cases.add(Arguments.of(literal));
        }
        return cases;
    }

    @ParameterizedTest(name = "schemaVersion 的数学整数写法 {0} 等同于 1")
    @Tag("small-heap")
    @MethodSource("schemaVersionEquivalentCases")
    void schemaVersionAcceptsEveryWritingOfTheMathematicalIntegerOne(String literal) {
        final String text = wireWithLiteral("/schemaVersion", literal);
        assertEquals(minimalDefinition(), assertDecodedAndReencodes(text),
                "canonical 化后 schemaVersion==1 的载荷必须解成最小定义：" + literal);
    }

    // ------------------------------------------------------ P02 数字上限与超长/巨大指数保真

    /** 两位数的指数上限由 P02 定：|exp| <= 1000000 合法，多 1 必须在解析期就被拒。 */
    static List<Arguments> exponentLimitCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String literal : List.of("1e1000001", "1e-1000001", "9E+2000000")) {
            cases.add(Arguments.of(literal));
        }
        return cases;
    }

    @ParameterizedTest(name = "指数越界字面量 {0} 在解析期被 P02 拒绝，decode(String) 按契约包装同一失败")
    @Tag("small-heap")
    @MethodSource("exponentLimitCases")
    void exponentBeyondTheLimitIsRejectedByParsingAndPropagatedUnchanged(String literal) {
        final String text = wireWithLiteral("/spawns/0/position/x", literal);
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(text), "P02 必须拒绝该指数：" + literal);
        final SchemaException fromCodec = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.decode(text),
                "decode(String) 必须在认证阶段就拒绝：" + literal);
        assertEquals(oracle.code(), fromCodec.code(), "认证失败的 code 必须被保留：" + literal);
        assertEquals(oracle.path(), fromCodec.path().pointer(),
                "认证失败的 path 必须被保留为同一指针：" + literal);
        assertEquals("payload text was rejected by the strict JSON certification: "
                + oracle.getMessage(), fromCodec.getMessage(), "包装消息必须只加固定前缀：" + literal);
        assertInstanceOf(JsonContractException.class, fromCodec.getCause(),
                "原始认证异常必须作为 cause 保留：" + literal);
        assertEquals(oracle.getMessage(), fromCodec.getCause().getMessage(), literal);
    }

    @ParameterizedTest(name = "指数在边界内的字面量 {0} 被 P02 接受，decode 按 double 规则拒绝")
    @Tag("small-heap")
    @MethodSource("exponentAtTheLimitCases")
    void exponentAtTheLimitIsAcceptedByParsingAndRejectedByTheDoubleRule(String literal) {
        final String text = wireWithLiteral("/spawns/0/position/x", literal);
        // 这一行本身就是断言：P02 若不接受该指数，这里会抛 JsonContractException。
        final ExactJson.FrozenJson payload = ExactJson.parse(text);
        assertTrue(payload.canonical().contains("\"position\""), "该载荷必须已被 P02 接受");
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE,
                "/spawns/0/position/x", null,
                "x must be an exactly representable finite double");
    }

    static List<Arguments> exponentAtTheLimitCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String literal : List.of("1E+1000000", "1e-1000000", "1E+400")) {
            cases.add(Arguments.of(literal));
        }
        return cases;
    }

    /** 任意形状成员必须逐字保留超长位数与巨大指数：期望值只来自 P02 自己的 canonical。 */
    static List<Arguments> rawLiteralFidelityCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (String pointer : List.of("/rules", "/extensions", "/teams/0/config")) {
            for (String literal : List.of(FIFTY_DIGITS, "1E+1000000",
                    "0.12345678901234567890123456789012345678901234567890")) {
                cases.add(Arguments.of(pointer, literal));
            }
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 逐字保留原始数字字面量 {1}")
    @Tag("small-heap")
    @MethodSource("rawLiteralFidelityCases")
    void arbitraryShapeMembersKeepRawNumberLiteralsVerbatim(String pointer, String literal) {
        final String text = wireWithLiteral(pointer, literal);
        final ExactJson.FrozenJson expected = ExactJson.parse("{\"v\":" + literal + "}");
        final JsonElement value = expected.toJson().getAsJsonObject().get("v");
        final MapDefinition fromText = assertDecodedAndReencodes(text);
        assertEquals(frozen(value), readAnySlot(fromText, anySlotNameOf(pointer)),
                pointer + " 必须逐字冻住 " + literal);
        assertEquals(frozen(value),
                readAnySlot(MapDefinitionCodec.decode(ExactJson.parse(text)), anySlotNameOf(pointer)),
                pointer + " 的两个入口必须冻出同一子树：" + literal);
    }

    private static String anySlotNameOf(String pointer) {
        return switch (pointer) {
            case "/rules" -> "rules";
            case "/extensions" -> "extensions";
            case "/teams/0/config" -> "team config";
            default -> throw new IllegalStateException("未知任意形状槽：" + pointer);
        };
    }


    // ------------------------------------------------------------------ 名称/描述/digest/revision 的文本规则

    /** 一个名称成员：绝对指针 + 该 owner 是否禁止空串。 */
    record NameMember(String pointer, boolean nonEmpty) {
    }

    private static final List<NameMember> NAME_MEMBERS = List.of(
            new NameMember("/displayName", false),
            new NameMember("/teams/0/name", true),
            new NameMember("/spawns/0/name", false),
            new NameMember("/regions/0/name", false));

    /** 名称字节预算与描述字节预算的报错后缀：越界的下标就是把它推到超预算的那一位。 */
    private static final String NAME_BUDGET_SUFFIX =
            "name must be at most 512 UTF-8 bytes, but exceeded the budget at index ";
    private static final String DESCRIPTION_BUDGET_SUFFIX =
            "description must be at most 8192 UTF-8 bytes, but exceeded the budget at index ";

    /** 按 pointer 读回任何一个本文件写入的文本成员。 */
    private static String textAt(MapDefinition definition, String pointer) {
        return switch (pointer) {
            case "/displayName" -> definition.displayName();
            case "/description" -> definition.description();
            case "/teams/0/name" -> definition.teams().get(0).name();
            case "/spawns/0/name" -> definition.spawns().get(0).name();
            case "/regions/0/name" -> definition.regions().get(0).name();
            case "/modeId" -> definition.modeId();
            case "/dimensionId" -> definition.dimensionId();
            case "/spawns/0/dimensionId" -> definition.spawns().get(0).dimensionId();
            case "/regions/0/kindId" -> definition.regions().get(0).kindId();
            case "/resources/0/kindId" -> definition.resources().get(0).kindId();
            case "/resources/0/digest" -> definition.resources().get(0).digest();
            default -> throw new IllegalStateException("未知文本成员：" + pointer);
        };
    }

    /** 期望的拒绝消息后缀；null 表示该形状必须被接受。 */
    static List<Arguments> nameBudgetCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (NameMember member : NAME_MEMBERS) {
            cases.add(Arguments.of(member, "a".repeat(512), null));
            cases.add(Arguments.of(member, "\u20ac".repeat(170) + "ab", null));
            cases.add(Arguments.of(member, "a".repeat(513), NAME_BUDGET_SUFFIX + "512"));
            cases.add(Arguments.of(member, "\u20ac".repeat(171), NAME_BUDGET_SUFFIX + "170"));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 的名称字节预算在真正的越界位上产生错误")
    @Tag("small-heap")
    @MethodSource("nameBudgetCases")
    void nameByteBudgetIsCheckedAtTheExactOffendingIndex(NameMember member, String value,
            String expectedSuffix) {
        final String text = wire(withNode(member.pointer(), str(value)));
        if (expectedSuffix == null) {
            final MapDefinition decoded = assertDecodedAndReencodes(text);
            assertEquals(value, textAt(decoded, member.pointer()), "String 入口读回的名称");
            assertEquals(value, textAt(MapDefinitionCodec.decode(ExactJson.parse(text)), member.pointer()),
                    "Frozen 入口读回的名称");
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, member.pointer(),
                IllegalArgumentException.class, REJECTED_PREFIX + expectedSuffix);
    }

    /** 空串只在 team name 上被拒绝，其余三个名称 owner 接受空串。 */
    static List<Arguments> emptyNameCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (NameMember member : NAME_MEMBERS) {
            cases.add(Arguments.of(member, member.nonEmpty() ? "name must not be empty" : null));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 的空串规则")
    @Tag("small-heap")
    @MethodSource("emptyNameCases")
    void emptyNameIsRejectedOnlyForTheTeamName(NameMember member, String expectedInner) {
        final String text = wire(withNode(member.pointer(), str("")));
        if (expectedInner == null) {
            assertEquals("", textAt(assertDecodedAndReencodes(text), member.pointer()));
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, member.pointer(),
                IllegalArgumentException.class, REJECTED_PREFIX + expectedInner);
    }

    /** 控制字符与合法配对代理项：C0 在名称里被拒、在描述里被接受，D83D DE00 始终合法。 */
    static List<Arguments> nameCharacterCases() {
        return List.of(
                Arguments.of("/teams/0/name", "a\u0001b",
                        "name must not contain control character U+0001 at index 1"),
                Arguments.of("/teams/0/name", "a\u001fb",
                        "name must not contain control character U+001F at index 1"),
                Arguments.of("/teams/0/name", "a\u007fb", null),
                Arguments.of("/teams/0/name", "\ud83d\ude00", null),
                Arguments.of("/description", "a\u0001b", null),
                Arguments.of("/description", "a\u007fb\u001f", null));
    }

    @ParameterizedTest(name = "{0} 的字符规则 {1}")
    @Tag("small-heap")
    @MethodSource("nameCharacterCases")
    void controlCharactersAreRejectedForNamesButAllowedForDescriptions(String pointer, String value,
            String expectedInner) {
        final String text = wire(withNode(pointer, str(value)));
        if (expectedInner == null) {
            assertEquals(value, textAt(assertDecodedAndReencodes(text), pointer));
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, pointer,
                IllegalArgumentException.class, REJECTED_PREFIX + expectedInner);
    }

    /**
     * 把一个成员写成给定的原始 JSON 字符串 token（含两侧引号与转义）：本文件的写入器不会产生
     * 落单代理项的转义，只能把该 token 直接交给 P02 认证。
     */
    private static String wireWithStringToken(String pointer, String token) {
        final String placeholder = "\"__RAW_STRING__\"";
        final String text = wire(withNode(pointer, str("__RAW_STRING__")));
        final int first = text.indexOf(placeholder);
        assertTrue(first >= 0, "占位符必须出现在 wire 里：" + pointer);
        assertEquals(first, text.lastIndexOf(placeholder), "占位符必须唯一：" + pointer);
        return text.replace(placeholder, token);
    }

    /**
     * 落单代理项：P02 认证本身先拒绝，wire 文本根本进不到任何 typed 规则，也不存在可认证的
     * Frozen 入口；因此期望值只能取自 {@link ExactJson#parse(String)} 的原异常对象。
     */
    static List<Arguments> unpairedSurrogateCases() {
        return List.of(
                Arguments.of("/teams/0/name", "\"\\ud800\"",
                        "unpaired high surrogate at the end of string value"),
                Arguments.of("/teams/0/name", "\"x\\udc00\"",
                        "unpaired low surrogate in string value"),
                Arguments.of("/description", "\"\\udfff\"",
                        "unpaired low surrogate in string value"));
    }

    @ParameterizedTest(name = "{0} 的落单代理项 {1}")
    @Tag("small-heap")
    @MethodSource("unpairedSurrogateCases")
    void unpairedSurrogatesAreRejectedByCertificationBeforeAnyTypedRule(String pointer, String token,
            String expectedCertificationMessage) {
        final String text = wireWithStringToken(pointer, token);
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(text), "P02 必须拒绝落单代理项：" + token);
        assertEquals(expectedCertificationMessage, oracle.getMessage(), "P02 的拒绝消息");
        final SchemaException fromCodec = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.decode(text),
                "decode(String) 必须在认证阶段就拒绝：" + token);
        assertEquals(oracle.code(), fromCodec.code(), "认证失败的 code 必须被保留：" + token);
        assertEquals(oracle.path(), fromCodec.path().pointer(),
                "认证失败的 path 必须被保留为同一指针：" + token);
        assertEquals("payload text was rejected by the strict JSON certification: "
                + oracle.getMessage(), fromCodec.getMessage(), "包装消息必须只加固定前缀：" + token);
        assertInstanceOf(JsonContractException.class, fromCodec.getCause(),
                "原始认证异常必须作为 cause 保留：" + token);
        assertEquals(oracle.getMessage(), fromCodec.getCause().getMessage(), token);
    }

    /** 描述字节预算：8192 字节恰好通过，超出的那一位必须被指名。 */
    static List<Arguments> descriptionBudgetCases() {
        return List.of(
                Arguments.of("", null),
                Arguments.of("a".repeat(8192), null),
                Arguments.of("\u20ac".repeat(2730) + "ab", null),
                Arguments.of("a".repeat(8193), DESCRIPTION_BUDGET_SUFFIX + "8192"),
                Arguments.of("\u20ac".repeat(2731), DESCRIPTION_BUDGET_SUFFIX + "2730"));
    }

    @ParameterizedTest(name = "描述字节预算用例 {index}")
    @Tag("small-heap")
    @MethodSource("descriptionBudgetCases")
    void descriptionByteBudgetIsCheckedAtTheExactOffendingIndex(String value, String expectedInner) {
        final String text = wire(withNode("/description", str(value)));
        if (expectedInner == null) {
            assertEquals(value, textAt(assertDecodedAndReencodes(text), "/description"));
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, "/description",
                IllegalArgumentException.class, REJECTED_PREFIX + expectedInner);
    }

    @Test
    @Tag("small-heap")
    @DisplayName("合法 digest 与 64 位 revision 上界都能原样解出")
    void legalDigestAndRevisionBoundaryAreAccepted() {
        final String text = wire(withNode("/resources/0/revision", num(Long.MAX_VALUE)));
        final MapDefinition decoded = assertDecodedAndReencodes(text);
        assertEquals(DIGEST, textAt(decoded, "/resources/0/digest"));
        assertEquals(Long.MAX_VALUE, decoded.resources().get(0).revision());
        assertEquals(Long.MAX_VALUE,
                MapDefinitionCodec.decode(ExactJson.parse(text)).resources().get(0).revision());
    }

    // ------------------------------------------------------------------ namespaced 标量

    /** 一个 namespaced 站点：绝对指针 + 该调用点的 what 词。 */
    record NamespacedSite(String pointer, String what) {
    }

    private static final List<NamespacedSite> NAMESPACED_SITES = List.of(
            new NamespacedSite("/modeId", "namespaced value"),
            new NamespacedSite("/dimensionId", "dimensionId"),
            new NamespacedSite("/spawns/0/dimensionId", "dimensionId"),
            new NamespacedSite("/regions/0/kindId", "namespaced value"),
            new NamespacedSite("/resources/0/kindId", "namespaced value"));

    /** 只改写目标站点：拒绝用例的错误必须停在目标站点自己的路径上。 */
    private static String wireWithNamespacedSite(String pointer, String value) {
        return wire(withNode(pointer, str(value)));
    }

    /** 改写 dimensionId 时两处必须同步，否则会先撞上跨成员的 dimension 一致规则。 */
    private static String wireWithDimensionPair(String value) {
        final JsonObject root = minimalRoot();
        withNode(root, "/dimensionId", str(value));
        withNode(root, "/spawns/0/dimensionId", str(value));
        return wire(root);
    }

    static List<Arguments> namespacedRejectCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (NamespacedSite site : NAMESPACED_SITES) {
            cases.add(Arguments.of(site, "", " must not be empty"));
            cases.add(Arguments.of(site, "noseparator",
                    " must be explicit 'namespace:path'; the namespace is never defaulted"));
            cases.add(Arguments.of(site, "a:b:c", " must contain exactly one ':' separator"));
            cases.add(Arguments.of(site, ":b", " namespace must not be empty"));
            cases.add(Arguments.of(site, "a:", " path must not be empty"));
            cases.add(Arguments.of(site, "A:b", " namespace must match [a-z0-9_.-]+ at index 0"));
            cases.add(Arguments.of(site, "a:B", " path must match [a-z0-9_./-]+ at index 2"));
            cases.add(Arguments.of(site, "a:\u00e9", " path must match [a-z0-9_./-]+ at index 2"));
            cases.add(Arguments.of(site, "a".repeat(90) + ":" + "b".repeat(101),
                    " must be at most 191 ASCII characters, got 192"));
        }
        return cases;
    }

    static List<Arguments> namespacedAcceptCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (NamespacedSite site : NAMESPACED_SITES) {
            cases.add(Arguments.of(site, "a".repeat(90) + ":" + "b".repeat(100)));
            cases.add(Arguments.of(site, "fpsmatch:model"));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 上的非法 namespaced 形状 {1}")
    @Tag("small-heap")
    @MethodSource("namespacedRejectCases")
    void everyIllegalNamespacedShapeIsRejectedAtItsOwnSite(NamespacedSite site, String value,
            String suffix) {
        assertBothEntriesWithMessage(wireWithNamespacedSite(site.pointer(), value),
                SchemaException.CODE_INVALID_VALUE, site.pointer(), IllegalArgumentException.class,
                REJECTED_PREFIX + site.what() + suffix);
    }

    @ParameterizedTest(name = "{0} 接受合法 namespaced 形状 {1}")
    @Tag("small-heap")
    @MethodSource("namespacedAcceptCases")
    void legalNamespacedValuesAreAcceptedAtEverySite(NamespacedSite site, String value) {
        final String text = site.pointer().endsWith("dimensionId")
                ? wireWithDimensionPair(value)
                : wireWithNamespacedSite(site.pointer(), value);
        assertEquals(value, textAt(assertDecodedAndReencodes(text), site.pointer()));
        assertEquals(value,
                textAt(MapDefinitionCodec.decode(ExactJson.parse(text)), site.pointer()));
    }

    // ------------------------------------------------------------------ digest 与 revision

    static List<Arguments> digestRejectCases() {
        return List.of(
                Arguments.of(DIGEST.substring(0, 63),
                        " must be exactly 64 lower case hex characters, got 63"),
                Arguments.of(DIGEST + "f",
                        " must be exactly 64 lower case hex characters, got 65"),
                Arguments.of("", " must be exactly 64 lower case hex characters, got 0"),
                Arguments.of("A" + DIGEST.substring(1),
                        " must match [0-9a-f] but has 'A' (U+0041) at index 0"),
                Arguments.of(DIGEST.substring(0, 5) + "g" + DIGEST.substring(6),
                        " must match [0-9a-f] but has 'g' (U+0067) at index 5"));
    }

    @ParameterizedTest(name = "digest 拒绝 {index}")
    @Tag("small-heap")
    @MethodSource("digestRejectCases")
    void illegalDigestsAreRejectedWithTheirOffendingIndex(String value, String suffix) {
        assertBothEntriesWithMessage(wire(withNode("/resources/0/digest", str(value))),
                SchemaException.CODE_INVALID_VALUE, "/resources/0/digest",
                IllegalArgumentException.class,
                REJECTED_PREFIX + "ResourceRevisionRef.digest" + suffix);
    }

    static List<Arguments> revisionAcceptCases() {
        return List.of(
                Arguments.of("1", 1L),
                Arguments.of("1.0", 1L),
                Arguments.of("9223372036854775807", Long.MAX_VALUE));
    }

    /** positiveRule=true 表示越过了 JDK 转换、由正数规则拒绝；false 表示 JDK 转换本身就失败。 */
    static List<Arguments> revisionRejectCases() {
        return List.of(
                Arguments.of("0", true,
                        "ResourceRevisionRef.revision must be > 0 but was 0"),
                Arguments.of("0.0", true,
                        "ResourceRevisionRef.revision must be > 0 but was 0"),
                Arguments.of("-1", true,
                        "ResourceRevisionRef.revision must be > 0 but was -1"),
                Arguments.of("9223372036854775808", false, null),
                Arguments.of("-9223372036854775809", false, null),
                Arguments.of("1.5", false, null),
                Arguments.of(FIFTY_DIGITS, false, null));
    }

    @ParameterizedTest(name = "revision 接受 {0}")
    @Tag("small-heap")
    @MethodSource("revisionAcceptCases")
    void revisionAcceptsEveryPositiveMathematicalIntegerInTheLongRange(String literal, long expected) {
        final String text = wireWithLiteral("/resources/0/revision", literal);
        assertEquals(expected, assertDecodedAndReencodes(text).resources().get(0).revision());
        assertEquals(expected,
                MapDefinitionCodec.decode(ExactJson.parse(text)).resources().get(0).revision());
    }

    @ParameterizedTest(name = "revision 拒绝 {0}")
    @Tag("small-heap")
    @MethodSource("revisionRejectCases")
    void revisionRejectsZeroNegativeAndNonIntegralLiterals(String literal, boolean positiveRule,
            String expectedInner) {
        final String text = wireWithLiteral("/resources/0/revision", literal);
        final String message = positiveRule
                ? REJECTED_PREFIX + expectedInner
                : "revision must be a mathematical integer inside the long range: "
                        + jdkExactFailure(literal, false);
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE,
                "/resources/0/revision",
                positiveRule ? IllegalArgumentException.class : ArithmeticException.class, message);
    }

    // ------------------------------------------------------------------ 重复 id / 悬空引用 / 跨 dimension

    /** 一条引用规则用例：名字 + 目标 wire 的根 + 期望的路径与完整消息。 */
    record ReferenceCase(String name, JsonObject root, String pointer, String message) {
    }

    /** 用给定成员替换根上的一个数组。 */
    private static JsonObject rootWithList(String key, JsonElement... items) {
        final JsonObject root = minimalRoot();
        final JsonArray array = new JsonArray();
        for (JsonElement item : items) {
            array.add(item);
        }
        root.add(key, array);
        return root;
    }

    private static JsonObject anyRegion(String id) {
        return region(id, "", REGION_KIND, box(tri(0, 0, 0), tri(1, 1, 1)), obj(), obj());
    }

    static List<ReferenceCase> referenceCases() {
        final JsonObject root = minimalRoot();
        final JsonObject team0 = root.getAsJsonArray("teams").get(0).getAsJsonObject();
        final JsonObject spawn0 = root.getAsJsonArray("spawns").get(0).getAsJsonObject();
        return List.of(
                new ReferenceCase("重复的 team id", rootWithList("teams", copy(team0), copy(team0)),
                        "/teams/1/id", "a team id must not repeat an earlier team id"),
                new ReferenceCase("重复的 spawn id", rootWithList("spawns", copy(spawn0), copy(spawn0)),
                        "/spawns/1/id", "an object id must not repeat an earlier spawn or region id"),
                new ReferenceCase("region 复用了 spawn 的 id",
                        rootWithList("regions", anyRegion(SPAWN_ONE)),
                        "/regions/0/id", "an object id must not repeat an earlier spawn or region id"),
                new ReferenceCase("后一个 region 复用了前一个 region 的 id",
                        rootWithList("regions", anyRegion(REGION_ONE), anyRegion(REGION_ONE)),
                        "/regions/1/id", "an object id must not repeat an earlier spawn or region id"),
                new ReferenceCase("悬空的 spawn teamId",
                        withNode(minimalRoot(), "/spawns/0/teamId",
                                str("99999999-9999-4999-8999-999999999999")),
                        "/spawns/0/teamId", "a spawn teamId must name a team of this same definition"),
                new ReferenceCase("spawn 的 dimension 与本定义不一致",
                        withNode(minimalRoot(), "/spawns/0/dimensionId", str("minecraft:nether")),
                        "/spawns/0/dimensionId",
                        "a spawn dimensionId must equal the definition dimensionId"));
    }

    @ParameterizedTest(name = "{0}")
    @Tag("small-heap")
    @MethodSource("referenceCases")
    void duplicateAndDanglingReferencesAreReportedAtTheRealPointer(ReferenceCase reference) {
        assertBothEntriesWithMessage(wire(reference.root()), SchemaException.CODE_INVALID_VALUE,
                reference.pointer(), null, reference.message());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("合法多对象 payload 的唯一 id、team 归属与 dimension 一致全部通过")
    void legalMultiObjectReferencesAreAccepted() {
        final JsonObject root = minimalRoot();
        root.getAsJsonArray("teams").add(team(uuidAt(901), "Blue", obj(), obj()));
        root.getAsJsonArray("spawns").add(spawn(uuidAt(902), "s2", TEAM_A, DIMENSION,
                dec3("1", "2", "3"), look("0", "0"), JsonNull.INSTANCE));
        root.getAsJsonArray("spawns").add(spawn(uuidAt(903), "s3", uuidAt(901), DIMENSION,
                dec3("-1", "0", "0.5"), look("90", "0"), obj()));
        root.getAsJsonArray("regions").add(anyRegion(uuidAt(904)));
        final MapDefinition decoded = assertDecodedAndReencodes(wire(root));
        assertEquals(2, decoded.teams().size());
        assertEquals(3, decoded.spawns().size());
        assertEquals(2, decoded.regions().size());
        assertEquals(UUID.fromString(uuidAt(901)), decoded.teams().get(1).id().value());
        assertEquals(UUID.fromString(uuidAt(901)), decoded.spawns().get(2).teamId().value());
        assertEquals(DIMENSION, decoded.spawns().get(2).dimensionId());
        assertEquals(UUID.fromString(uuidAt(904)), decoded.regions().get(1).id().value());
    }

    // ------------------------------------------------------------------ 元素个数上限

    /** 第 index 个互异的小写十六进制 UUID：组内只用十进制数字，保证形状与大小写都合法。 */
    private static String uuidAt(int index) {
        return String.format(java.util.Locale.ROOT, "00000000-0000-4000-8000-%012d", index);
    }

    private static JsonObject rootWithItems(String key, int size) {
        final JsonArray array = new JsonArray();
        for (int i = 0; i < size; i++) {
            array.add(switch (key) {
                case "teams" -> i == 0
                        ? team(TEAM_A, "Red", obj(), JsonNull.INSTANCE)
                        : team(uuidAt(i), "t" + i, obj(), obj());
                case "regions" -> anyRegion(uuidAt(i));
                case "resources" -> resource(RESOURCE_KIND, uuidAt(i), (long) i + 1L, DIGEST);
                default -> throw new IllegalStateException("未知数组：" + key);
            });
        }
        final JsonObject root = minimalRoot();
        root.add(key, array);
        return root;
    }

    static List<Arguments> countLimitCases() {
        final List<Arguments> cases = new ArrayList<>();
        for (int size : List.of(64, 65)) {
            cases.add(Arguments.of("teams", size));
        }
        for (int size : List.of(1024, 1025)) {
            cases.add(Arguments.of("regions", size));
            cases.add(Arguments.of("resources", size));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0} 持有 {1} 个元素")
    @Tag("small-heap")
    @MethodSource("countLimitCases")
    void elementCountLimitsAreEnforcedAtTheOwnerPath(String key, int size) {
        final String text = wire(rootWithItems(key, size));
        final int max = switch (key) {
            case "teams" -> 64;
            case "regions", "resources" -> 1024;
            default -> throw new IllegalStateException("未知数组：" + key);
        };
        if (size <= max) {
            final MapDefinition decoded = assertDecodedAndReencodes(text);
            assertEquals(size, switch (key) {
                case "teams" -> decoded.teams().size();
                case "regions" -> decoded.regions().size();
                case "resources" -> decoded.resources().size();
                default -> throw new IllegalStateException("未知数组：" + key);
            });
            assertEquals(size, switch (key) {
                case "teams" -> MapDefinitionCodec.decode(ExactJson.parse(text)).teams().size();
                case "regions" -> MapDefinitionCodec.decode(ExactJson.parse(text)).regions().size();
                case "resources" -> MapDefinitionCodec.decode(ExactJson.parse(text)).resources().size();
                default -> throw new IllegalStateException("未知数组：" + key);
            });
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, "/" + key, null,
                key + " must hold at most " + max + " items but holds " + size);
    }

    // ------------------------------------------------------------------ 重复解码的隔离性

    /** 一场解码里的十三个未知成员槽，顺序固定。 */
    private static List<ExactJson.FrozenJson> thirteenExtras(MapDefinition decoded) {
        final WireExtras.MapExtras map = decoded.wireExtras();
        final TeamDefinition team = decoded.teams().get(0);
        final SpawnDefinition spawn = decoded.spawns().get(0);
        final RegionDefinition region = decoded.regions().get(0);
        return List.of(map.members(), map.boundaryMembers(), map.boundaryFirstMembers(),
                map.boundarySecondMembers(), team.wireExtras().members(), spawn.wireExtras().members(),
                spawn.wireExtras().positionMembers(), spawn.wireExtras().orientationMembers(),
                region.wireExtras().members(), region.wireExtras().cornersMembers(),
                region.wireExtras().cornersFirstMembers(), region.wireExtras().cornersSecondMembers(),
                decoded.resources().get(0).wireExtras().members());
    }

    @Test
    @Tag("small-heap")
    @DisplayName("重复解码互不共享容器，且污染交出的入参树不影响后续解码")
    void repeatedDecodesShareNothingAndAreNotPolluted() {
        final ExactJson.FrozenJson payload = ExactJson.parse(MINIMAL_WIRE);
        final MapDefinition first = MapDefinitionCodec.decode(payload);
        final MapDefinition second = MapDefinitionCodec.decode(payload);
        assertNotSame(first, second, "两次解码必须给出不同实例");
        assertEquals(first, second, "两次解码必须给出相等模型");
        assertEquals(MINIMAL_WIRE, MapDefinitionCodec.encode(first).canonical());
        assertEquals(MINIMAL_WIRE, MapDefinitionCodec.encode(second).canonical());
        final List<ExactJson.FrozenJson> slots = thirteenExtras(first);
        assertEquals(13, slots.size(), "未知成员槽必须是十三个");
        for (ExactJson.FrozenJson slot : slots) {
            assertSame(slots.get(0), slot, "同一场内十三个空槽必须复用同一个冻结 {}");
        }
        assertNotSame(first.wireExtras().members(), second.wireExtras().members(),
                "跨场解码不得共享冻结实例");
        final JsonElement handed = payload.toJson();
        handed.getAsJsonObject().add("teams", new JsonArray());
        handed.getAsJsonObject().addProperty("displayName", "被污染的名字");
        assertEquals(first, MapDefinitionCodec.decode(payload), "污染交出的树不得影响后续解码");
        assertEquals(MINIMAL_WIRE, payload.canonical(), "Frozen 自身必须保持不变");
    }

    // ------------------------------------------------------------------ 大堆：预算上界与固定语料

    /** 固定种子语料的条数：逐条只由下标决定，任何两条都不相同。 */
    private static final int CORPUS_SIZE = 1000;

    /**
     * 1000 条语料按 index 顺序拼接后的 UTF-8 总字节数与 SHA-256。
     *
     * <p>这两个值由 {@code freeze-corpus.py}（python 侧独立重实现同一 wire 文本规则）先算好并冻结，
     * 测试只用 JDK 的 {@code getBytes(UTF_8)} 与 {@code MessageDigest} 复算，绝不由生产 encode 反推。
     */
    private static final int CORPUS_UTF8_BYTES = 1112450;
    private static final String CORPUS_SHA256 =
            "76fded9764a337e243ee67ca196e4491404b691c90715f7507337507c975de5e";
    private static final int CORPUS_FIRST_UTF8_BYTES = 1076;
    private static final String CORPUS_FIRST_SHA256 =
            "a32d7f71af5ad0e3bf48e9fad000d9278d3297388649ac5376b8d7bca2f088b5";
    private static final int CORPUS_LAST_UTF8_BYTES = 1114;
    private static final String CORPUS_LAST_SHA256 =
            "2cd1e425a470b8bd3159afe2c42187c7b72d24cdf2eb203f7a3786c3e7e6fe85";

    /**
     * 第 index 条语料使用的整数：末位恒为 1，因此 P02 的 canonical 拼写就是最短十进制直写本身。
     *
     * <p>若直接使用 10、100 这类值，canonical 拼写会变成 {@code 1E+1}、{@code 1E+2}，
     * 语料文本就不再与重编码结果逐字相同；这条约束由 python 侧同规则自检。</p>
     */
    private static int corpusNumber(int index) {
        return index * 10 + 1;
    }

    /** 与 python 侧逐字对应的第 index 条语料：全部成员合法，全部差异只来自下标。 */
    private static JsonObject corpusRoot(int index) {
        final String teamId = uuidAt(index + 1 + 200000);
        final int number = corpusNumber(index);
        return obj(
                "schemaVersion", num(1),
                "id", str(uuidAt(index + 1)),
                "worldId", str(uuidAt(index + 1 + 100000)),
                "modeId", str("fpsmatch:corpus"),
                "dimensionId", str(DIMENSION),
                "displayName", str("地图-" + index),
                "description", str("第 " + index + " 张图"),
                "boundary", box(tri(-number, 0, number), tri(number, 64, -number)),
                "teams", arr(team(teamId, "Red", obj("score", num(number)), JsonNull.INSTANCE)),
                "spawns", arr(spawn(uuidAt(index + 1 + 300000), "s" + index, teamId, DIMENSION,
                        dec3(Integer.toString(number), "0", "-1"), look("0.5", "0"),
                        JsonNull.INSTANCE)),
                "regions", arr(region(uuidAt(index + 1 + 400000), "r" + index, REGION_KIND,
                        box(tri(0, 0, 0), tri(number, 1, 1)), obj(), JsonNull.INSTANCE)),
                "resources", arr(resource(RESOURCE_KIND, uuidAt(index + 1 + 500000),
                        number, corpusDigest(index))),
                "rules", obj("limit", num(number)),
                "extensions", obj("seed", num(number)));
    }

    /** 64 位小写十六进制摘要：只由下标决定，逐条互异。 */
    private static String corpusDigest(int index) {
        return String.format(java.util.Locale.ROOT, "%064x",
                Integer.valueOf(corpusNumber(index)));
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 独立清点整棵 JSON 树的值节点数（对象、数组与标量，含根），不经过任何生产代码。 */
    private static int countNodes(JsonElement element) {
        if (element.isJsonObject()) {
            int total = 1;
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                total += countNodes(member.getValue());
            }
            return total;
        }
        if (element.isJsonArray()) {
            int total = 1;
            for (JsonElement item : element.getAsJsonArray()) {
                total += countNodes(item);
            }
            return total;
        }
        return 1;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("JDK 必须提供 SHA-256", failure);
        }
    }

    private static String hexOf(byte[] hash) {
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte one : hash) {
            hex.append(String.format(java.util.Locale.ROOT, "%02x", Integer.valueOf(one & 0xFF)));
        }
        return hex.toString();
    }

    /** 认证层预算拒绝：String 入口必须按契约包装同一失败，并原样保留 code/path 与原始异常。 */
    private static void assertCertificationRejects(String text, String expectedMessage) {
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(text), "认证层必须拒绝该文本：" + expectedMessage);
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, oracle.code(),
                "该拒绝必须来自文档化预算：" + expectedMessage);
        assertEquals(expectedMessage, oracle.getMessage(), "认证层的消息必须逐字给定");
        final SchemaException fromCodec = rejectedByText(text);
        assertEquals(oracle.code(), fromCodec.code(), "认证失败的 code 必须被保留");
        assertEquals(oracle.path(), fromCodec.path().pointer(), "认证失败的 path 必须被保留");
        assertEquals("payload text was rejected by the strict JSON certification: "
                + oracle.getMessage(), fromCodec.getMessage(), "包装消息必须只加固定前缀");
        assertInstanceOf(JsonContractException.class, fromCodec.getCause(),
                "原始认证异常必须作为 cause 保留");
    }

    /** 给定 spawn 条数的合法 wire 根：只有一支 team，全部 spawn 指向它并共用同一个 dimension。 */
    private static JsonObject rootWithSpawns(int size) {
        final JsonArray spawns = new JsonArray();
        for (int index = 0; index < size; index++) {
            spawns.add(spawn(index == 0 ? SPAWN_ONE : uuidAt(index), "s" + index, TEAM_A, DIMENSION,
                    dec3("0", "0", "0"), look("0", "0"), JsonNull.INSTANCE));
        }
        final JsonObject root = minimalRoot();
        root.add("spawns", spawns);
        return root;
    }

    /** 围绕一个标量套 depth 层数组，用来精确测定认证层的嵌套深度上界。 */
    private static JsonElement nestedArrays(int depth) {
        JsonElement value = num(0);
        for (int level = 0; level < depth; level++) {
            value = arr(value);
        }
        return value;
    }

    static List<Arguments> spawnCountCases() {
        return List.of(Arguments.of(Integer.valueOf(16384), Boolean.TRUE),
                Arguments.of(Integer.valueOf(16385), Boolean.FALSE));
    }

    @ParameterizedTest(name = "spawns 恰有 {0} 条")
    @Tag("large-heap")
    @MethodSource("spawnCountCases")
    void spawnCountBoundaryIsEnforcedAtTheOwnerPath(int size, boolean accepted) {
        final JsonObject root = rootWithSpawns(size);
        assertTrue(countNodes(root) < ExactJson.MAX_NODES,
                "该 fixture 的节点数必须留在预算内：" + size);
        final String text = wire(root);
        assertTrue(utf8Length(text) < ExactJson.MAX_UTF8_BYTES,
                "该 fixture 的字节数必须留在预算内：" + size);
        if (accepted) {
            final MapDefinition decoded = MapDefinitionCodec.decode(text);
            assertEquals(16384, decoded.spawns().size(), "上界本身必须被接受");
            assertEquals(text, MapDefinitionCodec.encode(decoded).canonical(), "必须逐字重编码");
            return;
        }
        assertBothEntriesWithMessage(text, SchemaException.CODE_INVALID_VALUE, "/spawns", null,
                "spawns must hold at most 16384 items but holds 16385");
    }

    @Test
    @Tag("large-heap")
    @DisplayName("384 MiB 量级 payload 上三套 API 的编解码组合互相一致")
    void threeApiCombinationsAgreeOnAHeapSizedPayload() {
        final JsonObject root = rootWithSpawns(16384);
        assertTrue(countNodes(root) < ExactJson.MAX_NODES, "该 fixture 必须只受元素个数规则约束");
        final String text = wire(root);
        assertTrue(utf8Length(text) < ExactJson.MAX_UTF8_BYTES, "该 fixture 必须只受元素个数规则约束");
        final byte[] jdkBytes = text.getBytes(StandardCharsets.UTF_8);

        final MapDefinition fromText = MapDefinitionCodec.decode(text);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(ExactJson.parse(text));
        assertEquals(16384, fromText.spawns().size(), "两个入口都必须读到全部 spawn");
        assertEquals(fromText, fromFrozen, "decode(String) 与 decode(FrozenJson) 必须给出相等模型");
        assertEquals(SPAWN_ONE, fromText.spawns().get(0).id().value().toString(), "首条 id");
        assertEquals(uuidAt(16383), fromText.spawns().get(16383).id().value().toString(), "末条 id");
        assertEquals(TEAM_A, fromText.spawns().get(16383).teamId().value().toString(), "末条 team 引用");
        assertEquals(DIMENSION, fromText.spawns().get(8000).dimensionId(), "中段 dimension");

        final ExactJson.FrozenJson encoded = MapDefinitionCodec.encode(fromText);
        assertEquals(text, encoded.canonical(), "encode(decode(String)) 必须逐字还原");
        assertTrue(java.util.Arrays.equals(jdkBytes, encoded.canonicalUtf8()),
                "encode 结果的 UTF-8 必须与 JDK 逐字节相同");
        assertEquals(jdkBytes.length, encoded.utf8Size(), "encode 结果的字节数必须与 JDK 相同");
        assertEquals(sha256(text), sha256(encoded.canonical()), "encode 结果的摘要必须与 JDK 相同");

        assertEquals(fromText, MapDefinitionCodec.decode(encoded), "encode → Frozen → decode");
        assertEquals(text, MapDefinitionCodec.encode(MapDefinitionCodec.decode(encoded)).canonical(),
                "encode → Frozen → decode → encode 必须回到同一文本");
        assertEquals(fromText, MapDefinitionCodec.decode(MapDefinitionCodec.encode(fromFrozen).canonical()),
                "encode → String → decode");
        assertEquals(text, MapDefinitionCodec.encode(fromFrozen).canonical(),
                "两个入口的 encode 结果必须逐字相同");

        // 没有未知成员时，十三个未知成员槽共用该次解码的空容器：16384 条 spawn 的三槽必须逐条全部
        // 核到同一引用，不允许只抽查首尾。两个入口各自持有自己的空容器，跨解码同引用不作要求。
        assertEmptyExtrasShareOneFrozen(fromText, 16384, "decode(String)");
        assertEmptyExtrasShareOneFrozen(fromFrozen, 16384, "decode(FrozenJson)");
    }

    /**
     * 一次解码里十三个未知成员槽必须复用同一个空 Frozen：map 根四槽、team/region/resource 的各槽，
     * 以及全部 {@code spawnCount} 条 spawn 的 own/position/orientation 三槽，都必须是同一引用。
     */
    private static void assertEmptyExtrasShareOneFrozen(MapDefinition decoded, int spawnCount,
            String entry) {
        final ExactJson.FrozenJson shared = decoded.wireExtras().members();
        assertEquals("{}", shared.canonical(), entry + "：空成员槽必须是空对象");
        final WireExtras.MapExtras map = decoded.wireExtras();
        assertSame(shared, map.boundaryMembers(), entry + "：boundary 空槽必须同引用");
        assertSame(shared, map.boundaryFirstMembers(), entry + "：boundary.first 空槽必须同引用");
        assertSame(shared, map.boundarySecondMembers(), entry + "：boundary.second 空槽必须同引用");
        assertSame(shared, decoded.teams().get(0).wireExtras().members(), entry + "：team 空槽必须同引用");
        final WireExtras.RegionExtras region = decoded.regions().get(0).wireExtras();
        assertSame(shared, region.members(), entry + "：region 空槽必须同引用");
        assertSame(shared, region.cornersMembers(), entry + "：region.corners 空槽必须同引用");
        assertSame(shared, region.cornersFirstMembers(), entry + "：corners.first 空槽必须同引用");
        assertSame(shared, region.cornersSecondMembers(), entry + "：corners.second 空槽必须同引用");
        assertSame(shared, decoded.resources().get(0).wireExtras().members(),
                entry + "：resource 空槽必须同引用");
        assertEquals(spawnCount, decoded.spawns().size(), entry + "：spawn 条数");
        for (int index = 0; index < spawnCount; index++) {
            final WireExtras.SpawnExtras extras = decoded.spawns().get(index).wireExtras();
            assertSame(shared, extras.members(), entry + "：第 " + index + " 条 spawn 的 own 空槽");
            assertSame(shared, extras.positionMembers(), entry + "：第 " + index + " 条 position 空槽");
            assertSame(shared, extras.orientationMembers(),
                    entry + "：第 " + index + " 条 orientation 空槽");
        }
    }

    @Test
    @Tag("large-heap")
    @DisplayName("超过 8 MiB 的输入文档与超过 8 MiB 的 canonical 输出分别在认证期被拒绝")
    void eightMebibyteBudgetsAreEnforcedByCertification() {
        final String chunk = "a".repeat(60000);
        final JsonArray huge = new JsonArray();
        for (int index = 0; index < 200; index++) {
            huge.add(str(chunk + index));
        }
        final JsonObject root = minimalRoot();
        root.add("rules", huge);
        assertTrue(countNodes(root) < ExactJson.MAX_NODES, "只有字节预算可以触发这次拒绝");

        final String text = wire(root);
        assertTrue(utf8Length(text) > ExactJson.MAX_UTF8_BYTES,
                "该文本必须真的越过 8 MiB 预算，实测 " + utf8Length(text));
        assertCertificationRejects(text, "document exceeds " + ExactJson.MAX_UTF8_BYTES
                + " UTF-8 bytes");

        final JsonContractException outputOracle = assertThrows(JsonContractException.class,
                () -> ExactJson.freeze(root),
                "同一棵树在被冻结成 canonical 文本时也必须因输出过大被拒绝");
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, outputOracle.code(),
                "canonical 输出预算拒绝必须是 LIMIT_EXCEEDED");
        assertEquals("canonical output exceeds " + ExactJson.MAX_UTF8_BYTES + " UTF-8 bytes",
                outputOracle.getMessage(), "canonical 输出预算消息必须逐字给定");
    }

    @Test
    @Tag("large-heap")
    @DisplayName("节点数恰为 500000 时被接受，恰为 500001 时在认证期被拒绝")
    void nodeBudgetIsExactlyFiveHundredThousand() {
        final int baseNodes = countNodes(minimalRoot());
        final JsonArray many = new JsonArray();
        for (int index = 0; index < ExactJson.MAX_NODES - baseNodes + 1; index++) {
            many.add(num(index % 10));
        }
        final JsonObject root = minimalRoot();
        root.add("rules", many);
        assertEquals(ExactJson.MAX_NODES, countNodes(root), "fixture 必须恰好落在节点预算上");
        final String text = wire(root);
        assertTrue(utf8Length(text) < ExactJson.MAX_UTF8_BYTES, "只允许节点预算触发这次拒绝");
        assertEquals(text, MapDefinitionCodec.encode(MapDefinitionCodec.decode(text)).canonical(),
                "恰在预算上的文档必须被接受并逐字重编码");
        assertEquals(text, MapDefinitionCodec
                        .encode(MapDefinitionCodec.decode(ExactJson.parse(text))).canonical(),
                "两个入口对恰在预算上的文档必须一致");

        many.add(num(0));
        final JsonObject over = minimalRoot();
        over.add("rules", many);
        assertEquals(ExactJson.MAX_NODES + 1, countNodes(over), "多一个节点就必须越界");
        final String overText = wire(over);
        assertTrue(utf8Length(overText) < ExactJson.MAX_UTF8_BYTES, "只允许节点预算触发这次拒绝");
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(overText), "认证层必须拒绝该节点数");
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, oracle.code(), "必须是预算拒绝");
        // 预算按文档序（读取器先序）逐节点记账，第 500001 个节点就是文档里最后一个节点；
        // 本 fixture 的 canonical 键序把 worldId 排在最后，所以越界点必然落在它身上。
        assertEquals("/worldId", oracle.path(), "越界节点必须是文档序里的最后一个节点");
        assertCertificationRejects(overText, "node count exceeds " + ExactJson.MAX_NODES);
    }

    static List<Arguments> nestingDepthCases() {
        return List.of(Arguments.of(Integer.valueOf(63), Boolean.TRUE),
                Arguments.of(Integer.valueOf(64), Boolean.FALSE));
    }

    @ParameterizedTest(name = "extensions 嵌套 {0} 层数组")
    @Tag("large-heap")
    @MethodSource("nestingDepthCases")
    void nestingDepthBoundaryIsExactlySixtyFour(int depth, boolean accepted) {
        final JsonObject root = minimalRoot();
        root.add("extensions", nestedArrays(depth));
        final String text = wire(root);
        if (accepted) {
            assertEquals(text, MapDefinitionCodec.encode(MapDefinitionCodec.decode(text)).canonical(),
                    "最深层落在第 64 层的文档必须被接受并逐字重编码");
            assertEquals(text, MapDefinitionCodec
                            .encode(MapDefinitionCodec.decode(ExactJson.parse(text))).canonical(),
                    "两个入口对最深层文档必须一致");
            return;
        }
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(text), "认证层必须拒绝该嵌套深度");
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, oracle.code(), "必须是预算拒绝");
        assertEquals("/extensions" + "/0".repeat(depth), oracle.path(),
                "越界节点必须是第 " + (depth + 1) + " 层的那个标量");
        assertCertificationRejects(text, "nesting depth exceeds " + ExactJson.MAX_DEPTH);
    }

    /**
     * 千例语料的一条：phase 1 独立造出的输入、完整 typed 期望，以及调用前的全部快照。
     *
     * <p>{@code wire} 由本文件自己的写入器现写，同时充当事前给定的 canonical 文本；{@code utf8} 与
     * {@code sha256} 由 JDK 从 {@code wire} 现算；{@code frozen} 是认证后的入参；四个 typed 列表与
     * {@code frozenSlots} 是调用前的引用快照，{@code frozenCanonicals} 与之一一对应。</p>
     */
    private record CorpusCase(int index, String wire, byte[] utf8, String sha256,
            ExactJson.FrozenJson frozen, MapDefinition expected, List<TeamDefinition> teams,
            List<SpawnDefinition> spawns, List<RegionDefinition> regions,
            List<ResourceRevisionRef> resources, List<ExactJson.FrozenJson> frozenSlots,
            List<String> frozenCanonicals) {
    }

    /**
     * 与 {@link #corpusRoot(int)} 逐字对应的完整 typed 期望，全部由公开构造器按下标直接给定，
     * 绝不从生产 decode/encode 的输出反推；十三个未知成员槽在无未知成员时共享一个空对象。
     */
    private static MapDefinition corpusDefinition(int index) {
        final String teamId = uuidAt(index + 1 + 200000);
        final int number = corpusNumber(index);
        final ExactJson.FrozenJson empty = ExactJson.parse("{}");
        final ExactJson.FrozenJson wireNull = ExactJson.parse("null");
        return new MapDefinition(
                new Ids.DefinitionId(UUID.fromString(uuidAt(index + 1))),
                new Ids.WorldId(UUID.fromString(uuidAt(index + 1 + 100000))),
                "fpsmatch:corpus",
                DIMENSION,
                "地图-" + index,
                "第 " + index + " 张图",
                new BoxCorners(new IntPosition(-number, 0, number),
                        new IntPosition(number, 64, -number)),
                List.of(new TeamDefinition(new Ids.TeamId(UUID.fromString(teamId)), "Red",
                        ExactJson.parse("{\"score\":" + number + "}"), wireNull,
                        new WireExtras.TeamExtras(empty))),
                List.of(new SpawnDefinition(new Ids.ObjectId(UUID.fromString(uuidAt(index + 1 + 300000))),
                        "s" + index, new Ids.TeamId(UUID.fromString(teamId)), DIMENSION,
                        new Position(number, 0.0, -1.0), new Orientation(0.5f, 0.0f), wireNull,
                        new WireExtras.SpawnExtras(empty, empty, empty))),
                List.of(new RegionDefinition(new Ids.ObjectId(UUID.fromString(uuidAt(index + 1 + 400000))),
                        "r" + index, REGION_KIND,
                        new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(number, 1, 1)),
                        empty, wireNull, new WireExtras.RegionExtras(empty, empty, empty, empty))),
                ExactJson.parse("{\"limit\":" + number + "}"),
                List.of(new ResourceRevisionRef(RESOURCE_KIND,
                        UUID.fromString(uuidAt(index + 1 + 500000)), number, corpusDigest(index),
                        new WireExtras.ResourceExtras(empty))),
                ExactJson.parse("{\"seed\":" + number + "}"),
                new WireExtras.MapExtras(empty, empty, empty, empty));
    }

    /** 一条期望模型里全部 Frozen 引用，固定顺序：rules/extensions、四个 typed config 与十三个未知成员槽。 */
    private static List<ExactJson.FrozenJson> corpusFrozenSlots(MapDefinition model) {
        final WireExtras.MapExtras map = model.wireExtras();
        final TeamDefinition team = model.teams().get(0);
        final SpawnDefinition spawn = model.spawns().get(0);
        final RegionDefinition region = model.regions().get(0);
        final List<ExactJson.FrozenJson> slots = new ArrayList<>(20);
        slots.add(model.rules());
        slots.add(model.extensions());
        slots.add(map.members());
        slots.add(map.boundaryMembers());
        slots.add(map.boundaryFirstMembers());
        slots.add(map.boundarySecondMembers());
        slots.add(team.config());
        slots.add(team.extensions());
        slots.add(team.wireExtras().members());
        slots.add(spawn.extensions());
        slots.add(spawn.wireExtras().members());
        slots.add(spawn.wireExtras().positionMembers());
        slots.add(spawn.wireExtras().orientationMembers());
        slots.add(region.config());
        slots.add(region.extensions());
        slots.add(region.wireExtras().members());
        slots.add(region.wireExtras().cornersMembers());
        slots.add(region.wireExtras().cornersFirstMembers());
        slots.add(region.wireExtras().cornersSecondMembers());
        slots.add(model.resources().get(0).wireExtras().members());
        return slots;
    }

    @Test
    @Tag("large-heap")
    @DisplayName("1000 条固定语料互异，逐条按 JDK UTF-8/SHA 复算并与预给摘要一致")
    void oneThousandFixedSeedWiresAreDistinctAndReencodeVerbatim() {
        // phase 1：只造输入与完整期望（本文件写入器 + JDK 摘要 + 认证层），不碰生产任何方法。
        // productionCalls 只在 phase 2 的两次入口调用后递增，所以此处必须仍是 0。
        int productionCalls = 0;
        final Set<String> seenWires = new HashSet<>();
        final Set<String> seenDigests = new HashSet<>();
        final MessageDigest jdkDigest = newDigest();
        final List<CorpusCase> cases = new ArrayList<>(CORPUS_SIZE);
        int jdkTotal = 0;
        for (int index = 0; index < CORPUS_SIZE; index++) {
            final String text = wire(corpusRoot(index));
            assertTrue(seenWires.add(text), "第 " + index + " 条 wire 必须与它之前的每一条都不同");
            final byte[] jdkBytes = text.getBytes(StandardCharsets.UTF_8);
            final String jdkHash = sha256(text);
            assertTrue(seenDigests.add(jdkHash), "第 " + index + " 条的 JDK 摘要必须与之前的每一条都不同");
            jdkDigest.update(jdkBytes);
            jdkTotal += jdkBytes.length;
            final ExactJson.FrozenJson frozen = ExactJson.parse(text);
            assertEquals(text, frozen.canonical(), "第 " + index + " 条的输入必须就是该 canonical 文本");
            final MapDefinition expected = corpusDefinition(index);
            final List<ExactJson.FrozenJson> frozenSlots =
                    List.copyOf(corpusFrozenSlots(expected));
            assertEquals(20, frozenSlots.size(), "期望模型的 Frozen 槽位必须是二十个");
            final List<String> frozenCanonicals = new ArrayList<>(frozenSlots.size());
            for (ExactJson.FrozenJson slot : frozenSlots) {
                frozenCanonicals.add(slot.canonical());
            }
            cases.add(new CorpusCase(index, text, jdkBytes, jdkHash, frozen, expected,
                    expected.teams(), expected.spawns(), expected.regions(), expected.resources(),
                    frozenSlots, List.copyOf(frozenCanonicals)));
            if (index == 0) {
                assertEquals(CORPUS_FIRST_UTF8_BYTES, jdkBytes.length, "首条字节数必须等于预给值");
                assertEquals(CORPUS_FIRST_SHA256, jdkHash, "首条摘要必须等于预给值");
            }
            if (index == CORPUS_SIZE - 1) {
                assertEquals(CORPUS_LAST_UTF8_BYTES, jdkBytes.length, "末条字节数必须等于预给值");
                assertEquals(CORPUS_LAST_SHA256, jdkHash, "末条摘要必须等于预给值");
            }
        }
        assertEquals(CORPUS_SIZE, cases.size(), "phase 1 必须造满 1000 条");
        assertEquals(CORPUS_SIZE, seenWires.size(), "1000 条语料必须全部互异");
        assertEquals(CORPUS_SIZE, seenDigests.size(), "1000 条 JDK 摘要必须全部互异");
        assertEquals(CORPUS_UTF8_BYTES, jdkTotal, "JDK 侧 UTF-8 总字节数必须等于预给值");
        assertEquals(CORPUS_SHA256, hexOf(jdkDigest.digest()), "JDK 侧聚合摘要必须等于预给值");
        assertEquals(0, productionCalls, "phase 1 不得调用生产的任何方法");

        // phase 2：两个入口各解一遍，逐条比完整 typed 期望，再逐条 encode 回事前 canonical/字节/摘要。
        int textCalls = 0;
        int frozenCalls = 0;
        final MessageDigest textDigest = newDigest();
        final MessageDigest frozenDigest = newDigest();
        int textTotal = 0;
        int frozenTotal = 0;
        for (CorpusCase item : cases) {
            final MapDefinition fromText = MapDefinitionCodec.decode(item.wire());
            assertEquals(item.expected(), fromText, "第 " + item.index() + " 条 String 入口的完整模型");
            productionCalls++;
            textCalls++;
            final ExactJson.FrozenJson textEncoded = MapDefinitionCodec.encode(fromText);
            assertEquals(item.wire(), textEncoded.canonical(),
                    "第 " + item.index() + " 条 String 入口必须 encode 回事前 canonical");
            assertTrue(java.util.Arrays.equals(item.utf8(), textEncoded.canonicalUtf8()),
                    "第 " + item.index() + " 条 String 入口的 UTF-8 必须与 JDK 逐字节相同");
            assertEquals(item.utf8().length, textEncoded.utf8Size(),
                    "第 " + item.index() + " 条 String 入口的字节数必须与 JDK 相同");
            assertEquals(item.sha256(), sha256(textEncoded.canonical()),
                    "第 " + item.index() + " 条 String 入口的摘要必须与 JDK 相同");
            textDigest.update(textEncoded.canonicalUtf8());
            textTotal += textEncoded.utf8Size();

            final MapDefinition fromFrozen = MapDefinitionCodec.decode(item.frozen());
            assertEquals(item.expected(), fromFrozen, "第 " + item.index() + " 条 Frozen 入口的完整模型");
            productionCalls++;
            frozenCalls++;
            final ExactJson.FrozenJson frozenEncoded = MapDefinitionCodec.encode(fromFrozen);
            assertEquals(item.wire(), frozenEncoded.canonical(),
                    "第 " + item.index() + " 条 Frozen 入口必须 encode 回事前 canonical");
            assertTrue(java.util.Arrays.equals(item.utf8(), frozenEncoded.canonicalUtf8()),
                    "第 " + item.index() + " 条 Frozen 入口的 UTF-8 必须与 JDK 逐字节相同");
            assertEquals(item.utf8().length, frozenEncoded.utf8Size(),
                    "第 " + item.index() + " 条 Frozen 入口的字节数必须与 JDK 相同");
            assertEquals(item.sha256(), sha256(frozenEncoded.canonical()),
                    "第 " + item.index() + " 条 Frozen 入口的摘要必须与 JDK 相同");
            frozenDigest.update(frozenEncoded.canonicalUtf8());
            frozenTotal += frozenEncoded.utf8Size();

            if (item.index() == 0) {
                assertEquals(CORPUS_FIRST_UTF8_BYTES, frozenEncoded.utf8Size(),
                        "首条编码字节数必须等于预给值");
                assertEquals(CORPUS_FIRST_SHA256, sha256(frozenEncoded.canonical()),
                        "首条编码摘要必须等于预给值");
            }
            if (item.index() == CORPUS_SIZE - 1) {
                assertEquals(CORPUS_LAST_UTF8_BYTES, frozenEncoded.utf8Size(),
                        "末条编码字节数必须等于预给值");
                assertEquals(CORPUS_LAST_SHA256, sha256(frozenEncoded.canonical()),
                        "末条编码摘要必须等于预给值");
            }

            // 生产调用之后：入参 Frozen、四个 typed 列表与全部既有 Frozen 引用/文本都必须保持原样。
            assertEquals(item.wire(), item.frozen().canonical(),
                    "第 " + item.index() + " 条入参 Frozen 的文本不得被改写");
            assertEquals(item.utf8().length, item.frozen().utf8Size(),
                    "第 " + item.index() + " 条入参 Frozen 的字节数不得被改写");
            assertSame(item.teams(), item.expected().teams(), "第 " + item.index() + " 条 teams 容器");
            assertSame(item.spawns(), item.expected().spawns(), "第 " + item.index() + " 条 spawns 容器");
            assertSame(item.regions(), item.expected().regions(), "第 " + item.index() + " 条 regions 容器");
            assertSame(item.resources(), item.expected().resources(),
                    "第 " + item.index() + " 条 resources 容器");
            final MapDefinition rebuilt = corpusDefinition(item.index());
            assertEquals(item.teams(), rebuilt.teams(), "第 " + item.index() + " 条 team 内容");
            assertEquals(item.spawns(), rebuilt.spawns(), "第 " + item.index() + " 条 spawn 内容");
            assertEquals(item.regions(), rebuilt.regions(), "第 " + item.index() + " 条 region 内容");
            assertEquals(item.resources(), rebuilt.resources(), "第 " + item.index() + " 条 resource 内容");
            final List<ExactJson.FrozenJson> now = corpusFrozenSlots(item.expected());
            assertEquals(item.frozenSlots().size(), now.size(), "第 " + item.index() + " 条槽位数");
            for (int slot = 0; slot < now.size(); slot++) {
                assertSame(item.frozenSlots().get(slot), now.get(slot),
                        "第 " + item.index() + " 条第 " + slot + " 个 Frozen 引用必须保持");
                assertEquals(item.frozenCanonicals().get(slot), now.get(slot).canonical(),
                        "第 " + item.index() + " 条第 " + slot + " 个 Frozen 文本必须保持");
            }
        }
        assertEquals(CORPUS_SIZE, textCalls, "String 入口必须恰好 1000 次");
        assertEquals(CORPUS_SIZE, frozenCalls, "Frozen 入口必须恰好 1000 次");
        assertEquals(2 * CORPUS_SIZE, productionCalls, "phase 2 必须恰好 2000 次生产调用");
        assertEquals(CORPUS_UTF8_BYTES, textTotal, "String 入口的 UTF-8 总字节数必须等于预给值");
        assertEquals(CORPUS_UTF8_BYTES, frozenTotal, "Frozen 入口的 UTF-8 总字节数必须等于预给值");
        assertEquals(CORPUS_SHA256, hexOf(textDigest.digest()), "String 入口的聚合摘要必须等于预给值");
        assertEquals(CORPUS_SHA256, hexOf(frozenDigest.digest()), "Frozen 入口的聚合摘要必须等于预给值");
    }

    // ------------------------------------------------------------------ 字节预算：原文与 canonical

    /** 填充块字符数：全 ASCII，1 字符恰 1 UTF-8 字节，且恰为单个字符串的合法上限。 */
    private static final int BUDGET_CHUNK_CHARS = 65536;

    /** 填充块个数：卡片给定的固定基座（127 × 65536 加一条尾巴）落在 8 MiB 附近。 */
    private static final int BUDGET_CHUNK_COUNT = 127;

    /**
     * 把 {@code rules} 换成长度恰好的 ASCII 填充数组，使整份原文的 JDK UTF-8 计数可控。
     * {@code rawExponentTail} 为真时数组末尾再放一个原始拼写 {@code 1e1}（canonical 是
     * {@code 1E+1}，恰好比原文多一个字节），用于把 canonical 预算单独顶过界。
     */
    private static JsonObject budgetRoot(int tailChars, boolean rawExponentTail) {
        final JsonObject root = minimalRoot();
        final JsonArray rules = new JsonArray();
        for (int index = 0; index < BUDGET_CHUNK_COUNT; index++) {
            rules.add(str("a".repeat(BUDGET_CHUNK_CHARS)));
        }
        rules.add(str("a".repeat(tailChars)));
        root.add("rules", rules);
        if (rawExponentTail) {
            // withNode 只能替换已存在的下标，所以先放一个占位元素，再由原始字面量替换它。
            root.getAsJsonArray("rules").add(num(0));
        }
        return root;
    }

    private static String budgetWireWithTail(int tailChars, boolean rawExponentTail) {
        final JsonObject root = budgetRoot(tailChars, rawExponentTail);
        if (!rawExponentTail) {
            return wire(root);
        }
        return wireWithRawNumbers(root,
                new String[] {"/rules/" + (BUDGET_CHUNK_COUNT + 1)}, new String[] {"1e1"});
    }

    /** 尾巴长度不手抄：先用空尾巴实测整份原文（JDK 计数），再用一次差值补齐。 */
    private static int budgetTailChars(int targetUtf8Bytes, boolean rawExponentTail) {
        final int tailChars = targetUtf8Bytes - utf8Length(budgetWireWithTail(0, rawExponentTail));
        assertTrue(tailChars >= 0 && tailChars <= BUDGET_CHUNK_CHARS,
                "尾巴必须仍是合法字符串长度，实测差值 " + tailChars);
        return tailChars;
    }

    /** 独立清点 JSON 树的最大嵌套深度（根算一层），不经过任何生产代码。 */
    private static int jsonDepth(JsonElement element) {
        if (element.isJsonObject()) {
            int deepest = 1;
            for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                deepest = Math.max(deepest, 1 + jsonDepth(member.getValue()));
            }
            return deepest;
        }
        if (element.isJsonArray()) {
            int deepest = 1;
            for (JsonElement item : element.getAsJsonArray()) {
                deepest = Math.max(deepest, 1 + jsonDepth(item));
            }
            return deepest;
        }
        return 1;
    }

    /**
     * 按文档序替换若干位置上的原始数值字面量：先给每个位置装一个互不相同的字符串占位符，
     * 渲染一次 canonical 文本，再逐个唯一替换。用于精确写入 {@code -0}/{@code 1e1} 这类
     * 本文件写入器无法直接产出的拼写。
     */
    private static String wireWithRawNumbers(JsonObject root, String[] pointers, String[] literals) {
        assertEquals(pointers.length, literals.length, "位置与字面量必须一一对应");
        final JsonObject target = copy(root);
        final String[] quoted = new String[pointers.length];
        for (int index = 0; index < pointers.length; index++) {
            final String token = "__RAW_NUMBER_" + index + "__";
            quoted[index] = "\"" + token + "\"";
            withNode(target, pointers[index], str(token));
        }
        String text = wire(target);
        for (int index = 0; index < pointers.length; index++) {
            final int first = text.indexOf(quoted[index]);
            assertTrue(first >= 0, "占位符必须出现在文本里：" + pointers[index]);
            assertEquals(first, text.lastIndexOf(quoted[index]), "占位符必须唯一：" + pointers[index]);
            text = text.replace(quoted[index], literals[index]);
        }
        assertFalse(text.contains("__RAW_NUMBER_"), "占位符必须被全部替换");
        return text;
    }

    @Test
    @Tag("large-heap")
    @DisplayName("原文恰为 8 MiB 时被接受，多一字节即在认证期按原文预算被拒绝")
    void documentByteBudgetBoundaryIsAcceptedAtMaxAndRejectedAbove() {
        final int atTail = budgetTailChars(ExactJson.MAX_UTF8_BYTES, false);
        final JsonObject atRoot = budgetRoot(atTail, false);
        // 只有字节预算可以触发这次判定：字符串长度、节点数与嵌套深度都仍远在各自预算内。
        assertTrue(BUDGET_CHUNK_CHARS <= ExactJson.MAX_STRING_UTF8_BYTES, "每段填充必须仍是合法字符串");
        assertTrue(countNodes(atRoot) < ExactJson.MAX_NODES, "节点数必须远在预算内");
        assertTrue(jsonDepth(atRoot) < ExactJson.MAX_DEPTH, "嵌套深度必须远在预算内");
        final String atBudget = budgetWireWithTail(atTail, false);
        assertEquals(ExactJson.MAX_UTF8_BYTES, utf8Length(atBudget), "原文必须恰在预算上");
        assertEquals(BUDGET_CHUNK_COUNT + 1, atRoot.getAsJsonArray("rules").size(), "fixture 形状");
        assertEquals(BUDGET_CHUNK_CHARS, atRoot.getAsJsonArray("rules").get(0).getAsString().length(),
                "每段填充长度必须恰为字符串上限");

        final ExactJson.FrozenJson certified = ExactJson.parse(atBudget);
        assertEquals(atBudget, certified.canonical(), "恰在预算上的原文必须被认证接受");
        final MapDefinition fromText = MapDefinitionCodec.decode(atBudget);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(certified);
        assertEquals(fromText, fromFrozen, "两个入口必须给出相等模型");
        assertEquals(canonical(atRoot.get("rules")), fromText.rules().canonical(),
                "任意形状的 rules 必须被逐字读入");
        assertEquals(BUDGET_CHUNK_COUNT + 1, fromText.rules().toJson().getAsJsonArray().size(),
                "填充数组必须完整读入");
        final byte[] jdkBytes = atBudget.getBytes(StandardCharsets.UTF_8);
        for (MapDefinition model : List.of(fromText, fromFrozen)) {
            final ExactJson.FrozenJson encoded = MapDefinitionCodec.encode(model);
            assertEquals(atBudget, encoded.canonical(), "恰在预算上的原文必须逐字重编码");
            assertTrue(java.util.Arrays.equals(jdkBytes, encoded.canonicalUtf8()),
                    "encode 的 UTF-8 必须与 JDK 逐字节相同");
            assertEquals(jdkBytes.length, encoded.utf8Size(), "encode 的字节数必须与 JDK 相同");
            assertEquals(sha256(atBudget), sha256(encoded.canonical()), "encode 的摘要必须与 JDK 相同");
        }

        final String overBudget = budgetWireWithTail(atTail + 1, false);
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1, utf8Length(overBudget), "原文必须只多一字节");
        // 原文预算先于任何 canonical 记账生效：路径是根，消息与 cause 都必须来自认证层。
        final JsonContractException rawOracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(overBudget), "认证层必须按原文预算拒绝");
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, rawOracle.code(), "必须是预算码");
        assertEquals("", rawOracle.path(), "原文预算在记账前生效，路径必须是根");
        assertCertificationRejects(overBudget,
                "document exceeds " + ExactJson.MAX_UTF8_BYTES + " UTF-8 bytes");
    }

    @Test
    @Tag("large-heap")
    @DisplayName("原文恰为 8 MiB 但含一个非最短拼写时，canonical 输出预算在 String 入口被真实触发")
    void canonicalByteBudgetIsReachableFromTheStringEntry() {
        final int rawTail = budgetTailChars(ExactJson.MAX_UTF8_BYTES, true);
        final JsonObject rawRoot = budgetRoot(rawTail, true);
        assertTrue(countNodes(rawRoot) < ExactJson.MAX_NODES, "节点数必须远在预算内");
        assertTrue(jsonDepth(rawRoot) < ExactJson.MAX_DEPTH, "嵌套深度必须远在预算内");
        final String rawText = budgetWireWithTail(rawTail, true);
        assertEquals(ExactJson.MAX_UTF8_BYTES, utf8Length(rawText), "原文必须恰在原文预算上");
        assertEquals(BUDGET_CHUNK_COUNT + 2, rawRoot.getAsJsonArray("rules").size(), "fixture 形状");
        assertTrue(rawText.contains(",1e1]"), "原始指数拼写必须留在原文里");
        assertFalse(rawText.contains("__RAW_NUMBER_"), "占位符必须被全部替换");

        // 除这一个指数拼写之外原文其余部分全是 canonical，因此 canonical 输出恰为预算 +1 字节；
        // 原文预算本身必须仍然通过，否则这次拒绝就证明不了 canonical 预算可达。
        final String exponentCanonical = ExactJson.parse("1e1").canonical();
        assertEquals("1E+1", exponentCanonical, "认证层对 1e1 的 canonical 拼写");
        assertEquals(ExactJson.MAX_UTF8_BYTES + 1,
                utf8Length(rawText) - utf8Length("1e1") + utf8Length(exponentCanonical),
                "canonical 输出必须恰为预算 +1 字节");
        final JsonContractException oracle = assertThrows(JsonContractException.class,
                () -> ExactJson.parse(rawText), "认证层必须在 canonical 记账阶段拒绝");
        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, oracle.code(), "必须是预算码");
        assertEquals("canonical output exceeds " + ExactJson.MAX_UTF8_BYTES + " UTF-8 bytes",
                oracle.getMessage(), "canonical 输出预算消息必须逐字给定");
        // 记账按 canonical 键序推进，越界的最后一次预留在键序最末的 worldId 上。
        assertEquals("/worldId", oracle.path(), "越界成员必须是 canonical 键序里的最后一个成员");
        assertCertificationRejects(rawText, "canonical output exceeds " + ExactJson.MAX_UTF8_BYTES
                + " UTF-8 bytes");
    }

    // ------------------------------------------------------------------ 几何分量的负零归一

    /** 负零的三种等价拼写，以及一个非最短却非零的未知数值成员。 */
    private static final String[] NEGATIVE_ZERO_POINTERS = {
            "/spawns/0/position/x", "/spawns/0/position/y", "/spawns/0/position/z",
            "/spawns/0/orientation/yaw", "/spawns/0/orientation/pitch", "/zeroSample"};

    private static final String[] NEGATIVE_ZERO_LITERALS = {
            "-0", "-0.0", "-0e0", "-0.0", "-0e0", "1e1"};

    @Test
    @Tag("small-heap")
    @DisplayName("position/orientation 五分量上的负零一律归一为 +0，未知数值成员不受该归一影响")
    void negativeZeroGeometryIsNormalisedToPositiveZeroWithoutWideningUnknownNumbers() {
        final String negativeWire = wireWithRawNumbers(minimalRoot(), NEGATIVE_ZERO_POINTERS,
                NEGATIVE_ZERO_LITERALS);
        assertTrue(negativeWire.contains("\"x\":-0,"), "原始负零拼写必须留在原文里");
        assertTrue(negativeWire.contains("\"z\":-0e0}"), "原始指数负零拼写必须留在原文里");
        assertNotEquals(negativeWire, ExactJson.parse(negativeWire).canonical(),
                "输入必须真的带着非 canonical 的负零拼写");

        final ExactJson.FrozenJson unknownOracle = ExactJson.parse("1e1");
        assertEquals("1E+1", unknownOracle.canonical(), "认证层对未知数值成员的 canonical 拼写");
        final String unknownMember = "{\"zeroSample\":" + unknownOracle.canonical() + "}";
        // 正零对照文本完全由本文件写入器与认证层独立给出：几何分量写 canonical 的 0，
        // 未知成员写认证层给的 canonical 拼写，绝不取自生产的 encode 输出。
        final String positiveWire = wireWithRawNumbers(
                withNode(withNode(minimalRoot(), "/spawns/0/position", dec3("0", "0", "0")),
                        "/spawns/0/orientation", look("0", "0")),
                new String[] {"/zeroSample"}, new String[] {unknownOracle.canonical()});
        assertEquals(positiveWire, ExactJson.parse(positiveWire).canonical(),
                "正零对照文本本身必须是 canonical");

        final MapDefinition expected = expectedDefinition(new Position(0.0, 0.0, 0.0),
                new Orientation(0.0f, 0.0f), minimalDefinition().resources(),
                ExactJson.parse(unknownMember));
        final MapDefinition fromText = MapDefinitionCodec.decode(negativeWire);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(ExactJson.parse(negativeWire));
        assertEquals(expected, fromText, "负零 wire 必须给出正零的完整 typed 期望");
        assertEquals(expected, fromFrozen, "Frozen 入口必须给出同一期望");

        final SpawnDefinition spawn = fromText.spawns().get(0);
        assertEquals(0L, Double.doubleToRawLongBits(spawn.position().x()), "x 必须是 +0 的位型");
        assertEquals(0L, Double.doubleToRawLongBits(spawn.position().y()), "y 必须是 +0 的位型");
        assertEquals(0L, Double.doubleToRawLongBits(spawn.position().z()), "z 必须是 +0 的位型");
        assertEquals(0, Float.floatToRawIntBits(spawn.orientation().yaw()), "yaw 必须是 +0 的位型");
        assertEquals(0, Float.floatToRawIntBits(spawn.orientation().pitch()), "pitch 必须是 +0 的位型");
        assertEquals(unknownMember, fromText.wireExtras().members().canonical(),
                "未知数值成员只按认证层的 canonical 拼写保留，typed 归一不得扩到未知数值");
        assertEquals(unknownMember, fromFrozen.wireExtras().members().canonical(),
                "两个入口对未知成员必须给出同一 canonical 拼写");

        for (MapDefinition model : List.of(fromText, fromFrozen)) {
            final ExactJson.FrozenJson encoded = MapDefinitionCodec.encode(model);
            assertEquals(positiveWire, encoded.canonical(), "归一后的 encode 必须等于正零对照文本");
            assertTrue(java.util.Arrays.equals(positiveWire.getBytes(StandardCharsets.UTF_8),
                    encoded.canonicalUtf8()), "encode 的 UTF-8 必须与 JDK 逐字节相同");
            assertEquals(utf8Length(positiveWire), encoded.utf8Size(), "encode 的字节数");
            assertEquals(sha256(positiveWire), sha256(encoded.canonical()), "encode 的摘要");
        }
    }

    // ------------------------------------------------------------------ 重复 resource 的逐次未知成员

    @Test
    @Tag("small-heap")
    @DisplayName("resources 允许同 typed id/revision 重复出现，顺序、摘要与逐次未知成员互不混淆")
    void duplicateResourceRevisionsKeepOrderAndPerOccurrenceExtras() {
        final JsonObject first = resource(RESOURCE_KIND, RESOURCE_ID, 7L, DIGEST);
        first.add("tag", str("first"));
        final JsonObject second = resource(RESOURCE_KIND, RESOURCE_ID, 7L, DIGEST);
        second.add("tag", str("second"));
        final JsonObject third = resource(RESOURCE_KIND, uuidAt(9), 8L, corpusDigest(3));
        third.add("tag", num(3));
        final JsonObject root = minimalRoot();
        root.add("resources", arr(first, second, third));
        final String text = wire(root);

        final List<ResourceRevisionRef> expectedResources = List.of(
                new ResourceRevisionRef(RESOURCE_KIND, UUID.fromString(RESOURCE_ID), 7L, DIGEST,
                        new WireExtras.ResourceExtras(ExactJson.parse("{\"tag\":\"first\"}"))),
                new ResourceRevisionRef(RESOURCE_KIND, UUID.fromString(RESOURCE_ID), 7L, DIGEST,
                        new WireExtras.ResourceExtras(ExactJson.parse("{\"tag\":\"second\"}"))),
                new ResourceRevisionRef(RESOURCE_KIND, UUID.fromString(uuidAt(9)), 8L, corpusDigest(3),
                        new WireExtras.ResourceExtras(ExactJson.parse("{\"tag\":3}"))));
        final MapDefinition expected = expectedDefinition(new Position(1.5, 0.0, 2.25),
                new Orientation(45.5f, -12.25f), expectedResources, ExactJson.parse("{}"));

        final MapDefinition fromText = MapDefinitionCodec.decode(text);
        final MapDefinition fromFrozen = MapDefinitionCodec.decode(ExactJson.parse(text));
        assertEquals(expected, fromText, "重复 resource 必须按出现顺序给出完整 typed 期望");
        assertEquals(expected, fromFrozen, "Frozen 入口必须给出同一期望");
        assertEquals(expectedResources, fromText.resources(), "顺序与各自的 kind/revision/digest/extras");
        assertEquals(3, fromText.resources().size(), "三次出现都必须保留");

        final ResourceRevisionRef firstRef = fromText.resources().get(0);
        final ResourceRevisionRef duplicateRef = fromText.resources().get(1);
        assertEquals(firstRef.kindId(), duplicateRef.kindId(), "同 kind 的重复必须都能通过");
        assertEquals(firstRef.resourceId(), duplicateRef.resourceId(), "同 typed id 的重复必须都能通过");
        assertEquals(firstRef.revision(), duplicateRef.revision(), "同 revision 的重复必须都能通过");
        assertEquals(firstRef.digest(), duplicateRef.digest(), "同 digest 的重复必须都能通过");
        assertNotSame(firstRef.wireExtras().members(), duplicateRef.wireExtras().members(),
                "同 id 的两次出现不得共享未知成员槽");
        assertEquals("{\"tag\":\"first\"}", firstRef.wireExtras().members().canonical(),
                "第一次出现的未知成员");
        assertEquals("{\"tag\":\"second\"}", duplicateRef.wireExtras().members().canonical(),
                "第二次出现的未知成员");
        assertEquals("{\"tag\":3}", fromText.resources().get(2).wireExtras().members().canonical(),
                "第三条资源的未知成员");

        for (MapDefinition model : List.of(fromText, fromFrozen)) {
            final ExactJson.FrozenJson encoded = MapDefinitionCodec.encode(model);
            assertEquals(text, encoded.canonical(), "必须逐字重编码");
            assertTrue(java.util.Arrays.equals(text.getBytes(StandardCharsets.UTF_8),
                    encoded.canonicalUtf8()), "encode 的 UTF-8 必须与 JDK 逐字节相同");
            assertEquals(utf8Length(text), encoded.utf8Size(), "encode 的字节数");
            assertEquals(sha256(text), sha256(encoded.canonical()), "encode 的摘要");
        }
    }

    // ------------------------------------------------------------------ 四个 typed 列表的隔离与只读

    /** 真调用 add/set/remove 三种改写路径，每条都必须抛出具体的只读异常且容器长度不变。 */
    private static <T> void assertUnmodifiable(List<T> container, T legal) {
        assertThrows(UnsupportedOperationException.class, () -> container.add(legal),
                "只读列表不得接受 add");
        assertThrows(UnsupportedOperationException.class, () -> container.set(0, legal),
                "只读列表不得接受 set");
        assertThrows(UnsupportedOperationException.class, () -> container.remove(0),
                "只读列表不得接受按下标 remove");
        assertThrows(UnsupportedOperationException.class, () -> container.remove(legal),
                "只读列表不得接受按元素 remove");
        assertEquals(1, container.size(), "失败改写之后容器长度必须不变");
    }

    @Test
    @Tag("small-heap")
    @DisplayName("同一非空 Frozen 的两次解码给出四个互不共享且不可改写的列表")
    void repeatedDecodesGiveIndependentUnmodifiableLists() {
        final ExactJson.FrozenJson payload = ExactJson.parse(MINIMAL_WIRE);
        final MapDefinition first = MapDefinitionCodec.decode(payload);
        final MapDefinition second = MapDefinitionCodec.decode(payload);
        final MapDefinition expected = minimalDefinition();
        assertNotSame(first, second, "两次解码必须给出不同实例");
        assertEquals(expected, first, "第一次解码必须等于完整 typed 期望");
        assertEquals(expected, second, "第二次解码必须等于完整 typed 期望");
        assertNotSame(first.teams(), second.teams(), "teams 容器不得跨解码共享");
        assertNotSame(first.spawns(), second.spawns(), "spawns 容器不得跨解码共享");
        assertNotSame(first.regions(), second.regions(), "regions 容器不得跨解码共享");
        assertNotSame(first.resources(), second.resources(), "resources 容器不得跨解码共享");
        assertEquals(expected.teams(), first.teams(), "teams 元素必须与完整期望逐一相同");
        assertEquals(expected.spawns(), first.spawns(), "spawns 元素必须与完整期望逐一相同");
        assertEquals(expected.regions(), first.regions(), "regions 元素必须与完整期望逐一相同");
        assertEquals(expected.resources(), first.resources(), "resources 元素必须与完整期望逐一相同");
        assertEquals(expected.teams(), second.teams(), "第二次解码的 teams 元素");
        assertEquals(expected.spawns(), second.spawns(), "第二次解码的 spawns 元素");
        assertEquals(expected.regions(), second.regions(), "第二次解码的 regions 元素");
        assertEquals(expected.resources(), second.resources(), "第二次解码的 resources 元素");
        assertTrue(first.spawns().get(0).name().equals("s1"), "四个列表都必须真读到内容");

        assertUnmodifiable(first.teams(), expected.teams().get(0));
        assertUnmodifiable(first.spawns(), expected.spawns().get(0));
        assertUnmodifiable(first.regions(), expected.regions().get(0));
        assertUnmodifiable(first.resources(), expected.resources().get(0));
        assertUnmodifiable(second.teams(), expected.teams().get(0));
        assertUnmodifiable(second.spawns(), expected.spawns().get(0));
        assertUnmodifiable(second.regions(), expected.regions().get(0));
        assertUnmodifiable(second.resources(), expected.resources().get(0));

        // 改写交出去的 getter 视图，既不得改变模型，也不得改变入参 Frozen。
        final JsonElement handedRules = first.rules().toJson();
        handedRules.getAsJsonObject().add("limit", num(999));
        handedRules.getAsJsonObject().addProperty("injected", "x");
        final JsonElement handedExtras = first.wireExtras().members().toJson();
        handedExtras.getAsJsonObject().addProperty("injected", "y");
        assertEquals(expected, first, "污染交出的树不得改变第一次解码的模型");
        assertEquals(expected, second, "污染交出的树不得改变第二次解码的模型");
        assertEquals("{\"limit\":5}", first.rules().canonical(), "Frozen 自身必须保持不变");
        assertEquals("{}", first.wireExtras().members().canonical(), "空槽 Frozen 必须保持不变");
        assertEquals(MINIMAL_WIRE, payload.canonical(), "入参 Frozen 必须保持不变");
        assertEquals(expected, MapDefinitionCodec.decode(payload), "再次解码仍必须给出同一期望");
    }

    /**
     * 与 {@link #minimalRoot()} 逐字对应的完整 typed 期望，只把 spawn 的几何、resources 与 map 级
     * 未知成员换成给定值；其余成员与 {@link #minimalDefinition()} 相同，全部由公开构造器独立给定。
     */
    private static MapDefinition expectedDefinition(Position spawnPosition, Orientation spawnOrientation,
            List<ResourceRevisionRef> resources, ExactJson.FrozenJson mapMembers) {
        final ExactJson.FrozenJson empty = ExactJson.parse("{}");
        final ExactJson.FrozenJson wireNull = ExactJson.parse("null");
        return new MapDefinition(
                new Ids.DefinitionId(UUID.fromString(DEF_ID)),
                new Ids.WorldId(UUID.fromString(WORLD_ID)),
                MODE,
                DIMENSION,
                "Dust",
                "line1\nline2",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(16, 256, 16)),
                List.of(new TeamDefinition(new Ids.TeamId(UUID.fromString(TEAM_A)), "Red",
                        ExactJson.parse("{\"score\":7}"), wireNull,
                        new WireExtras.TeamExtras(empty))),
                List.of(new SpawnDefinition(new Ids.ObjectId(UUID.fromString(SPAWN_ONE)), "s1",
                        new Ids.TeamId(UUID.fromString(TEAM_A)), DIMENSION, spawnPosition,
                        spawnOrientation, wireNull, new WireExtras.SpawnExtras(empty, empty, empty))),
                List.of(new RegionDefinition(new Ids.ObjectId(UUID.fromString(REGION_ONE)), "zone",
                        REGION_KIND, new BoxCorners(new IntPosition(4, 0, 4), new IntPosition(0, 0, 0)),
                        wireNull, ExactJson.parse("{\"note\":\"x\"}"),
                        new WireExtras.RegionExtras(empty, empty, empty, empty))),
                ExactJson.parse("{\"limit\":5}"),
                resources,
                wireNull,
                new WireExtras.MapExtras(mapMembers, empty, empty, empty));
    }

    // __APPEND__
}
