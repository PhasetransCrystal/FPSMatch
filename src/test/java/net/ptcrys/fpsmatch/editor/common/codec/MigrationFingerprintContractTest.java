package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 正式契约测试：{@code R06.2b4} 完整迁移对象的流式 canonical 摘要（{@code MIGRATION-FINGERPRINT}）。
 *
 * <p>本类只用两种独立材料构造全部预期，两种材料都先于生产调用建立：冻结的 P02 canonical 成员文本
 * （含其按解码后 key 的排序规则与转义规则）以及 JDK 的 {@link StandardCharsets#UTF_8} 加
 * {@link MessageDigest}。任何合法候选类的完整 canonical 文本都在本文件内逐字拼写，并与
 * piece 树 oracle 交叉核对；大输入的摘要在 oracle 侧按不超过 64 KiB 的分片喂给 JDK 摘要器，
 * 既不构造整份输入的 {@code byte[]}，也不把成对摘要不同当作唯一证据。</p>
 *
 * <p>本文件在“生产段落”标记之前的所有 helper 一律不调用被测生产方法，证据 harness 对该段落
 * 静态断言零生产调用。</p>
 */
class MigrationFingerprintContractTest {

    // ------------------------------------------------------------------
    // 固定常量：seed、计数、预算与固定标识
    // ------------------------------------------------------------------

    private static final long SEED = 20261115L;
    private static final int GENERATED_CASES = 1000;
    private static final int REPEATS = 100;
    private static final int CONCURRENT_THREADS = 2;
    private static final long MEBI = 1024L * 1024L;
    private static final int LANE_CHUNK_CHARS = 65536;
    private static final int LARGE_LEGACY_BYTES = 8388608;
    private static final int LARGE_UNKNOWN_MEMBERS = 127;
    private static final int LARGE_UNKNOWN_VALUE_CHARS = 65000;
    private static final int LEGACY_CHUNK_CHARS = 65536;
    private static final int LARGE_CHUNK_MEMBERS = 127;
    private static final long SINK_BUDGET_BYTES = 67108864L;
    private static final int SMALL_SHARED_MEMBERS = 17;
    private static final int SMALL_SHARED_VALUE_CHARS = 65525;
    private static final int SMALL_SHARED_ENTRIES = 65;
    private static final int MAX_ID_MAP_ENTRIES = 17473;
    private static final int MAX_TEAM_TARGETS = 64;
    private static final int MAX_SPAWN_TARGETS = 16384;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final String RAW_JSON_DIGEST = repeat('1');
    private static final String CANON_JSON_DIGEST = repeat('2');
    private static final String RAW_CFG_DIGEST = repeat('3');
    private static final String CANON_CFG_DIGEST = repeat('4');

    private static final String WORLD = "0f0f0f0f-1111-2222-3333-444444444444";
    private static final String OTHER_WORLD = "0f0f0f0f-1111-2222-3333-444444444445";
    private static final String DEFINITION = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String OTHER_DEFINITION = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String TEAM_ID = "11111111-1111-1111-1111-111111111111";
    private static final String SPAWN_ID = "22222222-2222-2222-2222-222222222222";
    private static final String REGION_ID = "33333333-3333-3333-3333-333333333333";
    private static final String MODE_ID = "fpsmatch:arena";
    private static final String MAP_KEY = "worlds/arena";
    private static final String LEGACY_JSON_TEXT = "{\"legacy\":true}";
    private static final String LEGACY_CFG_TEXT = "{\"legacyCfg\":true}";
    /**
     * 测试独立手写的 legacy 文档 canonical 期望。它们不是从生产 {@code canonical()} 读回来的，而是由
     * 夹具作者按冻结 P02 规则（按 key 排序、数字用去尾零指数写法）独立写出，随后在夹具认证时与
     * {@code ExactJson.parse(raw).canonical()} 逐字核对一次；此后所有期望只用这里的手写文本。
     */
    private static final String LEGACY_JSON_CANONICAL = "{\"legacy\":true}";
    private static final String LEGACY_CFG_CANONICAL = "{\"legacyCfg\":true}";
    private static final String EMPTY_OBJECT = "{}";
    private static final String NULL_JSON = "null";

    private static final String[] RESERVED_MIGRATION = {"cfgState", "idMap", "legacyCfg", "legacyJson",
            "missingCfgPolicy", "sourceHashes", "sourceScope"};
    private static final String[] RESERVED_SCOPE = {"legacyMapKey", "modeId", "worldId"};
    private static final String[] RESERVED_HASHES = {"cfg", "json"};
    private static final String[] RESERVED_HASH = {"canonicalSha256", "rawSha256", "sourceKind"};
    private static final String[] RESERVED_ENTRY = {"id", "kind", "sourceDocument", "sourceSegments"};

    private static final String DEFINITION_ENTRY_TEXT = "{\"id\":\"" + DEFINITION
            + "\",\"kind\":\"DEFINITION\",\"sourceDocument\":\"JSON\",\"sourceSegments\":[]}";
    private static final String JSON_HASH_TEXT = "{\"canonicalSha256\":\"" + CANON_JSON_DIGEST
            + "\",\"rawSha256\":\"" + RAW_JSON_DIGEST + "\",\"sourceKind\":\"FILE_BYTES\"}";
    private static final String SCOPE_TEXT = "{\"legacyMapKey\":\"" + MAP_KEY + "\",\"modeId\":\"" + MODE_ID
            + "\",\"worldId\":\"" + WORLD + "\"}";

    /** 合法候选类一：{@code ABSENT} 加 {@code REJECT} 的逐字完整 canonical 文本。 */
    private static final String ABSENT_REJECT_CANONICAL = "{\"cfgState\":\"ABSENT\",\"idMap\":["
            + DEFINITION_ENTRY_TEXT + "],\"legacyCfg\":null,\"legacyJson\":" + LEGACY_JSON_TEXT
            + ",\"missingCfgPolicy\":\"REJECT\",\"sourceHashes\":{\"cfg\":null,\"json\":"
            + JSON_HASH_TEXT + "},\"sourceScope\":" + SCOPE_TEXT + "}";

    /** 合法候选类二：只有 {@code missingCfgPolicy} 换成 {@code PROVIDER_DEFAULTS}。 */
    private static final String ABSENT_PROVIDER_CANONICAL = "{\"cfgState\":\"ABSENT\",\"idMap\":["
            + DEFINITION_ENTRY_TEXT + "],\"legacyCfg\":null,\"legacyJson\":" + LEGACY_JSON_TEXT
            + ",\"missingCfgPolicy\":\"PROVIDER_DEFAULTS\",\"sourceHashes\":{\"cfg\":null,\"json\":"
            + JSON_HASH_TEXT + "},\"sourceScope\":" + SCOPE_TEXT + "}";

    /** 合法候选类三：{@code PRESENT} 且 legacy cfg 是真实对象。 */
    private static final String PRESENT_CANONICAL = "{\"cfgState\":\"PRESENT\",\"idMap\":["
            + DEFINITION_ENTRY_TEXT + "],\"legacyCfg\":" + LEGACY_CFG_TEXT + ",\"legacyJson\":"
            + LEGACY_JSON_TEXT + ",\"missingCfgPolicy\":null,\"sourceHashes\":{\"cfg\":{\"canonicalSha256\":\""
            + CANON_CFG_DIGEST + "\",\"rawSha256\":\"" + RAW_CFG_DIGEST
            + "\",\"sourceKind\":\"JDBC_UTF8_TEXT\"},\"json\":" + JSON_HASH_TEXT + "},\"sourceScope\":"
            + SCOPE_TEXT + "}";

    /** 合法候选类四：{@code PRESENT} 且 legacy cfg 是 JSON {@code null}，仍是 PRESENT。 */
    private static final String PRESENT_NULL_CFG_CANONICAL = "{\"cfgState\":\"PRESENT\",\"idMap\":["
            + DEFINITION_ENTRY_TEXT + "],\"legacyCfg\":null,\"legacyJson\":" + LEGACY_JSON_TEXT
            + ",\"missingCfgPolicy\":null,\"sourceHashes\":{\"cfg\":{\"canonicalSha256\":\""
            + CANON_CFG_DIGEST + "\",\"rawSha256\":\"" + RAW_CFG_DIGEST
            + "\",\"sourceKind\":\"JDBC_UTF8_TEXT\"},\"json\":" + JSON_HASH_TEXT + "},\"sourceScope\":"
            + SCOPE_TEXT + "}";

    // ==================================================================
    // oracle 段落：只用冻结 canonical 成员文本 + JDK 编码器/摘要
    // 本段落绝不调用被测生产方法；harness 对此静态断言。
    // ==================================================================

    private static String repeat(char character) {
        final char[] text = new char[64];
        java.util.Arrays.fill(text, character);
        return new String(text);
    }

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    private static UUID derived(long group, int index) {
        return new UUID(group, index);
    }

    private static String ascii(int length) {
        final char[] text = new char[length];
        java.util.Arrays.fill(text, 'a');
        return new String(text);
    }

    /**
     * 完全独立的数字 canonical oracle：只用 JDK {@link java.math.BigDecimal} 的符号判定与去尾零规则，
     * 与冻结 P02 同规则但走另一条代码路径。用来手写夹具里的数字 token，而不是把生产写出的文本读回来。
     */
    private static String canonicalNumber(String raw) {
        final java.math.BigDecimal value = new java.math.BigDecimal(raw);
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toString();
    }

    /** 一个成员：解码后的 key、它在 canonical 文本里的逐字拼写、以及逐字 value 文本。 */
    private record Member(String key, String escapedKey, String value) {}

    /** 一个已知成员：key 与它的 value piece（value 可以是嵌套对象）。 */
    private record Known(String key, Piece value) {}

    private static Member member(String key, String value) {
        return new Member(key, escape(key), value);
    }

    /** 用一个非 P02 的转义拼写表达同一个解码 key，用于证明碰撞判定发生在解码之后。 */
    private static Member spelled(String decodedKey, String escapedKeySpelling, String value) {
        return new Member(decodedKey, escapedKeySpelling, value);
    }

    private static Known known(String key, String value) {
        return new Known(key, literal(value));
    }

    private static Known known(String key, Piece value) {
        return new Known(key, value);
    }

    /** P02 转义：引号、反斜杠、五个短转义、其余 C0 用小写十六进制转义，其余逐字。 */
    private static String escape(String text) {
        final StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (character < 0x20) {
                        out.append("\\u00");
                        out.append(HEX[character >> 4]);
                        out.append(HEX[character & 0x0F]);
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String quoted(String text) {
        return "\"" + escape(text) + "\"";
    }

    private static Piece literal(String text) {
        return new Literal(text);
    }

    private static Piece joined(List<Piece> pieces) {
        return new Joined(pieces);
    }

    /** 一个固定的 canonical 成员容器文本：成员按解码 key 用 {@code String.compareTo} 排序。 */
    private static String membersObject(List<Member> members) {
        final List<Member> sorted = new ArrayList<>(members);
        sorted.sort((left, right) -> left.key().compareTo(right.key()));
        final StringBuilder out = new StringBuilder("{");
        String previous = null;
        for (int index = 0; index < sorted.size(); index++) {
            final Member item = sorted.get(index);
            assertFalse(item.key().equals(previous), "fixture member keys must be distinct");
            previous = item.key();
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(item.escapedKey()).append("\":").append(item.value());
        }
        return out.append('}').toString();
    }

    /** 解析固定夹具文本，并断言它已经是 canonical 文本（否则 oracle 不成立）。 */
    private static ExactJson.FrozenJson frozen(String canonicalText) {
        final ExactJson.FrozenJson value = ExactJson.parse(canonicalText);
        assertEquals(canonicalText, value.canonical(), "fixture text must already be canonical");
        return value;
    }

    private static ExactJson.FrozenJson members(Member... items) {
        return frozen(membersObject(List.of(items)));
    }

    /** 每个 owner 容器按 Frozen 引用登记它自己的 unknown 成员，避免 owner 对齐漂移。 */
    private static final class Owners {

        private final IdentityHashMap<ExactJson.FrozenJson, List<Member>> index = new IdentityHashMap<>();

        ExactJson.FrozenJson object(Member... items) {
            final ExactJson.FrozenJson value = members(items);
            this.index.put(value, List.of(items));
            return value;
        }

        ExactJson.FrozenJson document(String canonicalText) {
            return frozen(canonicalText);
        }

        List<Member> of(ExactJson.FrozenJson value) {
            final List<Member> found = this.index.get(value);
            assertNotNull(found, "every owner container must be registered in the fixture index");
            return found;
        }
    }

    /** 预期 canonical 文本的 piece 树：大输入只按分片喂给摘要器，不拼成整份字符串。 */
    private interface Piece {}

    private record Literal(String text) implements Piece {}

    private record Joined(List<Piece> pieces) implements Piece {}

    private static void appendText(Piece piece, StringBuilder out) {
        if (piece instanceof Literal literal) {
            out.append(literal.text());
        } else {
            for (Piece child : ((Joined) piece).pieces()) {
                appendText(child, out);
            }
        }
    }

    private static String text(Piece piece) {
        final StringBuilder out = new StringBuilder();
        appendText(piece, out);
        return out.toString();
    }

    /**
     * 独立的第二种成员顺序实现：完全用 {@link TreeMap} 的键序决定 owner 内的成员顺序。它与 piece 树
     * 的合并循环彼此独立，两者必须产生逐字相同的文本；重复的键在这里也会被立刻发现。
     */
    private static String treeMapOwner(List<Known> knowns, List<Member> unknowns) {
        final TreeMap<String, Member> all = new TreeMap<>();
        for (Known item : knowns) {
            assertNull(all.put(item.key(), new Member(item.key(), item.key(), text(item.value()))));
        }
        for (Member item : unknowns) {
            assertNull(all.put(item.key(), item));
        }
        final StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String key : all.keySet()) {
            final Member item = all.get(key);
            if (!first) {
                out.append(',');
            }
            out.append('"').append(item.escapedKey()).append("\":").append(item.value());
            first = false;
        }
        return out.append('}').toString();
    }

    /** 一次遍历同时得到 UTF-8 字节数和小写十六进制摘要，分片不切断代理对。 */
    private static final class Oracle {

        private final MessageDigest digest = sha256();
        private long bytes;

        void feed(Piece piece) {
            if (piece instanceof Literal literal) {
                final String value = literal.text();
                int start = 0;
                while (start < value.length()) {
                    int end = Math.min(value.length(), start + LANE_CHUNK_CHARS);
                    if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) {
                        end++;
                    }
                    final byte[] encoded = value.substring(start, end).getBytes(StandardCharsets.UTF_8);
                    this.bytes += encoded.length;
                    this.digest.update(encoded);
                    start = end;
                }
            } else {
                for (Piece child : ((Joined) piece).pieces()) {
                    feed(child);
                }
            }
        }

        String hexDigest() {
            return hex(this.digest.digest());
        }

        long utf8Bytes() {
            return this.bytes;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new AssertionError(missing);
        }
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            out.append(HEX[(item >> 4) & 0x0F]);
            out.append(HEX[item & 0x0F]);
        }
        return out.toString();
    }

    /** 独立摘要 oracle：把逐字文本按不超过 64 KiB 的分片喂给 JDK 摘要器。 */
    private static String jdkSha256(String canonicalText) {
        final MessageDigest digest = sha256();
        int start = 0;
        while (start < canonicalText.length()) {
            int end = Math.min(canonicalText.length(), start + LANE_CHUNK_CHARS);
            if (end < canonicalText.length() && Character.isHighSurrogate(canonicalText.charAt(end - 1))) {
                end++;
            }
            digest.update(canonicalText.substring(start, end).getBytes(StandardCharsets.UTF_8));
            start = end;
        }
        return hex(digest.digest());
    }

    private static long jdkUtf8Bytes(String canonicalText) {
        long total = 0;
        int start = 0;
        while (start < canonicalText.length()) {
            int end = Math.min(canonicalText.length(), start + LANE_CHUNK_CHARS);
            if (end < canonicalText.length() && Character.isHighSurrogate(canonicalText.charAt(end - 1))) {
                end++;
            }
            total += canonicalText.substring(start, end).getBytes(StandardCharsets.UTF_8).length;
            start = end;
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 预期 canonical 文本 oracle：按 owner 交错已知成员与解码后排序的 unknown 成员
    // ------------------------------------------------------------------

    private static void addUnknown(List<Piece> pieces, Member item, boolean first) {
        if (!first) {
            pieces.add(literal(","));
        }
        pieces.add(literal("\"" + item.escapedKey() + "\":"));
        pieces.add(literal(item.value()));
    }

    private static Piece mergedOwner(List<Known> knowns, List<Member> unknowns) {
        final List<Member> sorted = new ArrayList<>(unknowns);
        sorted.sort((left, right) -> left.key().compareTo(right.key()));
        final List<Piece> pieces = new ArrayList<>();
        pieces.add(literal("{"));
        int cursor = 0;
        boolean first = true;
        for (Known item : knowns) {
            while (cursor < sorted.size() && sorted.get(cursor).key().compareTo(item.key()) < 0) {
                addUnknown(pieces, sorted.get(cursor++), first);
                first = false;
            }
            if (!first) {
                pieces.add(literal(","));
            }
            pieces.add(literal("\"" + item.key() + "\":"));
            pieces.add(item.value());
            first = false;
        }
        while (cursor < sorted.size()) {
            addUnknown(pieces, sorted.get(cursor++), first);
            first = false;
        }
        pieces.add(literal("}"));
        return joined(pieces);
    }

    private static Piece expectedEntry(PersistenceValues.IdMapEntry value, Owners owners) {
        final List<Piece> segments = new ArrayList<>();
        segments.add(literal("["));
        for (int index = 0; index < value.sourceSegments().size(); index++) {
            if (index > 0) {
                segments.add(literal(","));
            }
            segments.add(literal(quoted(value.sourceSegments().get(index))));
        }
        segments.add(literal("]"));
        final List<Known> knowns = List.of(
                known("id", quoted(value.id().toString())),
                known("kind", quoted(value.kind().name())),
                known("sourceDocument", quoted(value.sourceDocument().name())),
                known("sourceSegments", joined(segments)));
        return mergedOwner(knowns, owners.of(value.members()));
    }

    private static Piece expectedIdMap(List<PersistenceValues.IdMapEntry> entries, Owners owners) {
        final List<Piece> pieces = new ArrayList<>();
        pieces.add(literal("["));
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) {
                pieces.add(literal(","));
            }
            pieces.add(expectedEntry(entries.get(index), owners));
        }
        pieces.add(literal("]"));
        return joined(pieces);
    }

    private static Piece expectedSourceHash(PersistenceValues.SourceHash value, Owners owners) {
        final List<Known> knowns = List.of(
                known("canonicalSha256", quoted(value.canonicalSha256())),
                known("rawSha256", quoted(value.rawSha256())),
                known("sourceKind", quoted(value.sourceKind().name())));
        return mergedOwner(knowns, owners.of(value.members()));
    }

    private static Piece expectedSourceHashes(PersistenceValues.SourceHashes value, Owners owners) {
        final PersistenceValues.SourceHash cfg = value.cfg().orElse(null);
        final List<Known> knowns = List.of(
                known("cfg", cfg == null ? literal(NULL_JSON) : expectedSourceHash(cfg, owners)),
                known("json", expectedSourceHash(value.json(), owners)));
        return mergedOwner(knowns, owners.of(value.members()));
    }

    private static Piece expectedSourceScope(PersistenceValues.SourceScope value, Owners owners) {
        final List<Known> knowns = List.of(
                known("legacyMapKey", quoted(value.legacyMapKey())),
                known("modeId", quoted(value.modeId())),
                known("worldId", quoted(value.worldId().wire())));
        return mergedOwner(knowns, owners.of(value.members()));
    }

    /**
     * 完整的 migration 预期：legacy 两个成员用夹具作者独立手写的 canonical 文本，绝不读回生产的
     * {@code canonical()}；DTO 只用来决定结构（cfg 是否存在、条目、scope、hashes）。
     */
    private static Piece expectedMigration(PersistenceValues.MigrationRecord value, Owners owners,
            String legacyJsonCanonical, String legacyCfgCanonical) {
        final boolean presentCfg = value.legacyCfg().isPresent();
        final List<Known> knowns = List.of(
                known("cfgState", quoted(value.cfgState().name())),
                known("idMap", expectedIdMap(value.idMap(), owners)),
                known("legacyCfg", presentCfg ? literal(legacyCfgCanonical) : literal(NULL_JSON)),
                known("legacyJson", literal(legacyJsonCanonical)),
                known("missingCfgPolicy", value.missingCfgPolicy().isPresent()
                        ? literal(quoted(value.missingCfgPolicy().get().name()))
                        : literal(NULL_JSON)),
                known("sourceHashes", expectedSourceHashes(value.sourceHashes(), owners)),
                known("sourceScope", expectedSourceScope(value.sourceScope(), owners)));
        return mergedOwner(knowns, owners.of(value.members()));
    }

    /** 一个完整的预期：候选本身、它的预期 canonical piece 树、摘要与 UTF-8 字节数。 */
    private static final class Fixture {

        private final PersistenceValues.MigrationRecord value;
        private final Piece canonical;
        private final String digest;
        private final long utf8Bytes;

        Fixture(PersistenceValues.MigrationRecord value, Piece canonical) {
            this.value = value;
            this.canonical = canonical;
            final Oracle oracle = new Oracle();
            oracle.feed(canonical);
            this.digest = oracle.hexDigest();
            this.utf8Bytes = oracle.utf8Bytes();
        }

        PersistenceValues.MigrationRecord value() {
            return this.value;
        }

        Piece canonical() {
            return this.canonical;
        }

        String digest() {
            return this.digest;
        }

        long utf8Bytes() {
            return this.utf8Bytes;
        }
    }

    private static Fixture fixture(PersistenceValues.MigrationRecord value, Owners owners,
            String legacyJsonCanonical, String legacyCfgCanonical) {
        return new Fixture(value, expectedMigration(value, owners, legacyJsonCanonical, legacyCfgCanonical));
    }


    /** Captures the original graph, including identities and frozen text, before production runs. */
    private static final class InputSnapshot {
        private final List<Runnable> checks = new ArrayList<>();

        private <T> T keep(java.util.function.Supplier<T> getter) {
            final T reference = getter.get();
            checks.add(() -> assertSame(reference, getter.get()));
            return reference;
        }

        private void frozen(java.util.function.Supplier<ExactJson.FrozenJson> getter) {
            final ExactJson.FrozenJson value = keep(getter);
            final String text = value.canonical();
            checks.add(() -> assertEquals(text, value.canonical()));
        }

        private void hash(PersistenceValues.SourceHash hash) {
            keep(hash::sourceKind); keep(hash::rawSha256); keep(hash::canonicalSha256);
            frozen(hash::members);
        }

        InputSnapshot(PersistenceValues.MigrationRecord value) {
            final PersistenceValues.SourceScope scope = keep(value::sourceScope);
            final Ids.WorldId world = keep(scope::worldId);
            keep(world::value); keep(scope::modeId); keep(scope::legacyMapKey); frozen(scope::members);
            final PersistenceValues.SourceHashes hashes = keep(value::sourceHashes);
            hash(keep(hashes::json));
            keep(hashes::cfg).ifPresent(this::hash); frozen(hashes::members);
            keep(value::cfgState); keep(value::missingCfgPolicy); frozen(value::members);
            frozen(value::legacyJson);
            keep(value::legacyCfg).ifPresent(cfg -> frozen(() -> cfg));
            final List<PersistenceValues.IdMapEntry> entries = keep(value::idMap);
            final List<PersistenceValues.IdMapEntry> original = new ArrayList<>(entries);
            checks.add(() -> assertEquals(original, entries));
            for (int index = 0; index < entries.size(); index++) {
                final int position = index;
                final PersistenceValues.IdMapEntry entry = keep(() -> entries.get(position));
                keep(entry::id); keep(entry::kind); keep(entry::sourceDocument); frozen(entry::members);
                final List<String> segments = keep(entry::sourceSegments);
                final List<String> copy = new ArrayList<>(segments);
                checks.add(() -> assertEquals(copy, segments));
                for (int part = 0; part < segments.size(); part++) {
                    final int at = part;
                    keep(() -> segments.get(at));
                }
            }
        }

        void assertUnchanged() { checks.forEach(Runnable::run); }
    }

    // ------------------------------------------------------------------
    // 候选构造器：默认是合法候选类一，测试只改一个变量
    // ------------------------------------------------------------------

    private static final class Candidate {

        final Owners owners = new Owners();
        PersistenceValues.CfgState cfgState = PersistenceValues.CfgState.ABSENT;
        PersistenceValues.MissingCfgPolicy policy = PersistenceValues.MissingCfgPolicy.REJECT;
        String modeId = MODE_ID;
        String legacyMapKey = MAP_KEY;
        String world = WORLD;
        PersistenceValues.SourceKind jsonKind = PersistenceValues.SourceKind.FILE_BYTES;
        String jsonRaw = RAW_JSON_DIGEST;
        String jsonCanonical = CANON_JSON_DIGEST;
        PersistenceValues.SourceKind cfgKind = PersistenceValues.SourceKind.JDBC_UTF8_TEXT;
        String cfgRaw = RAW_CFG_DIGEST;
        String cfgCanonical = CANON_CFG_DIGEST;
        String legacyJsonText = LEGACY_JSON_TEXT;
        String legacyCfgText = LEGACY_CFG_TEXT;
        String legacyJsonExpected = LEGACY_JSON_CANONICAL;
        String legacyCfgExpected = LEGACY_CFG_CANONICAL;
        boolean legacyCfgNull;
        final List<PersistenceValues.IdMapEntry> entries = new ArrayList<>();
        ExactJson.FrozenJson migrationMembers;
        ExactJson.FrozenJson scopeMembers;
        ExactJson.FrozenJson hashesMembers;
        ExactJson.FrozenJson jsonHashMembers;
        ExactJson.FrozenJson cfgHashMembers;

        Candidate() {
            this.migrationMembers = this.owners.object();
            this.scopeMembers = this.owners.object();
            this.hashesMembers = this.owners.object();
            this.jsonHashMembers = this.owners.object();
            this.cfgHashMembers = this.owners.object();
        }

        /** 标准候选：一个 DEFINITION 条目，三个 owner 容器都是空对象。 */
        static Candidate standard() {
            final Candidate candidate = new Candidate();
            candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.DEFINITION,
                    List.of(), DEFINITION);
            return candidate;
        }

        Candidate present() {
            this.cfgState = PersistenceValues.CfgState.PRESENT;
            return this;
        }

        Candidate providerDefaults() {
            this.policy = PersistenceValues.MissingCfgPolicy.PROVIDER_DEFAULTS;
            return this;
        }

        Candidate entry(PersistenceValues.SourceDocument document, PersistenceValues.IdKind kind,
                List<String> segments, String id) {
            return entryWith(this.owners.object(), document, kind, segments, id);
        }

        Candidate entryWith(ExactJson.FrozenJson members, PersistenceValues.SourceDocument document,
                PersistenceValues.IdKind kind, List<String> segments, String id) {
            this.entries.add(new PersistenceValues.IdMapEntry(document, kind, segments, uuid(id), members));
            return this;
        }

        ExactJson.FrozenJson shared(Member... items) {
            return this.owners.object(items);
        }

        Candidate migration(Member... items) {
            this.migrationMembers = this.owners.object(items);
            return this;
        }

        Candidate scope(Member... items) {
            this.scopeMembers = this.owners.object(items);
            return this;
        }

        Candidate hashes(Member... items) {
            this.hashesMembers = this.owners.object(items);
            return this;
        }

        Candidate jsonHash(Member... items) {
            this.jsonHashMembers = this.owners.object(items);
            return this;
        }

        Candidate cfgHash(Member... items) {
            this.cfgHashMembers = this.owners.object(items);
            return this;
        }

        PersistenceValues.MigrationRecord build() {
            final PersistenceValues.SourceHash json = new PersistenceValues.SourceHash(this.jsonKind,
                    this.jsonRaw, this.jsonCanonical, this.jsonHashMembers);
            final PersistenceValues.SourceHash cfg = new PersistenceValues.SourceHash(this.cfgKind,
                    this.cfgRaw, this.cfgCanonical, this.cfgHashMembers);
            final boolean present = this.cfgState == PersistenceValues.CfgState.PRESENT;
            final PersistenceValues.SourceHashes hashes = new PersistenceValues.SourceHashes(json,
                    present ? Optional.of(cfg) : Optional.empty(), this.hashesMembers);
            final PersistenceValues.SourceScope scope = new PersistenceValues.SourceScope(
                    new Ids.WorldId(uuid(this.world)), this.modeId, this.legacyMapKey, this.scopeMembers);
            final Optional<ExactJson.FrozenJson> legacyCfg = present
                    ? Optional.of(this.legacyCfgNull ? ExactJson.parse(NULL_JSON)
                            : ExactJson.parse(this.legacyCfgText))
                    : Optional.empty();
            final Optional<PersistenceValues.MissingCfgPolicy> missing = present ? Optional.empty()
                    : Optional.of(this.policy);
            return new PersistenceValues.MigrationRecord(scope, hashes, this.cfgState, missing,
                    List.copyOf(this.entries), ExactJson.parse(this.legacyJsonText), legacyCfg,
                    this.migrationMembers);
        }

        Fixture fixture() {
            final PersistenceValues.MigrationRecord value = build();
            assertEquals(this.legacyJsonExpected, value.legacyJson().canonical(), "independent legacy JSON token");
            final String cfgToken = this.legacyCfgNull ? NULL_JSON : this.legacyCfgExpected;
            value.legacyCfg().ifPresent(cfg -> assertEquals(cfgToken, cfg.canonical(), "independent legacy CFG token"));
            return MigrationFingerprintContractTest.fixture(value, this.owners, this.legacyJsonExpected, cfgToken);
        }
    }

    // ------------------------------------------------------------------
    // production 段落
    // 以下每个用例都直接调用 RequestFingerprint.migrationSha256
    // ------------------------------------------------------------------

    private static String repeated(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    /** 一个由 index 唯一决定的合法候选，覆盖四种 cfg 组合与两种 unknown 成员位置。 */
    private static Candidate generated(int index) {
        final Candidate candidate = new Candidate();
        candidate.legacyJsonText = "{\"case\":" + index + "}";
        candidate.legacyJsonExpected = "{\"case\":" + canonicalNumber(Integer.toString(index)) + "}";
        switch (index % 4) {
            case 0 -> {
            }
            case 1 -> candidate.providerDefaults();
            case 2 -> candidate.present();
            default -> {
                candidate.present();
                candidate.legacyCfgNull = true;
            }
        }
        if (candidate.cfgState == PersistenceValues.CfgState.PRESENT) {
            candidate.cfgRaw = String.format("%064x", index + 10000);
            candidate.cfgCanonical = String.format("%064x", index + 20000);
            candidate.legacyCfgText = "{\"cfg\":" + index + "}";
            candidate.legacyCfgExpected = "{\"cfg\":" + canonicalNumber(Integer.toString(index)) + "}";
        }
        candidate.jsonRaw = String.format("%064x", index);
        candidate.jsonCanonical = String.format("%064x", index + 5000);
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.DEFINITION,
                List.of(), DEFINITION);
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of(String.format("team-%04d", index)), derived(0x6000L, index).toString());
        if (index % 2 == 0) {
            candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.SPAWN,
                    List.of(String.format("spawn-%04d", index)),
                    derived(0x7000L, index).toString());
        }
        candidate.migration(member("zz", canonicalNumber(Integer.toString(index))));
        if (index % 3 == 0) {
            candidate.scope(member("aa", canonicalNumber(Integer.toString(index))));
        }
        return candidate;
    }

    private static EnvelopeException collision(Candidate candidate) {
        final PersistenceValues.MigrationRecord value = candidate.build();
        return assertThrows(EnvelopeException.class,
                () -> RequestFingerprint.migrationSha256(value));
    }

    /** 完整断言一次保留键碰撞：code、owner、relativePath、cause 与逐字消息。 */
    private static void assertCollision(Candidate candidate, String ownerPointer, String key) {
        final EnvelopeException thrown = collision(candidate);
        assertEquals("RESERVED_COLLISION", thrown.code());
        assertEquals(ownerPointer, thrown.owner().pointer());
        assertEquals("/" + key, thrown.relativePath().pointer());
        assertEquals("reserved member key \"" + key + "\" in " + ownerPointer
                + " collides with the migration fingerprint schema", thrown.getMessage());
        assertNull(thrown.getCause());
        System.out.println("[collision] owner=" + ownerPointer + " key=" + key
                + " relativePath=" + thrown.relativePath().pointer()
                + " code=" + thrown.code() + " cause=null");
    }

    @Test
    void nullValueIsRejectedBeforeAnyDigest() {
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RequestFingerprint.migrationSha256(null));
        assertEquals("value must not be null", thrown.getMessage());
        assertNull(thrown.getCause());
        assertFalse(thrown instanceof EnvelopeException);
    }

    @Test
    void absentRejectCandidateMatchesTheHandWrittenCanonical() {
        final Candidate candidate = Candidate.standard();
        final Fixture fixture = candidate.fixture();
        assertEquals(ABSENT_REJECT_CANONICAL, text(fixture.canonical()));
        assertEquals(jdkSha256(ABSENT_REJECT_CANONICAL), fixture.digest());
        assertEquals(jdkUtf8Bytes(ABSENT_REJECT_CANONICAL), fixture.utf8Bytes());
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
    }

    @Test
    void absentProviderDefaultsCandidateMatchesTheHandWrittenCanonical() {
        final Candidate candidate = Candidate.standard().providerDefaults();
        final Fixture fixture = candidate.fixture();
        assertEquals(ABSENT_PROVIDER_CANONICAL, text(fixture.canonical()));
        assertEquals(jdkSha256(ABSENT_PROVIDER_CANONICAL), fixture.digest());
        assertEquals(jdkUtf8Bytes(ABSENT_PROVIDER_CANONICAL), fixture.utf8Bytes());
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        assertNotEquals(ABSENT_REJECT_CANONICAL, ABSENT_PROVIDER_CANONICAL);
    }

    @Test
    void presentCandidateMatchesTheHandWrittenCanonical() {
        final Candidate candidate = Candidate.standard().present();
        final Fixture fixture = candidate.fixture();
        assertEquals(PRESENT_CANONICAL, text(fixture.canonical()));
        assertEquals(jdkSha256(PRESENT_CANONICAL), fixture.digest());
        assertEquals(jdkUtf8Bytes(PRESENT_CANONICAL), fixture.utf8Bytes());
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
    }

    @Test
    void presentStateSurvivesANullLegacyCfg() {
        final Candidate candidate = Candidate.standard().present();
        candidate.legacyCfgNull = true;
        final Fixture fixture = candidate.fixture();
        final String expected = text(fixture.canonical());
        assertEquals(PRESENT_NULL_CFG_CANONICAL, expected);
        assertTrue(expected.contains("\"cfgState\":\"PRESENT\""));
        assertTrue(expected.contains("\"legacyCfg\":null"));
        assertTrue(expected.contains("\"cfg\":{\"canonicalSha256\":\"" + CANON_CFG_DIGEST + "\""));
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        assertNotEquals(PRESENT_CANONICAL, PRESENT_NULL_CFG_CANONICAL);
    }

    @Test
    void legacyMapKeyVariantsAreEscapedWithP02Rules() {
        assertEquals("\"ctrl\\u0001\"", quoted("ctrl\u0001"));
        assertEquals("\"del\u007f\"", quoted("del\u007f"));
        assertEquals("\"pair\uD83D\uDE00\"", quoted("pair\uD83D\uDE00"));
        assertEquals("\"back\\\\slash\"", quoted("back\\slash"));
        final String[] variants = {"", "caf\u00e9", "cafe\u0301", "\"quoted\"", "back\\slash",
                "slash/key", "del\u007f", "ctrl\u0001", "pair\uD83D\uDE00"};
        final Set<String> digests = new HashSet<>();
        for (String variant : variants) {
            final Candidate candidate = Candidate.standard();
            candidate.legacyMapKey = variant;
            final Fixture fixture = candidate.fixture();
            assertTrue(text(fixture.canonical()).contains("\"legacyMapKey\":" + quoted(variant)),
                    "P02 escaping of " + variant);
            assertEquals(jdkSha256(text(fixture.canonical())), fixture.digest());
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
            assertTrue(digests.add(fixture.digest()),
                    "distinct map keys must not share a digest: " + variant);
        }
        assertEquals(variants.length, digests.size());
        final Candidate surrogate = Candidate.standard();
        surrogate.legacyMapKey = "bad\uD800";
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                surrogate::build);
        assertTrue(thrown.getMessage().contains("unpaired"), thrown.getMessage());
    }

    @Test
    void maxLengthLegacyMapKeyIsAcceptedAndHashed() {

        for (String longKey : List.of(ascii(65536), repeated('\u00e9', 32768))) {
            final Candidate accepted = Candidate.standard();
            accepted.legacyMapKey = longKey;
            final Fixture fixture = accepted.fixture();
            assertEquals(65536L, jdkUtf8Bytes(quoted(longKey)) - 2L);
            assertEquals(longKey, fixture.value().sourceScope().legacyMapKey());
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
            final Candidate rejected = Candidate.standard();
            rejected.legacyMapKey = longKey + "x";
            final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, rejected::build);
            assertTrue(thrown.getMessage().contains("65536"), thrown.getMessage());
        }
    }

    @Test
    void segmentPartitionsAndSegmentLimitsAreEnforced() {
        final Candidate joined = Candidate.standard();
        joined.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("a/b"), TEAM_ID);
        final Candidate split = Candidate.standard();
        split.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("a", "b"), TEAM_ID);
        final Fixture splitFixture = split.fixture();
        assertEquals(splitFixture.digest(), RequestFingerprint.migrationSha256(splitFixture.value()));
        final Fixture joinedFixture = joined.fixture();
        assertEquals(joinedFixture.digest(), RequestFingerprint.migrationSha256(joinedFixture.value()));
        assertNotEquals(joinedFixture.digest(), splitFixture.digest());
        assertTrue(text(joinedFixture.canonical()).contains("\"sourceSegments\":[\"a/b\"]"));
        assertTrue(text(splitFixture.canonical()).contains("\"sourceSegments\":[\"a\",\"b\"]"));

        final String segment = repeated('s', 1024);
        final Candidate full = Candidate.standard();
        full.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                java.util.Collections.nCopies(64, segment), TEAM_ID);
        final Fixture fullFixture = full.fixture();
        assertEquals(fullFixture.digest(), RequestFingerprint.migrationSha256(fullFixture.value()));

        final IllegalArgumentException tooManySegments = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = Candidate.standard();
                    final List<String> segments = new ArrayList<>();
                    for (int index = 0; index < 65; index++) {
                        segments.add("s" + index);
                    }
                    over.entry(PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.TEAM, segments, TEAM_ID);
                    over.build();
                });
        assertEquals("sourceSegments must hold at most 64 entries, reported 65",
                tooManySegments.getMessage());

        final IllegalArgumentException tooLongSegment = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = Candidate.standard();
                    over.entry(PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.TEAM, List.of(segment + "s"), TEAM_ID);
                    over.build();
                });
        assertTrue(tooLongSegment.getMessage().contains("1024"), tooLongSegment.getMessage());
    }

    @Test
    void sourceKindAndDigestsAreWrittenVerbatim() {
        for (boolean cfg : List.of(false, true)) {
            final Fixture baseline = Candidate.standard().present().fixture();
            assertEquals(baseline.digest(), RequestFingerprint.migrationSha256(baseline.value()));
            for (int field = 0; field < 3; field++) {
                final Candidate changed = Candidate.standard().present();
                if (cfg) {
                    if (field == 0) changed.cfgKind = PersistenceValues.SourceKind.FILE_BYTES;
                    if (field == 1) changed.cfgRaw = repeat('5');
                    if (field == 2) changed.cfgCanonical = repeat('6');
                } else {
                    if (field == 0) changed.jsonKind = PersistenceValues.SourceKind.JDBC_UTF8_TEXT;
                    if (field == 1) changed.jsonRaw = repeat('5');
                    if (field == 2) changed.jsonCanonical = repeat('6');
                }
                final Fixture fixture = changed.fixture();
                assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
                assertNotEquals(baseline.digest(), fixture.digest());
            }
        }
        final Candidate swapped = Candidate.standard();
        swapped.jsonRaw = CANON_JSON_DIGEST;
        swapped.jsonCanonical = RAW_JSON_DIGEST;
        final Fixture fixture = swapped.fixture();
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        final Fixture baseline = Candidate.standard().fixture();
        assertEquals(baseline.digest(), RequestFingerprint.migrationSha256(baseline.value()));
        assertNotEquals(baseline.digest(), fixture.digest());
    }

    @Test
    void modeIdAndWorldIdAreWrittenVerbatim() {
        final Candidate base = Candidate.standard();
        final Candidate otherMode = Candidate.standard();
        otherMode.modeId = "other:path";
        final Candidate otherWorld = Candidate.standard();
        otherWorld.world = OTHER_WORLD;
        final Fixture baseFixture = base.fixture();
        final String baseText = text(baseFixture.canonical());
        assertTrue(baseText.contains("\"modeId\":\"fpsmatch:arena\""));
        assertTrue(baseText.contains("\"worldId\":\"" + WORLD + "\""));
        assertEquals(WORLD, baseFixture.value().sourceScope().worldId().wire());
        assertEquals(baseFixture.digest(), RequestFingerprint.migrationSha256(baseFixture.value()));
        final Fixture otherModeFixture = otherMode.fixture();
        assertEquals(otherModeFixture.digest(),
                RequestFingerprint.migrationSha256(otherModeFixture.value()));
        final Fixture otherWorldFixture = otherWorld.fixture();
        assertEquals(otherWorldFixture.digest(),
                RequestFingerprint.migrationSha256(otherWorldFixture.value()));
        final Set<String> digests = new HashSet<>();
        digests.add(baseFixture.digest());
        digests.add(otherModeFixture.digest());
        digests.add(otherWorldFixture.digest());
        assertEquals(3, digests.size());
    }

    @Test
    void cfgCombinationsArePairwiseDistinct() {
        final Candidate absentReject = Candidate.standard();
        final Candidate absentProvider = Candidate.standard().providerDefaults();
        final Candidate present = Candidate.standard().present();
        final Candidate presentNull = Candidate.standard().present();
        presentNull.legacyCfgNull = true;
        final List<Candidate> candidates = List.of(absentReject, absentProvider, present, presentNull);
        final List<String> literals = List.of(ABSENT_REJECT_CANONICAL, ABSENT_PROVIDER_CANONICAL,
                PRESENT_CANONICAL, PRESENT_NULL_CFG_CANONICAL);
        final Set<String> digests = new HashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            final Fixture fixture = candidates.get(index).fixture();
            assertEquals(literals.get(index), text(fixture.canonical()));
            assertEquals(jdkSha256(literals.get(index)), fixture.digest());
            assertEquals(fixture.digest(),
                    RequestFingerprint.migrationSha256(fixture.value()));
            digests.add(fixture.digest());
        }
        assertEquals(4, digests.size());
    }

    @Test
    void nonDefinitionEntriesWriteKindDocumentSegmentsAndId() {

        final Set<String> digests = new HashSet<>();
        for (int field = -1; field < 4; field++) {
            final Candidate candidate = Candidate.standard().present();
            candidate.entry(field == 1 ? PersistenceValues.SourceDocument.CFG : PersistenceValues.SourceDocument.JSON,
                    field == 0 ? PersistenceValues.IdKind.REGION : PersistenceValues.IdKind.TEAM,
                    field == 2 ? List.of("teams", "2") : List.of("teams", "1"),
                    field == 3 ? OTHER_DEFINITION : TEAM_ID);
            candidate.entry(PersistenceValues.SourceDocument.CFG, PersistenceValues.IdKind.SPAWN,
                    List.of("spawns", "2"), SPAWN_ID);
            final Fixture fixture = candidate.fixture();
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
            assertTrue(digests.add(fixture.digest()), "each single field must affect the full wire");
        }
        final Candidate otherDefinition = new Candidate().present();
        otherDefinition.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.DEFINITION,
                List.of(), OTHER_DEFINITION);
        final Fixture fixture = otherDefinition.fixture();
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        final Fixture baseline = Candidate.standard().present().fixture();
        assertEquals(baseline.digest(), RequestFingerprint.migrationSha256(baseline.value()));
        assertNotEquals(baseline.digest(), fixture.digest());
    }

    @Test
    void definitionAnchorViolationsAreRejectedByTheDto() {
        final IllegalArgumentException fromCfg = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate candidate = new Candidate();
            candidate.entry(PersistenceValues.SourceDocument.CFG,
                    PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
        });
        assertEquals("a DEFINITION entry must come from the JSON document", fromCfg.getMessage());
        final IllegalArgumentException withSegments = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate candidate = new Candidate();
                    candidate.entry(PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.DEFINITION, List.of("x"), DEFINITION);
                });
        assertEquals("a DEFINITION entry must carry no source segment", withSegments.getMessage());
        final IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate candidate = Candidate.standard();
            candidate.entry(PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.DEFINITION, List.of(), OTHER_DEFINITION);
            candidate.build();
        });
        assertEquals("a migration candidate accepts at most one DEFINITION entry",
                twice.getMessage());
        final IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate candidate = new Candidate();
            candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                    List.of("teams"), TEAM_ID);
            candidate.build();
        });
        assertEquals("a migration candidate needs exactly one DEFINITION entry, found 0",
                none.getMessage());
    }

    @Test
    void reorderingTheIdMapChangesTheDigest() {
        final Candidate first = Candidate.standard();
        first.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        first.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.SPAWN,
                List.of("spawns", "1"), SPAWN_ID);
        final Candidate second = Candidate.standard();
        second.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.SPAWN,
                List.of("spawns", "1"), SPAWN_ID);
        second.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        final Fixture firstFixture = first.fixture();
        assertEquals(firstFixture.digest(), RequestFingerprint.migrationSha256(firstFixture.value()));
        final Fixture secondFixture = second.fixture();
        assertEquals(secondFixture.digest(), RequestFingerprint.migrationSha256(secondFixture.value()));
        assertNotEquals(firstFixture.digest(), secondFixture.digest());
    }

    @Test
    void entryMembersStayWithTheirOwnEntry() {
        final Candidate onFirst = new Candidate();
        onFirst.entryWith(onFirst.shared(member("tag", "1")), PersistenceValues.SourceDocument.JSON,
                PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
        onFirst.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        final Candidate onSecond = new Candidate();
        onSecond.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.DEFINITION,
                List.of(), DEFINITION);
        onSecond.entryWith(onSecond.shared(member("tag", "1")), PersistenceValues.SourceDocument.JSON,
                PersistenceValues.IdKind.TEAM, List.of("teams", "1"), TEAM_ID);
        final Fixture onFirstFixture = onFirst.fixture();
        final String firstText = text(onFirstFixture.canonical());
        final Fixture onSecondFixture = onSecond.fixture();
        final String secondText = text(onSecondFixture.canonical());
        assertTrue(firstText.contains("{\"id\":\"" + DEFINITION + "\",\"kind\":\"DEFINITION\","
                + "\"sourceDocument\":\"JSON\",\"sourceSegments\":[],\"tag\":1}"));
        assertTrue(secondText.contains("{\"id\":\"" + TEAM_ID + "\",\"kind\":\"TEAM\","
                + "\"sourceDocument\":\"JSON\",\"sourceSegments\":[\"teams\",\"1\"],\"tag\":1}"));
        assertEquals(onFirstFixture.digest(), RequestFingerprint.migrationSha256(onFirstFixture.value()));
        assertEquals(onSecondFixture.digest(), RequestFingerprint.migrationSha256(onSecondFixture.value()));
        assertNotEquals(onFirstFixture.digest(), onSecondFixture.digest());
    }

    @Test
    void sameUuidInDifferentTargetDomainsStaysLegal() {
        final Candidate candidate = Candidate.standard().present();
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), DEFINITION);
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.SPAWN,
                List.of("spawns", "1"), DEFINITION);
        final Fixture candidateFixture = candidate.fixture();
        assertEquals(candidateFixture.digest(), RequestFingerprint.migrationSha256(candidateFixture.value()));

        final IllegalArgumentException teamTwice = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate twice = Candidate.standard();
            twice.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                    List.of("teams", "1"), TEAM_ID);
            twice.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                    List.of("teams", "2"), TEAM_ID);
            twice.build();
        });
        assertEquals("two TEAM entries must not share the same target UUID", teamTwice.getMessage());
        final IllegalArgumentException shared = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate mixed = Candidate.standard();
            mixed.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.SPAWN,
                    List.of("spawns", "1"), SPAWN_ID);
            mixed.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.REGION,
                    List.of("regions", "1"), SPAWN_ID);
            mixed.build();
        });
        assertEquals("SPAWN and REGION entries must not share the same target UUID",
                shared.getMessage());
    }

    @Test
    void fullSizeIdMapCandidateIsHashed() {
        final Candidate candidate = new Candidate();
        final ExactJson.FrozenJson shared = candidate.shared(member("tag", "1"));
        candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
        for (int index = 0; index < MAX_TEAM_TARGETS; index++) {
            candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.TEAM, List.of("t" + index),
                    derived(0x1000L, index).toString());
        }
        for (int index = 0; index < MAX_SPAWN_TARGETS; index++) {
            candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.SPAWN, List.of("s" + index),
                    derived(0x2000L, index).toString());
        }
        for (int index = 0; index < 1024; index++) {
            candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.REGION, List.of("r" + index),
                    derived(0x3000L, index).toString());
        }
        final Fixture candidateFixture = candidate.fixture();
        final PersistenceValues.MigrationRecord value = candidateFixture.value();
        assertEquals(MAX_ID_MAP_ENTRIES, value.idMap().size());
        assertEquals(candidateFixture.digest(), RequestFingerprint.migrationSha256(value));
    }

    @Test
    void quotasAreClosedAndOneMoreEntryIsRejected() {
        final Candidate teams = new Candidate();
        final ExactJson.FrozenJson shared = teams.shared();
        teams.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
        for (int index = 0; index < MAX_TEAM_TARGETS; index++) {
            teams.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.TEAM, List.of("t" + index),
                    derived(0x1000L, index).toString());
        }
        final Fixture teamsFixture = teams.fixture();
        assertEquals(teamsFixture.digest(), RequestFingerprint.migrationSha256(teamsFixture.value()));

        final IllegalArgumentException tooManyTeams = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = new Candidate();
                    final ExactJson.FrozenJson members = over.shared();
                    over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
                    for (int index = 0; index <= MAX_TEAM_TARGETS; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.TEAM, List.of("t" + index),
                                derived(0x1000L, index).toString());
                    }
                    over.build();
                });
        assertEquals("a migration candidate accepts at most 64 TEAM entries",
                tooManyTeams.getMessage());

        final IllegalArgumentException tooManySpawns = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = new Candidate();
                    final ExactJson.FrozenJson members = over.shared();
                    over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
                    for (int index = 0; index <= MAX_SPAWN_TARGETS; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.SPAWN, List.of("s" + index),
                                derived(0x2000L, index).toString());
                    }
                    over.build();
                });
        assertEquals("a migration candidate accepts at most 16384 SPAWN entries",
                tooManySpawns.getMessage());

        final IllegalArgumentException tooManyRegions = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = new Candidate();
                    final ExactJson.FrozenJson members = over.shared();
                    over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
                    for (int index = 0; index <= 1024; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.REGION, List.of("r" + index),
                                derived(0x3000L, index).toString());
                    }
                    over.build();
                });
        assertEquals("a migration candidate accepts at most 1024 REGION entries",
                tooManyRegions.getMessage());

        final IllegalArgumentException tooManyEntries = assertThrows(IllegalArgumentException.class,
                () -> {
                    final Candidate over = new Candidate();
                    final ExactJson.FrozenJson members = over.shared();
                    over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                            PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
                    for (int index = 0; index < MAX_TEAM_TARGETS; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.TEAM, List.of("t" + index),
                                derived(0x1000L, index).toString());
                    }
                    for (int index = 0; index < MAX_SPAWN_TARGETS; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.SPAWN, List.of("s" + index),
                                derived(0x2000L, index).toString());
                    }
                    for (int index = 0; index <= 1024; index++) {
                        over.entryWith(members, PersistenceValues.SourceDocument.JSON,
                                PersistenceValues.IdKind.REGION, List.of("r" + index),
                                derived(0x3000L, index).toString());
                    }
                    over.build();
                });
        assertEquals("idMap must hold at most 17473 entries, reported 17474",
                tooManyEntries.getMessage());
    }

    @Test
    void migrationReservedKeysCollide() {
        for (String key : RESERVED_MIGRATION) {
            final Candidate candidate = Candidate.standard();
            candidate.migration(member(key, "1"));
            assertCollision(candidate, "/migration", key);
        }
    }

    @Test
    void sourceScopeReservedKeysCollide() {
        for (String key : RESERVED_SCOPE) {
            final Candidate candidate = Candidate.standard();
            candidate.scope(member(key, "1"));
            assertCollision(candidate, "/migration/sourceScope", key);
        }
    }

    @Test
    void sourceHashesReservedKeysCollide() {
        for (String key : RESERVED_HASHES) {
            final Candidate candidate = Candidate.standard();
            candidate.hashes(member(key, "1"));
            assertCollision(candidate, "/migration/sourceHashes", key);
        }
    }

    @Test
    void sourceHashReservedKeysCollide() {
        for (String key : RESERVED_HASH) {
            final Candidate json = Candidate.standard();
            json.jsonHash(member(key, "1"));
            assertCollision(json, "/migration/sourceHashes/json", key);
            final Candidate cfg = Candidate.standard().present();
            cfg.cfgHash(member(key, "1"));
            assertCollision(cfg, "/migration/sourceHashes/cfg", key);
        }
    }

    @Test
    void idMapEntryReservedKeysCollide() {
        for (String key : RESERVED_ENTRY) {
            final Candidate candidate = Candidate.standard();
            candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                    List.of("teams", "1"), TEAM_ID);
            candidate.entryWith(candidate.shared(member(key, "1")),
                    PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                    List.of("teams", "2"), SPAWN_ID);
            assertCollision(candidate, "/migration/idMap/2", key);
        }
    }

    @Test
    void collisionDiagnosticsCarryOwnerAndRelativePath() {
        final Candidate candidate = Candidate.standard().present();
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        candidate.entry(PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "2"), SPAWN_ID);
        candidate.entryWith(candidate.shared(member("sourceSegments", "[]")),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "3"), REGION_ID);
        candidate.entryWith(candidate.shared(member("kind", "1")),
                PersistenceValues.SourceDocument.CFG, PersistenceValues.IdKind.TEAM,
                List.of("teams", "4"), OTHER_DEFINITION);
        final EnvelopeException thrown = collision(candidate);
        assertEquals("RESERVED_COLLISION", thrown.code());
        assertEquals("/migration/idMap/3", thrown.owner().pointer());
        assertEquals("/sourceSegments", thrown.relativePath().pointer());
        assertEquals("reserved member key \"sourceSegments\" in /migration/idMap/3"
                + " collides with the migration fingerprint schema", thrown.getMessage());
        assertNull(thrown.getCause());
        System.out.println("[collision] owner=" + thrown.owner().pointer() + " key=sourceSegments"
                + " relativePath=" + thrown.relativePath().pointer()
                + " code=" + thrown.code() + " cause=null");
    }

    @Test
    void escapedSpellingsCollideAfterDecoding() {
        assertEquals("{\"cfgState\":1}", decodeFixture("{\"\\u0063fgState\":1}"));
        assertEquals("{\"SourceScope\":1}", decodeFixture("{\"\\u0053ourceScope\":1}"));
        assertEquals("{\"a/b\":1}", decodeFixture("{\"a\\/b\":1}"));
        final Candidate escaped = Candidate.standard();
        escaped.migrationMembers = ExactJson.parse("{\"\\u0063fgState\":1}");
        assertCollision(escaped, "/migration", "cfgState");
        final Candidate escapedScope = Candidate.standard();
        escapedScope.scopeMembers = ExactJson.parse("{\"\\u006cegacyMapKey\":1}");
        assertCollision(escapedScope, "/migration/sourceScope", "legacyMapKey");
        final Candidate escapedEntry = Candidate.standard();
        escapedEntry.entryWith(ExactJson.parse("{\"\\u0069d\":1}"),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        assertCollision(escapedEntry, "/migration/idMap/1", "id");
        final Candidate escapedHashes = Candidate.standard().present();
        escapedHashes.hashesMembers = ExactJson.parse("{\"\\u0063fg\":1}");
        assertCollision(escapedHashes, "/migration/sourceHashes", "cfg");
        final Candidate escapedJsonHash = Candidate.standard();
        escapedJsonHash.jsonHashMembers = ExactJson.parse("{\"\\u0072awSha256\":1}");
        assertCollision(escapedJsonHash, "/migration/sourceHashes/json", "rawSha256");
        final Candidate escapedCfgHash = Candidate.standard().present();
        escapedCfgHash.cfgHashMembers = ExactJson.parse("{\"\\u0073ourceKind\":1}");
        assertCollision(escapedCfgHash, "/migration/sourceHashes/cfg", "sourceKind");
        final Candidate legal = Candidate.standard();
        legal.migrationMembers = ExactJson.parse("{\"\\u0074ag\":1}");
        legal.owners.index.put(legal.migrationMembers, List.of(member("tag", "1")));
        final Candidate plain = Candidate.standard();
        plain.migration(member("tag", "1"));
        final Fixture plainFixture = plain.fixture();
        assertEquals(plainFixture.digest(), RequestFingerprint.migrationSha256(plainFixture.value()));
        final Fixture legalFixture = legal.fixture();
        assertEquals(plainFixture.digest(), legalFixture.digest());
        assertEquals(legalFixture.digest(), RequestFingerprint.migrationSha256(legalFixture.value()));
    }

    @Test
    void otherOwnersNamesAndCaseVariantsStayLegal() {
        final Candidate candidate = Candidate.standard().present();
        candidate.migration(member("cfgstate", "1"), member("CFGSTATE", "2"),
                member("mode", "3"));
        candidate.scope(member("cfgState", "1"), member("sourceScope", "2"));
        candidate.hashes(member("legacyMapKey", "1"));
        candidate.jsonHash(member("cfg", "1"), member("sourceHashes", "2"));
        candidate.cfgHash(member("legacyJson", "1"));
        candidate.entryWith(candidate.shared(member("worldId", "1"), member("idMap", "2")),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        final Fixture candidateFixture = candidate.fixture();
        final String expected = text(candidateFixture.canonical());
        assertTrue(expected.contains("\"CFGSTATE\":2"));
        assertTrue(expected.contains("\"cfgstate\":1"));
        assertEquals(candidateFixture.digest(), RequestFingerprint.migrationSha256(candidateFixture.value()));
    }

    @Test
    void severalCollisionsReportTheFirstEntryIndex() {
        final Candidate candidate = Candidate.standard();
        candidate.entryWith(candidate.shared(member("id", "1")),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "1"), TEAM_ID);
        candidate.entryWith(candidate.shared(member("id", "2")),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM,
                List.of("teams", "2"), SPAWN_ID);
        final EnvelopeException thrown = collision(candidate);
        assertEquals("/migration/idMap/1", thrown.owner().pointer());
        assertEquals("reserved member key \"id\" in /migration/idMap/1"
                + " collides with the migration fingerprint schema", thrown.getMessage());
        System.out.println("[collision] owner=" + thrown.owner().pointer() + " key=id"
                + " relativePath=" + thrown.relativePath().pointer()
                + " code=" + thrown.code() + " cause=null");
    }

    @Test
    void collisionOrderFollowsTheFixedOwnerOrder() {
        final String[] pointers = {"/migration", "/migration/sourceScope", "/migration/sourceHashes",
                "/migration/sourceHashes/json", "/migration/sourceHashes/cfg", "/migration/idMap/1"};
        final String[] keys = {"legacyJson", "modeId", "cfg", "rawSha256", "sourceKind", "id"};
        for (int first = 0; first < pointers.length; first++) {
            final Candidate candidate = Candidate.standard().present();
            if (first <= 0) candidate.migration(member("legacyJson", "1"));
            if (first <= 1) candidate.scope(member("modeId", "1"));
            if (first <= 2) candidate.hashes(member("cfg", "1"));
            if (first <= 3) candidate.jsonHash(member("rawSha256", "1"));
            if (first <= 4) candidate.cfgHash(member("sourceKind", "1"));
            candidate.entryWith(candidate.shared(member("id", "1")), PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.TEAM, List.of("teams", "1"), TEAM_ID);
            assertCollision(candidate, pointers[first], keys[first]);
        }
        assertCollision(Candidate.standard().migration(member("legacyJson", "1"), member("cfgState", "2")),
                "/migration", "cfgState");
        for (int owner = 1; owner < 6; owner++) {
            final Candidate candidate = Candidate.standard().present();
            final String key;
            switch (owner) {
                case 1 -> { candidate.scope(member("worldId", "1"), member("legacyMapKey", "2")); key = "legacyMapKey"; }
                case 2 -> { candidate.hashes(member("json", "1"), member("cfg", "2")); key = "cfg"; }
                case 3 -> { candidate.jsonHash(member("sourceKind", "1"), member("canonicalSha256", "2")); key = "canonicalSha256"; }
                case 4 -> { candidate.cfgHash(member("sourceKind", "1"), member("canonicalSha256", "2")); key = "canonicalSha256"; }
                default -> {
                    candidate.entryWith(candidate.shared(member("kind", "1"), member("id", "2")),
                            PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.TEAM, List.of("t"), TEAM_ID);
                    key = "id";
                }
            }
            assertCollision(candidate, pointers[owner], key);
        }
    }

    @Test
    void unknownMembersMergeIntoEveryOwner() {
        final Candidate candidate = new Candidate();
        candidate.migration(member("", "1"), member("a\u0001b", "2"), member("zz", NULL_JSON));
        candidate.scope(member("mode", "true"), member("", "4"));
        candidate.hashes(member("c", "[1,2]"));
        candidate.jsonHash(member("d", "{\"k\":1}"));
        candidate.entryWith(candidate.shared(member("tag", "9")),
                PersistenceValues.SourceDocument.JSON, PersistenceValues.IdKind.DEFINITION,
                List.of(), DEFINITION);
        final String jsonHash = "{\"canonicalSha256\":\"" + CANON_JSON_DIGEST
                + "\",\"d\":{\"k\":1},\"rawSha256\":\"" + RAW_JSON_DIGEST
                + "\",\"sourceKind\":\"FILE_BYTES\"}";
        final String hashes = "{\"c\":[1,2],\"cfg\":null,\"json\":" + jsonHash + "}";
        final String scope = "{\"\":4,\"legacyMapKey\":\"" + MAP_KEY + "\",\"mode\":true,\"modeId\":\""
                + MODE_ID + "\",\"worldId\":\"" + WORLD + "\"}";
        final String expected = "{\"\":1,\"a\\u0001b\":2,\"cfgState\":\"ABSENT\",\"idMap\":[{"
                + "\"id\":\"" + DEFINITION + "\",\"kind\":\"DEFINITION\",\"sourceDocument\":\"JSON\","
                + "\"sourceSegments\":[],\"tag\":9}],\"legacyCfg\":null,"
                + "\"legacyJson\":{\"legacy\":true},\"missingCfgPolicy\":\"REJECT\","
                + "\"sourceHashes\":" + hashes + ",\"sourceScope\":" + scope + ",\"zz\":null}";
        final Fixture fixture = candidate.fixture();
        assertEquals(expected, text(fixture.canonical()));
        assertEquals(jdkSha256(expected), fixture.digest());
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
    }

    @Test
    void unknownKeysWithControlCharsAndSupplementaryPairsStayExact() {
        final Member[] shapes = {
                member("", "null"), member("c\u0000\"\\", "[true,null]"),
                member("é😀", "{\"a\":1,\"z\":2}"),
                member("precise", "12345678901234567890123456789012345678901234567891"),
                member("exponent", "1E+999"), member("zero", "0")};
        for (int owner = 0; owner < 6; owner++) {
            final Candidate varied = Candidate.standard().present();
            switch (owner) {
                case 0 -> varied.migration(shapes);
                case 1 -> varied.scope(shapes);
                case 2 -> varied.hashes(shapes);
                case 3 -> varied.jsonHash(shapes);
                case 4 -> varied.cfgHash(shapes);
                default -> varied.entryWith(varied.shared(shapes), PersistenceValues.SourceDocument.JSON,
                        PersistenceValues.IdKind.TEAM, List.of("teams", "1"), TEAM_ID);
            }
            final Fixture fixture = varied.fixture();
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        }
        // The encoded token puts a surrogate pair across the 65536-character oracle chunk boundary.
        final String boundaryToken = quoted(repeated('\n', 32767) + "😀");
        assertTrue(Character.isHighSurrogate(boundaryToken.charAt(65535)));
        final Candidate boundary = Candidate.standard().migration(member("boundary", boundaryToken));
        final Fixture boundaryFixture = boundary.fixture();
        final String boundaryWire = text(boundaryFixture.canonical());
        assertEquals(hex(sha256().digest(boundaryWire.getBytes(StandardCharsets.UTF_8))), boundaryFixture.digest());
        assertEquals(hex(sha256().digest(boundaryToken.getBytes(StandardCharsets.UTF_8))), jdkSha256(boundaryToken));
        assertEquals(boundaryToken.getBytes(StandardCharsets.UTF_8).length, jdkUtf8Bytes(boundaryToken));
        assertEquals(boundaryFixture.digest(), RequestFingerprint.migrationSha256(boundaryFixture.value()));
        final Candidate candidate = Candidate.standard();
        candidate.migration(member("k\u0001", "1"), member("k\uD83D\uDE00", "2"),
                member("k\u007f", "3"), member("k\u00e9", "4"));
        candidate.jsonHash(member("ctrl\u0001del\u007f", "1"), member("emoji\uD83D\uDE00", "2"));
        final Fixture candidateFixture = candidate.fixture();
        final String expected = text(candidateFixture.canonical());
        assertTrue(expected.contains("\"k\\u0001\":1"));
        assertTrue(expected.contains("\"k\uD83D\uDE00\":2"));
        assertTrue(expected.contains("\"k\u007f\":3"));
        assertTrue(expected.contains("\"k\u00e9\":4"));
        assertTrue(expected.contains("\"ctrl\\u0001del\u007f\":1"));
        assertTrue(expected.contains("\"emoji\uD83D\uDE00\":2"));
        assertEquals(candidateFixture.digest(), RequestFingerprint.migrationSha256(candidateFixture.value()));

        final PersistenceValues.SourceHash json = candidateFixture.value().sourceHashes().json();
        final List<Known> knowns = List.of(
                known("canonicalSha256", quoted(json.canonicalSha256())),
                known("rawSha256", quoted(json.rawSha256())),
                known("sourceKind", quoted(json.sourceKind().name())));
        assertEquals(treeMapOwner(knowns, candidate.owners.of(json.members())),
                text(expectedSourceHash(json, candidate.owners)));
    }

    @Test
    void oneThousandPrebuiltCandidatesAreStableAndDistinct() {

        final List<Fixture> fixtures = new ArrayList<>(GENERATED_CASES);
        final List<InputSnapshot> snapshots = new ArrayList<>(GENERATED_CASES);
        final Set<String> wires = new HashSet<>();
        final Set<String> digests = new HashSet<>();
        // Phase 1: all inputs and independent oracles exist before any production call.
        for (int index = 0; index < GENERATED_CASES; index++) {
            final Fixture fixture = generated(index).fixture();
            fixtures.add(fixture);
            snapshots.add(new InputSnapshot(fixture.value()));
            assertTrue(wires.add(text(fixture.canonical())), "distinct complete wire " + index);
            assertTrue(digests.add(fixture.digest()), "distinct digest " + index);
        }
        for (int round = 0; round < 2; round++) {
            for (int index = 0; index < GENERATED_CASES; index++) {
                final Fixture fixture = fixtures.get(index);
                assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
                snapshots.get(index).assertUnchanged();
            }
        }
        final List<Integer> order = new ArrayList<>();
        for (int index = 0; index < GENERATED_CASES; index++) order.add(index);
        java.util.Collections.shuffle(order, new Random(SEED));
        for (int index : order) {
            final Fixture fixture = fixtures.get(index);
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
            snapshots.get(index).assertUnchanged();
        }
        assertEquals(GENERATED_CASES, wires.size());
        assertEquals(GENERATED_CASES, digests.size());
    }

    @Test
    void digestAfterThousandCandidatesStaysInvariant() {

        final Fixture before = generated(4242).fixture();
        final InputSnapshot snapshot = new InputSnapshot(before.value());
        assertEquals(before.digest(), RequestFingerprint.migrationSha256(before.value()));
        for (int index = 0; index < GENERATED_CASES; index++) {
            final Fixture fixture = generated(index).fixture();
            assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        }
        assertEquals(before.digest(), RequestFingerprint.migrationSha256(before.value()));
        snapshot.assertUnchanged();
    }

    @Test
    void repeatedAndConcurrentCallsAreStable() throws Exception {

        final List<Fixture> fixtures = List.of(generated(7).fixture(), generated(8).fixture());
        final List<InputSnapshot> snapshots = fixtures.stream().map(f -> new InputSnapshot(f.value())).toList();
        assertNotEquals(fixtures.get(0).digest(), fixtures.get(1).digest());
        for (int index = 0; index < REPEATS; index++) {
            assertEquals(fixtures.get(0).digest(), RequestFingerprint.migrationSha256(fixtures.get(0).value()));
        }
        final ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            final CountDownLatch start = new CountDownLatch(1);
            final List<Future<Integer>> futures = new ArrayList<>();
            for (Fixture fixture : fixtures) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < REPEATS; round++) {
                        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
                    }
                    return REPEATS;
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) assertEquals(REPEATS, future.get(30, TimeUnit.SECONDS).intValue());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30L, TimeUnit.SECONDS));
        }
        snapshots.forEach(InputSnapshot::assertUnchanged);
    }

    @Test
    @Tag("large-heap")
    void taggedLargeLegacyPairAndSharedMembersMatchOracle() {
        final String legacyJsonText = exactSizeLegacyDocument(LARGE_LEGACY_BYTES, 'j');
        final String legacyCfgText = exactSizeLegacyDocument(LARGE_LEGACY_BYTES, 'c');
        assertEquals(LARGE_LEGACY_BYTES, jdkUtf8Bytes(legacyJsonText));
        assertEquals(LARGE_LEGACY_BYTES, jdkUtf8Bytes(legacyCfgText));
        assertNotEquals(legacyJsonText, legacyCfgText);
        final String memberValue = "\"" + ascii(LARGE_UNKNOWN_VALUE_CHARS) + "\"";
        final List<Member> members = new ArrayList<>(LARGE_UNKNOWN_MEMBERS);
        for (int index = 0; index < LARGE_UNKNOWN_MEMBERS; index++) {
            members.add(member(String.format("z%04d", index), memberValue));
        }
        final String sharedText = membersObject(members);
        assertTrue(sharedText.length() <= ExactJson.MAX_UTF8_BYTES,
                "one member container must stay inside a document");
        final Candidate candidate = Candidate.standard().present();
        candidate.legacyJsonText = legacyJsonText;
        candidate.legacyJsonExpected = legacyJsonText;
        candidate.legacyCfgText = legacyCfgText;
        candidate.legacyCfgExpected = legacyCfgText;
        final ExactJson.FrozenJson shared = candidate.shared(members.toArray(new Member[0]));
        candidate.migrationMembers = shared;
        candidate.scopeMembers = shared;
        final Fixture fixture = candidate.fixture();
        final PersistenceValues.MigrationRecord record = fixture.value();
        assertSame(record.members(), record.sourceScope().members());
        assertTrue(fixture.utf8Bytes() > 16L * MEBI, "observed " + fixture.utf8Bytes());
        assertTrue(fixture.utf8Bytes() <= SINK_BUDGET_BYTES, "observed " + fixture.utf8Bytes());
        final long observed = 2L * LARGE_LEGACY_BYTES + 2L * sharedText.length();
        assertTrue(fixture.utf8Bytes() > observed, "observed " + fixture.utf8Bytes());
        assertTrue(fixture.utf8Bytes() < observed + 4096L, "observed " + fixture.utf8Bytes());
        assertEquals(2, countLiteralsOfLength(fixture.canonical(), LARGE_LEGACY_BYTES),
                "both legacy occurrences must be fed to the oracle as one complete 8388608 byte fragment");
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(record));
        System.out.println("[large-pin] legacy_json_bytes=" + jdkUtf8Bytes(legacyJsonText)
                + " legacy_cfg_bytes=" + jdkUtf8Bytes(legacyCfgText)
                + " shared_members_bytes=" + sharedText.length()
                + " migration_utf8_bytes=" + fixture.utf8Bytes()
                + " sink_budget_bytes=" + SINK_BUDGET_BYTES
                + " complete_legacy_fragments=" + countLiteralsOfLength(fixture.canonical(), LARGE_LEGACY_BYTES)
                + " above_16mib=" + (fixture.utf8Bytes() > 16L * MEBI)
                + " within_budget=" + (fixture.utf8Bytes() <= SINK_BUDGET_BYTES));
    }

    @Test
    void taggedLargeBudgetExceededIsRejectedThenNormalCallSucceeds() {
        final String filler = ascii(SMALL_SHARED_VALUE_CHARS);
        final List<Member> members = new ArrayList<>(SMALL_SHARED_MEMBERS);
        for (int index = 0; index < SMALL_SHARED_MEMBERS; index++) {
            members.add(member(String.format("k%02d", index), "\"" + filler + "\""));
        }
        final Candidate candidate = new Candidate();
        final ExactJson.FrozenJson shared = candidate.shared(members.toArray(new Member[0]));
        candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                PersistenceValues.IdKind.DEFINITION, List.of(), DEFINITION);
        for (int index = 1; index < SMALL_SHARED_ENTRIES; index++) {
            candidate.entryWith(shared, PersistenceValues.SourceDocument.JSON,
                    PersistenceValues.IdKind.TEAM, List.of("member-" + index),
                    derived(0x5000L, index).toString());
        }
        final Fixture fixture = candidate.fixture();
        final PersistenceValues.MigrationRecord record = fixture.value();
        assertTrue(fixture.utf8Bytes() > SINK_BUDGET_BYTES, "observed " + fixture.utf8Bytes());
        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> RequestFingerprint.migrationSha256(record));
        assertFalse(thrown instanceof EnvelopeException);
        final Candidate normal = generated(77);
        final Fixture normalFixture = normal.fixture();
        assertEquals(normalFixture.digest(), RequestFingerprint.migrationSha256(normalFixture.value()));
        final IllegalArgumentException again = assertThrows(IllegalArgumentException.class,
                () -> RequestFingerprint.migrationSha256(record));
        assertEquals(thrown.getMessage(), again.getMessage());
        System.out.println("[large-pin] entries=" + SMALL_SHARED_ENTRIES
                + " shared_members=" + SMALL_SHARED_MEMBERS
                + " value_chars=" + SMALL_SHARED_VALUE_CHARS
                + " migration_utf8_bytes=" + fixture.utf8Bytes()
                + " sink_budget_bytes=" + SINK_BUDGET_BYTES
                + " budget_exceeded=" + (fixture.utf8Bytes() > SINK_BUDGET_BYTES)
                + " rejected=" + thrown.getClass().getName()
                + " rejection_message=\"" + thrown.getMessage() + "\""
                + " oom=false normal_call_after_denial=" + normalFixture.digest());
    }

    @Test
    void legacyInteriorsAreNeverWalkedForCollisions() {

        final String json = "{\"cfgState\":1,\"idMap\":2,\"legacyCfg\":3,\"legacyJson\":4,"
                + "\"missingCfgPolicy\":5,\"sourceHashes\":6,\"sourceScope\":7}";
        final String cfg = "{\"cfg\":1,\"json\":2,\"canonicalSha256\":3,\"rawSha256\":4,"
                + "\"sourceKind\":5,\"id\":6,\"kind\":7}";
        final String cfgExpected = "{\"canonicalSha256\":3,\"cfg\":1,\"id\":6,\"json\":2,"
                + "\"kind\":7,\"rawSha256\":4,\"sourceKind\":5}";
        final List<String[]> shapes = List.of(new String[]{json, json}, new String[]{cfg, cfgExpected},
                new String[]{"null", "null"}, new String[]{"true", "true"},
                new String[]{"false", "false"},
                new String[]{"\"quote\\\"\\\\😀\"", "\"quote\\\"\\\\😀\""},
                new String[]{"[3, -0.0, {\"z\":2,\"a\":1}]", "[3,0,{\"a\":1,\"z\":2}]"},
                new String[]{"12345678901234567890123456789012345678901234567891", "12345678901234567890123456789012345678901234567891"},
                new String[]{"1e999", "1E+999"}, new String[]{"-0.00e100", "0"});
        for (String[] shape : shapes) {
            for (boolean cfgOwner : List.of(false, true)) {
                final Candidate candidate = Candidate.standard().present();
                if (cfgOwner) {
                    candidate.legacyCfgText = shape[0]; candidate.legacyCfgExpected = shape[1];
                } else {
                    candidate.legacyJsonText = shape[0]; candidate.legacyJsonExpected = shape[1];
                }
                final Fixture fixture = candidate.fixture();
                assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
            }
        }
        assertCollision(candidateWith(member("cfgState", "1")), "/migration", "cfgState");
    }

    @Test
    void sameFrozenValueAtTwoOwnersIsWrittenPerOccurrence() {
        final Candidate candidate = Candidate.standard();
        final ExactJson.FrozenJson shared = candidate.shared(member("tag", "1"));
        candidate.migrationMembers = shared;
        candidate.scopeMembers = shared;
        final Fixture candidateFixture = candidate.fixture();
        final PersistenceValues.MigrationRecord value = candidateFixture.value();
        assertSame(value.members(), value.sourceScope().members());
        final String expected = text(candidateFixture.canonical());
        assertEquals(2, expected.split("\"tag\":1", -1).length - 1);
        assertTrue(expected.contains("\"sourceScope\":{\"legacyMapKey\":\"" + MAP_KEY
                + "\",\"modeId\":\"" + MODE_ID + "\",\"tag\":1,\"worldId\":\"" + WORLD + "\"}"));
        assertTrue(expected.startsWith("{\"cfgState\":\"ABSENT\",\"idMap\":["));
        assertEquals(candidateFixture.digest(), RequestFingerprint.migrationSha256(value));
    }

    @Test
    void absentCfgRejectsCfgSourcedEntryByDto() {
        final IllegalArgumentException sourced = assertThrows(IllegalArgumentException.class, () -> {
            final Candidate candidate = Candidate.standard();
            candidate.entry(PersistenceValues.SourceDocument.CFG, PersistenceValues.IdKind.SPAWN,
                    List.of("spawns", "1"), SPAWN_ID);
            candidate.build();
        });
        assertEquals("an ABSENT cfg state forbids a CFG sourced entry", sourced.getMessage());
        final Candidate present = Candidate.standard().present();
        present.entry(PersistenceValues.SourceDocument.CFG, PersistenceValues.IdKind.SPAWN,
                List.of("spawns", "1"), SPAWN_ID);
        final Fixture fixture = present.fixture();
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
        assertTrue(text(fixture.canonical()).contains("\"sourceDocument\":\"CFG\""));
    }

    @Test
    void everyKnownFieldIsCoveredInOneLegalOracle() {
        final Candidate candidate = Candidate.standard();
        final Fixture fixture = candidate.fixture();
        final String expected = text(fixture.canonical());
        for (String key : RESERVED_MIGRATION) {
            assertTrue(expected.contains("\"" + key + "\":"), "missing known member " + key);
        }
        for (String key : RESERVED_SCOPE) {
            assertTrue(expected.contains("\"" + key + "\":"), "missing known member " + key);
        }
        for (String key : RESERVED_HASHES) {
            assertTrue(expected.contains("\"" + key + "\":"), "missing known member " + key);
        }
        for (String key : RESERVED_HASH) {
            assertTrue(expected.contains("\"" + key + "\":"), "missing known member " + key);
        }
        for (String key : RESERVED_ENTRY) {
            assertTrue(expected.contains("\"" + key + "\":"), "missing known member " + key);
        }
        assertTrue(expected.contains("\"id\":\"" + DEFINITION + "\""));
        assertTrue(expected.contains("\"worldId\":\"" + WORLD + "\""));
        assertTrue(expected.contains("\"modeId\":\"" + MODE_ID + "\""));
        assertTrue(expected.contains("\"legacyMapKey\":\"" + MAP_KEY + "\""));
        assertEquals(jdkSha256(expected), fixture.digest());
        assertEquals(jdkUtf8Bytes(expected), fixture.utf8Bytes());
        assertEquals(fixture.digest(), RequestFingerprint.migrationSha256(fixture.value()));
    }

    @Test
    void digestIsLowercaseHex64() {
        for (int index = 0; index < 8; index++) {
            final Candidate candidate = generated(index);
            final Fixture candidateFixture = candidate.fixture();
            final String digest = RequestFingerprint.migrationSha256(candidateFixture.value());
            assertEquals(64, digest.length());
            assertEquals(candidateFixture.digest(), digest);
            for (int position = 0; position < digest.length(); position++) {
                final char character = digest.charAt(position);
                assertTrue((character >= '0' && character <= '9')
                        || (character >= 'a' && character <= 'f'), "not lowercase hex: " + digest);
            }
            assertFalse(digest.equals(digest.toUpperCase()));
        }
    }

    /**
     * 恰好 {@code totalBytes} 个 ASCII 字节的 legacy 文档。历史 legacy 的 key 不进入 migration schema，
     * 所以这里自造 key；每个 string 值都不超过冻结 P02 的 65536 字节上限，最后一个成员按剩余字节精确补齐，
     * 因此长度可逐字节核对，且不需要任何超限的单字符串。
     */
    private static String exactSizeLegacyDocument(int totalBytes, char fill) {
        final String chunk = repeated(fill, LEGACY_CHUNK_CHARS);
        final StringBuilder out = new StringBuilder(totalBytes + 16);
        out.append('{');
        int index = 0;
        while (true) {
            final String key = legacyKey(index);
            final long projected = out.length() + (index == 0 ? 0L : 1L)
                    + key.length() + chunk.length() + 3L;
            if (projected > totalBytes) {
                break;
            }
            if (index > 0) {
                out.append(',');
            }
            out.append(key).append('"').append(chunk).append('"');
            index++;
        }
        final String fillerKey = legacyKey(index);
        final int filler = totalBytes - out.length() - (index == 0 ? 0 : 1) - fillerKey.length() - 3;
        if (filler < 1 || filler > LEGACY_CHUNK_CHARS) {
            throw new AssertionError("legacy fixture filler is out of budget: " + filler);
        }
        if (index > 0) {
            out.append(',');
        }
        out.append(fillerKey).append('"').append(repeated(fill, filler)).append('"');
        out.append('}');
        assertEquals(totalBytes, out.length(), "exact legacy fixture length");
        assertEquals(LARGE_CHUNK_MEMBERS, index, "exact legacy fixture member count");
        return out.toString();
    }

    private static String legacyKey(int index) {
        return "\"p" + String.format("%04d", index) + "\":";
    }

    /** 统计 piece 树里长度恰好为 {@code length} 的完整 fragment 数。 */
    private static int countLiteralsOfLength(Piece piece, int length) {
        if (piece instanceof Literal literal) {
            return literal.text().length() == length ? 1 : 0;
        }
        int total = 0;
        for (Piece child : ((Joined) piece).pieces()) {
            total += countLiteralsOfLength(child, length);
        }
        return total;
    }

    private static Candidate candidateWith(Member member) {
        final Candidate candidate = Candidate.standard();
        candidate.migration(member);
        return candidate;
    }

    private static String decodeFixture(String text) {
        return ExactJson.parse(text).canonical();
    }
}
