package net.ptcrys.fpsmatch.editor.common.codec;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 完整地图 payload 的严格编码器（R06.1b1，仅 encode）。
 *
 * <p>
 * 编码固定为一条流水线：先用 {@link BudgetSink} 完整预演一遍 wire 预算（节点、深度、UTF-8
 * 字节与所属 reserved 碰撞），只有全部成功后才创建 {@link WriterSink}，用同一套 schema 遍历写出一份
 * 有界 canonical 文本，最后只调用一次 {@link ExactJson#parse(String)} 把这份文本认证成返回的
 * {@code FrozenJson}。writer 累计的字节必须与预演结果相同，最终 parse 也必须是认证而不是提前预算的
 * 替代品。
 * </p>
 *
 * <p>
 * 两种 sink 只表达 object/array 起止、member、array separator、String、canonical number、已认证
 * Frozen 片段与 extras 成员，它们不持有 typed 图、不缓存全图中间文本、不建第二套 schema，也不以编解码
 * 自循环验证。数字一律先经 {@link ExactJsonNumber#parse} 得到 canonical 拼写；字符串一律按
 * {@link ExactJson} 的 canonical 转义规则逐字符写出。extras 成员直接追加进所属 object，不共用额外的
 * {@code {}} 包装，也不按 UUID 去重：每一次出现都独立计入。
 * </p>
 *
 * <p>
 * 本类型没有可变状态、没有全局 cache、没有 source wire sidecar；输入 {@link MapDefinition} 在编码
 * 前后保持完全不变。
 * </p>
 */
public final class MapDefinitionCodec {

    /** 根 wire object 的 known 成员数。 */
    private static final int ROOT_KNOWN = 14;

    /** boundary 与 region.corners 两个位置 object 的 known 成员数。 */
    private static final int BOX_KNOWN = 2;

    /** 整数与 double 位置 object 的 known 成员数。 */
    private static final int POSITION_KNOWN = 3;

    /** orientation object 的 known 成员数。 */
    private static final int ORIENTATION_KNOWN = 2;

    /** team wire object 的 known 成员数。 */
    private static final int TEAM_KNOWN = 4;

    /** spawn wire object 的 known 成员数。 */
    private static final int SPAWN_KNOWN = 7;

    /** region wire object 的 known 成员数。 */
    private static final int REGION_KNOWN = 6;

    /** resource 引用 occurrence 的 known 成员数。 */
    private static final int RESOURCE_KNOWN = 4;

    /** 根 wire object 的 known/reserved key 全集。 */
    private static final Set<String> ROOT_RESERVED = Set.of(
            "schemaVersion", "id", "worldId", "modeId", "dimensionId", "displayName", "description",
            "boundary", "teams", "spawns", "regions", "rules", "resources", "extensions");

    /** boundary 与 region.corners 两个位置 object 的 reserved key 全集。 */
    private static final Set<String> BOX_RESERVED = Set.of("first", "second");

    /** 位置 object 的 reserved key 全集（整数端点与 double position 共用同一张表）。 */
    private static final Set<String> POSITION_RESERVED = Set.of("x", "y", "z");

    /** orientation object 的 reserved key 全集。 */
    private static final Set<String> ORIENTATION_RESERVED = Set.of("yaw", "pitch");

    /** team wire object 的 reserved key 全集。 */
    private static final Set<String> TEAM_RESERVED = Set.of("id", "name", "config", "extensions");

    /** spawn wire object 的 reserved key 全集。 */
    private static final Set<String> SPAWN_RESERVED = Set.of(
            "id", "name", "teamId", "dimensionId", "position", "orientation", "extensions");

    /** region wire object 的 reserved key 全集。 */
    private static final Set<String> REGION_RESERVED = Set.of(
            "id", "name", "kindId", "corners", "config", "extensions");

    /** resource 引用 occurrence 的 reserved key 全集。 */
    private static final Set<String> RESOURCE_RESERVED = Set.of(
            "kindId", "resourceId", "revision", "digest");

    /** canonical 转义写四位小写 hex 时用的数字表。 */
    private static final String HEX_DIGITS = "0123456789abcdef";

    /**
     * 非实例化：本类型只是 encode 的命名空间，不持有任何状态。
     */
    private MapDefinitionCodec() {}

    /**
     * 把一个已经完全构建并自校验过的 {@link MapDefinition} 编码成已认证的 canonical payload。
     *
     * <p>
     * 固定顺序：Java {@code null} 立即 IAE；第一遍预演预算，任何超限、保留字段碰撞或深度问题都以
     * helper 真实的 {@link SchemaException}（原 code/path/cause）抛出；预演全通过后才创建 writer，
     * 用同一遍历写出文本；writer 字节数与预演不一致是内部缺陷 {@link IllegalStateException}；最后一次
     * {@link ExactJson#parse(String)} 认证这份文本并返回，同时核对认证结果的真实 UTF-8 宽度。
     * </p>
     *
     * @param definition 待编码的地图定义，必须非 null
     * @return 该地图的完整 canonical payload
     * @throws IllegalArgumentException 当 {@code definition} 为 null 时
     * @throws SchemaException          当 wire 预算、深度、节点或所属保留字段违反 P06.1a 契约时
     * @throws IllegalStateException    当内部生成与预演结果不一致时
     */
    public static ExactJson.FrozenJson encode(MapDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("definition must not be null");
        }

        final BudgetSink preflight = new BudgetSink();
        walkMap(definition, preflight);
        final long expectedBytes = preflight.bytes();

        final WriterSink writer = new WriterSink(expectedBytes);
        walkMap(definition, writer);
        if (writer.bytes() != expectedBytes) {
            throw new IllegalStateException("the writer produced " + writer.bytes() + " UTF-8 bytes but the preflight certified " + expectedBytes);
        }

        final ExactJson.FrozenJson payload = ExactJson.parse(writer.text());
        if (payload.utf8Size() != expectedBytes) {
            throw new IllegalStateException("the certified payload holds " + payload.utf8Size() + " UTF-8 bytes but the preflight certified " + expectedBytes);
        }
        return payload;
    }

    // ------------------------------------------------------------------
    // 固定的 schema 遍历：两遍共用同一套代码
    // ------------------------------------------------------------------

    /** 遍历根 object：14 个 known 成员按 PAYLOAD-CODEC 表顺序，最后追加根 extras。 */
    private static void walkMap(MapDefinition map, Sink sink) {
        final FieldPath root = FieldPath.root();
        sink.objectStart(0, root);

        final FieldPath schemaVersion = root.child("schemaVersion");
        sink.member("schemaVersion", 0, schemaVersion);
        sink.number("1", 1, schemaVersion);

        final FieldPath id = root.child("id");
        sink.member("id", 1, id);
        sink.string(map.id().wire(), 1, id);

        final FieldPath worldId = root.child("worldId");
        sink.member("worldId", 2, worldId);
        sink.string(map.worldId().wire(), 1, worldId);

        final FieldPath modeId = root.child("modeId");
        sink.member("modeId", 3, modeId);
        sink.string(map.modeId(), 1, modeId);

        final FieldPath dimensionId = root.child("dimensionId");
        sink.member("dimensionId", 4, dimensionId);
        sink.string(map.dimensionId(), 1, dimensionId);

        final FieldPath displayName = root.child("displayName");
        sink.member("displayName", 5, displayName);
        sink.string(map.displayName(), 1, displayName);

        final FieldPath description = root.child("description");
        sink.member("description", 6, description);
        sink.string(map.description(), 1, description);

        final FieldPath boundary = root.child("boundary");
        sink.member("boundary", 7, boundary);
        walkBox(map.boundary(), boundary, 1, map.wireExtras().boundaryMembers(),
                map.wireExtras().boundaryFirstMembers(), map.wireExtras().boundarySecondMembers(),
                sink);

        final FieldPath teams = root.child("teams");
        sink.member("teams", 8, teams);
        sink.arrayStart(1, teams);
        final List<TeamDefinition> teamList = map.teams();
        for (int i = 0; i < teamList.size(); i++) {
            final FieldPath element = teams.child(Integer.toString(i));
            sink.arraySeparator(i, element);
            walkTeam(teamList.get(i), element, sink);
        }
        sink.arrayEnd();

        final FieldPath spawns = root.child("spawns");
        sink.member("spawns", 9, spawns);
        sink.arrayStart(1, spawns);
        final List<SpawnDefinition> spawnList = map.spawns();
        for (int i = 0; i < spawnList.size(); i++) {
            final FieldPath element = spawns.child(Integer.toString(i));
            sink.arraySeparator(i, element);
            walkSpawn(spawnList.get(i), element, sink);
        }
        sink.arrayEnd();

        final FieldPath regions = root.child("regions");
        sink.member("regions", 10, regions);
        sink.arrayStart(1, regions);
        final List<RegionDefinition> regionList = map.regions();
        for (int i = 0; i < regionList.size(); i++) {
            final FieldPath element = regions.child(Integer.toString(i));
            sink.arraySeparator(i, element);
            walkRegion(regionList.get(i), element, sink);
        }
        sink.arrayEnd();

        final FieldPath rules = root.child("rules");
        sink.member("rules", 11, rules);
        sink.value(map.rules(), 1, rules);

        final FieldPath resources = root.child("resources");
        sink.member("resources", 12, resources);
        sink.arrayStart(1, resources);
        final List<ResourceRevisionRef> resourceList = map.resources();
        for (int i = 0; i < resourceList.size(); i++) {
            final FieldPath element = resources.child(Integer.toString(i));
            sink.arraySeparator(i, element);
            walkResource(resourceList.get(i), element, sink);
        }
        sink.arrayEnd();

        final FieldPath extensions = root.child("extensions");
        sink.member("extensions", 13, extensions);
        sink.value(map.extensions(), 1, extensions);

        sink.members(map.wireExtras().members(), ROOT_RESERVED, 0, ROOT_KNOWN, root);
        sink.objectEnd();
    }

    /**
     * 遍历一个 first/second 位置对：{@code boxDepth} 是该 object 的真实 wire 深度，两个端点的真实深度
     * 为 {@code boxDepth + 1}。
     */
    private static void walkBox(BoxCorners box, FieldPath path, int boxDepth,
                                ExactJson.FrozenJson boxExtras, ExactJson.FrozenJson firstExtras,
                                ExactJson.FrozenJson secondExtras, Sink sink) {
        sink.objectStart(boxDepth, path);

        final FieldPath first = path.child("first");
        sink.member("first", 0, first);
        walkIntPosition(box.first(), first, boxDepth + 1, firstExtras, sink);

        final FieldPath second = path.child("second");
        sink.member("second", 1, second);
        walkIntPosition(box.second(), second, boxDepth + 1, secondExtras, sink);

        sink.members(boxExtras, BOX_RESERVED, boxDepth, BOX_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历一个整数位置 object：x/y/z 按 schema 顺序，最后追加该位置自己的 extras。 */
    private static void walkIntPosition(IntPosition position, FieldPath path, int ownerDepth,
                                        ExactJson.FrozenJson extras, Sink sink) {
        final int valueDepth = ownerDepth + 1;
        sink.objectStart(ownerDepth, path);

        final FieldPath x = path.child("x");
        sink.member("x", 0, x);
        sink.number(canonicalNumber(Integer.toString(position.x()), x), valueDepth, x);

        final FieldPath y = path.child("y");
        sink.member("y", 1, y);
        sink.number(canonicalNumber(Integer.toString(position.y()), y), valueDepth, y);

        final FieldPath z = path.child("z");
        sink.member("z", 2, z);
        sink.number(canonicalNumber(Integer.toString(position.z()), z), valueDepth, z);

        sink.members(extras, POSITION_RESERVED, ownerDepth, POSITION_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历一个 team：wire 深度固定为 2，成员值深度为 3。 */
    private static void walkTeam(TeamDefinition team, FieldPath path, Sink sink) {
        sink.objectStart(2, path);

        final FieldPath id = path.child("id");
        sink.member("id", 0, id);
        sink.string(team.id().wire(), 3, id);

        final FieldPath name = path.child("name");
        sink.member("name", 1, name);
        sink.string(team.name(), 3, name);

        final FieldPath config = path.child("config");
        sink.member("config", 2, config);
        sink.value(team.config(), 3, config);

        final FieldPath extensions = path.child("extensions");
        sink.member("extensions", 3, extensions);
        sink.value(team.extensions(), 3, extensions);

        sink.members(team.wireExtras().members(), TEAM_RESERVED, 2, TEAM_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历一个 spawn：wire 深度固定为 2，position/orientation 为深度 3。 */
    private static void walkSpawn(SpawnDefinition spawn, FieldPath path, Sink sink) {
        sink.objectStart(2, path);

        final FieldPath id = path.child("id");
        sink.member("id", 0, id);
        sink.string(spawn.id().wire(), 3, id);

        final FieldPath name = path.child("name");
        sink.member("name", 1, name);
        sink.string(spawn.name(), 3, name);

        final FieldPath teamId = path.child("teamId");
        sink.member("teamId", 2, teamId);
        sink.string(spawn.teamId().wire(), 3, teamId);

        final FieldPath dimensionId = path.child("dimensionId");
        sink.member("dimensionId", 3, dimensionId);
        sink.string(spawn.dimensionId(), 3, dimensionId);

        final FieldPath position = path.child("position");
        sink.member("position", 4, position);
        walkPosition(spawn.position(), position, spawn.wireExtras().positionMembers(), sink);

        final FieldPath orientation = path.child("orientation");
        sink.member("orientation", 5, orientation);
        walkOrientation(spawn.orientation(), orientation, spawn.wireExtras().orientationMembers(),
                sink);

        final FieldPath extensions = path.child("extensions");
        sink.member("extensions", 6, extensions);
        sink.value(spawn.extensions(), 3, extensions);

        sink.members(spawn.wireExtras().members(), SPAWN_RESERVED, 2, SPAWN_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历 spawn.position：wire 深度固定为 3，三分量为 double canonical 文本。 */
    private static void walkPosition(Position position, FieldPath path, ExactJson.FrozenJson extras,
                                     Sink sink) {
        sink.objectStart(3, path);

        final FieldPath x = path.child("x");
        sink.member("x", 0, x);
        sink.number(canonicalNumber(Double.toString(position.x()), x), 4, x);

        final FieldPath y = path.child("y");
        sink.member("y", 1, y);
        sink.number(canonicalNumber(Double.toString(position.y()), y), 4, y);

        final FieldPath z = path.child("z");
        sink.member("z", 2, z);
        sink.number(canonicalNumber(Double.toString(position.z()), z), 4, z);

        sink.members(extras, POSITION_RESERVED, 3, POSITION_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历 spawn.orientation：wire 深度固定为 3，两分量为 float canonical 文本。 */
    private static void walkOrientation(Orientation orientation, FieldPath path,
                                        ExactJson.FrozenJson extras, Sink sink) {
        sink.objectStart(3, path);

        final FieldPath yaw = path.child("yaw");
        sink.member("yaw", 0, yaw);
        sink.number(canonicalNumber(Float.toString(orientation.yaw()), yaw), 4, yaw);

        final FieldPath pitch = path.child("pitch");
        sink.member("pitch", 1, pitch);
        sink.number(canonicalNumber(Float.toString(orientation.pitch()), pitch), 4, pitch);

        sink.members(extras, ORIENTATION_RESERVED, 3, ORIENTATION_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历一个 region：wire 深度固定为 2，corners 为深度 3、其端点为深度 4。 */
    private static void walkRegion(RegionDefinition region, FieldPath path, Sink sink) {
        sink.objectStart(2, path);

        final FieldPath id = path.child("id");
        sink.member("id", 0, id);
        sink.string(region.id().wire(), 3, id);

        final FieldPath name = path.child("name");
        sink.member("name", 1, name);
        sink.string(region.name(), 3, name);

        final FieldPath kindId = path.child("kindId");
        sink.member("kindId", 2, kindId);
        sink.string(region.kindId(), 3, kindId);

        final FieldPath corners = path.child("corners");
        sink.member("corners", 3, corners);
        walkBox(region.corners(), corners, 3, region.wireExtras().cornersMembers(),
                region.wireExtras().cornersFirstMembers(), region.wireExtras().cornersSecondMembers(),
                sink);

        final FieldPath config = path.child("config");
        sink.member("config", 4, config);
        sink.value(region.config(), 3, config);

        final FieldPath extensions = path.child("extensions");
        sink.member("extensions", 5, extensions);
        sink.value(region.extensions(), 3, extensions);

        sink.members(region.wireExtras().members(), REGION_RESERVED, 2, REGION_KNOWN, path);
        sink.objectEnd();
    }

    /** 遍历一个 resource 引用 occurrence：wire 深度固定为 2，每次出现独立计入 extras。 */
    private static void walkResource(ResourceRevisionRef resource, FieldPath path, Sink sink) {
        sink.objectStart(2, path);

        final FieldPath kindId = path.child("kindId");
        sink.member("kindId", 0, kindId);
        sink.string(resource.kindId(), 3, kindId);

        final FieldPath resourceId = path.child("resourceId");
        sink.member("resourceId", 1, resourceId);
        sink.string(resource.resourceId().toString(), 3, resourceId);

        final FieldPath revision = path.child("revision");
        sink.member("revision", 2, revision);
        sink.number(canonicalNumber(Long.toString(resource.revision()), revision), 3, revision);

        final FieldPath digest = path.child("digest");
        sink.member("digest", 3, digest);
        sink.string(resource.digest(), 3, digest);

        sink.members(resource.wireExtras().members(), RESOURCE_RESERVED, 2, RESOURCE_KNOWN, path);
        sink.objectEnd();
    }

    /**
     * 把一个已知有限数值的最短十进制文本先交给冻结的 {@link ExactJsonNumber} 得到 canonical 拼写。
     *
     * <p>
     * 这是两条遍历共用的唯一数字入口：int/long 传 {@code Integer/Long.toString}，double/float 传
     * {@code Double/Float.toString}。非法输入由 {@link ExactJsonNumber} 按其固定 code/path 报错，本方法
     * 不解释、不修补、不改精度。
     * </p>
     *
     * @param raw  已定型的十进制文本
     * @param path 该分量的业务路径，用于 pointer
     * @return 唯一 canonical 拼写
     */
    private static String canonicalNumber(String raw, FieldPath path) {
        return ExactJsonNumber.parse(raw, path.pointer()).canonical();
    }

    // ------------------------------------------------------------------
    // 私有 sink：两种实现只表达 wire 结构，不持有 typed 图
    // ------------------------------------------------------------------

    /** 一次 schema 遍历可能发出的全部 wire 事件；两种实现必须对同一输入发出完全相同的序列。 */
    private interface Sink {

        void objectStart(int depth, FieldPath path);

        void objectEnd();

        void arrayStart(int depth, FieldPath path);

        void arrayEnd();

        void member(String key, int zeroBasedMemberIndex, FieldPath childPath);

        void arraySeparator(int zeroBasedElementIndex, FieldPath elementPath);

        void string(String value, int depth, FieldPath path);

        void number(String canonicalNumber, int depth, FieldPath path);

        void value(ExactJson.FrozenJson value, int depth, FieldPath path);

        void members(ExactJson.FrozenJson members, Set<String> reserved, int ownerDepth,
                     int knownMemberCount, FieldPath ownerPath);
    }

    /** 只在冻结的 {@link PayloadWireBudget} 上累积计数，不写任何文本、不建任何缓冲区。 */
    private static final class BudgetSink implements Sink {

        private final PayloadWireBudget budget = new PayloadWireBudget();

        @Override
        public void objectStart(int depth, FieldPath path) {
            this.budget.addObject(depth, path);
        }

        @Override
        public void objectEnd() {
            // 起点已计入两个括号：终点不重复计入。
        }

        @Override
        public void arrayStart(int depth, FieldPath path) {
            this.budget.addArray(depth, path);
        }

        @Override
        public void arrayEnd() {
            // 起点已计入两个括号：终点不重复计入。
        }

        @Override
        public void member(String key, int zeroBasedMemberIndex, FieldPath childPath) {
            this.budget.addMember(key, zeroBasedMemberIndex, childPath);
        }

        @Override
        public void arraySeparator(int zeroBasedElementIndex, FieldPath elementPath) {
            this.budget.addArraySeparator(zeroBasedElementIndex, elementPath);
        }

        @Override
        public void string(String value, int depth, FieldPath path) {
            this.budget.addString(value, depth, path);
        }

        @Override
        public void number(String canonicalNumber, int depth, FieldPath path) {
            this.budget.addNumber(canonicalNumber, depth, path);
        }

        @Override
        public void value(ExactJson.FrozenJson value, int depth, FieldPath path) {
            this.budget.addValue(value, depth, path);
        }

        @Override
        public void members(ExactJson.FrozenJson members, Set<String> reserved, int ownerDepth,
                            int knownMemberCount, FieldPath ownerPath) {
            this.budget.addMembers(members, reserved, ownerDepth, knownMemberCount, ownerPath);
        }

        /** 预演得到的完整 wire UTF-8 字节数。 */
        long bytes() {
            return this.budget.bytes();
        }
    }

    /**
     * 把同一套遍历写进一个按预演字节数精确预分配容量的 {@link StringBuilder}。
     *
     * <p>
     * 所有 append 之前先核累计 UTF-8 字节；ASCII 记 1、两字节 BMP 记 2、三字节 BMP 记 3、配对
     * surrogate 记 4；控制字符按 canonical 写成 ASCII 转义序列，因此只记其 ASCII 宽度。未配对
     * surrogate 在输入已认证的前提下不会出现，一旦出现即内部 {@link IllegalStateException}，绝不悄悄
     * 替换。
     * </p>
     */
    private static final class WriterSink implements Sink {

        private final StringBuilder sink;

        private final long expectedBytes;

        private long byteCount;

        WriterSink(long expectedBytes) {
            if (expectedBytes < 0 || expectedBytes > ExactJson.MAX_UTF8_BYTES) {
                throw new IllegalStateException(
                        "the preflight byte count is out of range: " + expectedBytes);
            }
            this.expectedBytes = expectedBytes;
            this.sink = new StringBuilder((int) expectedBytes);
        }

        @Override
        public void objectStart(int depth, FieldPath path) {
            appendAscii('{');
        }

        @Override
        public void objectEnd() {
            appendAscii('}');
        }

        @Override
        public void arrayStart(int depth, FieldPath path) {
            appendAscii('[');
        }

        @Override
        public void arrayEnd() {
            appendAscii(']');
        }

        @Override
        public void member(String key, int zeroBasedMemberIndex, FieldPath childPath) {
            if (zeroBasedMemberIndex > 0) {
                appendAscii(',');
            }
            writeQuoted(key);
            appendAscii(':');
        }

        @Override
        public void arraySeparator(int zeroBasedElementIndex, FieldPath elementPath) {
            if (zeroBasedElementIndex > 0) {
                appendAscii(',');
            }
        }

        @Override
        public void string(String value, int depth, FieldPath path) {
            writeQuoted(value);
        }

        @Override
        public void number(String canonicalNumber, int depth, FieldPath path) {
            appendAsciiText(canonicalNumber);
        }

        @Override
        public void value(ExactJson.FrozenJson value, int depth, FieldPath path) {
            final String canonical = value.canonical();
            reserve(value.utf8Size());
            this.sink.append(canonical);
        }

        @Override
        public void members(ExactJson.FrozenJson members, Set<String> reserved, int ownerDepth,
                            int knownMemberCount, FieldPath ownerPath) {
            final String canonical = members.canonical();
            final int length = canonical.length();
            if (length <= 2) {
                // 空 object：真实贡献 0 字节，不因虚拟 {} 多算内容。
                return;
            }
            if (knownMemberCount > 0) {
                // 所有带 extras 的 owner 都至少有一个 known 成员，故用逗号接在最后一个 known 成员后。
                appendAscii(',');
            }
            final int bodyBytes = members.utf8Size() - 2;
            reserve(bodyBytes);
            this.sink.append(canonical, 1, length - 1);
        }

        /** 写一个 canonical 转义字符串，判定规则与 {@link ExactJson} 的 canonical writer 完全一致。 */
        private void writeQuoted(String value) {
            appendAscii('"');
            final int length = value.length();
            for (int i = 0; i < length; i++) {
                final char c = value.charAt(i);
                if (c < 0x80) {
                    switch (c) {
                        case '"':
                            appendAsciiText("\\\"");
                            break;
                        case '\\':
                            appendAsciiText("\\\\");
                            break;
                        case '\b':
                            appendAsciiText("\\b");
                            break;
                        case '\t':
                            appendAsciiText("\\t");
                            break;
                        case '\n':
                            appendAsciiText("\\n");
                            break;
                        case '\f':
                            appendAsciiText("\\f");
                            break;
                        case '\r':
                            appendAsciiText("\\r");
                            break;
                        default:
                            if (c < 0x20) {
                                appendAsciiText("\\u00");
                                appendAscii(HEX_DIGITS.charAt((c >> 4) & 0xF));
                                appendAscii(HEX_DIGITS.charAt(c & 0xF));
                            } else {
                                appendAscii(c);
                            }
                            break;
                    }
                    continue;
                }
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= length || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new IllegalStateException(
                                "an already certified string holds an unpaired high surrogate at " + "index " + i);
                    }
                    reserve(4);
                    this.sink.append(c).append(value.charAt(i + 1));
                    i++;
                    continue;
                }
                if (Character.isLowSurrogate(c)) {
                    throw new IllegalStateException(
                            "an already certified string holds an unpaired low surrogate at index " + i);
                }
                reserve(c < 0x800 ? 2 : 3);
                this.sink.append(c);
            }
            appendAscii('"');
        }

        /** 写一个已知 ASCII 的一字节字符，并在写入前核累计 UTF-8 字节。 */
        private void appendAscii(char ascii) {
            if (ascii > 0x7F) {
                throw new IllegalStateException(
                        "an internal ASCII fragment holds a non ASCII unit: " + (int) ascii);
            }
            reserve(1);
            this.sink.append(ascii);
        }

        /** 写一段已知 ASCII 的文本，逐字符核累计 UTF-8 字节。 */
        private void appendAsciiText(String fragment) {
            final int length = fragment.length();
            for (int i = 0; i < length; i++) {
                appendAscii(fragment.charAt(i));
            }
        }

        /** 在任何写入之前把即将到来的 UTF-8 宽度计入累计值，越界即内部缺陷。 */
        private void reserve(long delta) {
            final long next = this.byteCount + delta;
            if (delta < 0 || next > ExactJson.MAX_UTF8_BYTES) {
                throw new IllegalStateException("the writer would exceed " + ExactJson.MAX_UTF8_BYTES + " UTF-8 bytes, the preflight certified " + this.expectedBytes);
            }
            this.byteCount = next;
        }

        /** 当前已经写出的真实 UTF-8 字节数。 */
        long bytes() {
            return this.byteCount;
        }

        /** 写出完成的文本，只交给唯一一次 {@link ExactJson#parse(String)}。 */
        String text() {
            return this.sink.toString();
        }
    }

    /**
     * 根对象按 PAYLOAD-CODEC 表顺序排列的 14 个已知成员，只用于缺失检测与解码次序。
     */
    private static final List<String> DECODE_ROOT_KEYS = List.of(
            "schemaVersion", "id", "worldId", "modeId", "dimensionId", "displayName", "description",
            "boundary", "teams", "spawns", "regions", "rules", "resources", "extensions");

    /** 盒对象按 PAYLOAD-CODEC 表顺序排列的 2 个已知成员。 */
    private static final List<String> DECODE_BOX_KEYS = List.of("first", "second");

    /** 整数与小数三元组按 PAYLOAD-CODEC 表顺序排列的 3 个已知成员。 */
    private static final List<String> DECODE_TRIPLE_KEYS = List.of("x", "y", "z");

    /** 朝向按 PAYLOAD-CODEC 表顺序排列的 2 个已知成员。 */
    private static final List<String> DECODE_ORIENTATION_KEYS = List.of("yaw", "pitch");

    /** team 对象按 PAYLOAD-CODEC 表顺序排列的 4 个已知成员。 */
    private static final List<String> DECODE_TEAM_KEYS = List.of("id", "name", "config",
            "extensions");

    /** spawn 对象按 PAYLOAD-CODEC 表顺序排列的 7 个已知成员。 */
    private static final List<String> DECODE_SPAWN_KEYS = List.of("id", "name", "teamId",
            "dimensionId", "position", "orientation", "extensions");

    /** region 对象按 PAYLOAD-CODEC 表顺序排列的 6 个已知成员。 */
    private static final List<String> DECODE_REGION_KEYS = List.of("id", "name", "kindId",
            "corners", "config", "extensions");

    /** resource 对象按 PAYLOAD-CODEC 表顺序排列的 4 个已知成员。 */
    private static final List<String> DECODE_RESOURCE_KEYS = List.of("kindId", "resourceId",
            "revision", "digest");

    /** 一次解码先得到的整数盒：两个端点，以及盒、first、second 三处未知成员位置。 */
    private record DecodedBox(IntPosition first, IntPosition second,
                              ExactJson.FrozenJson boxMembers, ExactJson.FrozenJson firstMembers,
                              ExactJson.FrozenJson secondMembers) {}

    /** 一次解码先得到的整数三元组：值本身与它自己的未知成员位置。 */
    private record DecodedIntPosition(IntPosition value, ExactJson.FrozenJson members) {}

    /** 一次解码先得到的小数三元组：值本身与它自己的未知成员位置。 */
    private record DecodedPosition(Position value, ExactJson.FrozenJson members) {}

    /** 一次解码先得到的朝向：值本身与它自己的未知成员位置。 */
    private record DecodedOrientation(Orientation value, ExactJson.FrozenJson members) {}

    /**
     * 把一份完整的 canonical payload 文本严格解码回 {@link MapDefinition}。
     *
     * <p>
     * 本方法只调用唯一一次 {@link ExactJson#parse(String)} 完成 P02 认证：文本非法、重复 key、
     * 非法 Unicode、数字不合法或超出结构预算时，认证抛出的 {@link JsonContractException} 被转成
     * {@link SchemaException}，code 与原异常完全相同，{@link FieldPath} 由 P02 的完整 pointer 重建，
     * 原异常对象本身作为 cause 保留，真实失败点从不被改写。文本通过认证后继续走
     * {@link #decode(ExactJson.FrozenJson)} 的同一条 schema 解码，不做任何二次 parse。
     * </p>
     *
     * @param strictJson 完整 payload 文本，必须非 null
     * @return 与该 payload 对应的、已自校验的地图定义
     * @throws IllegalArgumentException 当 {@code strictJson} 为 null 时
     * @throws SchemaException          当文本未通过严格 JSON 认证，或 wire 内容违反 P06 schema 时
     */
    public static MapDefinition decode(String strictJson) {
        if (strictJson == null) {
            throw new IllegalArgumentException("strictJson must not be null");
        }
        final ExactJson.FrozenJson payload;
        try {
            payload = ExactJson.parse(strictJson);
        } catch (JsonContractException failure) {
            throw new SchemaException(failure.code(), FieldPath.fromPointer(failure.path()),
                    "payload text was rejected by the strict JSON certification: " + failure.getMessage(), failure);
        }
        return decode(payload);
    }

    /**
     * 把一份已认证的 canonical payload 严格解码回 {@link MapDefinition}。
     *
     * <p>
     * 入参必须是对象形状的 frozen 值：根不是对象（含 {@code null}、数组、字符串、数字、布尔与显式
     * JSON null）时以 {@code WRONG_TYPE} 停在 root 路径。形状正确时只取一次
     * {@link ExactJson.FrozenJson#toJson()} 交出的私有副本，本次解码全程只读这一份副本，不保留入参
     * 引用。每个对象先核齐全 required 成员存在，再按 PAYLOAD-CODEC 的固定字段顺序读类型与数值；缺失
     * 成员报 {@code MISSING_FIELD}，null 用于不允许 null 的成员报 {@code WRONG_TYPE}；typed 列表在
     * 真实计数核验后才分配。{@code rules}、{@code config}、{@code extensions} 允许任意形状（含
     * {@code null}）并各自冻结成独立的私有子树，绝不与未知成员的容器合并。所有未知成员按 13 个语义槽
     * 逐一提取并冻结，没有未知成员时整场复用同一个冻结 {@code {}}。
     * </p>
     *
     * @param payload 已认证的 frozen payload，必须非 null
     * @return 与该 payload 对应的、已自校验的地图定义
     * @throws IllegalArgumentException 当 {@code payload} 为 null 时
     * @throws SchemaException          当根不是对象，或 wire 内容违反 P06 schema 时
     */
    public static MapDefinition decode(ExactJson.FrozenJson payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        final String canonical = payload.canonical();
        if (canonical.isEmpty() || canonical.charAt(0) != '{') {
            throw new SchemaException(SchemaException.CODE_WRONG_TYPE, FieldPath.root(),
                    "the payload root must be a JSON object, not " + canonical);
        }
        final JsonElement copied = payload.toJson();
        if (!copied.isJsonObject()) {
            throw new IllegalStateException(
                    "the frozen canonical text and its private tree disagree on the root shape");
        }
        return new DecodeContext(copied.getAsJsonObject()).decodeMap();
    }

    /**
     * 一次解码的私有上下文：持有唯一一份由 {@code payload.toJson()} 交出的私有树，并在本次解码内
     * 只冻结一次 {@code {}} 供全部空槽复用。
     *
     * <p>
     * 本类不缓存任何 wire 文本、不做任何二次 parse、不保留入参引用；四类 typed 列表在完整计数核验
     * 通过后才分配 typed {@code ArrayList}；对象按 schema 顺序 {@code teams → spawns → regions →
     * rules → resources → extensions} 遍历。这里同时复制了冻结的标量规则，因为
     * {@code MapScalars} 是包私有类型，既不能 import 也不能反射。
     * </p>
     */
    private static final class DecodeContext {

        /** 冻结的 team 计数上限，只在建 typed 列表之前做真实 size 核验。 */
        private static final int MAX_TEAMS = 64;

        /** 冻结的 spawn 计数上限，只在建 typed 列表之前做真实 size 核验。 */
        private static final int MAX_SPAWNS = 16384;

        /** 冻结的 region 计数上限，只在建 typed 列表之前做真实 size 核验。 */
        private static final int MAX_REGIONS = 1024;

        /** 冻结的 resource 计数上限，只在建 typed 列表之前做真实 size 核验。 */
        private static final int MAX_RESOURCES = 1024;

        /** 冻结的单行名称 UTF-8 字节上限。 */
        private static final int MAX_NAME_UTF8_BYTES = 512;

        /** 冻结的描述 UTF-8 字节上限。 */
        private static final int MAX_DESCRIPTION_UTF8_BYTES = 8192;

        /** 冻结的 namespaced 标识 ASCII 字符上限。 */
        private static final int MAX_NAMESPACED_CHARS = 191;

        /** 冻结的 resource digest 精确长度。 */
        private static final int DIGEST_LENGTH = 64;

        /** 唯一一份私有根对象，来自一次 {@code toJson()} 的 deep copy。 */
        private final JsonObject root;

        /** 本次解码第一次遇到空槽时冻结出的 {@code {}}，之后整场复用同一实例。 */
        private ExactJson.FrozenJson emptyMembers;

        private DecodeContext(JsonObject root) {
            this.root = root;
        }

        /** 按 PAYLOAD-CODEC 的固定顺序解出根对象，并在最后交给 {@link MapDefinition} 复核聚合不变量。 */
        private MapDefinition decodeMap() {
            final FieldPath rootPath = FieldPath.root();
            final JsonObject object = this.root;
            requireMembers(object, rootPath, DECODE_ROOT_KEYS);

            final FieldPath schemaVersionPath = rootPath.child("schemaVersion");
            final int schemaVersion = readMathInt(object.get("schemaVersion"), schemaVersionPath,
                    "schemaVersion");
            if (schemaVersion != 1) {
                throw new SchemaException(SchemaException.CODE_UNSUPPORTED_SCHEMA, schemaVersionPath,
                        "schemaVersion must be exactly 1 but was " + schemaVersion);
            }

            final FieldPath idPath = rootPath.child("id");
            final Ids.DefinitionId id = new Ids.DefinitionId(readCanonicalUuid(object, "id", idPath));
            final FieldPath worldIdPath = rootPath.child("worldId");
            final Ids.WorldId worldId = new Ids.WorldId(readCanonicalUuid(object, "worldId", worldIdPath));

            final String modeId = readNamespaced(object, "modeId", rootPath, "namespaced value");
            final String dimensionId = readNamespaced(object, "dimensionId", rootPath, "dimensionId");
            final String displayName = readName(object, "displayName", rootPath, false);
            final String description = readDescription(object, "description", rootPath);

            final FieldPath boundaryPath = rootPath.child("boundary");
            final DecodedBox boundary = decodeBox(
                    requireObject(object, "boundary", boundaryPath), boundaryPath);

            final FieldPath teamsPath = rootPath.child("teams");
            final JsonArray teamArray = requireArray(object, "teams", teamsPath);
            requireCount(teamArray.size(), MAX_TEAMS, teamsPath, "teams");
            final List<TeamDefinition> teams = new ArrayList<>(teamArray.size());
            final Set<UUID> teamIds = new HashSet<>();
            for (int i = 0; i < teamArray.size(); i++) {
                final FieldPath teamPath = teamsPath.child(Integer.toString(i));
                teams.add(decodeTeam(requireArrayItem(teamArray, i, teamPath), teamPath, teamIds));
            }

            final FieldPath spawnsPath = rootPath.child("spawns");
            final JsonArray spawnArray = requireArray(object, "spawns", spawnsPath);
            requireCount(spawnArray.size(), MAX_SPAWNS, spawnsPath, "spawns");
            final List<SpawnDefinition> spawns = new ArrayList<>(spawnArray.size());
            final Set<UUID> objectIds = new HashSet<>();
            for (int i = 0; i < spawnArray.size(); i++) {
                final FieldPath spawnPath = spawnsPath.child(Integer.toString(i));
                final SpawnDefinition spawn = decodeSpawn(
                        requireArrayItem(spawnArray, i, spawnPath), spawnPath, objectIds);
                if (!teamIds.contains(spawn.teamId().value())) {
                    throw new SchemaException(SchemaException.CODE_INVALID_VALUE,
                            spawnPath.child("teamId"),
                            "a spawn teamId must name a team of this same definition");
                }
                if (!dimensionId.equals(spawn.dimensionId())) {
                    throw new SchemaException(SchemaException.CODE_INVALID_VALUE,
                            spawnPath.child("dimensionId"),
                            "a spawn dimensionId must equal the definition dimensionId");
                }
                spawns.add(spawn);
            }

            final FieldPath regionsPath = rootPath.child("regions");
            final JsonArray regionArray = requireArray(object, "regions", regionsPath);
            requireCount(regionArray.size(), MAX_REGIONS, regionsPath, "regions");
            final List<RegionDefinition> regions = new ArrayList<>(regionArray.size());
            for (int i = 0; i < regionArray.size(); i++) {
                final FieldPath regionPath = regionsPath.child(Integer.toString(i));
                regions.add(decodeRegion(requireArrayItem(regionArray, i, regionPath), regionPath,
                        objectIds));
            }

            final ExactJson.FrozenJson rules = readFrozen(object, "rules");

            final FieldPath resourcesPath = rootPath.child("resources");
            final JsonArray resourceArray = requireArray(object, "resources", resourcesPath);
            requireCount(resourceArray.size(), MAX_RESOURCES, resourcesPath, "resources");
            final List<ResourceRevisionRef> resources = new ArrayList<>(resourceArray.size());
            for (int i = 0; i < resourceArray.size(); i++) {
                final FieldPath resourcePath = resourcesPath.child(Integer.toString(i));
                resources.add(decodeResource(requireArrayItem(resourceArray, i, resourcePath),
                        resourcePath));
            }

            final ExactJson.FrozenJson extensions = readFrozen(object, "extensions");
            final ExactJson.FrozenJson members = extras(object, ROOT_RESERVED);

            return buildAt(rootPath, () -> new MapDefinition(id, worldId, modeId, dimensionId,
                    displayName, description, new BoxCorners(boundary.first(), boundary.second()),
                    teams, spawns, regions, rules, resources, extensions,
                    new WireExtras.MapExtras(members, boundary.boxMembers(),
                            boundary.firstMembers(), boundary.secondMembers())));
        }

        /** 解出一个 team，并把它自己的 id 记入本次解码的 TeamId 域。 */
        private TeamDefinition decodeTeam(JsonObject object, FieldPath path, Set<UUID> seenTeamIds) {
            requireMembers(object, path, DECODE_TEAM_KEYS);
            final FieldPath idPath = path.child("id");
            final Ids.TeamId id = new Ids.TeamId(readCanonicalUuid(object, "id", idPath));
            if (!seenTeamIds.add(id.value())) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, idPath,
                        "a team id must not repeat an earlier team id");
            }
            final String name = readName(object, "name", path, true);
            final ExactJson.FrozenJson config = readFrozen(object, "config");
            final ExactJson.FrozenJson extensions = readFrozen(object, "extensions");
            final ExactJson.FrozenJson members = extras(object, TEAM_RESERVED);
            return buildAt(path, () -> new TeamDefinition(id, name, config, extensions,
                    new WireExtras.TeamExtras(members)));
        }

        /** 解出一个 spawn 的几何与标量，并把它自己的 id 记入共享的 ObjectId 域。 */
        private SpawnDefinition decodeSpawn(JsonObject object, FieldPath path, Set<UUID> seenObjectIds) {
            requireMembers(object, path, DECODE_SPAWN_KEYS);
            final FieldPath idPath = path.child("id");
            final Ids.ObjectId id = new Ids.ObjectId(readCanonicalUuid(object, "id", idPath));
            if (!seenObjectIds.add(id.value())) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, idPath,
                        "an object id must not repeat an earlier spawn or region id");
            }
            final String name = readName(object, "name", path, false);
            final FieldPath teamIdPath = path.child("teamId");
            final Ids.TeamId teamId = new Ids.TeamId(readCanonicalUuid(object, "teamId", teamIdPath));
            final String dimensionId = readNamespaced(object, "dimensionId", path, "dimensionId");
            final FieldPath positionPath = path.child("position");
            final DecodedPosition position = decodePosition(
                    requireObject(object, "position", positionPath), positionPath);
            final FieldPath orientationPath = path.child("orientation");
            final DecodedOrientation orientation = decodeOrientation(
                    requireObject(object, "orientation", orientationPath), orientationPath);
            final ExactJson.FrozenJson extensions = readFrozen(object, "extensions");
            final ExactJson.FrozenJson members = extras(object, SPAWN_RESERVED);
            return buildAt(path, () -> new SpawnDefinition(id, name, teamId, dimensionId,
                    position.value(), orientation.value(), extensions,
                    new WireExtras.SpawnExtras(members, position.members(),
                            orientation.members())));
        }

        /** 解出一个 region，并把它自己的 id 记入共享的 ObjectId 域（域晚于 spawn 填充）。 */
        private RegionDefinition decodeRegion(JsonObject object, FieldPath path,
                                              Set<UUID> seenObjectIds) {
            requireMembers(object, path, DECODE_REGION_KEYS);
            final FieldPath idPath = path.child("id");
            final Ids.ObjectId id = new Ids.ObjectId(readCanonicalUuid(object, "id", idPath));
            if (!seenObjectIds.add(id.value())) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, idPath,
                        "an object id must not repeat an earlier spawn or region id");
            }
            final String name = readName(object, "name", path, false);
            final String kindId = readNamespaced(object, "kindId", path, "namespaced value");
            final FieldPath cornersPath = path.child("corners");
            final DecodedBox corners = decodeBox(
                    requireObject(object, "corners", cornersPath), cornersPath);
            final ExactJson.FrozenJson config = readFrozen(object, "config");
            final ExactJson.FrozenJson extensions = readFrozen(object, "extensions");
            final ExactJson.FrozenJson members = extras(object, REGION_RESERVED);
            return buildAt(path, () -> new RegionDefinition(id, name, kindId,
                    new BoxCorners(corners.first(), corners.second()), config, extensions,
                    new WireExtras.RegionExtras(members, corners.boxMembers(),
                            corners.firstMembers(), corners.secondMembers())));
        }

        /** 解出一条 resource 引用，逐处定位 kindId、resourceId、revision 与 digest。 */
        private ResourceRevisionRef decodeResource(JsonObject object, FieldPath path) {
            requireMembers(object, path, DECODE_RESOURCE_KEYS);
            final String kindId = readNamespaced(object, "kindId", path, "namespaced value");
            final FieldPath resourceIdPath = path.child("resourceId");
            final UUID resourceId = readCanonicalUuid(object, "resourceId", resourceIdPath);
            final FieldPath revisionPath = path.child("revision");
            final long revision = readRevision(object.get("revision"), revisionPath);
            final String digest = readDigest(object, "digest", path);
            final ExactJson.FrozenJson members = extras(object, RESOURCE_RESERVED);
            return buildAt(path, () -> new ResourceRevisionRef(kindId, resourceId, revision, digest,
                    new WireExtras.ResourceExtras(members)));
        }

        /** 解出一个整数盒（boundary 与 region.corners 共用同一套规则）。 */
        private DecodedBox decodeBox(JsonObject object, FieldPath path) {
            requireMembers(object, path, DECODE_BOX_KEYS);
            final FieldPath firstPath = path.child("first");
            final DecodedIntPosition first = decodeIntPosition(
                    requireObject(object, "first", firstPath), firstPath);
            final FieldPath secondPath = path.child("second");
            final DecodedIntPosition second = decodeIntPosition(
                    requireObject(object, "second", secondPath), secondPath);
            return new DecodedBox(first.value(), second.value(), extras(object, BOX_RESERVED),
                    first.members(), second.members());
        }

        /** 解出一个整数三元组，端点坐标必须是数学整数且落在 int 内。 */
        private DecodedIntPosition decodeIntPosition(JsonObject object, FieldPath path) {
            requireMembers(object, path, DECODE_TRIPLE_KEYS);
            final int x = readMathInt(object.get("x"), path.child("x"), "x");
            final int y = readMathInt(object.get("y"), path.child("y"), "y");
            final int z = readMathInt(object.get("z"), path.child("z"), "z");
            final IntPosition value = buildAt(path, () -> new IntPosition(x, y, z));
            return new DecodedIntPosition(value, extras(object, POSITION_RESERVED));
        }

        /** 解出一个小数三元组，三个坐标都必须精确往返且有限。 */
        private DecodedPosition decodePosition(JsonObject object, FieldPath path) {
            requireMembers(object, path, DECODE_TRIPLE_KEYS);
            final double x = readMathDouble(object.get("x"), path.child("x"), "x");
            final double y = readMathDouble(object.get("y"), path.child("y"), "y");
            final double z = readMathDouble(object.get("z"), path.child("z"), "z");
            final Position value = buildAt(path, () -> new Position(x, y, z));
            return new DecodedPosition(value, extras(object, POSITION_RESERVED));
        }

        /** 解出朝向，yaw 与 pitch 都必须精确往返且有限，pitch 不做任何夹取。 */
        private DecodedOrientation decodeOrientation(JsonObject object, FieldPath path) {
            requireMembers(object, path, DECODE_ORIENTATION_KEYS);
            final float yaw = readMathFloat(object.get("yaw"), path.child("yaw"), "yaw");
            final float pitch = readMathFloat(object.get("pitch"), path.child("pitch"), "pitch");
            final Orientation value = buildAt(path, () -> new Orientation(yaw, pitch));
            return new DecodedOrientation(value, extras(object, ORIENTATION_RESERVED));
        }

        /** 先核该对象全部 required 成员存在，报出第一个缺失成员自己的路径。 */
        private void requireMembers(JsonObject object, FieldPath path, List<String> keys) {
            for (int i = 0; i < keys.size(); i++) {
                final String key = keys.get(i);
                if (!object.has(key)) {
                    throw new SchemaException(SchemaException.CODE_MISSING_FIELD, path.child(key),
                            "the required member is absent from the wire object");
                }
            }
        }

        /** 读一个必须是对象的成员，任何非对象（含显式 JSON null）都是 WRONG_TYPE。 */
        private JsonObject requireObject(JsonObject object, String key, FieldPath path) {
            final JsonElement value = object.get(key);
            if (!value.isJsonObject()) {
                throw new SchemaException(SchemaException.CODE_WRONG_TYPE, path,
                        "the member must be a JSON object, not " + shape(value));
            }
            return value.getAsJsonObject();
        }

        /** 读一个必须是数组的成员，任何非数组（含显式 JSON null）都是 WRONG_TYPE。 */
        private JsonArray requireArray(JsonObject object, String key, FieldPath path) {
            final JsonElement value = object.get(key);
            if (!value.isJsonArray()) {
                throw new SchemaException(SchemaException.CODE_WRONG_TYPE, path,
                        "the member must be a JSON array, not " + shape(value));
            }
            return value.getAsJsonArray();
        }

        /** 读一个必须是对象的数组元素，逐元素定位错误。 */
        private JsonObject requireArrayItem(JsonArray array, int index, FieldPath path) {
            final JsonElement value = array.get(index);
            if (!value.isJsonObject()) {
                throw new SchemaException(SchemaException.CODE_WRONG_TYPE, path,
                        "the array item must be a JSON object, not " + shape(value));
            }
            return value.getAsJsonObject();
        }

        /** 在分配 typed 列表之前核真实元素个数上限。 */
        private void requireCount(int size, int max, FieldPath path, String what) {
            if (size > max) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        what + " must hold at most " + max + " items but holds " + size);
            }
        }

        /** 读一个必须是 JSON 字符串的成员：字符串形状之外的一切都是 WRONG_TYPE。 */
        private String requireString(JsonObject object, String key, FieldPath path) {
            final JsonElement value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new SchemaException(SchemaException.CODE_WRONG_TYPE, path,
                        "the member must be a JSON string, not " + shape(value));
            }
            return value.getAsJsonPrimitive().getAsString();
        }

        /** 取一个 JSON 数字的精确十进制值，WIRE 之外的形状一律 WRONG_TYPE。 */
        private BigDecimal requireMathDecimal(JsonElement value, FieldPath path, String what) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new SchemaException(SchemaException.CODE_WRONG_TYPE, path,
                        what + " must be a JSON number, not " + shape(value));
            }
            return value.getAsJsonPrimitive().getAsBigDecimal();
        }

        /** 以 intValueExact 做数学整数判定，截断、小数与溢出都保真实 ArithmeticException。 */
        private int readMathInt(JsonElement value, FieldPath path, String what) {
            final BigDecimal exact = requireMathDecimal(value, path, what);
            try {
                return exact.intValueExact();
            } catch (ArithmeticException failure) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        what + " must be a mathematical integer inside the int range: " + failure.getMessage(), failure);
            }
        }

        /** 以 longValueExact 做数学整数判定，截断、小数与溢出都保真实 ArithmeticException。 */
        private long readMathLong(JsonElement value, FieldPath path, String what) {
            final BigDecimal exact = requireMathDecimal(value, path, what);
            try {
                return exact.longValueExact();
            } catch (ArithmeticException failure) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        what + " must be a mathematical integer inside the long range: " + failure.getMessage(), failure);
            }
        }

        /** 小数必须精确往返 {@code Double.toString} 且有限，否则 INVALID_VALUE。 */
        private double readMathDouble(JsonElement value, FieldPath path, String what) {
            final BigDecimal exact = requireMathDecimal(value, path, what);
            final double result = exact.doubleValue();
            if (!Double.isFinite(result) || new BigDecimal(Double.toString(result)).compareTo(exact) != 0) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        what + " must be an exactly representable finite double");
            }
            return result;
        }

        /** 小数必须精确往返 {@code Float.toString} 且有限，否则 INVALID_VALUE。 */
        private float readMathFloat(JsonElement value, FieldPath path, String what) {
            final BigDecimal exact = requireMathDecimal(value, path, what);
            final float result = exact.floatValue();
            if (!Float.isFinite(result) || new BigDecimal(Float.toString(result)).compareTo(exact) != 0) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        what + " must be an exactly representable finite float");
            }
            return result;
        }

        /** 读一个名称成员，并以冻结的名称规则判定。 */
        private String readName(JsonObject object, String key, FieldPath owner, boolean nonEmpty) {
            final FieldPath path = owner.child(key);
            final String name = requireString(object, key, path);
            return buildAt(path, () -> requireName(name, nonEmpty));
        }

        /** 读一个描述成员，并以冻结的描述规则判定（允许 C0 控制字符）。 */
        private String readDescription(JsonObject object, String key, FieldPath owner) {
            final FieldPath path = owner.child(key);
            final String description = requireString(object, key, path);
            return buildAt(path, () -> requireDescription(description));
        }

        /** 读一个 namespaced 标量成员，并以冻结的 {@code namespace:path} 规则判定。 */
        private String readNamespaced(JsonObject object, String key, FieldPath owner, String what) {
            final FieldPath path = owner.child(key);
            final String value = requireString(object, key, path);
            return buildAt(path, () -> requireNamespacedScalar(value, what));
        }

        /** 读一个 digest 成员，并以冻结的 64 位小写十六进制规则判定。 */
        private String readDigest(JsonObject object, String key, FieldPath owner) {
            final FieldPath path = owner.child(key);
            final String digest = requireString(object, key, path);
            return buildAt(path, () -> requireDigest(digest));
        }

        /** 读一个 revision，先数学整数判定，再以冻结的严格大于零规则判定。 */
        private long readRevision(JsonElement value, FieldPath path) {
            final long revision = readMathLong(value, path, "revision");
            return buildAt(path, () -> requirePositiveRevision(revision));
        }

        /** 把一个任意形状的成员（含显式 JSON null）冻结成它自己的独立私有子树。 */
        private ExactJson.FrozenJson readFrozen(JsonObject object, String key) {
            return ExactJson.freeze(object.get(key));
        }

        /** 取出该对象中不属于任何已知成员的成员，没有未知成员时返回共享的空对象。 */
        private ExactJson.FrozenJson extras(JsonObject object, Set<String> reserved) {
            JsonObject members = null;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!reserved.contains(entry.getKey())) {
                    if (members == null) {
                        members = new JsonObject();
                    }
                    members.add(entry.getKey(), entry.getValue());
                }
            }
            return members == null ? emptyMembers() : ExactJson.freeze(members);
        }

        /** 本次解码内只冻结一次 {@code {}}，之后整场复用同一实例。 */
        private ExactJson.FrozenJson emptyMembers() {
            if (this.emptyMembers == null) {
                this.emptyMembers = ExactJson.freeze(new JsonObject());
            }
            return this.emptyMembers;
        }

        /**
         * 在指定路径构造一个值，并把构造器抛出的真实 {@link IllegalArgumentException} 转成
         * {@code INVALID_VALUE}：原因保持同一个异常对象，路径保持真实失败点，绝不替换或吞掉原因。
         */
        private static <T> T buildAt(FieldPath path, Supplier<T> factory) {
            try {
                return factory.get();
            } catch (IllegalArgumentException failure) {
                throw new SchemaException(SchemaException.CODE_INVALID_VALUE, path,
                        "the wire value is rejected at this path: " + failure.getMessage(), failure);
            }
        }

        /** 描述一个 JSON 元素的实际形状，只用于失败细节，判型本身从不依赖它。 */
        private static String shape(JsonElement value) {
            if (value == null) {
                return "an absent value";
            }
            if (value.isJsonNull()) {
                return "JSON null";
            }
            if (value.isJsonObject()) {
                return "a JSON object";
            }
            if (value.isJsonArray()) {
                return "a JSON array";
            }
            final JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return "a JSON boolean";
            }
            if (primitive.isString()) {
                return "a JSON string";
            }
            return "a JSON number";
        }

        /**
         * 冻结名称规则的本地副本：{@code MapScalars} 是包私有类型，既不能 import 也不能反射；成对与
         * 字节预算在同一次前向扫描中判定，先出现的缺陷先报，{@code nonEmpty} 只决定空串是否被拒。
         */
        private static String requireName(String value, boolean nonEmpty) {
            if (value == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (nonEmpty && value.isEmpty()) {
                throw new IllegalArgumentException("name must not be empty");
            }
            int utf8 = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new IllegalArgumentException("name must be legal paired Unicode but has" + " unpaired high surrogate U+" + toHex4(c) + " at index " + i);
                    }
                    utf8 += 4;
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException("name must be legal paired Unicode but has" + " unpaired low surrogate U+" + toHex4(c) + " at index " + i);
                } else {
                    if (c <= 0x1F) {
                        throw new IllegalArgumentException("name must not contain control character" + " U+" + toHex4(c) + " at index " + i);
                    }
                    utf8 += (c < 0x80) ? 1 : (c < 0x800) ? 2 : 3;
                }
                if (utf8 > MAX_NAME_UTF8_BYTES) {
                    throw new IllegalArgumentException("name must be at most " + MAX_NAME_UTF8_BYTES + " UTF-8 bytes, but exceeded the budget at index " + i);
                }
            }
            return value;
        }

        /** 冻结描述规则的本地副本：与名称规则同样判定成对与 8192 字节预算，但不限制控制字符。 */
        private static String requireDescription(String value) {
            if (value == null) {
                throw new IllegalArgumentException("description must not be null");
            }
            int utf8 = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new IllegalArgumentException("description must be legal paired Unicode" + " but has unpaired high surrogate U+" + toHex4(c) + " at index " + i);
                    }
                    utf8 += 4;
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException("description must be legal paired Unicode but" + " has unpaired low surrogate U+" + toHex4(c) + " at index " + i);
                } else {
                    utf8 += (c < 0x80) ? 1 : (c < 0x800) ? 2 : 3;
                }
                if (utf8 > MAX_DESCRIPTION_UTF8_BYTES) {
                    throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION_UTF8_BYTES + " UTF-8 bytes, but exceeded the budget at index " + i);
                }
            }
            return value;
        }

        /**
         * 冻结 {@code namespace:path} 规则的本地副本：整串 ASCII、最多 191 字符、恰一个冒号、两侧
         * 都非空且各自匹配冻结字符集，命名空间从不隐式补全。
         */
        private static String requireNamespacedScalar(String value, String what) {
            if (value == null) {
                throw new IllegalArgumentException(what + " must not be null");
            }
            final int length = value.length();
            if (length == 0) {
                throw new IllegalArgumentException(what + " must not be empty");
            }
            if (length > MAX_NAMESPACED_CHARS) {
                throw new IllegalArgumentException(what + " must be at most " + MAX_NAMESPACED_CHARS + " ASCII characters, got " + length);
            }
            final int colon = value.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException(
                        what + " must be explicit 'namespace:path'; the namespace is never defaulted");
            }
            if (value.indexOf(':', colon + 1) >= 0) {
                throw new IllegalArgumentException(what + " must contain exactly one ':' separator");
            }
            if (colon == 0) {
                throw new IllegalArgumentException(what + " namespace must not be empty");
            }
            if (colon == length - 1) {
                throw new IllegalArgumentException(what + " path must not be empty");
            }
            for (int i = 0; i < colon; i++) {
                if (!isNamespaceCharacter(value.charAt(i))) {
                    throw new IllegalArgumentException(
                            what + " namespace must match [a-z0-9_.-]+ at index " + i);
                }
            }
            for (int i = colon + 1; i < length; i++) {
                if (!isPathCharacter(value.charAt(i))) {
                    throw new IllegalArgumentException(
                            what + " path must match [a-z0-9_./-]+ at index " + i);
                }
            }
            return value;
        }

        /** 冻结的命名空间字符集。 */
        private static boolean isNamespaceCharacter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
        }

        /** 冻结的路径字符集。 */
        private static boolean isPathCharacter(char c) {
            return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-';
        }

        /**
         * 冻结 digest 规则的本地副本：长度先于任何字符判定，随后逐字符核 {@code [0-9a-f]}。
         */
        private static String requireDigest(String digest) {
            if (digest == null) {
                throw new IllegalArgumentException("ResourceRevisionRef.digest must not be null");
            }
            if (digest.length() != DIGEST_LENGTH) {
                throw new IllegalArgumentException("ResourceRevisionRef.digest must be exactly " + DIGEST_LENGTH + " lower case hex characters, got " + digest.length());
            }
            for (int i = 0; i < DIGEST_LENGTH; i++) {
                final char c = digest.charAt(i);
                if (!isLowerCaseHexDigit(c)) {
                    throw new IllegalArgumentException("ResourceRevisionRef.digest must match" + " [0-9a-f] but has '" + c + "' (U+" + toHex4(c) + ") at index " + i);
                }
            }
            return digest;
        }

        /** 冻结的 revision 规则本地副本：必须严格大于零。 */
        private static long requirePositiveRevision(long revision) {
            if (revision <= 0L) {
                throw new IllegalArgumentException(
                        "ResourceRevisionRef.revision must be > 0 but was " + revision);
            }
            return revision;
        }

        /**
         * 读一个已知 UUID 位置：先在值异常包装之外要求 JSON 字符串（非 string 与显式 null 保持原
         * WRONG_TYPE、原 path、cause=null），再在 {@code buildAt} 内以冻结的规范小写 36 字符规则校验文本。
         */
        private UUID readCanonicalUuid(JsonObject object, String key, FieldPath path) {
            final String wire = requireString(object, key, path);
            return buildAt(path, () -> requireCanonicalUuid(wire));
        }

        /** 把 typed id 的 36 字符 wire 读成 {@link UUID}：必须带连字符、规范小写，缩写与大小写变换都拒。 */
        private static UUID requireCanonicalUuid(String wire) {
            if (wire.length() != 36) {
                throw new IllegalArgumentException(
                        "a UUID must be exactly 36 characters, got " + wire.length());
            }
            for (int i = 0; i < 36; i++) {
                final char c = wire.charAt(i);
                if (i == 8 || i == 13 || i == 18 || i == 23) {
                    if (c != '-') {
                        throw new IllegalArgumentException(
                                "a UUID must hold '-' at index " + i + " but has '" + c + "'");
                    }
                } else if (!isLowerCaseHexDigit(c)) {
                    throw new IllegalArgumentException("a UUID must match [0-9a-f] but has '" + c + "' (U+" + toHex4(c) + ") at index " + i);
                }
            }
            return UUID.fromString(wire);
        }

        /** 冻结的小写十六进制字符集。 */
        private static boolean isLowerCaseHexDigit(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
        }

        /** 冻结的 U+XXXX 失败细节格式。 */
        private static String toHex4(char c) {
            final String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
            return "0000".substring(hex.length()) + hex;
        }
    }
}
