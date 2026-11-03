package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * R06.1b1c 的正式 encode 契约测试（作者会话 restart-c）。
 *
 * <p>本测试的全部期望 payload 都由本测试自己独立写出，不调用生产 codec、不用其输出推导样本、不做
 * 筛选：typed fixture 在这里按字段构造，expected 文本在这里逐字段拼好，UTF-8 宽度与 SHA-256 由
 * {@link java.nio.charset.StandardCharsets} 与 JDK {@link java.security.MessageDigest} 独立计算。
 * 生产 {@code MapDefinitionCodec} 只作为被测对象，每次断言都真实调用一次 {@code encode}。</p>
 *
 * <p><strong>expected 的书写形态。</strong>{@code encode} 的返回值是冻结的
 * {@link ExactJson.FrozenJson}，其唯一 canonical 文本按 exact-json-r1 定义：object key 按
 * {@link String#compareTo} 排序、array 保序、数字用 {@link ExactJsonNumber} 的最短拼写。因此本测试
 * 的 expected 文本按 key 排序书写（例如 {@code "first"} 在 {@code "gridHint"} 前、{@code "pin"}
 * 在 {@code "x"} 前、{@code "resourceId"} 在 {@code "revision"} 前）。codec 内部 writer 的固定
 * schema 顺序只决定它写出的中间原文，key 排序不改变 byte 数（PAYLOAD-CODEC 第 61 行），故预算与
 * canonical 文本一致。</p>
 *
 * <p>头 14 个 root known、boundary/角点、四 list 与其 owner 的 known/extras 归属严格遵循
 * PAYLOAD-CODEC 表；extras 成员只出现在其所属 owner object 内，不额外写独立 {@code {}} 容器。</p>
 */
class MapDefinitionEncodeContractTest {

    /** 独立期望 wire 的根 id。 */
    private static final UUID DEF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    /** 独立期望 wire 的 worldId。 */
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** 队列中唯一队伍的 TeamId。 */
    private static final UUID TEAM_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    /** 同坐标不同 ObjectId 的第一个出生点。 */
    private static final UUID SPAWN_ONE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /** 同坐标不同 ObjectId 的第二个出生点。 */
    private static final UUID SPAWN_TWO = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    /** 唯一区域的 ObjectId。 */
    private static final UUID REGION_ONE = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    /** 被重复引用的资源身份 UUID。 */
    private static final UUID RESOURCE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** 固定的 64 位小写 ASCII hex digest。 */
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    /** 全图唯一维度。 */
    private static final String DIMENSION = "minecraft:overworld";

    private static final ExactJson.FrozenJson EMPTY = ExactJson.parse("{}");

    /** 冻结 P02 入口只用于构造 typed 输入里的 FrozenJson 载体与固定 JSON 文本。 */
    private static ExactJson.FrozenJson json(String text) {
        return ExactJson.parse(text);
    }

    /**
     * 独立构造的最小实质地图：两出生点同坐标不同 ID、反序角点、重复资源的两个 occurrence 各带不同
     * extras，并且 13 类 extras 位置中有 6 类在此出现不同 future key。
     */
    private static MapDefinition fixture() {
        return fixtureWithRootExtras(json("{\"futureRoot\":true}"));
    }

    /**
     * 与 {@link #fixture()} 逐字段相同的实质地图，只有 root extras 的成员片段由调用方给出；其余
     * 13 类位置保持不变，使 {@link #EXPECTED_WIRE} 在换掉 root extras 成员后仍然逐字节可用。
     */
    private static MapDefinition fixtureWithRootExtras(ExactJson.FrozenJson rootMembers) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(TEAM_A), "Red",
                json("{\"score\":7}"), json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition spawnOne = new SpawnDefinition(new Ids.ObjectId(SPAWN_ONE), "s1",
                new Ids.TeamId(TEAM_A), DIMENSION, new Position(1.5, 0.0, 2.25),
                new Orientation(45.5f, -12.25f), json("{\"traits\":[\"a\"]}"),
                new WireExtras.SpawnExtras(json("{\"futureFlag\":false}"), json("{\"gridX\":1}"),
                        json("{\"snap\":true}")));
        final SpawnDefinition spawnTwo = new SpawnDefinition(new Ids.ObjectId(SPAWN_TWO), "s2",
                new Ids.TeamId(TEAM_A), DIMENSION, new Position(1.5, 0.0, 2.25),
                new Orientation(0.0f, 0.0f), json("null"),
                new WireExtras.SpawnExtras(EMPTY, EMPTY, EMPTY));
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(REGION_ONE), "zone",
                "fpsmatch:spawn_area", new BoxCorners(new IntPosition(4, 0, 4), new IntPosition(0, 0, 0)),
                json("null"), json("{\"note\":\"x\"}"),
                new WireExtras.RegionExtras(EMPTY, EMPTY, json("{\"pin\":[1,2]}"), EMPTY));
        final ResourceRevisionRef firstOccurrence = new ResourceRevisionRef("fpsmatch:model", RESOURCE,
                7L, DIGEST, new WireExtras.ResourceExtras(json("{\"occ\":\"one\"}")));
        final ResourceRevisionRef secondOccurrence = new ResourceRevisionRef("fpsmatch:model", RESOURCE,
                7L, DIGEST, new WireExtras.ResourceExtras(json("{\"occ\":\"two\"}")));
        return new MapDefinition(new Ids.DefinitionId(DEF_ID), new Ids.WorldId(WORLD_ID),
                "fpsmatch:conquest", DIMENSION, "Dust", "line1\nline2",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(16, 256, 16)),
                List.of(team), List.of(spawnOne, spawnTwo), List.of(region), json("{\"limit\":5}"),
                List.of(firstOccurrence, secondOccurrence), json("null"),
                new WireExtras.MapExtras(rootMembers,
                        json("{\"gridHint\":\"legacy\"}"), EMPTY, EMPTY));
    }

    /**
     * 数值最短拼写的最小复现 fixture：boundary 的第二个角点是给定三元组、region 的第二个角点也是它，
     * 资源 revision 取给定 long，其余字段最小合法，13 类 extras 全空。
     */
    private static MapDefinition spellingFixture(int x, int y, int z, long revision) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(TEAM_A), "Red",
                json("{\"score\":7}"), json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(SPAWN_ONE), "s1",
                new Ids.TeamId(TEAM_A), DIMENSION, new Position(1.5, 0.0, 2.25),
                new Orientation(45.5f, -12.25f), json("null"),
                new WireExtras.SpawnExtras(EMPTY, EMPTY, EMPTY));
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(REGION_ONE), "zone",
                "fpsmatch:spawn_area", new BoxCorners(new IntPosition(x, y, z), new IntPosition(0, 0, 0)),
                json("null"), json("null"),
                new WireExtras.RegionExtras(EMPTY, EMPTY, EMPTY, EMPTY));
        final ResourceRevisionRef occurrence = new ResourceRevisionRef("fpsmatch:model", RESOURCE,
                revision, DIGEST, new WireExtras.ResourceExtras(EMPTY));
        return new MapDefinition(new Ids.DefinitionId(DEF_ID), new Ids.WorldId(WORLD_ID),
                "fpsmatch:conquest", DIMENSION, "Dust", "line1\nline2",
                new BoxCorners(new IntPosition(x, y, z), new IntPosition(0, 0, 0)),
                List.of(team), List.of(spawn), List.of(region), json("null"),
                List.of(occurrence), json("null"),
                new WireExtras.MapExtras(EMPTY, EMPTY, EMPTY, EMPTY));
    }

    /**
     * 独立写出的完整 expected canonical payload：成员位置按 exact-json-r1 的 key 排序（本测试自己排序，
     * 从 schema 表逐字段手写），description 的换行按 canonical 短转义写成 {@code \\n}，extras 成员只在
     * 其所属 owner object 内、参与该 owner 的排序。
     */
    private static final String EXPECTED_WIRE = """
            {"boundary":{"first":{"x":0,"y":0,"z":0},"gridHint":"legacy","second":{"x":16,"y":256,"z":16}},"description":"line1\\nline2","dimensionId":"minecraft:overworld","displayName":"Dust","extensions":null,"futureRoot":true,"id":"11111111-1111-1111-1111-111111111111","modeId":"fpsmatch:conquest","regions":[{"config":null,"corners":{"first":{"pin":[1,2],"x":4,"y":0,"z":4},"second":{"x":0,"y":0,"z":0}},"extensions":{"note":"x"},"id":"dddddddd-dddd-dddd-dddd-dddddddddddd","kindId":"fpsmatch:spawn_area","name":"zone"}],"resources":[{"digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","kindId":"fpsmatch:model","occ":"one","resourceId":"33333333-3333-3333-3333-333333333333","revision":7},{"digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","kindId":"fpsmatch:model","occ":"two","resourceId":"33333333-3333-3333-3333-333333333333","revision":7}],"rules":{"limit":5},"schemaVersion":1,"spawns":[{"dimensionId":"minecraft:overworld","extensions":{"traits":["a"]},"futureFlag":false,"id":"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","name":"s1","orientation":{"pitch":-12.25,"snap":true,"yaw":45.5},"position":{"gridX":1,"x":1.5,"y":0,"z":2.25},"teamId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"},{"dimensionId":"minecraft:overworld","extensions":null,"id":"cccccccc-cccc-cccc-cccc-cccccccccccc","name":"s2","orientation":{"pitch":0,"yaw":0},"position":{"x":1.5,"y":0,"z":2.25},"teamId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}],"teams":[{"config":{"score":7},"extensions":null,"id":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","name":"Red"}],"worldId":"22222222-2222-2222-2222-222222222222"}""";

    @Test
    @DisplayName("the minimal map encodes to exactly the independently written canonical wire")
    void minimalMapEncodesToIndependentWire() {
        final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(fixture());
        assertNotNull(payload, "encode must return a certified value");
        assertEquals(EXPECTED_WIRE, payload.canonical(),
                "canonical payload must equal the independent expected wire");
    }

    @Test
    @DisplayName("the certified payload width equals the JDK UTF-8 width of the expected wire")
    void certifiedPayloadWidthMatchesJdkUtf8() {
        final int expectedBytes = EXPECTED_WIRE.getBytes(StandardCharsets.UTF_8).length;
        final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(fixture());
        assertEquals(expectedBytes, payload.utf8Size(),
                "utf8Size must equal the JDK UTF-8 encoding width of the same wire");
        assertTrue(expectedBytes > 0, "the fixture wire must be a real payload");
    }

    @Test
    @DisplayName("a Java null definition is rejected with IllegalArgumentException")
    void nullDefinitionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> MapDefinitionCodec.encode(null));
    }

    // ------------------------------------------------------------------
    // 13 类语义 extras 位置
    // ------------------------------------------------------------------

    private static final UUID RICH_DEF_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID RICH_WORLD_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    private static final UUID RICH_TEAM = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final UUID RICH_SPAWN = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private static final UUID RICH_REGION = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private static final UUID RICH_RESOURCE = UUID.fromString("99999999-9999-9999-9999-999999999999");

    /**
     * 13 类 extras 位置各放一个不同的 future key：root、boundary、boundary 两端点、team、spawn、
     * spawn.position、spawn.orientation、region、region.corners、corners 两端点、resource occurrence。
     * 另外 root 的 extras 里放一个名为 {@code wireExtras} 的普通未知成员，证明 record 组件名不是 reserved。
     */
    private static MapDefinition richFixture() {
        final WireExtras.SpawnExtras spawnExtras = new WireExtras.SpawnExtras(
                json("{\"spawnFuture\":\"p\"}"), json("{\"posFuture\":\"q\"}"),
                json("{\"oriFuture\":\"r\"}"));
        final WireExtras.RegionExtras regionExtras = new WireExtras.RegionExtras(
                json("{\"regionFuture\":\"m\"}"), json("{\"cornersFuture\":\"n\"}"),
                json("{\"cornerFirstFuture\":\"o\"}"), json("{\"cornerSecondFuture\":\"p\"}"));
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(RICH_TEAM), "T",
                json("{\"nest\":{\"id\":1}}"), json("[]"),
                new WireExtras.TeamExtras(json("{\"teamFuture\":\"t\"}")));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(RICH_SPAWN), "S",
                new Ids.TeamId(RICH_TEAM), DIMENSION, new Position(2.0, 1.0, -4.0),
                new Orientation(90.0f, -0.5f), json("null"), spawnExtras);
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(RICH_REGION), "R",
                "fpsmatch:spawn_area", new BoxCorners(new IntPosition(1, 2, 3), new IntPosition(4, 5, 6)),
                json("{\"kindId\":\"nested\"}"), json("\"text\""), regionExtras);
        final ResourceRevisionRef resource = new ResourceRevisionRef("fpsmatch:model", RICH_RESOURCE,
                1L, DIGEST, new WireExtras.ResourceExtras(json("{\"resourceFuture\":\"z\"}")));
        return new MapDefinition(new Ids.DefinitionId(RICH_DEF_ID), new Ids.WorldId(RICH_WORLD_ID),
                "fpsmatch:conquest", DIMENSION, "Rich", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(8, 128, 8)), List.of(team),
                List.of(spawn), List.of(region), json("[1,2,3]"), List.of(resource), json("true"),
                new WireExtras.MapExtras(
                        json("{\"rootFuture\":\"a\",\"wireExtras\":{\"nested\":true}}"),
                        json("{\"boxFuture\":\"b\"}"), json("{\"firstFuture\":\"f\"}"),
                        json("{\"secondFuture\":\"s\"}")));
    }

    /**
     * 13 槽全命中的独立 expected：每个 future key 都落在其所属 owner 内并参与该 owner 的 key 排序
     * （注意 UTF-16 码元序使 {@code oriFuture} 在 {@code orientation} 前、{@code posFuture} 在
     * {@code position} 前、{@code resourceFuture} 在 {@code resourceId} 前；{@code yaw} 的
     * {@code 90.0f} 最短拼写经 canonical 后为 {@code 9E+1}，{@code 2.0/1.0/-4.0} 为 {@code 2/1/-4}）。
     */
    private static final String RICH_EXPECTED_WIRE = """
            {"boundary":{"boxFuture":"b","first":{"firstFuture":"f","x":0,"y":0,"z":0},"second":{"secondFuture":"s","x":8,"y":128,"z":8}},"description":"","dimensionId":"minecraft:overworld","displayName":"Rich","extensions":true,"id":"44444444-4444-4444-4444-444444444444","modeId":"fpsmatch:conquest","regions":[{"config":{"kindId":"nested"},"corners":{"cornersFuture":"n","first":{"cornerFirstFuture":"o","x":1,"y":2,"z":3},"second":{"cornerSecondFuture":"p","x":4,"y":5,"z":6}},"extensions":"text","id":"88888888-8888-8888-8888-888888888888","kindId":"fpsmatch:spawn_area","name":"R","regionFuture":"m"}],"resources":[{"digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","kindId":"fpsmatch:model","resourceFuture":"z","resourceId":"99999999-9999-9999-9999-999999999999","revision":1}],"rootFuture":"a","rules":[1,2,3],"schemaVersion":1,"spawns":[{"dimensionId":"minecraft:overworld","extensions":null,"id":"77777777-7777-7777-7777-777777777777","name":"S","orientation":{"oriFuture":"r","pitch":-0.5,"yaw":9E+1},"position":{"posFuture":"q","x":2,"y":1,"z":-4},"spawnFuture":"p","teamId":"66666666-6666-6666-6666-666666666666"}],"teams":[{"config":{"nest":{"id":1}},"extensions":[],"id":"66666666-6666-6666-6666-666666666666","name":"T","teamFuture":"t"}],"wireExtras":{"nested":true},"worldId":"55555555-5555-5555-5555-555555555555"}""";

    @Test
    @DisplayName("all thirteen extras slots land in their own owner object")
    void allThirteenExtrasSlotsLandInTheirOwnOwner() {
        final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(richFixture());
        assertEquals(RICH_EXPECTED_WIRE, payload.canonical(),
                "every future key must appear inside its own owner object");
    }

    // ------------------------------------------------------------------
    // 每个 owner 的 reserved 冲突：code 与 pointer 必须精确
    // ------------------------------------------------------------------

    private static final UUID SLOT_DEF_ID = UUID.fromString("12121212-1212-1212-1212-121212121212");

    private static final UUID SLOT_WORLD_ID = UUID.fromString("13131313-1313-1313-1313-131313131313");

    private static final UUID SLOT_TEAM = UUID.fromString("14141414-1414-1414-1414-141414141414");

    private static final UUID SLOT_SPAWN = UUID.fromString("15151515-1515-1515-1515-151515151515");

    private static final UUID SLOT_REGION = UUID.fromString("16161616-1616-1616-1616-161616161616");

    private static final UUID SLOT_RESOURCE = UUID.fromString("17171717-1717-1717-1717-171717171717");

    /** 13 个 extras 槽的注入位：默认全空，参数化用例只改其中一个。 */
    private static final class Slots {

        private ExactJson.FrozenJson root = EMPTY;

        private ExactJson.FrozenJson box = EMPTY;

        private ExactJson.FrozenJson boxFirst = EMPTY;

        private ExactJson.FrozenJson boxSecond = EMPTY;

        private ExactJson.FrozenJson team = EMPTY;

        private ExactJson.FrozenJson spawn = EMPTY;

        private ExactJson.FrozenJson spawnPosition = EMPTY;

        private ExactJson.FrozenJson spawnOrientation = EMPTY;

        private ExactJson.FrozenJson region = EMPTY;

        private ExactJson.FrozenJson regionCorners = EMPTY;

        private ExactJson.FrozenJson regionCornersFirst = EMPTY;

        private ExactJson.FrozenJson regionCornersSecond = EMPTY;

        private ExactJson.FrozenJson resource = EMPTY;
    }

    /** 固定的单 team/spawn/region/resource 骨架，13 个槽由参数注入。 */
    private static MapDefinition slotsMap(Slots slots) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(SLOT_TEAM), "T", json("null"),
                json("null"), new WireExtras.TeamExtras(slots.team));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(SLOT_SPAWN), "S",
                new Ids.TeamId(SLOT_TEAM), DIMENSION, new Position(1.0, 2.0, 3.0),
                new Orientation(0.0f, 0.0f), json("null"), new WireExtras.SpawnExtras(slots.spawn,
                        slots.spawnPosition, slots.spawnOrientation));
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(SLOT_REGION), "R",
                "fpsmatch:spawn_area", new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                json("null"), json("null"), new WireExtras.RegionExtras(slots.region, slots.regionCorners,
                        slots.regionCornersFirst, slots.regionCornersSecond));
        final ResourceRevisionRef resource = new ResourceRevisionRef("fpsmatch:model", SLOT_RESOURCE, 1L,
                DIGEST, new WireExtras.ResourceExtras(slots.resource));
        return new MapDefinition(new Ids.DefinitionId(SLOT_DEF_ID), new Ids.WorldId(SLOT_WORLD_ID),
                "fpsmatch:conquest", DIMENSION, "N", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)), List.of(team),
                List.of(spawn), List.of(region), json("null"), List.of(resource), json("null"),
                new WireExtras.MapExtras(slots.root, slots.box, slots.boxFirst, slots.boxSecond));
    }

    /**
     * 13 个 owner 各用自己的一个 reserved key 触发冲突，期望 pointer 是 owner 路径的该 key 子节点。
     */
    private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> reservedCollisions() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("root members", "/teams",
                        (java.util.function.Consumer<Slots>) slots -> slots.root = json("{\"teams\":[]}")),
                org.junit.jupiter.params.provider.Arguments.of("boundary members", "/boundary/first",
                        (java.util.function.Consumer<Slots>) slots -> slots.box = json("{\"first\":null}")),
                org.junit.jupiter.params.provider.Arguments.of("boundary first members",
                        "/boundary/first/x",
                        (java.util.function.Consumer<Slots>) slots -> slots.boxFirst = json("{\"x\":0}")),
                org.junit.jupiter.params.provider.Arguments.of("boundary second members",
                        "/boundary/second/z",
                        (java.util.function.Consumer<Slots>) slots -> slots.boxSecond = json("{\"z\":0}")),
                org.junit.jupiter.params.provider.Arguments.of("team members", "/teams/0/name",
                        (java.util.function.Consumer<Slots>) slots -> slots.team = json("{\"name\":\"n\"}")),
                org.junit.jupiter.params.provider.Arguments.of("spawn members", "/spawns/0/position",
                        (java.util.function.Consumer<Slots>) slots -> slots.spawn = json("{\"position\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("spawn position members",
                        "/spawns/0/position/y",
                        (java.util.function.Consumer<Slots>) slots -> slots.spawnPosition = json("{\"y\":2}")),
                org.junit.jupiter.params.provider.Arguments.of("spawn orientation members",
                        "/spawns/0/orientation/pitch",
                        (java.util.function.Consumer<Slots>) slots -> slots.spawnOrientation = json("{\"pitch\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("region members", "/regions/0/kindId",
                        (java.util.function.Consumer<Slots>) slots -> slots.region = json("{\"kindId\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("region corners members",
                        "/regions/0/corners/second",
                        (java.util.function.Consumer<Slots>) slots -> slots.regionCorners = json("{\"second\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("region corners first members",
                        "/regions/0/corners/first/x",
                        (java.util.function.Consumer<Slots>) slots -> slots.regionCornersFirst = json("{\"x\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("region corners second members",
                        "/regions/0/corners/second/z",
                        (java.util.function.Consumer<Slots>) slots -> slots.regionCornersSecond = json("{\"z\":1}")),
                org.junit.jupiter.params.provider.Arguments.of("resource members", "/resources/0/revision",
                        (java.util.function.Consumer<Slots>) slots -> slots.resource = json("{\"revision\":9}")));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} rejects its reserved key")
    @org.junit.jupiter.params.provider.MethodSource("reservedCollisions")
    @DisplayName("every owner rejects its own reserved key with the exact pointer")
    void eachOwnerRejectsItsOwnReservedKey(String label, String expectedPointer,
            java.util.function.Consumer<Slots> mutation) {
        final Slots slots = new Slots();
        mutation.accept(slots);
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(slotsMap(slots)), label);
        assertEquals(SchemaException.CODE_RESERVED_COLLISION, failure.code(), label);
        assertEquals(expectedPointer, failure.path().pointer(), label);
    }

    private static FieldPath pathOf(String... segments) {
        FieldPath path = FieldPath.root();
        for (String segment : segments) {
            path = path.child(segment);
        }
        return path;
    }

    @Test
    @DisplayName("reserved collision paths are the real owner children built independently")
    void reservedCollisionPathsAreRealOwnerChildren() {
        final FieldPath deep = pathOf("regions", "0", "corners", "first", "x");
        assertEquals("/regions/0/corners/first/x", deep.pointer(),
                "the independently built expectation must itself be a real pointer");
        final Slots slots = new Slots();
        slots.regionCornersFirst = json("{\"x\":1}");
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(slotsMap(slots)));
        assertEquals(deep.pointer(), failure.path().pointer(),
                "the failure path must be the owner child, not a sibling or the owner itself");

        final FieldPath rootChild = pathOf("teams");
        assertEquals("/teams", rootChild.pointer(),
                "the independently built expectation must itself be a real pointer");
        final Slots rootSlots = new Slots();
        rootSlots.root = json("{\"teams\":[]}");
        final SchemaException rootFailure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(slotsMap(rootSlots)));
        assertEquals(rootChild.pointer(), rootFailure.path().pointer(),
                "root extras collisions are reported at the colliding root member");
    }

    /** 只有 fragment 根的 key 参与 reserved 比较：extras body 内的 reserved 同名 key 是合法内容。 */
    @Test
    @DisplayName("reserved names nested inside extras bodies are legal content")
    void reservedNamesInsideExtrasBodiesAreLegal() {
        final MapDefinition definition = new MapDefinition(new Ids.DefinitionId(SLOT_DEF_ID),
                new Ids.WorldId(SLOT_WORLD_ID), "fpsmatch:conquest", DIMENSION, "N", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)), List.of(), List.of(),
                List.of(), json("null"), List.of(), json("null"),
                new WireExtras.MapExtras(
                        json("{\"futureRoot\":{\"first\":{},\"id\":1,\"teams\":[]},\"wireExtras\":true}"),
                        EMPTY, EMPTY, EMPTY));
        final String expected = "{\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"second\":{\"x\":1,\"y\":1,\"z\":1}},\"description\":\"\","
                + "\"dimensionId\":\"minecraft:overworld\",\"displayName\":\"N\",\"extensions\":null,"
                + "\"futureRoot\":{\"first\":{},\"id\":1,\"teams\":[]},"
                + "\"id\":\"12121212-1212-1212-1212-121212121212\",\"modeId\":\"fpsmatch:conquest\","
                + "\"regions\":[],\"resources\":[],\"rules\":null,\"schemaVersion\":1,\"spawns\":[],"
                + "\"teams\":[],\"wireExtras\":true,"
                + "\"worldId\":\"13131313-1313-1313-1313-131313131313\"}";
        assertEquals(expected, MapDefinitionCodec.encode(definition).canonical(),
                "nested reserved names and the wireExtras member must be preserved as ordinary content");
    }

    // ------------------------------------------------------------------
    // 已知数值：double/float 最短形式（含次正规与极值）与负零归一
    // ------------------------------------------------------------------

    /** 数值向量 fixture 的 definition id。 */
    private static final UUID NUM_DEF = UUID.fromString("31313131-3131-3131-3131-313131313131");

    /** 数值向量 fixture 的 worldId。 */
    private static final UUID NUM_WORLD = UUID.fromString("32323232-3232-3232-3232-323232323232");

    /** 数值向量 fixture 唯一队伍的 TeamId。 */
    private static final UUID NUM_TEAM = UUID.fromString("33333333-3333-3333-3333-333333333333");

    /** 数值向量 fixture 唯一出生点的 ObjectId。 */
    private static final UUID NUM_SPAWN = UUID.fromString("34343434-3434-3434-3434-343434343434");

    /** 只带一个 spawn（无 region/resource/extras）的骨架，用于逐向量核对 typed 数值拼写。 */
    private static MapDefinition numericMap(double x, double y, double z, float yaw, float pitch) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(NUM_TEAM), "T", json("null"),
                json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(NUM_SPAWN), "S",
                new Ids.TeamId(NUM_TEAM), DIMENSION, new Position(x, y, z), new Orientation(yaw, pitch),
                json("null"), new WireExtras.SpawnExtras(EMPTY, EMPTY, EMPTY));
        return new MapDefinition(new Ids.DefinitionId(NUM_DEF), new Ids.WorldId(NUM_WORLD),
                "fpsmatch:conquest", DIMENSION, "Num", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)), List.of(team),
                List.of(spawn), List.of(), json("null"), List.of(), json("null"),
                new WireExtras.MapExtras(EMPTY, EMPTY, EMPTY, EMPTY));
    }

    /** 固定 expected wire：五个数字位置由调用方给出的字面量填入，其余全部是本测试写死的文本。 */
    private static String numericWire(String x, String y, String z, String yaw, String pitch) {
        return "{\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"second\":{\"x\":1,\"y\":1,\"z\":1}},\"description\":\"\","
                + "\"dimensionId\":\"minecraft:overworld\",\"displayName\":\"Num\",\"extensions\":null,"
                + "\"id\":\"31313131-3131-3131-3131-313131313131\",\"modeId\":\"fpsmatch:conquest\","
                + "\"regions\":[],\"resources\":[],\"rules\":null,\"schemaVersion\":1,"
                + "\"spawns\":[{\"dimensionId\":\"minecraft:overworld\",\"extensions\":null,"
                + "\"id\":\"34343434-3434-3434-3434-343434343434\",\"name\":\"S\","
                + "\"orientation\":{\"pitch\":" + pitch + ",\"yaw\":" + yaw + "},"
                + "\"position\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "},"
                + "\"teamId\":\"33333333-3333-3333-3333-333333333333\"}],"
                + "\"teams\":[{\"config\":null,\"extensions\":null,"
                + "\"id\":\"33333333-3333-3333-3333-333333333333\",\"name\":\"T\"}],"
                + "\"worldId\":\"32323232-3232-3232-3232-323232323232\"}";
    }

    /** double 向量与它的 canonical 字面量，全部由作者按冻结规则独立算出。 */
    private static Stream<Arguments> positionDoubles() {
        return Stream.of(
                Arguments.of(0.0, "0"),
                Arguments.of(-0.0, "0"),
                Arguments.of(1.0, "1"),
                Arguments.of(-1.0, "-1"),
                Arguments.of(0.5, "0.5"),
                Arguments.of(-12.25, "-12.25"),
                Arguments.of(0.1, "0.1"),
                Arguments.of(0.001, "0.001"),
                Arguments.of(100.0, "1E+2"),
                Arguments.of(700.0, "7E+2"),
                Arguments.of(1.0E-4, "0.0001"),
                Arguments.of(1.0E-5, "0.00001"),
                Arguments.of(1.0E-7, "1E-7"),
                Arguments.of(1.0E20, "1E+20"),
                Arguments.of(123456789.0, "123456789"),
                Arguments.of(4503599627370496.0, "4503599627370496"),
                Arguments.of(1.2345678901234567, "1.2345678901234567"),
                Arguments.of(Double.MIN_VALUE, "4.9E-324"),
                Arguments.of(Double.MAX_VALUE, "1.7976931348623157E+308"));
    }

    /** float 向量与它的 canonical 字面量，全部由作者按冻结规则独立算出。 */
    private static Stream<Arguments> orientationFloats() {
        return Stream.of(
                Arguments.of(0.0f, "0"),
                Arguments.of(-0.0f, "0"),
                Arguments.of(1.0f, "1"),
                Arguments.of(0.5f, "0.5"),
                Arguments.of(-0.5f, "-0.5"),
                Arguments.of(0.001f, "0.001"),
                Arguments.of(90.0f, "9E+1"),
                Arguments.of(123.456f, "123.456"),
                Arguments.of(1.0E7f, "1E+7"),
                Arguments.of(1.0E-4f, "0.0001"),
                Arguments.of(1.0E-5f, "0.00001"),
                Arguments.of(1.0E-7f, "1E-7"),
                Arguments.of(16777216.0f, "16777216"),
                Arguments.of(1.17549435E-38f, "1.17549435E-38"),
                Arguments.of(Float.MIN_VALUE, "1.4E-45"),
                Arguments.of(Float.MAX_VALUE, "3.4028235E+38"));
    }

    @ParameterizedTest(name = "position {0} -> {1}")
    @MethodSource("positionDoubles")
    @DisplayName("known double positions use the frozen shortest canonical spelling")
    void knownDoublesUseCanonicalShortestForm(double value, String expected) {
        final MapDefinition definition = numericMap(value, value, value, 0.0f, 0.0f);
        assertEquals(numericWire(expected, expected, expected, "0", "0"),
                MapDefinitionCodec.encode(definition).canonical(),
                "double " + value + " must encode as " + expected);
    }

    @ParameterizedTest(name = "orientation {0} -> {1}")
    @MethodSource("orientationFloats")
    @DisplayName("known float orientations use the frozen shortest canonical spelling")
    void knownFloatsUseCanonicalShortestForm(float value, String expected) {
        final MapDefinition definition = numericMap(0.0, 0.0, 0.0, value, value);
        assertEquals(numericWire("0", "0", "0", expected, expected),
                MapDefinitionCodec.encode(definition).canonical(),
                "float " + value + " must encode as " + expected);
    }

    // ------------------------------------------------------------------
    // int/long 边界、未知大数与巨指数、输入纯函数性、同一 Frozen 复用累计
    // ------------------------------------------------------------------

    /** 边界向量 fixture 的 definition id。 */
    private static final UUID BOUND_DEF = UUID.fromString("41414141-4141-4141-4141-414141414141");

    /** 边界向量 fixture 的 worldId。 */
    private static final UUID BOUND_WORLD = UUID.fromString("42424242-4242-4242-4242-424242424242");

    /** 边界向量 fixture 的 region ObjectId。 */
    private static final UUID BOUND_REGION = UUID.fromString("43434343-4343-4343-4343-434343434343");

    /** 边界向量 fixture 的 resourceId。 */
    private static final UUID BOUND_RESOURCE = UUID.fromString("45454545-4545-4545-4545-454545454545");

    /** 边界向量 fixture 的 digest（本测试自写的 64 位十六进制）。 */
    private static final String BOUND_DIGEST =
            "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";

    @Test
    @DisplayName("int and long extremes keep their exact integer spelling")
    void intAndLongExtremesKeepExactSpelling() {
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(BOUND_REGION), "R",
                "fpsmatch:spawn_area",
                new BoxCorners(new IntPosition(Integer.MIN_VALUE, Integer.MAX_VALUE, 0),
                        new IntPosition(Integer.MAX_VALUE, Integer.MIN_VALUE, -1)),
                json("null"), json("null"), new WireExtras.RegionExtras(EMPTY, EMPTY, EMPTY, EMPTY));
        // ResourceRevisionRef 的模型不变量是 revision > 0，合法的 long 下界因此是 1，而不是 Long.MIN_VALUE。
        final ResourceRevisionRef low = new ResourceRevisionRef("fpsmatch:model", BOUND_RESOURCE,
                1L, BOUND_DIGEST, new WireExtras.ResourceExtras(EMPTY));
        final ResourceRevisionRef high = new ResourceRevisionRef("fpsmatch:model", BOUND_RESOURCE,
                Long.MAX_VALUE, BOUND_DIGEST, new WireExtras.ResourceExtras(EMPTY));
        final MapDefinition definition = new MapDefinition(new Ids.DefinitionId(BOUND_DEF),
                new Ids.WorldId(BOUND_WORLD), "fpsmatch:conquest", DIMENSION, "B", "",
                new BoxCorners(new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                        new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)),
                List.of(), List.of(), List.of(region), json("null"), List.of(low, high), json("null"),
                new WireExtras.MapExtras(EMPTY, EMPTY, EMPTY, EMPTY));
        final String expected = "{\"boundary\":{\"first\":{\"x\":-2147483648,\"y\":-2147483648,"
                + "\"z\":-2147483648},\"second\":{\"x\":2147483647,\"y\":2147483647,\"z\":2147483647}},"
                + "\"description\":\"\",\"dimensionId\":\"minecraft:overworld\",\"displayName\":\"B\","
                + "\"extensions\":null,\"id\":\"41414141-4141-4141-4141-414141414141\","
                + "\"modeId\":\"fpsmatch:conquest\",\"regions\":[{\"config\":null,\"corners\":{"
                + "\"first\":{\"x\":-2147483648,\"y\":2147483647,\"z\":0},"
                + "\"second\":{\"x\":2147483647,\"y\":-2147483648,\"z\":-1}},\"extensions\":null,"
                + "\"id\":\"43434343-4343-4343-4343-434343434343\",\"kindId\":\"fpsmatch:spawn_area\","
                + "\"name\":\"R\"}],\"resources\":[{\"digest\":\"" + BOUND_DIGEST + "\","
                + "\"kindId\":\"fpsmatch:model\","
                + "\"resourceId\":\"45454545-4545-4545-4545-454545454545\","
                + "\"revision\":1},{\"digest\":\"" + BOUND_DIGEST + "\","
                + "\"kindId\":\"fpsmatch:model\","
                + "\"resourceId\":\"45454545-4545-4545-4545-454545454545\","
                + "\"revision\":9223372036854775807}],\"rules\":null,\"schemaVersion\":1,\"spawns\":[],"
                + "\"teams\":[],\"worldId\":\"42424242-4242-4242-4242-424242424242\"}";
        assertEquals(expected, MapDefinitionCodec.encode(definition).canonical(),
                "Integer/Long extremes must be exact decimal literals, never routed through double");
    }

    @Test
    @DisplayName("unknown big integers and huge exponents survive as exact canonical numbers")
    void unknownNumbersKeepExactCanonicalForm() {
        final MapDefinition definition = new MapDefinition(new Ids.DefinitionId(BOUND_DEF),
                new Ids.WorldId(BOUND_WORLD), "fpsmatch:conquest", DIMENSION, "Big", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)), List.of(), List.of(),
                List.of(), json("null"), List.of(), json("null"),
                new WireExtras.MapExtras(json("{\"big\":12345678901234567890123456789012345678901234567897,"
                        + "\"bigZero\":12345678901234567890123456789012345678901234567890,"
                        + "\"huge\":1e1000000,\"tiny\":1e-1000000,\"plain\":1.5000,\"negZero\":-0.0,"
                        + "\"two\":2.0,\"pow\":1e5}"), EMPTY, EMPTY, EMPTY));
        final String expected = "{\"big\":12345678901234567890123456789012345678901234567897,"
                + "\"bigZero\":1.234567890123456789012345678901234567890123456789E+49,"
                + "\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"second\":{\"x\":1,\"y\":1,\"z\":1}},\"description\":\"\","
                + "\"dimensionId\":\"minecraft:overworld\",\"displayName\":\"Big\",\"extensions\":null,"
                + "\"huge\":1E+1000000,\"id\":\"41414141-4141-4141-4141-414141414141\","
                + "\"modeId\":\"fpsmatch:conquest\",\"negZero\":0,\"plain\":1.5,\"pow\":1E+5,"
                + "\"regions\":[],\"resources\":[],\"rules\":null,\"schemaVersion\":1,\"spawns\":[],"
                + "\"teams\":[],\"tiny\":1E-1000000,\"two\":2,\"worldId\":\"42424242-4242-4242-4242-"
                + "424242424242\"}";
        assertEquals(expected, MapDefinitionCodec.encode(definition).canonical(),
                "unknown numbers must keep their exact value in canonical spelling, not float precision");
    }

    @Test
    @DisplayName("encoding is pure: the caller's Frozen values and repeated calls stay identical")
    void encodingIsPureAndIdempotent() {
        final ExactJson.FrozenJson rootExtras = json("{\"futureRoot\":true}");
        final ExactJson.FrozenJson teamConfig = json("{\"score\":7}");
        final ExactJson.FrozenJson rules = json("{\"limit\":5}");
        final ExactJson.FrozenJson extensions = json("{\"note\":\"x\"}");
        final ExactJson.FrozenJson spawnExtras = json("{\"futureFlag\":false}");
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(NUM_TEAM), "T", teamConfig,
                json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(NUM_SPAWN), "S",
                new Ids.TeamId(NUM_TEAM), DIMENSION, new Position(1.5, 0.0, 2.25),
                new Orientation(45.5f, -12.25f), json("null"),
                new WireExtras.SpawnExtras(spawnExtras, EMPTY, EMPTY));
        final MapDefinition definition = new MapDefinition(new Ids.DefinitionId(NUM_DEF),
                new Ids.WorldId(NUM_WORLD), "fpsmatch:conquest", DIMENSION, "Num", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(team), List.of(spawn), List.of(), rules, List.of(), extensions,
                new WireExtras.MapExtras(rootExtras, EMPTY, EMPTY, EMPTY));
        final String rootText = rootExtras.canonical();
        final String configText = teamConfig.canonical();
        final String rulesText = rules.canonical();
        final String extensionsText = extensions.canonical();
        final String spawnText = spawnExtras.canonical();
        final int rootBytes = rootExtras.utf8Size();
        final ExactJson.FrozenJson first = MapDefinitionCodec.encode(definition);
        assertEquals(rootText, rootExtras.canonical(), "the root extras Frozen must not be rewritten");
        assertEquals(configText, teamConfig.canonical(), "the team config Frozen must not be rewritten");
        assertEquals(rulesText, rules.canonical(), "the rules Frozen must not be rewritten");
        assertEquals(extensionsText, extensions.canonical(), "the extensions Frozen must not be rewritten");
        assertEquals(spawnText, spawnExtras.canonical(), "the spawn extras Frozen must not be rewritten");
        assertEquals(rootBytes, rootExtras.utf8Size(),
                "the caller's Frozen utf8Size must not be overwritten by the payload budget");
        assertEquals(new Position(1.5, 0.0, 2.25), definition.spawns().get(0).position(),
                "typed geometry must not be replaced while encoding");
        assertEquals(new Orientation(45.5f, -12.25f), definition.spawns().get(0).orientation(),
                "typed orientation must not be replaced while encoding");
        assertEquals("T", definition.teams().get(0).name(), "typed scalars must not be replaced");
        final ExactJson.FrozenJson second = MapDefinitionCodec.encode(definition);
        assertEquals(first.canonical(), second.canonical(),
                "two encodes of one definition must produce the same canonical wire");
        assertEquals(first.utf8Size(), second.utf8Size(),
                "two encodes of one definition must report the same width");
    }

    /** 复用同一 Frozen 实例的 fixture 的 definition id。 */
    private static final UUID SHARE_DEF = UUID.fromString("51515151-5151-5151-5151-515151515151");

    /** 复用同一 Frozen 实例的 fixture 的 worldId。 */
    private static final UUID SHARE_WORLD = UUID.fromString("52525252-5252-5252-5252-525252525252");

    /** 复用同一 Frozen 实例的 fixture 的 TeamId。 */
    private static final UUID SHARE_TEAM = UUID.fromString("53535353-5353-5353-5353-535353535353");

    /** 复用同一 Frozen 实例的第一个出生点。 */
    private static final UUID SHARE_ONE = UUID.fromString("54545454-5454-5454-5454-545454545454");

    /** 复用同一 Frozen 实例的第二个出生点。 */
    private static final UUID SHARE_TWO = UUID.fromString("56565656-5656-5656-5656-565656565656");

    /** 数出 needle 在 haystack 中出现的次数（本测试自己的计数，不依赖生产类型）。 */
    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            final int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    @Test
    @DisplayName("one shared Frozen instance is charged and written once per occurrence")
    void sharedFrozenIsChargedPerOccurrence() {
        final ExactJson.FrozenJson shared = json("{\"sharedTag\":\"0123456789\"}");
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(SHARE_TEAM), "T", json("null"),
                json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition one = new SpawnDefinition(new Ids.ObjectId(SHARE_ONE), "s1",
                new Ids.TeamId(SHARE_TEAM), DIMENSION, new Position(1.0, 2.0, 3.0),
                new Orientation(0.0f, 0.0f), json("null"),
                new WireExtras.SpawnExtras(shared, EMPTY, EMPTY));
        final SpawnDefinition two = new SpawnDefinition(new Ids.ObjectId(SHARE_TWO), "s2",
                new Ids.TeamId(SHARE_TEAM), DIMENSION, new Position(4.0, 5.0, 6.0),
                new Orientation(0.0f, 0.0f), json("null"),
                new WireExtras.SpawnExtras(shared, EMPTY, EMPTY));
        final MapDefinition definition = new MapDefinition(new Ids.DefinitionId(SHARE_DEF),
                new Ids.WorldId(SHARE_WORLD), "fpsmatch:conquest", DIMENSION, "Share", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(team), List.of(one, two), List.of(), json("null"), List.of(), json("null"),
                new WireExtras.MapExtras(EMPTY, EMPTY, EMPTY, EMPTY));
        final String expected = "{\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},"
                + "\"second\":{\"x\":1,\"y\":1,\"z\":1}},\"description\":\"\","
                + "\"dimensionId\":\"minecraft:overworld\",\"displayName\":\"Share\",\"extensions\":null,"
                + "\"id\":\"51515151-5151-5151-5151-515151515151\",\"modeId\":\"fpsmatch:conquest\","
                + "\"regions\":[],\"resources\":[],\"rules\":null,\"schemaVersion\":1,"
                + "\"spawns\":[{\"dimensionId\":\"minecraft:overworld\",\"extensions\":null,"
                + "\"id\":\"54545454-5454-5454-5454-545454545454\",\"name\":\"s1\","
                + "\"orientation\":{\"pitch\":0,\"yaw\":0},\"position\":{\"x\":1,\"y\":2,\"z\":3},"
                + "\"sharedTag\":\"0123456789\","
                + "\"teamId\":\"53535353-5353-5353-5353-535353535353\"},"
                + "{\"dimensionId\":\"minecraft:overworld\",\"extensions\":null,"
                + "\"id\":\"56565656-5656-5656-5656-565656565656\",\"name\":\"s2\","
                + "\"orientation\":{\"pitch\":0,\"yaw\":0},\"position\":{\"x\":4,\"y\":5,\"z\":6},"
                + "\"sharedTag\":\"0123456789\","
                + "\"teamId\":\"53535353-5353-5353-5353-535353535353\"}],"
                + "\"teams\":[{\"config\":null,\"extensions\":null,"
                + "\"id\":\"53535353-5353-5353-5353-535353535353\",\"name\":\"T\"}],"
                + "\"worldId\":\"52525252-5252-5252-5252-525252525252\"}";
        final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(definition);
        assertEquals(2, occurrences(expected, "\"sharedTag\":\"0123456789\""),
                "the independent expectation must really hold two occurrences");
        assertEquals(expected, payload.canonical(),
                "a reused Frozen must be written into every owner that holds it");
        assertEquals(expected.getBytes(StandardCharsets.UTF_8).length, payload.utf8Size(),
                "the shared fragment must be charged once per occurrence in the budget");
        assertEquals("{\"sharedTag\":\"0123456789\"}", shared.canonical(),
                "the shared Frozen itself must stay untouched");
    }

    // ------------------------------------------------------------------
    // 真实 owner 深度偏移与 64 层精确边界
    // ------------------------------------------------------------------

    /** 深度探针 fixture 的 definition id。 */
    private static final UUID DEPTH_DEF = UUID.fromString("61616161-6161-6161-6161-616161616161");

    /** 深度探针 fixture 的 worldId。 */
    private static final UUID DEPTH_WORLD = UUID.fromString("62626262-6262-6262-6262-626262626262");

    /** 深度探针 fixture 的 TeamId。 */
    private static final UUID DEPTH_TEAM = UUID.fromString("63636363-6363-6363-6363-636363636363");

    /** 深度探针 fixture 的出生点 ObjectId。 */
    private static final UUID DEPTH_SPAWN = UUID.fromString("64646464-6464-6464-6464-646464646464");

    /**
     * 生成一个 extras 片段文本：其最深的值恰好位于该片段的相对深度 {@code deepest}。片段根是
     * {@code {"deep":...}}，因此 {@code deepest-1} 层数组之后的最内层标量即位于相对深度 deepest。
     */
    private static String nestedNumberText(int deepest) {
        final StringBuilder text = new StringBuilder("{\"deep\":");
        for (int i = 1; i < deepest; i++) {
            text.append('[');
        }
        text.append('0');
        for (int i = 1; i < deepest; i++) {
            text.append(']');
        }
        return text.append('}').toString();
    }

    /** 与 {@link #nestedNumberText(int)} 同文本的已认证片段。 */
    private static ExactJson.FrozenJson nestedNumber(int deepest) {
        return json(nestedNumberText(deepest));
    }

    /**
     * 片段被合并进 owner 对象后出现在 wire 里的成员文本：去掉片段根的 {@code {}} 包裹层，
     * 因为 extras 成员是同层并入 owner，而不是作为子对象写入。
     */
    private static String nestedMemberText(int deepest) {
        final String fragment = nestedNumberText(deepest);
        return fragment.substring(1, fragment.length() - 1);
    }

    /**
     * 深度探针地图：一个队伍、一个出生点（0,0,0）与 null 朝向，root extras、rules 与出生点
     * position 的 extras 由调用方给出，其余位置全部为空。position 的 owner 真实深度是 3。
     */
    private static MapDefinition depthProbeMap(ExactJson.FrozenJson rootExtras,
            ExactJson.FrozenJson rules, ExactJson.FrozenJson positionMembers) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(DEPTH_TEAM), "T", json("null"),
                json("null"), new WireExtras.TeamExtras(EMPTY));
        final SpawnDefinition spawn = new SpawnDefinition(new Ids.ObjectId(DEPTH_SPAWN), "S",
                new Ids.TeamId(DEPTH_TEAM), DIMENSION, new Position(0.0, 0.0, 0.0),
                new Orientation(0.0f, 0.0f), json("null"),
                new WireExtras.SpawnExtras(EMPTY, positionMembers, EMPTY));
        return new MapDefinition(new Ids.DefinitionId(DEPTH_DEF), new Ids.WorldId(DEPTH_WORLD),
                "fpsmatch:conquest", DIMENSION, "D", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(team), List.of(spawn), List.of(), rules, List.of(), json("null"),
                new WireExtras.MapExtras(rootExtras, EMPTY, EMPTY, EMPTY));
    }

    @Test
    @DisplayName("extras depth is measured from the real owner depth, not from the wrapper")
    void extrasDepthUsesTheRealOwnerOffset() {
        final ExactJson.FrozenJson atRoot =
                MapDefinitionCodec.encode(depthProbeMap(nestedNumber(64), json("null"), EMPTY));
        assertEquals(depthProbeWire(nestedNumber(64), json("null"), EMPTY), atRoot.canonical(),
                "a relative depth 64 fragment at the root extras owner (0+64=64) must write the"
                        + " independently rebuilt wire byte for byte");
        final ExactJson.FrozenJson atPosition = MapDefinitionCodec.encode(
                depthProbeMap(EMPTY, json("null"), nestedNumber(61)));
        assertEquals(depthProbeWire(EMPTY, json("null"), nestedNumber(61)), atPosition.canonical(),
                "relative depth 61 under the position owner (3+61=64) must still write the whole wire");
        final SchemaException failure = assertThrows(SchemaException.class, () -> MapDefinitionCodec
                .encode(depthProbeMap(EMPTY, json("null"), nestedNumber(62))));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "relative depth 62 under the position owner must exceed the depth contract (3+62=65)");
        assertEquals("/spawns/0/position", failure.path().pointer(),
                "a fragment failure is reported at its insertion position, not at an invented child");
        assertEquals(65, reportedRealDepth(failure.getMessage()),
                "the reported real depth must be 3+62=65, not the relative 62: " + failure.getMessage());
    }

    @Test
    @DisplayName("a root level Frozen value is measured from its real wire depth")
    void frozenValueDepthUsesItsRealPosition() {
        final ExactJson.FrozenJson legal =
                MapDefinitionCodec.encode(depthProbeMap(EMPTY, nestedNumber(63), EMPTY));
        assertEquals(depthProbeWire(EMPTY, nestedNumber(63), EMPTY), legal.canonical(),
                "a rules fragment of relative depth 63 at wire depth 1 (1+63=64) must write the whole wire");
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(depthProbeMap(EMPTY, nestedNumber(64), EMPTY)));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "a rules fragment of relative depth 64 must exceed the depth contract (1+64=65)");
        assertEquals("/rules", failure.path().pointer(),
                "the rules depth failure must name the real wire position /rules");
        assertEquals(65, reportedRealDepth(failure.getMessage()),
                "the reported real depth must be 1+64=65: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 13 个 owner 的真实深度：成功侧对着完整独立 wire，失败侧对着精确 owner 指针
    // ------------------------------------------------------------------

    /** 一个 owner 注入位：显示名、它在 wire 里的真实深度、失败时被点名的 owner 指针与注入动作。 */
    private static final class OwnerSlot {

        private final String name;

        private final int depth;

        private final String pointer;

        private final java.util.function.BiConsumer<Slots, ExactJson.FrozenJson> set;

        private OwnerSlot(String name, int depth, String pointer,
                java.util.function.BiConsumer<Slots, ExactJson.FrozenJson> set) {
            this.name = name;
            this.depth = depth;
            this.pointer = pointer;
            this.set = set;
        }

        @Override
        public String toString() {
            return this.name + " (owner depth " + this.depth + ", owner " + this.pointer + ")";
        }
    }

    /**
     * 13 个 extras owner 的真实深度与指针，全部由 schema 结构手工数出：根对象的 extras 并入根对象，
     * 所以它在根对象里是深度 0；boundary 对象在深度 1，list 元素在深度 2，出生点 position/orientation
     * 与 region 的 corners 在深度 3，corners 的两个角点在深度 4。这里不查 codec、不读预算实现。
     */
    private static final List<OwnerSlot> OWNER_SLOTS = List.of(
            new OwnerSlot("root extras", 0, "", (slots, value) -> slots.root = value),
            new OwnerSlot("boundary extras", 1, "/boundary", (slots, value) -> slots.box = value),
            new OwnerSlot("boundary first extras", 2, "/boundary/first",
                    (slots, value) -> slots.boxFirst = value),
            new OwnerSlot("boundary second extras", 2, "/boundary/second",
                    (slots, value) -> slots.boxSecond = value),
            new OwnerSlot("team extras", 2, "/teams/0", (slots, value) -> slots.team = value),
            new OwnerSlot("spawn extras", 2, "/spawns/0", (slots, value) -> slots.spawn = value),
            new OwnerSlot("spawn position extras", 3, "/spawns/0/position",
                    (slots, value) -> slots.spawnPosition = value),
            new OwnerSlot("spawn orientation extras", 3, "/spawns/0/orientation",
                    (slots, value) -> slots.spawnOrientation = value),
            new OwnerSlot("region extras", 2, "/regions/0", (slots, value) -> slots.region = value),
            new OwnerSlot("region corners extras", 3, "/regions/0/corners",
                    (slots, value) -> slots.regionCorners = value),
            new OwnerSlot("region corners first extras", 4, "/regions/0/corners/first",
                    (slots, value) -> slots.regionCornersFirst = value),
            new OwnerSlot("region corners second extras", 4, "/regions/0/corners/second",
                    (slots, value) -> slots.regionCornersSecond = value),
            new OwnerSlot("resource extras", 2, "/resources/0", (slots, value) -> slots.resource = value));

    private static Stream<Arguments> ownerSlots() {
        return OWNER_SLOTS.stream().map(owner -> Arguments.of(owner));
    }

    /**
     * 逐个 owner 复核 64 这条边界：注入口径是「片段自身的相对深度」，所以合法半径是 {@code 64-ownerDepth}
     * ——这个片段自己不超过 64，P02 一定收得下，只有并入 owner 之后的真实深度才可能越界。非 root 的越界侧
     * 用 {@code 65-ownerDepth}（片段自身仍然合法）并真的跑一次 encode，要求它以精确的 owner 指针报 65。
     *
     * <p>root 的 0+65 落在片段自己身上：P02 会在 codec 之前就用 {@code LIMIT_EXCEEDED} 拒绝整段文本，
     * 因此这里只声明片段自身的边界，明确不把这次拒绝当作 codec 的深度判定，也不当作 codec 拒绝超深的证据。</p>
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} keeps the depth boundary at 64")
    @org.junit.jupiter.params.provider.MethodSource("ownerSlots")
    @DisplayName("every extras owner measures depth from its own real wire position")
    void extrasDepthIsMeasuredFromEveryOwnersRealPosition(OwnerSlot owner) {
        final int legalRelative = 64 - owner.depth;
        final Slots legalSlots = new Slots();
        owner.set.accept(legalSlots, nestedNumber(legalRelative));
        // 完整期望 wire 先由测试侧文本拼好，之后才允许调用 codec。
        final String expected = slotsWireText(legalSlots);
        assertEquals(expected, MapDefinitionCodec.encode(slotsMap(legalSlots)).canonical(),
                owner + " must accept relative depth " + legalRelative + " and write the independent wire");

        if (owner.depth == 0) {
            // 片段自身的相对深度 65 已被 P02 拒掉，与 codec 的真实深度检查无关。
            final JsonContractException preRejected =
                    assertThrows(JsonContractException.class, () -> nestedNumber(65));
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, preRejected.code(),
                    "a standalone fragment whose own deepest value is 65 is refused by P02 before the codec");
            return;
        }

        final int illegalRelative = 65 - owner.depth;
        final Slots illegalSlots = new Slots();
        owner.set.accept(illegalSlots, nestedNumber(illegalRelative));
        final MapDefinition illegal = slotsMap(illegalSlots);
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(illegal),
                owner + " must refuse relative depth " + illegalRelative);
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(), owner.toString());
        assertEquals(owner.pointer, failure.path().pointer(),
                owner + " must report its own owner object, not an invented child");
        assertEquals(65, reportedRealDepth(failure.getMessage()),
                owner + " must report the real depth " + (owner.depth + illegalRelative)
                        + ", not the relative " + illegalRelative + ": " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 独立期望 wire：13 槽骨架与深度探针骨架的完整 canonical 文本
    // ------------------------------------------------------------------

    /** 一个 box 的独立 canonical 文本：两个 int 角点各自带 extras，以及该 box 自己的 extras。 */
    private static String wireBox(int x1, int y1, int z1, int x2, int y2, int z2,
            String firstExtras, String secondExtras, String boxExtras) {
        final TreeMap<String, String> box = new TreeMap<>();
        box.put("first", intPosition(x1, y1, z1, firstExtras));
        box.put("second", intPosition(x2, y2, z2, secondExtras));
        putMembers(box, boxExtras, "box");
        return canonicalObject(box);
    }

    /** 一个 team object 的独立 canonical 文本。 */
    private static String wireTeam(UUID id, String name, String config, String extensions, String extras) {
        final TreeMap<String, String> team = new TreeMap<>();
        team.put("id", quote(id.toString()));
        team.put("name", quote(name));
        team.put("config", config);
        team.put("extensions", extensions);
        putMembers(team, extras, "team");
        return canonicalObject(team);
    }

    /** 一个 spawn object 的独立 canonical 文本：坐标按最短数字拼写，position/orientation 各带 extras。 */
    private static String wireSpawn(UUID id, String name, UUID teamId, double x, double y, double z,
            float yaw, float pitch, String extensions, String spawnExtras, String positionExtras,
            String orientationExtras) {
        final TreeMap<String, String> spawn = new TreeMap<>();
        spawn.put("id", quote(id.toString()));
        spawn.put("name", quote(name));
        spawn.put("teamId", quote(teamId.toString()));
        spawn.put("dimensionId", quote(DIMENSION));
        spawn.put("position", doublePosition(x, y, z, positionExtras));
        spawn.put("orientation", orientation(yaw, pitch, orientationExtras));
        spawn.put("extensions", extensions);
        putMembers(spawn, spawnExtras, "spawn");
        return canonicalObject(spawn);
    }

    /** 一个 region object 的独立 canonical 文本：corners 的两个角点各自带 extras。 */
    private static String wireRegion(UUID id, String name, String kindId, int x1, int y1, int z1,
            int x2, int y2, int z2, String config, String extensions, String regionExtras,
            String cornersExtras, String firstExtras, String secondExtras) {
        final TreeMap<String, String> region = new TreeMap<>();
        region.put("id", quote(id.toString()));
        region.put("name", quote(name));
        region.put("kindId", quote(kindId));
        region.put("corners", wireBox(x1, y1, z1, x2, y2, z2, firstExtras, secondExtras, cornersExtras));
        region.put("config", config);
        region.put("extensions", extensions);
        putMembers(region, regionExtras, "region");
        return canonicalObject(region);
    }

    /** 一个 resource object 的独立 canonical 文本：digest 与 revision 都是测试侧字面量。 */
    private static String wireResource(String kindId, UUID resourceId, long revision, String digest,
            String extras) {
        final TreeMap<String, String> resource = new TreeMap<>();
        resource.put("kindId", quote(kindId));
        resource.put("resourceId", quote(resourceId.toString()));
        resource.put("revision", canonicalLong(revision));
        resource.put("digest", quote(digest));
        putMembers(resource, extras, "resource");
        return canonicalObject(resource);
    }

    /** 深度探针骨架（{@link #depthProbeMap}）的完整独立 canonical wire。 */
    private static String depthProbeWire(ExactJson.FrozenJson rootExtras, ExactJson.FrozenJson rules,
            ExactJson.FrozenJson positionMembers) {
        final TreeMap<String, String> root = new TreeMap<>();
        root.put("boundary", wireBox(0, 0, 0, 1, 1, 1, "", "", ""));
        root.put("description", quote(""));
        root.put("dimensionId", quote(DIMENSION));
        root.put("displayName", quote("D"));
        root.put("extensions", "null");
        root.put("id", quote(DEPTH_DEF.toString()));
        root.put("modeId", quote("fpsmatch:conquest"));
        root.put("regions", canonicalArray(List.<String>of()));
        root.put("resources", canonicalArray(List.<String>of()));
        root.put("rules", rules.canonical());
        root.put("schemaVersion", canonicalInt(1));
        root.put("spawns", canonicalArray(List.of(wireSpawn(DEPTH_SPAWN, "S", DEPTH_TEAM, 0.0, 0.0, 0.0,
                0.0f, 0.0f, "null", "", givenMemberText(positionMembers), ""))));
        root.put("teams", canonicalArray(List.of(wireTeam(DEPTH_TEAM, "T", "null", "null", ""))));
        root.put("worldId", quote(DEPTH_WORLD.toString()));
        putMembers(root, givenMemberText(rootExtras), "root");
        return canonicalObject(root);
    }

    /** 13 槽骨架（{@link #slotsMap}）的完整独立 canonical wire：每个槽的成员按给定 canonical 并入各自 owner。 */
    private static String slotsWireText(Slots slots) {
        final TreeMap<String, String> root = new TreeMap<>();
        root.put("boundary", wireBox(0, 0, 0, 1, 1, 1, givenMemberText(slots.boxFirst),
                givenMemberText(slots.boxSecond), givenMemberText(slots.box)));
        root.put("description", quote(""));
        root.put("dimensionId", quote(DIMENSION));
        root.put("displayName", quote("N"));
        root.put("extensions", "null");
        root.put("id", quote(SLOT_DEF_ID.toString()));
        root.put("modeId", quote("fpsmatch:conquest"));
        root.put("regions", canonicalArray(List.of(wireRegion(SLOT_REGION, "R", "fpsmatch:spawn_area",
                0, 0, 0, 1, 1, 1, "null", "null", givenMemberText(slots.region),
                givenMemberText(slots.regionCorners), givenMemberText(slots.regionCornersFirst),
                givenMemberText(slots.regionCornersSecond)))));
        root.put("resources", canonicalArray(List.of(wireResource("fpsmatch:model", SLOT_RESOURCE, 1L,
                DIGEST, givenMemberText(slots.resource)))));
        root.put("rules", "null");
        root.put("schemaVersion", canonicalInt(1));
        root.put("spawns", canonicalArray(List.of(wireSpawn(SLOT_SPAWN, "S", SLOT_TEAM, 1.0, 2.0, 3.0,
                0.0f, 0.0f, "null", givenMemberText(slots.spawn), givenMemberText(slots.spawnPosition),
                givenMemberText(slots.spawnOrientation)))));
        root.put("teams", canonicalArray(List.of(wireTeam(SLOT_TEAM, "T", "null", "null",
                givenMemberText(slots.team)))));
        root.put("worldId", quote(SLOT_WORLD_ID.toString()));
        putMembers(root, givenMemberText(slots.root), "root");
        return canonicalObject(root);
    }

    // ------------------------------------------------------------------
    // 节点预算：独立计数与 500000 的 MAX-1 / MAX / MAX+1
    // ------------------------------------------------------------------

    /**
     * 独立统计一段 canonical JSON 文本的值节点数：每个 object、array、string、number 与 literal 各
     * 算一个节点，object 的 key 不算节点。只用一个最小状态机识别结构与转义，不解释语义、不调用生产
     * 计数 API。
     */
    private static int countJsonValues(String text) {
        int nodes = 0;
        int cursor = 0;
        final int length = text.length();
        while (cursor < length) {
            final char current = text.charAt(cursor);
            if (current == '"') {
                cursor++;
                while (cursor < length) {
                    final char inner = text.charAt(cursor);
                    if (inner == '\\') {
                        cursor += 2;
                        continue;
                    }
                    cursor++;
                    if (inner == '"') {
                        break;
                    }
                }
                int probe = cursor;
                while (probe < length && text.charAt(probe) == ' ') {
                    probe++;
                }
                if (probe >= length || text.charAt(probe) != ':') {
                    nodes++; // 不是 key，而是一个 string 值。
                }
                continue;
            }
            if (current == '{' || current == '[') {
                nodes++;
                cursor++;
                continue;
            }
            if (current == '}' || current == ']' || current == ',' || current == ':' || current == ' ') {
                cursor++;
                continue;
            }
            final int start = cursor;
            while (cursor < length) {
                final char scalar = text.charAt(cursor);
                if (scalar == ',' || scalar == '}' || scalar == ']' || scalar == ' ') {
                    break;
                }
                cursor++;
            }
            if (cursor > start) {
                nodes++;
            }
        }
        return nodes;
    }

    /** 成员文本 {@code "many":[0,...]}；{@code count} 至少为 1。 */
    private static String manyExtrasMember(long count) {
        final StringBuilder text = new StringBuilder((int) (2L * count) + 16);
        text.append("\"many\":[");
        for (long i = 0; i < count; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append('0');
        }
        return text.append(']').toString();
    }

    /** 与 {@link #manyExtrasMember(long)} 同文本的已认证 root extras 成员片段。 */
    private static ExactJson.FrozenJson manyExtras(long count) {
        return json("{" + manyExtrasMember(count) + "}");
    }

    /**
     * 逐字节期望 wire：把 {@link #EXPECTED_WIRE} 里 root extras 的 {@code "futureRoot":true} 换成本
     * 测试自己拼出的 many 数组成员，并按 key 排序把它插到 {@code "modeId"} 之前。
     */
    private static String manyExtrasWire(long count) {
        final String withoutFuture = EXPECTED_WIRE.replace("\"futureRoot\":true,", "");
        return withoutFuture.replace("\"modeId\":", manyExtrasMember(count) + ",\"modeId\":");
    }

    @Test
    @Tag("large-heap")
    @DisplayName("node accounting matches the real canonical value count at the 500000 boundary")
    void nodeAccountingMatchesTheRealValueCount() {
        assertEquals(5, countJsonValues("{\"a\":1,\"b\":[2,3]}"),
                "the independent counter must be checked before it is trusted");
        assertEquals(3, countJsonValues("{\"k\":\"v\",\"n\":null}"),
                "string values and literals count as nodes while keys do not");
        final int skeletonNodes = countJsonValues(EXPECTED_WIRE);
        final long maxCount = 500000L - skeletonNodes;
        assertEquals(500000, countJsonValues(manyExtrasWire(maxCount)),
                "the assembled expected wire must sit exactly on the node limit");
        assertEquals(manyExtrasWire(maxCount), MapDefinitionCodec
                .encode(fixtureWithRootExtras(manyExtras(maxCount))).canonical(),
                "a payload of exactly 500000 nodes must be written byte for byte");
        assertEquals(499999, countJsonValues(manyExtrasWire(maxCount - 1)));
        assertEquals(manyExtrasWire(maxCount - 1), MapDefinitionCodec
                .encode(fixtureWithRootExtras(manyExtras(maxCount - 1))).canonical(),
                "a payload of 499999 nodes must be written byte for byte");
        assertEquals(500001, countJsonValues(manyExtrasWire(maxCount + 1)));
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(fixtureWithRootExtras(manyExtras(maxCount + 1))));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "500001 nodes must be rejected as a limit breach");
        assertEquals("", failure.path().pointer(),
                "the root extras fragment is charged at the document root, not at an invented child");
        assertTrue(failure.getMessage().contains("500001") && failure.getMessage().contains("500000"),
                "the rejection must report the real overflow: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 字节预算：8 MiB 的 MAX-1 / MAX / MAX+1（宽度由 JDK 独立测量）
    // ------------------------------------------------------------------

    /** 契约的 payload 字节上限，按契约字面量独立写出。 */
    private static final int MAX_PAYLOAD_BYTES = 8388608;

    /** 契约的单个字符串值字节上限，按契约字面量独立写出。 */
    private static final int MAX_STRING_BYTES = 65536;

    /** 契约的 payload 值节点上限，按契约字面量独立写出。 */
    private static final long NODE_LIMIT = 500000L;

    /** 契约的容器嵌套深度上限，按契约字面量独立写出（根是深度 0）。 */
    private static final int MAX_DEPTH = 64;

    /**
     * 从真实深度拒绝消息里抽出「真实 wire 深度」与「契约允许的最大深度」两个整数，并当场断言
     * 契约上限就是 {@link #MAX_DEPTH}。用整数比较代替子串包含，165 之类的短串不再可能蒙混过关。
     */
    private static int reportedRealDepth(String message) {
        final Matcher matcher = Pattern
                .compile("the real wire depth would be (\\d+), the contract allows at most (\\d+)")
                .matcher(message == null ? "" : message);
        if (!matcher.find()) {
            throw new AssertionError("the depth rejection must spell both integers: " + message);
        }
        assertEquals(MAX_DEPTH, Integer.parseInt(matcher.group(2)),
                "the depth rejection must name the real limit " + MAX_DEPTH + ": " + message);
        return Integer.parseInt(matcher.group(1));
    }

    /** 用 JDK 独立测量文本的 UTF-8 宽度。 */
    private static int utf8Bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 用 JDK 对给定的 canonical UTF-8 字节独立算 SHA-256，输出固定小写 hex。输入永远是本测试自己拼出
     * 的 expected wire 的字节，绝不取 codec 的输出文本。
     */
    private static String sha256Hex(byte[] utf8) {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(utf8);
        } catch (java.security.NoSuchAlgorithmException missing) {
            throw new AssertionError("the JDK must provide SHA-256", missing);
        }
        final StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte octet : digest) {
            hex.append(Character.forDigit((octet >> 4) & 0xF, 16));
            hex.append(Character.forDigit(octet & 0xF, 16));
        }
        return hex.toString();
    }

    /** 追加一个 {@code "bigNNNN":"aaa…"} 成员；key 宽度固定 7 字符，故字典序等于数字序。 */
    private static void appendBigMember(StringBuilder text, int index, int valueLength) {
        text.append('"').append(String.format(java.util.Locale.ROOT, "big%04d", index)).append("\":\"");
        for (int i = 0; i < valueLength; i++) {
            text.append('a');
        }
        text.append('"');
    }

    /**
     * root extras 的成员文本：{@code fullCount} 个正好 64 KiB 的 big 字符串，若 {@code tailLength}
     * 为正再追加一个该长度的尾串（key 序号紧随其后）。
     */
    private static String bigExtrasMembers(int fullCount, int tailLength) {
        final int capacity = fullCount * (MAX_STRING_BYTES + 16) + tailLength + 32;
        final StringBuilder text = new StringBuilder(capacity);
        for (int i = 0; i < fullCount; i++) {
            if (i > 0) {
                text.append(',');
            }
            appendBigMember(text, i, MAX_STRING_BYTES);
        }
        if (tailLength > 0) {
            text.append(',');
            appendBigMember(text, fullCount, tailLength);
        }
        return text.toString();
    }

    /** 与 {@link #bigExtrasMembers(int, int)} 同文本的已认证 root extras 成员片段。 */
    private static ExactJson.FrozenJson bigExtras(int fullCount, int tailLength) {
        return json("{" + bigExtrasMembers(fullCount, tailLength) + "}");
    }

    /**
     * 逐字节期望 wire：root extras 成员全部排在已知 root key 之前（{@code "big…"} 的字典序小于
     * {@code "boundary"}），其余部分与 {@link #EXPECTED_WIRE} 去掉 {@code "futureRoot":true} 后相同。
     */
    private static String bigExtrasWire(int fullCount, int tailLength) {
        final String base = EXPECTED_WIRE.replace("\"futureRoot\":true,", "");
        return "{" + bigExtrasMembers(fullCount, tailLength) + "," + base.substring(1);
    }

    @Test
    @Tag("large-heap")
    @DisplayName("the byte budget stops exactly at the 8388608 byte limit")
    void byteBudgetStopsAtTheByteLimit() {
        final int fullCount = 127;
        final int withoutTail = utf8Bytes(bigExtrasWire(fullCount, 0));
        // 追加一个尾成员要额外写 1 个逗号、2 个引号、7 字符 key、冒号与 2 个值引号，共 13 字节 + 值长。
        final int tailLength = MAX_PAYLOAD_BYTES - withoutTail - 13;
        assertTrue(tailLength >= 1 && tailLength <= MAX_STRING_BYTES,
                "the tail string must stay inside the per string limit: " + tailLength);
        assertEquals(MAX_PAYLOAD_BYTES, utf8Bytes(bigExtrasWire(fullCount, tailLength)),
                "the assembled expected wire must sit exactly on the byte limit");
        assertEquals(MAX_PAYLOAD_BYTES - 1, utf8Bytes(bigExtrasWire(fullCount, tailLength - 1)));
        assertEquals(MAX_PAYLOAD_BYTES + 1, utf8Bytes(bigExtrasWire(fullCount, tailLength + 1)));

        final ExactJson.FrozenJson atMax = MapDefinitionCodec
                .encode(fixtureWithRootExtras(bigExtras(fullCount, tailLength)));
        assertEquals(bigExtrasWire(fullCount, tailLength), atMax.canonical(),
                "a payload of exactly 8388608 bytes must be written byte for byte");
        assertEquals(MAX_PAYLOAD_BYTES, atMax.utf8Size(),
                "the certified payload must report exactly 8388608 UTF-8 bytes");

        final ExactJson.FrozenJson underMax = MapDefinitionCodec
                .encode(fixtureWithRootExtras(bigExtras(fullCount, tailLength - 1)));
        assertEquals(bigExtrasWire(fullCount, tailLength - 1), underMax.canonical(),
                "a payload of 8388607 bytes must be written byte for byte");
        assertEquals(MAX_PAYLOAD_BYTES - 1, underMax.utf8Size());

        final SchemaException failure = assertThrows(SchemaException.class, () -> MapDefinitionCodec
                .encode(fixtureWithRootExtras(bigExtras(fullCount, tailLength + 1))));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "8388609 bytes must be rejected as a limit breach");
        assertEquals("", failure.path().pointer(),
                "the root extras fragment is charged at the document root, not at an invented child");
        assertTrue(failure.getMessage().contains("8388609") && failure.getMessage().contains("8388608"),
                "the rejection must report the real overflow: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // 64MiB 小堆下的真实预flight拒绝：多个各自合法的共享片段按出现次数累积越过 8MiB/50万节点。
    //
    // 样本必须落在“模型接受、codec 自己拒”的区间里：MapDefinition 构造期还带一个只计 extras 成员
    // 全文（另加已知 frozen carrier）的下界守门，它不看字段名、标点与节点数，因此本节把出现次数定在
    // 该下界之内（另有显式断言），再让 R06.1 codec 的完整 preflight 去拒绝同一张图。
    // ------------------------------------------------------------------

    /** 累积探针地图的 definition id。 */
    private static final UUID ACC_DEF = UUID.fromString("71717171-7171-7171-7171-717171717171");

    /** 累积探针地图的 worldId。 */
    private static final UUID ACC_WORLD = UUID.fromString("72727272-7272-7272-7272-727272727272");

    /** 累积探针地图的 TeamId。 */
    private static final UUID ACC_TEAM = UUID.fromString("73737373-7373-7373-7373-737373737373");

    /** 出生点 ObjectId 的高 64 位：低 64 位按序号递增，既保证唯一，又使 36 字符的 canonical 宽度恒定。 */
    private static final long ACC_SPAWN_HIGH = 0x7474747474747474L;

    /** 资源身份 UUID 的高 64 位：低 64 位按序号递增。 */
    private static final long ACC_RES_HIGH = 0x7575757575757575L;

    /** 累积探针的 digest：本测试自己给出的固定重复 16 字符模式。 */
    private static final String ACC_DIGEST = "0123456789abcdef".repeat(4);

    /** 一个只有一个成员、值长由调用方给定的合法片段。 */
    private static ExactJson.FrozenJson filledFragment(String key, int valueLength) {
        return json("{\"" + key + "\":\"" + "a".repeat(valueLength) + "\"}");
    }

    /** 与 {@link #filledFragment(String, int)} 同文本的 canonical 字节宽度：括号、quoted key、冒号、quoted value。 */
    private static int filledFragmentBytes(String key, int valueLength) {
        return 1 + utf8Bytes(key) + 2 + 1 + valueLength + 2 + 1;
    }

    /**
     * 累积探针地图：{@code spawnCount} 个出生点交替共享两个合法大片段，另加 {@code resourceCount} 个
     * occurrence 资源共享第三个合法大片段。输入侧只有这三个片段对象本身，wire 侧每出现一次就按整段
     * 累加一遍；{@code withFragments} 为 false 时全部换成空片段，作为同一张图的合法对照。
     */
    private static MapDefinition accumulatedFragmentMap(int spawnCount, int resourceCount,
            ExactJson.FrozenJson evenSpawn, ExactJson.FrozenJson oddSpawn,
            ExactJson.FrozenJson resourceMembers, boolean withFragments) {
        final TeamDefinition team = new TeamDefinition(new Ids.TeamId(ACC_TEAM), "T", json("null"),
                json("null"), new WireExtras.TeamExtras(EMPTY));
        final List<SpawnDefinition> spawns = new ArrayList<>(spawnCount);
        for (int i = 0; i < spawnCount; i++) {
            final ExactJson.FrozenJson extras = !withFragments ? EMPTY
                    : (i % 2 == 0 ? evenSpawn : oddSpawn);
            spawns.add(new SpawnDefinition(new Ids.ObjectId(new UUID(ACC_SPAWN_HIGH, i)), "s",
                    new Ids.TeamId(ACC_TEAM), DIMENSION, new Position(0.0, 0.0, 0.0),
                    new Orientation(0.0f, 0.0f), json("null"),
                    new WireExtras.SpawnExtras(extras, EMPTY, EMPTY)));
        }
        final List<ResourceRevisionRef> resources = new ArrayList<>(resourceCount);
        for (int i = 0; i < resourceCount; i++) {
            resources.add(new ResourceRevisionRef("fpsmatch:model", new UUID(ACC_RES_HIGH, i), 1L,
                    ACC_DIGEST, new WireExtras.ResourceExtras(withFragments ? resourceMembers : EMPTY)));
        }
        return new MapDefinition(new Ids.DefinitionId(ACC_DEF), new Ids.WorldId(ACC_WORLD),
                "fpsmatch:conquest", DIMENSION, "Acc", "",
                new BoxCorners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                List.of(team), spawns, List.of(), json("null"), resources, json("null"),
                new WireExtras.MapExtras(EMPTY, EMPTY, EMPTY, EMPTY));
    }

    /** 从真实拒绝文本里取出“the payload would hold N &lt;unit&gt;”里的 N，用于独立核对溢出量。 */
    private static long reportedCount(String message, String unit) {
        final String marker = "the payload would hold ";
        final int start = message.indexOf(marker);
        assertTrue(start >= 0, "the rejection must report the would be count: " + message);
        final int from = start + marker.length();
        final int end = message.indexOf(' ', from);
        assertTrue(end > from, "the count must be followed by a unit: " + message);
        final long value = Long.parseLong(message.substring(from, end));
        assertTrue(message.startsWith(unit, end + 1),
                "the rejection must report " + unit + ": " + message);
        return value;
    }

    /** 片段在 wire 里贡献的成员字节：{@link #filledFragmentBytes(String, int)} 去掉两个容器括号。 */
    private static int filledMemberBytes(String key, int valueLength) {
        return filledFragmentBytes(key, valueLength) - 2;
    }

    /** 一个 extras 载体（片段对象）按给定 canonical 并入 owner 同层时的成员文本：去掉最外层容器括号。 */
    private static String givenMemberText(ExactJson.FrozenJson fragment) {
        final String canonical = fragment.canonical();
        assertTrue(canonical.length() >= 2 && canonical.charAt(0) == '{'
                        && canonical.charAt(canonical.length() - 1) == '}',
                "an extras carrier must be an object literal: " + canonical);
        return canonical.substring(1, canonical.length() - 1);
    }

    /**
     * 累积探针骨架的独立 canonical wire 文本：字段、UUID、数字拼写与数组顺序全部由测试侧字面量搭建，extras
     * 成员按调用方给定的 canonical 并入所属 owner，再与该 owner 的已知成员同表按 {@link String#compareTo}
     * 排序（片段根对象只是合并语义的载体，不作为子对象写入）。
     *
     * <p>这里只读 typed fixture 与 {@link ExactJson} 的输入侧文本：不调用 {@code MapDefinitionCodec}、不读
     * {@code PayloadWireBudget}、不借用任何生产 walker 的计费数字。本节全部预期先由本方法算出，之后才允许调用
     * codec 做 actual 侧对照。走序前缀与累计计费另按固定 schema 的已知增量在测试侧求和，不依赖 canonical 文本
     * 的前缀（两者顺序不同，前缀不可互换）。
     */
    private static String accWireText(int spawnCount, int resourceCount,
            ExactJson.FrozenJson evenSpawn, ExactJson.FrozenJson oddSpawn,
            ExactJson.FrozenJson resourceMembers, boolean withFragments) {
        final TreeMap<String, String> root = new TreeMap<>();
        final TreeMap<String, String> boundary = new TreeMap<>();
        boundary.put("first", intPosition(0, 0, 0, ""));
        boundary.put("second", intPosition(1, 1, 1, ""));
        root.put("boundary", canonicalObject(boundary));
        root.put("description", quote(""));
        root.put("dimensionId", quote(DIMENSION));
        root.put("displayName", quote("Acc"));
        root.put("extensions", "null");
        root.put("id", quote(ACC_DEF.toString()));
        root.put("modeId", quote("fpsmatch:conquest"));
        root.put("regions", canonicalArray(List.<String>of()));

        final List<String> resources = new ArrayList<>(resourceCount);
        for (int i = 0; i < resourceCount; i++) {
            final TreeMap<String, String> resource = new TreeMap<>();
            resource.put("digest", quote(ACC_DIGEST));
            resource.put("kindId", quote("fpsmatch:model"));
            resource.put("resourceId", quote(new UUID(ACC_RES_HIGH, i).toString()));
            resource.put("revision", canonicalLong(1L));
            if (withFragments) {
                putMembers(resource, givenMemberText(resourceMembers), "accumulated resource");
            }
            resources.add(canonicalObject(resource));
        }
        root.put("resources", canonicalArray(resources));
        root.put("rules", "null");
        root.put("schemaVersion", canonicalInt(1));

        final List<String> spawns = new ArrayList<>(spawnCount);
        for (int i = 0; i < spawnCount; i++) {
            final TreeMap<String, String> spawn = new TreeMap<>();
            spawn.put("dimensionId", quote(DIMENSION));
            spawn.put("extensions", "null");
            spawn.put("id", quote(new UUID(ACC_SPAWN_HIGH, i).toString()));
            spawn.put("name", quote("s"));
            spawn.put("orientation", orientation(0.0f, 0.0f, ""));
            spawn.put("position", doublePosition(0.0, 0.0, 0.0, ""));
            spawn.put("teamId", quote(ACC_TEAM.toString()));
            if (withFragments) {
                putMembers(spawn, givenMemberText(i % 2 == 0 ? evenSpawn : oddSpawn), "accumulated spawn");
            }
            spawns.add(canonicalObject(spawn));
        }
        root.put("spawns", canonicalArray(spawns));

        final TreeMap<String, String> team = new TreeMap<>();
        team.put("config", "null");
        team.put("extensions", "null");
        team.put("id", quote(ACC_TEAM.toString()));
        team.put("name", quote("T"));
        root.put("teams", canonicalArray(List.of(canonicalObject(team))));
        root.put("worldId", quote(ACC_WORLD.toString()));
        return canonicalObject(root);
    }

    /** 同骨架、全空 extras 的对照图独立 wire 宽度：只数本测试自己拼出的文本的 JDK UTF-8 字节。 */
    private static int controlWireBytes(int spawnCount) {
        return utf8Bytes(accWireText(spawnCount, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false));
    }

    /** 同骨架、全空 extras 的对照图独立值计数：只数本测试自己拼出的文本，用测试侧词法计数器。 */
    private static int controlValueCount(int spawnCount) {
        return countJsonValues(accWireText(spawnCount, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false));
    }

    /** 带真实共享片段的同骨架样本的独立 wire 宽度：片段在测试侧文本里按出现次数整段累加。 */
    private static int sampleWireBytes(int spawnCount, ExactJson.FrozenJson even, ExactJson.FrozenJson odd,
            ExactJson.FrozenJson resource) {
        return utf8Bytes(accWireText(spawnCount, CONTROL_RESOURCES, even, odd, resource, true));
    }

    /** 生产对照样本的真实 canonical 文本：只在 actual 侧做交叉核对，绝不参与任何预期。 */
    private static String actualWire(int spawnCount, int resourceCount, ExactJson.FrozenJson even,
            ExactJson.FrozenJson odd, ExactJson.FrozenJson resource, boolean withFragments) {
        return MapDefinitionCodec
                .encode(accumulatedFragmentMap(spawnCount, resourceCount, even, odd, resource, withFragments))
                .canonical();
    }

    /** 生产对照样本的真实全 wire 字节：只在 actual 侧做交叉核对，绝不参与任何预期。 */
    private static int actualWireBytes(int spawnCount, int resourceCount, ExactJson.FrozenJson even,
            ExactJson.FrozenJson odd, ExactJson.FrozenJson resource, boolean withFragments) {
        return MapDefinitionCodec
                .encode(accumulatedFragmentMap(spawnCount, resourceCount, even, odd, resource, withFragments))
                .utf8Size();
    }

    /** 累积探针里的资源共享出现次数，与构造调用保持一致。 */
    private static final int CONTROL_RESOURCES = 4;

    @Test
    @DisplayName("several individually legal shared fragments are rejected once their occurrences pass 8 MiB")
    void accumulatedSharedFragmentsAreRejectedPastTheByteLimit() {
        final int spawnCount = 1000;
        final int evenLength = 8298;
        final int oddLength = 8294;
        final ExactJson.FrozenJson even = filledFragment("f1", evenLength);
        final ExactJson.FrozenJson odd = filledFragment("f2", oddLength);
        final ExactJson.FrozenJson resource = filledFragment("f3", 693);
        final int evenMember = filledMemberBytes("f1", evenLength);
        final int oddMember = filledMemberBytes("f2", oddLength);
        final int resourceMember = filledMemberBytes("f3", 693);
        assertEquals(8305, evenMember, "the independent member arithmetic must match this test's own literal");
        assertEquals(8301, oddMember, "the independent member arithmetic must match this test's own literal");
        assertEquals(700, resourceMember, "the independent member arithmetic must match this test's own literal");
        assertEquals(8307, even.utf8Size(), "the fragment must equal its independently spelled text width");
        assertEquals(8303, odd.utf8Size(), "the fragment must equal its independently spelled text width");
        assertEquals(702, resource.utf8Size(), "the fragment must equal its independently spelled text width");
        assertTrue(evenLength < MAX_STRING_BYTES && oddLength < MAX_STRING_BYTES && 693 < MAX_STRING_BYTES,
                "every shared fragment value must stay individually legal");
        assertTrue(evenMember + oddMember + resourceMember < 20_000,
                "the whole input side must stay a few kilobytes, so no giant tree or wire can be materialised first");

        // 对照图（全空 extras）自身合法；走序里第一个出生点与后续出生点的真实增量在 0/1/2/3 个对象上完全一致。
        // 本方法先在测试侧文本上把全部预期（对照宽度、走序前缀、越界序号、区间端点）独立定死，之后才允许
        // 调用 codec：actual 侧交叉核对集中在下面的“实际调用”段里，绝不参与任何预期数字的来源。
        final int controlZero = controlWireBytes(0);
        final int controlOne = controlWireBytes(1);
        final int controlTwo = controlWireBytes(2);
        final int knownFirst = controlOne - controlZero;
        final int knownStep = controlTwo - controlOne;
        assertEquals(knownStep, controlWireBytes(3) - controlTwo,
                "one more spawn must cost one fixed wire delta");
        assertTrue(knownStep > 0, "a spawn must cost real wire bytes");
        assertEquals(1129, controlZero, "the independent control text must spell this literal width");
        assertEquals(223, knownFirst, "the first spawn must cost this independently counted width");
        assertEquals(224, knownStep, "one later spawn must cost one extra array commas' worth of bytes");

        // 走序把 regions/rules/resources/extensions 排在 spawns 之后，而这张图的这四个成员都是空容器或空值，
        // 字节可以逐字写出来。走序里这四个成员都不是第一个成员，每个成员前面都各有一个分隔逗号，所以尾部
        // 片段的真实长度 = 四段正文 + 四个逗号。fixture 的根 extras 写作 null（不是 {}），这里按 given 正文
        // 逐字给出，不再沿用旧的 {} 字面量。这一段长度只由测试侧字面量与 JDK UTF-8 宽度给出；canonical 排序
        // 文本的前缀不是 schema 走序前缀，两者在这里被明确分开使用。
        final int emptyTailBytes = 1 + utf8Bytes("\"regions\":[]") + 1 + utf8Bytes("\"rules\":null") + 1
                + utf8Bytes("\"resources\":[]") + 1 + utf8Bytes("\"extensions\":null");
        assertEquals(12 + 12 + 14 + 17 + 4, emptyTailBytes,
                "the four empty tail members must be exactly these four literals plus four separator commas");
        assertEquals(59, emptyTailBytes, "the four empty tail members with their commas must be these wire bytes");
        // 零出生点零资源的同骨架独立文本 = 走序里第一个出生点之前的全部累计 + 这四个空尾部成员。
        final int emptyTailMap = utf8Bytes(accWireText(0, 0, EMPTY, EMPTY, EMPTY, false));
        final long walkPrefix = emptyTailMap - emptyTailBytes;
        assertEquals(454, emptyTailMap, "the independent zero spawn zero resource text must spell this width");
        assertEquals(395, walkPrefix, "everything walked before the first spawn must be this many bytes");
        assertTrue(walkPrefix > 0 && walkPrefix <= controlZero,
                "everything walked before the first spawn must sit between nothing and the empty control: "
                        + walkPrefix + " control " + controlZero);

        // 模型下界只看 extras 成员全文：整张图必须留在该下界之内，才轮得到 codec 的完整 preflight 拒绝。
        final long lowerBound = (long) (spawnCount / 2) * evenMember + (long) (spawnCount / 2) * oddMember
                + (long) CONTROL_RESOURCES * resourceMember;
        assertEquals(8_305_800L, lowerBound, "the literals must spell this sample's own model lower bound");
        assertTrue(lowerBound + 64_000L <= MAX_PAYLOAD_BYTES,
                "the model lower bound must stay legal with room for the model's own carriers: " + lowerBound);
        // 出现次数翻倍后模型层先拒，证明本样本正落在“模型接受、codec 自己拒”的边界区间里。
        assertThrows(IllegalArgumentException.class,
                () -> accumulatedFragmentMap(spawnCount * 2, CONTROL_RESOURCES, even, odd, resource, true),
                "twice as many occurrences must be refused by the model lower bound before any codec runs");

        // 字节上限跑在走序累计上：spawns 排在 regions/rules/resources/extensions 之前，所以先证明光凭出生点
        // 自己就会越界，越界必然落在出生点段里，而不是被尾部成员抢先。
        final long[] walk = new long[spawnCount + 1];
        for (int i = 0; i < spawnCount; i++) {
            final int known = (i == 0) ? knownFirst : knownStep;
            walk[i + 1] = walk[i] + known + 1L + ((i % 2 == 0) ? evenMember : oddMember);
        }
        assertTrue(walk[spawnCount] > MAX_PAYLOAD_BYTES,
                "the shared occurrences by themselves must exceed the byte limit: " + walk[spawnCount]);
        // 走序求和必须等于测试侧独立文本之差：64 个出现的小样本整条文本由 accWireText 独立拼出，两端都不碰 codec。
        final int scaleSpawns = 64;
        assertEquals(walk[scaleSpawns],
                utf8Bytes(accWireText(scaleSpawns, CONTROL_RESOURCES, even, odd, resource, true))
                        - utf8Bytes(accWireText(0, CONTROL_RESOURCES, even, odd, resource, true)),
                "the schema walk sum of " + scaleSpawns
                        + " occurrences must equal the independent text difference");

        // 越界之前的走序前缀 K 只能夹在 (0, controlZero] 里（controlZero 把尾部计费也算进去了），于是首个放不
        // 下的出现序号 i 必然满足 walk[i] <= 上限 < walk[i+1] + controlZero；样本尺寸选得让这个集合只剩一个
        // 元素，出现序号因此可以不依赖 K 而唯一确定，并且可以被测试侧公式直接点名。
        final List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < spawnCount; i++) {
            if (walk[i] <= MAX_PAYLOAD_BYTES && walk[i + 1] + controlZero > MAX_PAYLOAD_BYTES) {
                candidates.add(i);
            }
        }
        assertEquals(1, candidates.size(),
                "the chosen sizes must leave exactly one occurrence that can be the first breach: " + candidates);
        final int crossing = candidates.get(0);
        assertEquals(983, crossing, "the independently predicted first occurrence that cannot fit");
        assertTrue(crossing < spawnCount - 5, "the crossing must sit well inside the spawn list: " + crossing);
        // 用真实前缀复核同一序号，并把两个端点写成精确值：越界之前的累计放得下，越过该出现的整段计费放不下。
        final long crossingStart = walkPrefix + walk[crossing];
        final long crossingEnd = walkPrefix + walk[crossing + 1];
        assertEquals(8_383_420L, crossingStart,
                "the charge walked before occurrence " + crossing + " must be this exact many bytes");
        assertTrue(crossingStart <= MAX_PAYLOAD_BYTES,
                "everything walked before occurrence " + crossing + " must still fit: " + crossingStart);
        assertEquals(8_391_946L, crossingEnd,
                "occurrence " + crossing + " must end at this exact cumulative charge");
        assertTrue(crossingEnd > MAX_PAYLOAD_BYTES,
                "that occurrence must be the first one that cannot fit: " + crossingEnd);

        // 公式必须与真实小样本逐步一致：一次出现给 wire 增加的字节 = 已知部分 + 一个连接逗号 + 片段 body。
        final int sampleOne = sampleWireBytes(1, even, odd, resource);
        final int sampleTwo = sampleWireBytes(2, even, odd, resource);
        final int sampleThree = sampleWireBytes(3, even, odd, resource);
        final int sampleFour = sampleWireBytes(4, even, odd, resource);
        final int sampleFive = sampleWireBytes(5, even, odd, resource);
        final int stepEven = sampleThree - sampleTwo;
        final int stepOdd = sampleTwo - sampleOne;
        // 资源片段排在出生点之后，只影响总长：先单独量出每个资源出现连同连接逗号的字节。
        assertEquals(CONTROL_RESOURCES * (1 + resourceMember),
                sampleWireBytes(0, even, odd, resource) - controlZero,
                "every shared resource occurrence must add one connector and its own body");
        assertEquals(knownFirst + 1 + evenMember, sampleOne - sampleWireBytes(0, even, odd, resource),
                "the first occurrence must add one connector and its own body: "
                        + (sampleOne - sampleWireBytes(0, even, odd, resource)));
        assertEquals(stepEven, sampleFive - sampleFour,
                "two even occurrences must cost the very same wire step");
        assertEquals(stepOdd, sampleFour - sampleThree,
                "two odd occurrences must cost the very same wire step");
        assertEquals(knownStep + 1 + evenMember, stepEven,
                "one occurrence must add one separator, one connector and its own body: measured "
                        + stepEven + " control1 " + controlOne + " known " + knownStep);
        assertEquals(knownStep + 1 + oddMember, stepOdd,
                "one occurrence must add one separator, one connector and its own body: measured "
                        + stepOdd + " control1 " + controlOne + " known " + knownStep);

        // 1000 出生点对照图（资源数仍是 CONTROL_RESOURCES，四个 extras 空片段与 fixture 的 null 一致）的完整文本
        // 与它的 JDK UTF-8 宽度同样必须在任何 codec 调用之前算好并保存：下面的 actual 段落只消费这两个已定死的
        // 值，不在 encode 之后再拼预期文本，也不在 encode 之后再量一次宽度。
        final String controlText = accWireText(spawnCount, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false);
        final int controlTextBytes = utf8Bytes(controlText);
        assertTrue(controlTextBytes > controlZero,
                "the thousand spawn control must stay wider than the zero spawn control: "
                        + controlTextBytes + " vs " + controlZero);

        // ---- 实际调用：对照宽度、尾部前缀 395、越界序号 983、两个端点 8383420/8391946 与样本步长都已由
        //      测试侧独立文本定死，从这里开始才允许调用 codec 做 actual 侧交叉核对。 ----
        assertEquals(controlZero, actualWireBytes(0, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false),
                "the codec must agree with the independent control wire of zero spawns");
        assertEquals(controlOne, actualWireBytes(1, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false),
                "the codec must agree with the independent control wire of one spawn");
        assertEquals(controlTwo, actualWireBytes(2, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false),
                "the codec must agree with the independent control wire of two spawns");
        assertEquals(accWireText(0, 0, EMPTY, EMPTY, EMPTY, false),
                actualWire(0, 0, EMPTY, EMPTY, EMPTY, false),
                "the codec must write the independently spelled zero spawn zero resource wire byte for byte");
        assertEquals(accWireText(scaleSpawns, CONTROL_RESOURCES, even, odd, resource, true),
                actualWire(scaleSpawns, CONTROL_RESOURCES, even, odd, resource, true),
                "the codec must write the independent wire of " + scaleSpawns + " occurrences byte for byte");
        for (int m = 0; m <= 5; m++) {
            assertEquals(accWireText(m, CONTROL_RESOURCES, even, odd, resource, true),
                    actualWire(m, CONTROL_RESOURCES, even, odd, resource, true),
                    "the codec must write the independently spelled wire of sample " + m);
        }

        final ExactJson.FrozenJson control = MapDefinitionCodec
                .encode(accumulatedFragmentMap(spawnCount, CONTROL_RESOURCES, even, odd, resource, false));
        assertTrue(control.utf8Size() < MAX_PAYLOAD_BYTES,
                "the same thousand spawns without fragments must stay legal: " + control.utf8Size());
        assertEquals(controlTextBytes, control.utf8Size(),
                "the thousand spawn control wire must equal the independent control text width");
        assertEquals(controlText, control.canonical(),
                "the thousand spawn control wire must equal the independent control text byte for byte");

        final MapDefinition accumulated =
                accumulatedFragmentMap(spawnCount, CONTROL_RESOURCES, even, odd, resource, true);
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(accumulated));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "accumulated occurrences of legal fragments must breach the byte limit");
        final long reported = reportedCount(failure.getMessage(), "bytes");
        assertTrue(reported > MAX_PAYLOAD_BYTES && reported <= crossingEnd,
                "the breach must be reported by the charge of the predicted occurrence: " + reported
                        + " predicted " + crossingStart + " -> " + crossingEnd);
        assertTrue(failure.getMessage().contains(String.valueOf(MAX_PAYLOAD_BYTES)),
                "the rejection must name the real limit: " + failure.getMessage());
        assertEquals("/spawns/" + crossing, failure.path().pointer(),
                "the crossing must be located at the independently predicted occurrence (candidates "
                        + candidates + " walk prefix " + walkPrefix + " of at most " + controlZero
                        + ", reported " + reported + " of at most " + crossingEnd + ", steps "
                        + stepEven + "/" + stepOdd
                        + " sample1/2/3 " + sampleOne + "/" + sampleTwo + "/" + sampleThree + ")");
        assertEquals("{\"f1\":\"" + "a".repeat(evenLength) + "\"}", even.canonical(),
                "the shared fragment itself must stay untouched");
        assertEquals(8307, even.utf8Size(), "the shared fragment itself must stay untouched");
        assertEquals(8303, odd.utf8Size(), "the shared fragment itself must stay untouched");
        assertEquals(702, resource.utf8Size(), "the shared fragment itself must stay untouched");
    }

    /** {@code values} 个零元素的合法数组片段文本：只用一个成员，但成员里含 {@code values} 个值。 */
    private static String zeroArrayFragmentText(int values) {
        final StringBuilder text = new StringBuilder(values * 2 + 8);
        text.append("{\"k\":[");
        for (int i = 0; i < values; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append('0');
        }
        return text.append("]}").toString();
    }

    @Test
    @DisplayName("one small fragment shared across many occurrences is rejected once nodes pass 500000")
    void accumulatedSharedFragmentNodesAreRejectedPastTheNodeLimit() {
        final int spawnCount = 120;
        final String fragmentText = zeroArrayFragmentText(5000);
        final ExactJson.FrozenJson fragment = json(fragmentText);
        // 片段自身的两项独立事实：逐值数出的节点数（object 容器不计入），以及 JDK 量出的字节宽度。
        final int fragmentNodes = countJsonValues(fragmentText) - 1;
        final int fragmentBytes = utf8Bytes(fragmentText);
        assertEquals(5001, fragmentNodes,
                "the independent value counter must see the object, the array and five thousand numbers");
        assertEquals(2 * 5000 + 7, fragmentBytes, "the independent byte arithmetic must match this literal");
        assertTrue(fragmentBytes < MAX_STRING_BYTES,
                "the shared fragment must stay a small legal fragment: " + fragmentBytes);

        // 走序前缀与每个出生点的已知费用都由测试侧独立骨架逐值数出：前缀 = spawns 数组（含它自己）之前
        // 的全部已知值（根对象 1、7 个根标量、boundary 的 9 个值、teams 的 6 个值、spawns 数组容器 1），
        // 出生点 = 一个出生点对象的全部已知成员值。两段骨架都只由测试侧文本给出，与完整对照图无关；
        // 完整对照图的全文计数只在下面用一条独立文本等式复核这两个口径。
        final int prefixNodes = countJsonValues("{\"schemaVersion\":1,\"id\":\"i\",\"worldId\":\"w\","
                + "\"modeId\":\"m\",\"dimensionId\":\"d\",\"displayName\":\"n\",\"description\":null,"
                + "\"boundary\":{\"first\":{\"x\":0,\"y\":0,\"z\":0},\"second\":{\"x\":0,\"y\":0,\"z\":0}},"
                + "\"teams\":[{\"config\":null,\"extensions\":null,\"id\":\"t\",\"name\":\"T\"}],"
                + "\"spawns\":[]}");
        assertEquals(24, prefixNodes,
                "everything walked before the spawns array must hold exactly these known values");
        final int spawnNodes = countJsonValues("{\"dimensionId\":\"d\",\"extensions\":null,\"id\":\"s\","
                + "\"name\":\"S\",\"orientation\":{\"pitch\":0,\"yaw\":0},"
                + "\"position\":{\"x\":0,\"y\":0,\"z\":0},\"teamId\":\"t\"}");
        assertEquals(13, spawnNodes, "one spawn object's known members must hold exactly these values");
        // spawns 之后的三段空尾部与一个资源对象的独立值计数，只用于把上面的登记口径和完整对照文本对齐。
        final int emptyTailNodes = countJsonValues("{\"regions\":[],\"rules\":null,\"extensions\":null}") - 1;
        final int resourceNodes = countJsonValues("{\"digest\":\"d\",\"kindId\":\"k\","
                + "\"resourceId\":\"r\",\"revision\":1}");
        assertEquals(3, emptyTailNodes, "regions/rules/extensions must each hold exactly one value");
        assertEquals(5, resourceNodes, "one resource object must hold exactly these values");
        final int controlNodes = prefixNodes + spawnNodes * spawnCount + emptyTailNodes
                + 1 + CONTROL_RESOURCES * resourceNodes;
        assertEquals(1608, controlNodes, "the full control text of this sample must hold this many values");
        assertEquals(controlValueCount(spawnCount), controlNodes,
                "the independent full control text must agree with prefix + spawns + tail registration");
        assertEquals(spawnNodes, controlValueCount(1) - controlValueCount(0),
                "the full control text must charge exactly this many values for its first spawn");

        // 字节侧必须留在 8MiB 之内，这样这条向量唯一拒绝它的理由就是节点数。
        final long lowerBound = (long) spawnCount * (fragmentBytes - 2);
        assertTrue(lowerBound <= MAX_PAYLOAD_BYTES,
                "the model lower bound of this sample must stay legal: " + lowerBound);
        assertTrue(lowerBound + (long) spawnCount * (spawnNodes + 1) < MAX_PAYLOAD_BYTES,
                "the byte side of this sample must stay well inside the byte limit: " + lowerBound);

        long runningNodes = 0;
        long beforeSpawnObject = 0;
        int crossing = -1;
        long crossingBefore = 0;
        long crossingAfter = 0;
        for (int i = 0; i < spawnCount; i++) {
            final long before = prefixNodes + (long) (i + 1) * spawnNodes + runningNodes;
            final long after = before + fragmentNodes;
            if (after > NODE_LIMIT) {
                crossing = i;
                beforeSpawnObject = prefixNodes + (long) i * spawnNodes + runningNodes;
                crossingBefore = before;
                crossingAfter = after;
                break;
            }
            runningNodes += fragmentNodes;
        }
        assertEquals(99, crossing, "the independently predicted first occurrence that cannot fit");
        assertTrue(crossing >= 0 && crossing < spawnCount,
                "the sample must cross the node limit inside the spawn list: " + crossing);
        assertTrue(crossingBefore <= NODE_LIMIT,
                "the predicted crossing must be the first occurrence that cannot fit: " + crossingBefore);
        // 三个阶段各自的精确累计值：这个出生点对象还没开始计费、它的已知部分刚计完、它的 extras 刚计完。
        // 这三个数都只由上面的独立登记（24 / 13 / 5001）给出，和完整对照图或 codec 输出无关。
        assertEquals(496_410L, beforeSpawnObject, "the charge accumulated before this spawn object starts");
        assertEquals(496_423L, crossingBefore,
                "occurrence " + crossing + " must reach this exact known charge");
        assertEquals(501_424L, crossingAfter,
                "occurrence " + crossing + " must end at this exact cumulative charge");
        assertEquals(fragmentNodes, crossingAfter - crossingBefore,
                "the crossing step must be exactly this occurrence's shared fragment charge");

        // 完整对照图的全文与它的 JDK UTF-8 宽度同样在任何 codec 调用之前算好并保存（资源数 CONTROL_RESOURCES，
        // 四个空片段与 fixture 的 null 一致）。actual 段落只消费这两个已定死的值，不在 encode 之后再拼文本或再量
        // 宽度；spawns 走序前缀 24 与出生点 13 仍然只由上面的独立骨架给出，不借完整对照图的计数替代走序前缀。
        final String controlText = accWireText(spawnCount, CONTROL_RESOURCES, EMPTY, EMPTY, EMPTY, false);
        final int controlTextBytes = utf8Bytes(controlText);
        assertTrue(controlTextBytes < MAX_PAYLOAD_BYTES,
                "the full control text of this sample must stay inside the byte limit: " + controlTextBytes);

        // ---- 实际调用：登记、越界序号 99 与三个精确累计值都已由测试侧独立文本定死，从这里开始才允许调用
        //      codec；完整对照图的全文计数只在这一段里用作 actual 侧交叉核对。 ----
        final ExactJson.FrozenJson control = MapDefinitionCodec
                .encode(accumulatedFragmentMap(spawnCount, CONTROL_RESOURCES, fragment, fragment, EMPTY, false));
        assertTrue(control.utf8Size() < MAX_PAYLOAD_BYTES,
                "the same spawns without fragments must stay legal: " + control.utf8Size());
        assertEquals(controlTextBytes, control.utf8Size(),
                "the full control wire must equal the independent control text width");
        assertEquals(controlText, control.canonical(),
                "the full control wire must equal the independent control text byte for byte");

        final MapDefinition accumulated =
                accumulatedFragmentMap(spawnCount, CONTROL_RESOURCES, fragment, fragment, EMPTY, true);
        final SchemaException failure = assertThrows(SchemaException.class,
                () -> MapDefinitionCodec.encode(accumulated));
        assertEquals(SchemaException.CODE_LIMIT_EXCEEDED, failure.code(),
                "accumulated occurrences of one legal fragment must breach the node limit");
        final long reported = reportedCount(failure.getMessage(), "nodes");
        assertTrue(reported > NODE_LIMIT && reported <= crossingAfter,
                "the breach must be reported by the charge of the predicted occurrence: " + reported
                        + " predicted " + crossingBefore + " -> " + crossingAfter);
        assertTrue(failure.getMessage().contains(String.valueOf(NODE_LIMIT)),
                "the rejection must name the real limit: " + failure.getMessage());
        assertEquals("/spawns/" + crossing, failure.path().pointer(),
                "the crossing must be located at the independently predicted occurrence");
        assertEquals(fragmentText, fragment.canonical(), "the shared fragment itself must stay untouched");
        assertEquals(fragmentBytes, fragment.utf8Size(), "the shared fragment itself must stay untouched");
    }

    // ------------------------------------------------------------------
    // 固定种子生成的图：模型、生成器与 typed fixture
    // ------------------------------------------------------------------

    private static final UUID GEN_DEF = UUID.fromString("81818181-8181-8181-8181-818181818181");

    private static final UUID GEN_WORLD = UUID.fromString("82828282-8282-8282-8282-828282828282");

    private static final UUID GEN_TEAM_ONE = UUID.fromString("83838383-8383-8383-8383-838383838383");

    private static final UUID GEN_TEAM_TWO = UUID.fromString("89898989-8989-8989-8989-898989898989");

    private static final UUID GEN_SPAWN_ONE = UUID.fromString("84848484-8484-8484-8484-848484848484");

    private static final UUID GEN_SPAWN_TWO = UUID.fromString("85858585-8585-8585-8585-858585858585");

    private static final UUID GEN_REGION = UUID.fromString("86868686-8686-8686-8686-868686868686");

    private static final UUID GEN_RESOURCE = UUID.fromString("87878787-8787-8787-8787-878787878787");

    /** 64 位小写 hex digest：固定重复的 16 字符模式，长度与字符集都由本测试自己数出来。 */
    private static final String GEN_DIGEST = "0f1e2d3c4b5a6978".repeat(4);

    /**
     * extras 成员值与 description 可选的字符串：含引号、反斜杠、短转义、控制字符、非 ASCII 与
     * BMP 外码点。description 允许 C0 控制字符，extras 值是任意合法 JSON。
     */
    private static final String[] GEN_TEXT = {"plain", "with space", "quote\"inside", "back\\slash",
            "line\nbreak", "tab\there", "form\ffeed", "return\rhere", "ctrl\u0001unit", "/slash/",
            "中文名", "naïve", "emoji\uD83D\uDE00", "paired\uD83D\uDE01tail", "", "\u007F"};

    /**
     * name 策略可选的字符串：按 {@code MapScalars.requireName} 的公开文字构造，不含 C0 控制字符、
     * 每个代理项都成对，DEL 与非 ASCII 保留。
     */
    private static final String[] GEN_NAMES = {"plain", "with space", "quote\"inside", "back\\slash",
            "/slash/", "中文名", "naïve", "emoji\uD83D\uDE00", "paired\uD83D\uDE01tail", "",
            "\u007F", "a name: with colon", "  leading and trailing  "};

    /** team name 走 {@code nonEmpty} 策略：空串会被拒，其余与 name 池相同。 */
    private static final String[] GEN_TEAM_NAMES = {"plain", "with space", "quote\"inside",
            "back\\slash", "/slash/", "中文名", "emoji\uD83D\uDE00", "  padded  ", "UPPER_case-1"};

    /** kindId 走 {@code namespace:path} 语法：纯 ASCII、恰好一个冒号、大小写敏感的小写字符集。 */
    private static final String[] GEN_KINDS = {"fpsmatch:spawn_area", "fpsmatch:model", "a:b",
            "ns_1:path.a-b_2", "x:y/z", "fpsmatch:1234"};

    private static final int[] GEN_INTS = {0, 1, -1, 16, 256, -256, 4096, Integer.MIN_VALUE,
            Integer.MAX_VALUE};

    private static final double[] GEN_DOUBLES = {0.0, -0.0, 1.0, -1.0, 1.5, -2.25, 1.0E-5, 1.0E-4,
            1.0E-7, 1.0E7, 1.0E100, 4.9E-324, 2.5E-323, 1.7976931348623157E308, 0.1, 123456.789,
            -0.0001, 3.0E7, 1.0E-310, 0.3333333333333333};

    private static final float[] GEN_FLOATS = {0.0f, -0.0f, 1.5f, -2.25f, 1.0E-5f, 1.0E-7f, 1.4E-45f,
            3.4028235E38f, 0.1f, 123.456f, -0.0001f, 2.0f, 1.0E7f, 0.33333334f};

    private static String pick(Random random, String[] pool) {
        return pool[random.nextInt(pool.length)];
    }

    private static int pick(Random random, int[] pool) {
        return pool[random.nextInt(pool.length)];
    }

    private static double pick(Random random, double[] pool) {
        return pool[random.nextInt(pool.length)];
    }

    private static float pick(Random random, float[] pool) {
        return pool[random.nextInt(pool.length)];
    }

    /**
     * 冻结规则的数值最短拼写，由本测试按契约文字独立实现：{@code signum()==0} 写 {@code 0}，否则取
     * {@code new BigDecimal(raw).stripTrailingZeros().toString()}。契约对每个数字只有这一条规则，
     * int 与 long 也先变成自己的十进制文本再进入同一路径，所以 {@code 120} 的 canonical 是
     * {@code 1.2E+2}，而不是 {@code 120}。绝不调用生产 codec 或 {@code ExactJsonNumber}。
     */
    private static String canonicalDecimal(String raw) {
        final BigDecimal value = new BigDecimal(raw);
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toString();
    }

    private static String canonicalInt(int value) {
        return canonicalDecimal(Integer.toString(value));
    }

    private static String canonicalLong(long value) {
        return canonicalDecimal(Long.toString(value));
    }

    private static String canonicalDouble(double value) {
        return canonicalDecimal(Double.toString(value));
    }

    /** {@code float} 的同一条冻结规则。 */
    private static String canonicalFloat(float value) {
        return canonicalDecimal(Float.toString(value));
    }

    /** canonical 字符串写出：短转义集合与 {@code ExactJson} 的 canonical writer 文字一致。 */
    private static String quote(String value) {
        final StringBuilder text = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            final char unit = value.charAt(i);
            switch (unit) {
                case '"' -> text.append("\\\"");
                case '\\' -> text.append("\\\\");
                case '\b' -> text.append("\\b");
                case '\t' -> text.append("\\t");
                case '\n' -> text.append("\\n");
                case '\f' -> text.append("\\f");
                case '\r' -> text.append("\\r");
                default -> {
                    if (unit < 0x20) {
                        text.append("\\u00").append(Character.forDigit((unit >> 4) & 0xF, 16))
                                .append(Character.forDigit(unit & 0xF, 16));
                    } else {
                        text.append(unit);
                    }
                }
            }
        }
        return text.append('"').toString();
    }

    /** 一个随机的 canonical JSON 值文本；{@code level} 限制嵌套深度，避免生成无意义的深层片段。 */
    private static String genValue(Random random, int level) {
        final int kind = random.nextInt(level >= 2 ? 6 : 8);
        return switch (kind) {
            case 0 -> "true";
            case 1 -> "false";
            case 2 -> "null";
            case 3 -> quote(pick(random, GEN_TEXT));
            case 4 -> canonicalInt(pick(random, GEN_INTS));
            case 5 -> canonicalDouble(genDouble(random));
            case 6 -> "[" + genValue(random, level + 1) + "," + genValue(random, level + 1) + "]";
            default -> "{\"inner\":" + genValue(random, level + 1) + "}";
        };
    }

    /** 一段规范的 extras 成员文本：key 由 {@link TreeMap} 升序给出，值取随机 canonical 形态。 */
    private static String genMembers(Random random, String prefix, int count) {
        final TreeMap<String, String> members = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            members.put(prefix + i, genValue(random, 0));
        }
        final StringBuilder text = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : members.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return text.toString();
    }

    private static double genDouble(Random random) {
        final int roll = random.nextInt(8);
        if (roll == 0) {
            return (random.nextInt(2001) - 1000) / 8.0;
        }
        if (roll == 1) {
            return (random.nextDouble() * 2048.0) - 1024.0;
        }
        return pick(random, GEN_DOUBLES);
    }

    private static float genFloat(Random random) {
        final int roll = random.nextInt(8);
        if (roll == 0) {
            return (random.nextInt(2001) - 1000) / 8.0f;
        }
        if (roll == 1) {
            return (float) ((random.nextDouble() * 512.0) - 256.0);
        }
        return pick(random, GEN_FLOATS);
    }

    private static int genCoord(Random random) {
        return random.nextInt(8) == 0 ? pick(random, GEN_INTS) : random.nextInt(4097) - 2048;
    }

    private static long genRevision(Random random) {
        final int roll = random.nextInt(8);
        if (roll == 0) {
            return 1L;
        }
        if (roll == 1) {
            return Long.MAX_VALUE;
        }
        return 1L + (random.nextLong() >>> 1);
    }

    /**
     * 一张由固定种子生成的图：每张图都有 2 队、2 出生点、1 区域、2 个资源 occurrence，13 类 extras
     * 槽全部带随机未知成员。typed fixture 与独立期望 wire 都只读这里的值，二者不共享任何输出。
     */
    private static final class GenGraph {

        String displayName;

        String description;

        int[] boundary = new int[6];

        String[] teamNames = new String[2];

        String[] teamConfig = new String[2];

        String[] teamExtensions = new String[2];

        String[] teamExtras = new String[2];

        String[] spawnNames = new String[2];

        double[] spawnPos = new double[6];

        float[] spawnOrient = new float[4];

        String[] spawnExtensions = new String[2];

        String[] spawnExtras = new String[2];

        String[] spawnPositionExtras = new String[2];

        String[] spawnOrientationExtras = new String[2];

        String regionName;

        String regionKindId;

        int[] regionCorners = new int[6];

        String regionConfig;

        String regionExtensions;

        String regionExtras;

        String regionCornersExtras;

        String regionCornersFirstExtras;

        String regionCornersSecondExtras;

        String resourceKindId;

        long[] revisions = new long[2];

        String[] resourceExtras = new String[2];

        String rules;

        String extensions;

        String rootExtras;

        String boundaryExtras;

        String boundaryFirstExtras;

        String boundarySecondExtras;
    }

    /** 固定种子的一次生成；同一 seed 在任何 JDK 17 上都给出同一张图。 */
    private static GenGraph generate(long seed) {
        final Random random = new Random(seed);
        final GenGraph graph = new GenGraph();
        graph.displayName = pick(random, GEN_NAMES);
        graph.description = pick(random, GEN_TEXT);
        for (int i = 0; i < 6; i++) {
            graph.boundary[i] = genCoord(random);
        }
        for (int i = 0; i < 2; i++) {
            graph.teamNames[i] = pick(random, GEN_TEAM_NAMES);
            graph.teamConfig[i] = "{\"score\":" + canonicalInt(random.nextInt(1_000_000)) + "}";
            graph.teamExtensions[i] = random.nextBoolean() ? "null" : "{\"tag\":" + quote(pick(random, GEN_TEXT)) + "}";
            graph.teamExtras[i] = genMembers(random, "teamK", 1 + random.nextInt(2));
            graph.spawnNames[i] = pick(random, GEN_NAMES);
            for (int axis = 0; axis < 3; axis++) {
                graph.spawnPos[3 * i + axis] = genDouble(random);
            }
            graph.spawnOrient[2 * i] = genFloat(random);
            graph.spawnOrient[2 * i + 1] = genFloat(random);
            graph.spawnExtensions[i] = random.nextBoolean() ? "null" : "{\"flag\":" + canonicalInt(pick(random, GEN_INTS)) + "}";
            graph.spawnExtras[i] = genMembers(random, "spawnK", 1 + random.nextInt(2));
            graph.spawnPositionExtras[i] = genMembers(random, "posK", random.nextInt(2));
            graph.spawnOrientationExtras[i] = genMembers(random, "oriK", random.nextInt(2));
            graph.revisions[i] = genRevision(random);
            graph.resourceExtras[i] = genMembers(random, "resK", 1 + random.nextInt(2));
        }
        graph.regionName = pick(random, GEN_NAMES);
        graph.regionKindId = pick(random, GEN_KINDS);
        for (int i = 0; i < 6; i++) {
            graph.regionCorners[i] = genCoord(random);
        }
        graph.regionConfig = random.nextBoolean() ? "null" : "{\"radius\":" + canonicalInt(random.nextInt(64)) + "}";
        graph.regionExtensions = random.nextBoolean() ? "null" : "[" + genValue(random, 1) + "]";
        graph.regionExtras = genMembers(random, "regionK", 1 + random.nextInt(2));
        graph.regionCornersExtras = genMembers(random, "cornersK", random.nextInt(2));
        graph.regionCornersFirstExtras = genMembers(random, "firstK", random.nextInt(2));
        graph.regionCornersSecondExtras = genMembers(random, "secondK", random.nextInt(2));
        graph.resourceKindId = pick(random, GEN_KINDS);
        graph.rules = random.nextBoolean() ? "null" : "{\"limit\":" + canonicalInt(random.nextInt(64)) + "}";
        graph.extensions = random.nextBoolean() ? "null" : "{\"note\":" + quote(pick(random, GEN_TEXT)) + "}";
        graph.rootExtras = genMembers(random, "rootK", 1 + random.nextInt(3));
        graph.boundaryExtras = genMembers(random, "boxK", random.nextInt(2));
        graph.boundaryFirstExtras = genMembers(random, "boxFirstK", random.nextInt(2));
        graph.boundarySecondExtras = genMembers(random, "boxSecondK", random.nextInt(2));
        return graph;
    }

    /** 生成图对应的 typed fixture；extras 片段由本测试自己写出的成员文本包成 object。 */
    private static MapDefinition genTyped(GenGraph graph) {
        final List<TeamDefinition> teams = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            teams.add(new TeamDefinition(new Ids.TeamId(i == 0 ? GEN_TEAM_ONE : GEN_TEAM_TWO),
                    graph.teamNames[i], json(graph.teamConfig[i]), json(graph.teamExtensions[i]),
                    new WireExtras.TeamExtras(fragment(graph.teamExtras[i]))));
        }
        final List<SpawnDefinition> spawns = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            spawns.add(new SpawnDefinition(new Ids.ObjectId(i == 0 ? GEN_SPAWN_ONE : GEN_SPAWN_TWO),
                    graph.spawnNames[i], new Ids.TeamId(GEN_TEAM_ONE), DIMENSION,
                    new Position(graph.spawnPos[3 * i], graph.spawnPos[3 * i + 1],
                            graph.spawnPos[3 * i + 2]),
                    new Orientation(graph.spawnOrient[2 * i], graph.spawnOrient[2 * i + 1]),
                    json(graph.spawnExtensions[i]),
                    new WireExtras.SpawnExtras(fragment(graph.spawnExtras[i]),
                            fragment(graph.spawnPositionExtras[i]),
                            fragment(graph.spawnOrientationExtras[i]))));
        }
        final RegionDefinition region = new RegionDefinition(new Ids.ObjectId(GEN_REGION),
                graph.regionName, graph.regionKindId,
                new BoxCorners(new IntPosition(graph.regionCorners[0], graph.regionCorners[1],
                        graph.regionCorners[2]), new IntPosition(graph.regionCorners[3],
                        graph.regionCorners[4], graph.regionCorners[5])),
                json(graph.regionConfig), json(graph.regionExtensions),
                new WireExtras.RegionExtras(fragment(graph.regionExtras),
                        fragment(graph.regionCornersExtras), fragment(graph.regionCornersFirstExtras),
                        fragment(graph.regionCornersSecondExtras)));
        final List<ResourceRevisionRef> resources = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            resources.add(new ResourceRevisionRef(graph.resourceKindId, GEN_RESOURCE,
                    graph.revisions[i], GEN_DIGEST, new WireExtras.ResourceExtras(
                            fragment(graph.resourceExtras[i]))));
        }
        return new MapDefinition(new Ids.DefinitionId(GEN_DEF), new Ids.WorldId(GEN_WORLD),
                "fpsmatch:conquest", DIMENSION, graph.displayName, graph.description,
                new BoxCorners(new IntPosition(graph.boundary[0], graph.boundary[1], graph.boundary[2]),
                        new IntPosition(graph.boundary[3], graph.boundary[4], graph.boundary[5])),
                teams, spawns, List.of(region), json(graph.rules), resources, json(graph.extensions),
                new WireExtras.MapExtras(fragment(graph.rootExtras), fragment(graph.boundaryExtras),
                        fragment(graph.boundaryFirstExtras), fragment(graph.boundarySecondExtras)));
    }

    /** 由成员文本包出的 extras 片段；空文本即合法的空片段。 */
    private static ExactJson.FrozenJson fragment(String members) {
        return members.isEmpty() ? EMPTY : json("{" + members + "}");
    }

    // ------------------------------------------------------------------
    // 独立期望 wire：成员切分、并入 owner，并按 key 升序写出
    // ------------------------------------------------------------------

    /** 返回从 {@code start}（值必须是 {@code '"'}）起、跳过转义后的闭引号之后的下标。 */
    private static int endOfString(String text, int start) {
        int cursor = start + 1;
        while (cursor < text.length()) {
            final char unit = text.charAt(cursor);
            if (unit == '\\') {
                cursor += 2;
                continue;
            }
            cursor++;
            if (unit == '"') {
                break;
            }
        }
        return cursor;
    }

    /** 返回值的结束下标（不含）；支持 string、object、array 与标量。 */
    private static int endOfValue(String text, int start) {
        final char first = text.charAt(start);
        if (first == '"') {
            return endOfString(text, start);
        }
        if (first == '{' || first == '[') {
            int cursor = start;
            int depth = 0;
            while (cursor < text.length()) {
                final char unit = text.charAt(cursor);
                if (unit == '"') {
                    cursor = endOfString(text, cursor);
                    continue;
                }
                if (unit == '{' || unit == '[') {
                    depth++;
                } else if (unit == '}' || unit == ']') {
                    depth--;
                    cursor++;
                    if (depth == 0) {
                        return cursor;
                    }
                    continue;
                }
                cursor++;
            }
            return cursor;
        }
        int cursor = start;
        while (cursor < text.length()) {
            final char unit = text.charAt(cursor);
            if (unit == ',' || unit == '}' || unit == ']') {
                break;
            }
            cursor++;
        }
        return cursor;
    }

    /**
     * 把一段 extras 成员文本并入 owner 的成员表：key 原样取出（本测试生成的 key 都是不含转义的
     * ASCII），值按原文本搬入，重复 key 视为本测试的生成缺陷。
     */
    private static void putMembers(TreeMap<String, String> owner, String members, String ownerName) {
        int cursor = 0;
        while (cursor < members.length()) {
            if (members.charAt(cursor) == ',') {
                cursor++;
                continue;
            }
            final int keyEnd = endOfString(members, cursor);
            final String key = members.substring(cursor + 1, keyEnd - 1);
            if (key.indexOf('\\') >= 0) {
                throw new IllegalStateException("generated extras keys must stay unescaped: " + key);
            }
            cursor = keyEnd;
            if (cursor >= members.length() || members.charAt(cursor) != ':') {
                throw new IllegalStateException("malformed generated members: " + members);
            }
            final int valueEnd = endOfValue(members, cursor + 1);
            if (owner.put(key, members.substring(cursor + 1, valueEnd)) != null) {
                throw new IllegalStateException("duplicate member " + key + " inside " + ownerName);
            }
            cursor = valueEnd;
        }
    }

    /** 独立的 canonical object 写出：key 按 {@link String#compareTo} 升序，值文本原样使用。 */
    private static String canonicalObject(TreeMap<String, String> members) {
        final StringBuilder text = new StringBuilder().append('{');
        boolean first = true;
        for (Map.Entry<String, String> entry : members.entrySet()) {
            if (!first) {
                text.append(',');
            }
            first = false;
            text.append(quote(entry.getKey())).append(':').append(entry.getValue());
        }
        return text.append('}').toString();
    }

    /** 独立的 canonical array 写出：array 保序。 */
    private static String canonicalArray(List<String> elements) {
        return "[" + String.join(",", elements) + "]";
    }

    /** 自检：生成的成员文本必须自己就是规范形态，否则期望 wire 会脱离 merge 语义。 */
    private static void assertMembersAreCanonical(String members) {
        final TreeMap<String, String> split = new TreeMap<>();
        putMembers(split, members, "self-check");
        assertEquals("{" + members + "}", canonicalObject(split),
                "generated extras members must already be canonical");
    }

    /** 13 类 extras 槽的成员文本都必须通过规范形态自检。 */
    private static void assertGeneratedMembersAreCanonical(GenGraph graph) {
        for (int i = 0; i < 2; i++) {
            assertMembersAreCanonical(graph.teamExtras[i]);
            assertMembersAreCanonical(graph.spawnExtras[i]);
            assertMembersAreCanonical(graph.spawnPositionExtras[i]);
            assertMembersAreCanonical(graph.spawnOrientationExtras[i]);
            assertMembersAreCanonical(graph.resourceExtras[i]);
        }
        assertMembersAreCanonical(graph.regionExtras);
        assertMembersAreCanonical(graph.regionCornersExtras);
        assertMembersAreCanonical(graph.regionCornersFirstExtras);
        assertMembersAreCanonical(graph.regionCornersSecondExtras);
        assertMembersAreCanonical(graph.rootExtras);
        assertMembersAreCanonical(graph.boundaryExtras);
        assertMembersAreCanonical(graph.boundaryFirstExtras);
        assertMembersAreCanonical(graph.boundarySecondExtras);
    }

    /** 含 extras 的 int 坐标 object；int 与其它数字共用同一条最短拼写规则。 */
    private static String intPosition(int x, int y, int z, String extras) {
        final TreeMap<String, String> members = new TreeMap<>();
        members.put("x", canonicalInt(x));
        members.put("y", canonicalInt(y));
        members.put("z", canonicalInt(z));
        putMembers(members, extras, "int position");
        return canonicalObject(members);
    }

    /** 含 extras 的 double 坐标 object。 */
    private static String doublePosition(double x, double y, double z, String extras) {
        final TreeMap<String, String> members = new TreeMap<>();
        members.put("x", canonicalDouble(x));
        members.put("y", canonicalDouble(y));
        members.put("z", canonicalDouble(z));
        putMembers(members, extras, "double position");
        return canonicalObject(members);
    }

    /** 含 extras 的朝向 object；构造顺序是 (yaw, pitch)。 */
    private static String orientation(float yaw, float pitch, String extras) {
        final TreeMap<String, String> members = new TreeMap<>();
        members.put("yaw", canonicalFloat(yaw));
        members.put("pitch", canonicalFloat(pitch));
        putMembers(members, extras, "orientation");
        return canonicalObject(members);
    }

    /** 独立重建的一张图的完整 canonical wire：所有已知成员与 13 类 extras 成员同表排序。 */
    private static String expectedWire(GenGraph graph) {
        final TreeMap<String, String> root = new TreeMap<>();
        root.put("schemaVersion", "1");
        root.put("id", quote(GEN_DEF.toString()));
        root.put("worldId", quote(GEN_WORLD.toString()));
        root.put("modeId", quote("fpsmatch:conquest"));
        root.put("dimensionId", quote(DIMENSION));
        root.put("displayName", quote(graph.displayName));
        root.put("description", quote(graph.description));

        final TreeMap<String, String> boundary = new TreeMap<>();
        boundary.put("first", intPosition(graph.boundary[0], graph.boundary[1], graph.boundary[2],
                graph.boundaryFirstExtras));
        boundary.put("second", intPosition(graph.boundary[3], graph.boundary[4], graph.boundary[5],
                graph.boundarySecondExtras));
        putMembers(boundary, graph.boundaryExtras, "boundary");
        root.put("boundary", canonicalObject(boundary));

        final List<String> teams = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final TreeMap<String, String> team = new TreeMap<>();
            team.put("id", quote((i == 0 ? GEN_TEAM_ONE : GEN_TEAM_TWO).toString()));
            team.put("name", quote(graph.teamNames[i]));
            team.put("config", graph.teamConfig[i]);
            team.put("extensions", graph.teamExtensions[i]);
            putMembers(team, graph.teamExtras[i], "team");
            teams.add(canonicalObject(team));
        }
        root.put("teams", canonicalArray(teams));

        final List<String> spawns = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final TreeMap<String, String> spawn = new TreeMap<>();
            spawn.put("id", quote((i == 0 ? GEN_SPAWN_ONE : GEN_SPAWN_TWO).toString()));
            spawn.put("name", quote(graph.spawnNames[i]));
            spawn.put("teamId", quote(GEN_TEAM_ONE.toString()));
            spawn.put("dimensionId", quote(DIMENSION));
            spawn.put("position", doublePosition(graph.spawnPos[3 * i], graph.spawnPos[3 * i + 1],
                    graph.spawnPos[3 * i + 2], graph.spawnPositionExtras[i]));
            spawn.put("orientation", orientation(graph.spawnOrient[2 * i],
                    graph.spawnOrient[2 * i + 1], graph.spawnOrientationExtras[i]));
            spawn.put("extensions", graph.spawnExtensions[i]);
            putMembers(spawn, graph.spawnExtras[i], "spawn");
            spawns.add(canonicalObject(spawn));
        }
        root.put("spawns", canonicalArray(spawns));

        final TreeMap<String, String> region = new TreeMap<>();
        region.put("id", quote(GEN_REGION.toString()));
        region.put("name", quote(graph.regionName));
        region.put("kindId", quote(graph.regionKindId));
        final TreeMap<String, String> corners = new TreeMap<>();
        corners.put("first", intPosition(graph.regionCorners[0], graph.regionCorners[1],
                graph.regionCorners[2], graph.regionCornersFirstExtras));
        corners.put("second", intPosition(graph.regionCorners[3], graph.regionCorners[4],
                graph.regionCorners[5], graph.regionCornersSecondExtras));
        putMembers(corners, graph.regionCornersExtras, "corners");
        region.put("corners", canonicalObject(corners));
        region.put("config", graph.regionConfig);
        region.put("extensions", graph.regionExtensions);
        putMembers(region, graph.regionExtras, "region");
        root.put("regions", canonicalArray(List.of(canonicalObject(region))));

        final List<String> resources = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final TreeMap<String, String> resource = new TreeMap<>();
            resource.put("kindId", quote(graph.resourceKindId));
            resource.put("resourceId", quote(GEN_RESOURCE.toString()));
            resource.put("revision", canonicalLong(graph.revisions[i]));
            resource.put("digest", quote(GEN_DIGEST));
            putMembers(resource, graph.resourceExtras[i], "resource");
            resources.add(canonicalObject(resource));
        }
        root.put("resources", canonicalArray(resources));

        root.put("rules", graph.rules);
        root.put("extensions", graph.extensions);
        putMembers(root, graph.rootExtras, "root");
        return canonicalObject(root);
    }

    @Test
    @DisplayName("twenty fixed seed graphs encode to their independently rebuilt canonical wires")
    void fixedSeedGraphsEncodeToIndependentWires() {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (long seed = 1L; seed <= 20L; seed++) {
            final GenGraph graph = generate(seed);
            assertGeneratedMembersAreCanonical(graph);
            final String expected = expectedWire(graph);
            final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(genTyped(graph));
            assertEquals(expected, payload.canonical(), "seed " + seed);
            assertEquals(utf8Bytes(expected), payload.utf8Size(), "seed " + seed);
            assertTrue(seen.add(expected), "seed " + seed + " must not repeat an earlier wire");
        }
        assertEquals(20, seen.size(), "all twenty graphs must be pairwise distinct");
    }

    @Test
    @DisplayName("one thousand fixed seed graphs encode to pairwise distinct certified payloads")
    void oneThousandFixedSeedGraphsEncodeToDistinctPayloads() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256Hex("abc".getBytes(StandardCharsets.UTF_8)),
                "the independent SHA-256 helper must reproduce the published vector for \"abc\"");

        final java.util.Set<String> wires = new java.util.HashSet<>();
        final java.util.Set<String> digests = new java.util.HashSet<>();
        long utf8Total = 0L;
        for (long seed = 1L; seed <= 1000L; seed++) {
            final GenGraph graph = generate(seed);
            assertGeneratedMembersAreCanonical(graph);
            // 期望侧全部先算完：整条 canonical 文本、它的 JDK UTF-8 宽度、JDK SHA-256，以及
            // HashSet 的真实去重结果；这些都在调用 codec 之前完成，样本与期望都不取自生产输出。
            final String expected = expectedWire(graph);
            final byte[] utf8 = expected.getBytes(StandardCharsets.UTF_8);
            final String expectedDigest = sha256Hex(utf8);
            assertTrue(wires.add(expected), "seed " + seed + " must not repeat an earlier wire");
            assertTrue(digests.add(expectedDigest),
                    "seed " + seed + " must not repeat an earlier payload digest");
            utf8Total += utf8.length;

            final ExactJson.FrozenJson payload = MapDefinitionCodec.encode(genTyped(graph));
            assertEquals(expected, payload.canonical(), "seed " + seed + " canonical wire");
            assertEquals(utf8.length, payload.utf8Size(), "seed " + seed + " JDK UTF-8 width");
            // 实际 digest 必须由 payload 自己现算，不能用期望 Frozen 代替。
            assertEquals(expectedDigest, ContentDigest.sha256(payload),
                    "seed " + seed + " payload digest must equal the independently computed SHA-256");
        }
        assertEquals(1000, wires.size(), "all one thousand wires must be pairwise distinct");
        assertEquals(1000, digests.size(), "all one thousand payload digests must be distinct");
        assertTrue(utf8Total > 1000L, "the thousand payloads must carry real bytes: " + utf8Total);
    }

    @Test
    @DisplayName("whole numbers reuse the one shortest spelling rule instead of their raw text")
    void wholeNumbersReuseTheShortestSpellingRule() {
        assertEquals("1.2E+2", canonicalInt(120));
        assertEquals("-1.57E+3", canonicalInt(-1570));
        assertEquals("7.9E+2", canonicalInt(790));
        assertEquals("1E+2", canonicalInt(100));
        assertEquals("1E+1", canonicalInt(10));
        assertEquals("1E+6", canonicalInt(1_000_000));
        assertEquals("0", canonicalInt(0));
        assertEquals("7", canonicalInt(7));
        assertEquals("8E+2", canonicalLong(800L));
        assertEquals("1.23456789E+9", canonicalLong(1_234_567_890L));
        assertEquals("1", canonicalLong(1L));

        final int x = 120;
        final int y = -1570;
        final int z = 790;
        final long revision = 1_234_567_890L;
        final String canonical =
                MapDefinitionCodec.encode(spellingFixture(x, y, z, revision)).canonical();

        final TreeMap<String, String> boundary = new TreeMap<>();
        boundary.put("first", intPosition(x, y, z, ""));
        boundary.put("second", intPosition(0, 0, 0, ""));
        assertTrue(canonical.contains("\"boundary\":" + canonicalObject(boundary)),
                "boundary ints must use the shared shortest spelling: " + canonical);
        assertTrue(canonical.contains("\"revision\":" + canonicalLong(revision)),
                "long revision must use the shared shortest spelling: " + canonical);
        assertFalse(canonical.contains("\"revision\":" + revision),
                "the raw long text must never survive into the canonical payload");
        assertFalse(canonical.contains("\"x\":" + x + ","),
                "the raw int text must never survive into the canonical payload");
    }
}
