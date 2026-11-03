package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.ptcrys.fpsmatch.editor.common.model.DefinitionRef;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.persistence.PersistenceValues;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 正式契约测试：{@code R06.2b3} 流式 canonical 请求指纹（{@code ENVELOPE-CODEC} 第 3 节）。
 *
 * <p>本类只使用两种独立材料构造全部预期，且两种材料都先于生产调用建立：
 * 冻结 P02 已认证的 canonical 成员文本（含其解码后的 key 顺序）、JDK 的
 * {@link StandardCharsets#UTF_8} 与 {@link MessageDigest}，以及独立实现的
 * {@link BigDecimal#stripTrailingZeros()} 长整数 canonical oracle。任何已知 key 的
 * 完整 canonical 文本都在本文件内逐字拼写并与 oracle 交叉核对，不使用生产结果反推，
 * 也不把成对摘要不同当作唯一证据。</p>
 *
 * <p>本文件在“生产入口”标记之前的所有 helper 一律不调用被测生产方法，证据 harness
 * 会对该段落静态断言零生产调用。</p>
 */
class RequestFingerprintContractTest {

    // ------------------------------------------------------------------
    // 固定常量：seed、分组计数、预算与固定夹具文本
    // ------------------------------------------------------------------

    private static final long SEED = 20261115L;
    private static final int GENERATED_CASES = 1000;
    private static final int GENERATED_UNKNOWN_MAX = 4;
    private static final int REPEATS = 100;
    private static final int CONCURRENT_ROUNDS = 16;
    private static final long MEBI = 1024L * 1024L;
    private static final long SHARED_MEMBERS_BYTES = 8388608L;
    private static final int SHARED_CHUNK_BYTES = 65536;
    private static final long PAIR_ROOT_BYTES = 4L * MEBI + 4096L;
    private static final long PAIR_TARGET_BYTES = 4L * MEBI + 8192L;

    private static final String ACTOR = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ACTOR = "11111111-2222-3333-4444-555555555556";
    private static final String REQUEST = "66666666-7777-8888-9999-aaaaaaaaaaaa";
    private static final String OTHER_REQUEST = "66666666-7777-8888-9999-aaaaaaaaaaab";
    private static final String WORLD = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff";
    private static final String OTHER_WORLD = "bbbbbbbb-cccc-dddd-eeee-fffffffffff0";
    private static final String DEFINITION = "01234567-89ab-cdef-0123-456789abcdef";
    private static final String OTHER_DEFINITION = "01234567-89ab-cdef-0123-456789abcde0";
    private static final String PAYLOAD = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_PAYLOAD = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde0";
    private static final String MIGRATION = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
    private static final String OTHER_MIGRATION = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543211";
    private static final String EMPTY_OBJECT = "{}";

    private static final String[] ROOT_RESERVED = {
        "actorUuid", "expectedRevision", "migrationDigest", "operationKind", "payloadDigest",
        "protocolVersion", "requestId", "schemaVersion", "target"};
    private static final String[] TARGET_RESERVED = {"definitionId", "worldId"};
    private static final long[] PINNED_REVISIONS = {0L, 1L, 10L, 100L, 1000L, 10000L, 1230000L, 1234567L, Long.MAX_VALUE};
    private static final String[] PINNED_REVISION_CANONICAL = {"0", "1", "1E+1", "1E+2", "1E+3", "1E+4", "1.23E+6", "1234567", "9223372036854775807"};

    /** 无 unknown 成员的固定夹具的逐字完整 canonical 文本（全九 known 与 target 二字段）。 */
    private static final String PLAIN_REVISION_ZERO_CANONICAL = "{\"actorUuid\":\"" + ACTOR
            + "\",\"expectedRevision\":0,\"migrationDigest\":null,\"operationKind\":\"SAVE_MAP\""
            + ",\"payloadDigest\":\"" + PAYLOAD + "\",\"protocolVersion\":1,\"requestId\":\"" + REQUEST
            + "\",\"schemaVersion\":1,\"target\":{\"definitionId\":\"" + DEFINITION
            + "\",\"worldId\":\"" + WORLD + "\"}}";

    // ==================================================================
    // oracle 段落：只用冻结 canonical 文本 + JDK 编码器/摘要 + 独立 BigDecimal
    // 本段落绝不调用被测生产方法；harness 对此静态断言。
    // ==================================================================

    private static UUID uuid(String text) {
        return UUID.fromString(text);
    }

    private static DefinitionRef reference(String world, String definition) {
        return new DefinitionRef(new Ids.WorldId(uuid(world)), new Ids.DefinitionId(uuid(definition)));
    }

    private static PersistenceValues.OperationKind saveKind() {
        return PersistenceValues.OperationKind.SAVE_MAP;
    }

    private static PersistenceValues.OperationKind migrateKind() {
        return PersistenceValues.OperationKind.MIGRATE_MAP;
    }

    private static PersistenceValues.FingerprintInputs value(String actor, String world, String definition,
            String request, long revision, String payload, PersistenceValues.OperationKind kind,
            String migration, String rootMembers, String targetMembers) {
        return new PersistenceValues.FingerprintInputs(uuid(actor),
                new PersistenceValues.FingerprintTarget(reference(world, definition), ExactJson.parse(targetMembers)),
                new Ids.RequestId(uuid(request)), revision, payload, kind,
                migration == null ? Optional.empty() : Optional.of(migration), ExactJson.parse(rootMembers));
    }

    private static PersistenceValues.FingerprintInputs value(long revision) {
        return value(ACTOR, WORLD, DEFINITION, REQUEST, revision, PAYLOAD, saveKind(), null,
                EMPTY_OBJECT, EMPTY_OBJECT);
    }

    private static PersistenceValues.FingerprintInputs unknown(String rootMembers, String targetMembers) {
        return value(ACTOR, WORLD, DEFINITION, REQUEST, 0L, PAYLOAD, saveKind(), null,
                rootMembers, targetMembers);
    }

    private static PersistenceValues.FingerprintInputs unknown(String rootMembers, String targetMembers, long revision) {
        return value(ACTOR, WORLD, DEFINITION, REQUEST, revision, PAYLOAD, saveKind(), null,
                rootMembers, targetMembers);
    }

    private static String longCanonical(long revision) {
        return new BigDecimal(Long.toString(revision)).stripTrailingZeros().toString();
    }

    private static String jsonString(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 2);
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            final char current = text.charAt(index);
            switch (current) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (current < 0x20) {
                        out.append('\\').append('u');
                        for (int shift = 12; shift >= 0; shift -= 4) {
                            out.append(Character.forDigit((current >> shift) & 0xf, 16));
                        }
                    } else {
                        out.append(current);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static String member(String key, String valueToken) {
        return jsonString(key) + ":" + valueToken;
    }

    private static String keyToken(String prefix, int index) {
        final String digits = Integer.toString(index);
        final StringBuilder out = new StringBuilder(prefix.length() + 6);
        out.append('"').append(prefix);
        for (int pad = digits.length(); pad < 4; pad++) {
            out.append('0');
        }
        return out.append(digits).append('"').toString();
    }

    private static int scanString(String text, int start) {
        if (text.charAt(start) != '"') {
            throw new IllegalStateException("oracle scanner expected a quote at " + start);
        }
        int cursor = start + 1;
        while (cursor < text.length()) {
            final char current = text.charAt(cursor);
            if (current == '\\') {
                cursor += 2;
                continue;
            }
            if (current == '"') {
                return cursor + 1;
            }
            cursor++;
        }
        throw new IllegalStateException("oracle scanner found an unterminated string at " + start);
    }

    private static int scanValue(String text, int start) {
        final char first = text.charAt(start);
        if (first == '"') {
            return scanString(text, start);
        }
        if (first == '{' || first == '[') {
            final char close = first == '{' ? '}' : ']';
            int depth = 0;
            int cursor = start;
            while (cursor < text.length()) {
                final char current = text.charAt(cursor);
                if (current == '"') {
                    cursor = scanString(text, cursor);
                    continue;
                }
                if (current == first) {
                    depth++;
                } else if (current == close) {
                    depth--;
                    if (depth == 0) {
                        return cursor + 1;
                    }
                }
                cursor++;
            }
            throw new IllegalStateException("oracle scanner found an unterminated container at " + start);
        }
        int cursor = start;
        while (cursor < text.length()) {
            final char current = text.charAt(cursor);
            if (current == ',' || current == '}' || current == ']') {
                return cursor;
            }
            cursor++;
        }
        return cursor;
    }

    private static String unescape(String text, int from, int to) {
        final StringBuilder out = new StringBuilder(to - from);
        int cursor = from;
        while (cursor < to) {
            final char current = text.charAt(cursor);
            if (current != '\\') {
                out.append(current);
                cursor++;
                continue;
            }
            final char kind = text.charAt(cursor + 1);
            switch (kind) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append((char) Integer.parseInt(text.substring(cursor + 2, cursor + 6), 16));
                default -> throw new IllegalStateException("oracle scanner cannot decode an escape: " + kind);
            }
            cursor += kind == 'u' ? 6 : 2;
        }
        return out.toString();
    }

    /**
     * 把冻结 P02 已认证的 canonical object 文本切成“解码后 key 到该成员逐字 token”的独立有序表，
     * 供后续按 {@link String#compareTo} 自然序合并。
     *
     * <p>保留字只按**所属 object**判定：调用方必须传入该 object 自己的 known key 集合。根 owner 的
     * 9 个 known key 与 target owner 的 2 个 known key 互不构成对方的保留字，因此 {@code members} 里
     * 名为 {@code definitionId} 的成员、或 target 里名为 {@code actorUuid} 的成员都是合法未知成员，
     * 绝不能在这里直接失败；只有被判定 owner 自己的保留字出现在输入里才失败，这样 oracle 就不会为
     * 真正的碰撞输入伪造预期。</p>
     */
    private static TreeMap<String, List<String>> memberTokens(String canonicalObjectText,
                                                              Set<String> reservedKeysOfOwner) {
        if (canonicalObjectText.isEmpty() || canonicalObjectText.charAt(0) != '{') {
            throw new IllegalStateException("oracle expects canonical object text, got " + canonicalObjectText);
        }
        final TreeMap<String, List<String>> members = new TreeMap<>();
        final int length = canonicalObjectText.length();
        int cursor = 1;
        while (true) {
            if (cursor >= length) {
                throw new IllegalStateException("oracle scanner ran past the end of the object");
            }
            if (canonicalObjectText.charAt(cursor) == '}') {
                if (cursor != length - 1) {
                    throw new IllegalStateException("oracle scanner found text after the object");
                }
                return members;
            }
            final int keyStart = cursor;
            final int keyEnd = scanString(canonicalObjectText, keyStart);
            final String key = unescape(canonicalObjectText, keyStart + 1, keyEnd - 1);
            if (reservedKeysOfOwner.contains(key)) {
                throw new IllegalStateException("oracle input carries a reserved key of this owner: " + key);
            }
            if (canonicalObjectText.charAt(keyEnd) != ':') {
                throw new IllegalStateException("oracle scanner expected a colon after " + key);
            }
            final int valueEnd = scanValue(canonicalObjectText, keyEnd + 1);
            final List<String> tokens = new ArrayList<>(1);
            tokens.add(canonicalObjectText.substring(keyStart, valueEnd));
            if (members.put(key, tokens) != null) {
                throw new IllegalStateException("oracle scanner found the key twice: " + key);
            }
            if (valueEnd == length) {
                throw new IllegalStateException("oracle scanner expected the object to continue");
            }
            final char next = canonicalObjectText.charAt(valueEnd);
            if (next == ',') {
                cursor = valueEnd + 1;
                continue;
            }
            if (next == '}') {
                if (valueEnd != length - 1) {
                    throw new IllegalStateException("oracle scanner found text after the object");
                }
                return members;
            }
            throw new IllegalStateException("oracle scanner expected a comma or a brace at " + valueEnd);
        }
    }

    /** 根 owner 自己的 9 个 known key：测试端独立列出的字面量集合，不读任何生产常量或 helper。 */
    private static Set<String> rootReservedKeys() {
        return Set.of(ROOT_RESERVED);
    }

    /** target owner 自己的 2 个 known key。同名成员出现在别的 owner 上是合法未知成员。 */
    private static Set<String> targetReservedKeys() {
        return Set.of(TARGET_RESERVED);
    }

    private static List<String> objectShards(TreeMap<String, List<String>> members) {
        final List<String> shards = new ArrayList<>();
        shards.add("{");
        boolean first = true;
        for (List<String> tokens : members.values()) {
            if (!first) {
                shards.add(",");
            }
            first = false;
            shards.addAll(tokens);
        }
        shards.add("}");
        return shards;
    }

    private static List<String> expectedShards(PersistenceValues.FingerprintInputs value) {
        final TreeMap<String, List<String>> root = memberTokens(value.members().canonical(), rootReservedKeys());
        root.put("actorUuid", List.of(member("actorUuid", jsonString(value.actorUuid().toString()))));
        root.put("expectedRevision", List.of(member("expectedRevision", longCanonical(value.expectedRevision()))));
        final String migration = value.migrationDigest().orElse(null);
        root.put("migrationDigest",
                List.of(member("migrationDigest", migration == null ? "null" : jsonString(migration))));
        root.put("operationKind", List.of(member("operationKind", jsonString(value.operationKind().name()))));
        root.put("payloadDigest", List.of(member("payloadDigest", jsonString(value.payloadDigest()))));
        root.put("protocolVersion", List.of(member("protocolVersion", "1")));
        root.put("requestId", List.of(member("requestId", jsonString(value.requestId().wire()))));
        root.put("schemaVersion", List.of(member("schemaVersion", "1")));

        final TreeMap<String, List<String>> target =
                memberTokens(value.target().members().canonical(), targetReservedKeys());
        target.put("definitionId",
                List.of(member("definitionId", jsonString(value.target().target().definitionId().wire()))));
        target.put("worldId", List.of(member("worldId", jsonString(value.target().target().worldId().wire()))));
        // target 是根上的 known key：把它的 canonical 值分片整体挂上 '"target":' 前缀，
        // 首片加键、其余分片原样保留，因此 16 MiB 级输入仍然只以分片形式参与摘要。
        final List<String> targetShards = objectShards(target);
        final List<String> targetMember = new ArrayList<>(targetShards.size());
        targetMember.add(jsonString("target") + ":" + targetShards.get(0));
        targetMember.addAll(targetShards.subList(1, targetShards.size()));
        root.put("target", targetMember);

        return objectShards(root);
    }

    private static String expectedCanonicalText(PersistenceValues.FingerprintInputs value) {
        return String.join("", expectedShards(value));
    }

    private static String toHex(byte[] bytes) {
        final char[] digits = "0123456789abcdef".toCharArray();
        final char[] out = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            out[index * 2] = digits[(bytes[index] >> 4) & 0xf];
            out[index * 2 + 1] = digits[bytes[index] & 0xf];
        }
        return new String(out);
    }

    private static String digestOfShards(List<String> shards) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String shard : shards) {
            digest.update(shard.getBytes(StandardCharsets.UTF_8));
        }
        return toHex(digest.digest());
    }

    private static String expectedDigest(PersistenceValues.FingerprintInputs value) throws Exception {
        return digestOfShards(expectedShards(value));
    }

    private static String sha256Of(String text) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static long totalShardBytes(List<String> shards) {
        long total = 0L;
        for (String shard : shards) {
            total += shard.getBytes(StandardCharsets.UTF_8).length;
        }
        return total;
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int cursor = text.indexOf(needle);
        while (cursor >= 0) {
            count++;
            cursor = text.indexOf(needle, cursor + needle.length());
        }
        return count;
    }

    private static boolean isAscii(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造恰好 {@code totalBytes} 个 ASCII 字节的 members object：每个 string 值不超过冻结 P02 的
     * 65536 字节上限，最后一个成员按剩余字节精确补齐，因此长度可逐字节核对。
     */
    private static String largeMembersObject(long totalBytes, String keyPrefix) {
        final String chunk = "a".repeat(SHARED_CHUNK_BYTES);
        final StringBuilder json = new StringBuilder((int) totalBytes + 8);
        json.append('{');
        int index = 0;
        while (true) {
            final String key = keyToken(keyPrefix, index);
            final long projected = json.length() + (index == 0 ? 0L : 1L) + key.length() + chunk.length() + 3L + 1L;
            if (projected > totalBytes) {
                break;
            }
            if (index > 0) {
                json.append(',');
            }
            json.append(key).append(":\"").append(chunk).append('"');
            index++;
        }
        final String fillerKey = keyToken(keyPrefix, index);
        final long fillerValue = totalBytes - json.length() - (index == 0 ? 0L : 1L) - fillerKey.length() - 4L;
        if (fillerValue <= 0L || fillerValue > SHARED_CHUNK_BYTES) {
            throw new IllegalStateException("large fixture filler is out of budget: " + fillerValue);
        }
        if (index > 0) {
            json.append(',');
        }
        json.append(fillerKey).append(":\"").append("b".repeat((int) fillerValue)).append('"');
        json.append('}');
        if (json.length() != totalBytes) {
            throw new IllegalStateException("large fixture length is " + json.length() + " instead of " + totalBytes);
        }
        if (!isAscii(json.toString())) {
            throw new IllegalStateException("large fixture must stay ASCII only");
        }
        return json.toString();
    }

    private static long generatedRevision(Random random) {
        switch (random.nextInt(9)) {
            case 0:
                return 0L;
            case 1:
                return 10L;
            case 2:
                return 1000L;
            case 3:
                return 1230000L;
            case 4:
                return Long.MAX_VALUE;
            default:
                return random.nextLong() & Long.MAX_VALUE;
        }
    }

    private static String randomUuid(Random random) {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    private static String randomDigest(Random random) {
        final byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return toHex(bytes);
    }

    private static String generatedText(Random random) {
        final int length = random.nextInt(9);
        final StringBuilder out = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            out.append((char) ('a' + random.nextInt(26)));
        }
        return out.toString();
    }

    private static String generatedValue(Random random) {
        switch (random.nextInt(7)) {
            case 0:
                return Long.toString(random.nextLong());
            case 1:
                return "\"" + generatedText(random) + "\"";
            case 2:
                return "null";
            case 3:
                return Boolean.toString(random.nextBoolean());
            case 4:
                return "[" + random.nextInt(1000) + "," + random.nextInt(1000) + "]";
            case 5:
                return "{\"k\":" + random.nextInt(1000) + "}";
            default:
                return Integer.toString(random.nextInt(1000));
        }
    }

    private static String generatedMembers(Random random, String prefix, int caseIndex) {
        final int wanted = random.nextInt(GENERATED_UNKNOWN_MAX + 1);
        if (wanted == 0) {
            return EMPTY_OBJECT;
        }
        final Set<String> keys = new HashSet<>();
        final StringBuilder json = new StringBuilder("{");
        for (int index = 0; index < wanted; index++) {
            final String key = prefix + caseIndex + "_" + (char) ('a' + random.nextInt(6)) + random.nextInt(2);
            if (!keys.add(key)) {
                continue;
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(key).append("\":").append(generatedValue(random));
        }
        return json.append('}').toString();
    }

    // ==================================================================
    // production 段落：以下才是被测生产调用的唯一入口
    // ==================================================================

    private static String fingerprint(PersistenceValues.FingerprintInputs value) {
        return RequestFingerprint.sha256(value);
    }

    private static void assertMatchesOracle(PersistenceValues.FingerprintInputs value, String expectedCanonical)
            throws Exception {
        assertEquals(expectedCanonical, expectedCanonicalText(value));
        assertEquals(sha256Of(expectedCanonical), expectedDigest(value));
        assertEquals(sha256Of(expectedCanonical), fingerprint(value));
    }

    private static void assertMatchesOracle(PersistenceValues.FingerprintInputs value) throws Exception {
        assertMatchesOracle(value, expectedCanonicalText(value));
    }

    private static EnvelopeException assertReservedCollision(PersistenceValues.FingerprintInputs value,
            String ownerPointer, String relativePointer) {
        final EnvelopeException failure = assertThrows(EnvelopeException.class, () -> fingerprint(value));
        assertEquals("RESERVED_COLLISION", failure.code());
        assertEquals(ownerPointer, failure.owner().pointer());
        assertEquals(relativePointer, failure.relativePath().pointer());
        assertNull(failure.getCause());
        return failure;
    }

    // ------------------------------------------------------------------
    // 固定无 unknown 夹具与逐字 canonical
    // ------------------------------------------------------------------

    @Test
    void knownOnlyFixtureMatchesItsVerbatimCanonicalText() throws Exception {
        final PersistenceValues.FingerprintInputs value = value(0L);
        assertEquals(PLAIN_REVISION_ZERO_CANONICAL, expectedCanonicalText(value));
        assertEquals(sha256Of(PLAIN_REVISION_ZERO_CANONICAL), expectedDigest(value));
        assertEquals(sha256Of(PLAIN_REVISION_ZERO_CANONICAL), fingerprint(value));
        assertEquals(11, occurrences(PLAIN_REVISION_ZERO_CANONICAL, ":"));
        assertEquals(1, occurrences(PLAIN_REVISION_ZERO_CANONICAL, "\"target\":"));
    }

    @Test
    void knownOnlyFixtureUnderEveryPinnedRevisionAndBothKinds() throws Exception {
        for (long revision : PINNED_REVISIONS) {
            for (PersistenceValues.OperationKind kind : PersistenceValues.OperationKind.values()) {
                for (String migration : new String[] {null, MIGRATION}) {
                    final PersistenceValues.FingerprintInputs value = value(ACTOR, WORLD, DEFINITION, REQUEST,
                            revision, PAYLOAD, kind, migration, EMPTY_OBJECT, EMPTY_OBJECT);
                    final String text = expectedCanonicalText(value);
                    assertTrue(text.contains("\"expectedRevision\":" + longCanonical(revision)));
                    assertTrue(text.contains("\"operationKind\":\"" + kind.name() + "\""));
                    assertTrue(text.contains(migration == null
                            ? "\"migrationDigest\":null" : "\"migrationDigest\":\"" + migration + "\""));
                    assertMatchesOracle(value, text);
                }
            }
        }
    }

    @Test
    void longCanonicalFollowsFrozenP02NumberSemantics() throws Exception {
        assertEquals(PINNED_REVISIONS.length, PINNED_REVISION_CANONICAL.length);
        for (int index = 0; index < PINNED_REVISIONS.length; index++) {
            final long revision = PINNED_REVISIONS[index];
            final String expected = PINNED_REVISION_CANONICAL[index];
            assertEquals(expected, longCanonical(revision));
            assertEquals(expected, ExactJsonNumber.parse(Long.toString(revision), "/expectedRevision").canonical());
            assertEquals("0", longCanonical(0L));
            final PersistenceValues.FingerprintInputs value = value(revision);
            final String text = expectedCanonicalText(value);
            assertTrue(text.contains("\"expectedRevision\":" + expected + ","));
            assertMatchesOracle(value, text);
        }
    }

    @Test
    void migrationDigestAbsentWritesJsonNullAndPresentWritesItsHex() throws Exception {
        final PersistenceValues.FingerprintInputs absent = value(ACTOR, WORLD, DEFINITION, REQUEST, 5L, PAYLOAD,
                migrateKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs present = value(ACTOR, WORLD, DEFINITION, REQUEST, 5L, PAYLOAD,
                migrateKind(), MIGRATION, EMPTY_OBJECT, EMPTY_OBJECT);
        final String absentText = expectedCanonicalText(absent);
        final String presentText = expectedCanonicalText(present);
        assertTrue(absentText.contains("\"migrationDigest\":null,"));
        assertTrue(presentText.contains("\"migrationDigest\":\"" + MIGRATION + "\","));
        assertNotEquals(absentText, presentText);
        assertMatchesOracle(absent, absentText);
        assertMatchesOracle(present, presentText);
    }

    @Test
    void operationKindEnumBothStatesKeepDistinctFullExpectations() throws Exception {
        final PersistenceValues.FingerprintInputs save = value(ACTOR, WORLD, DEFINITION, REQUEST, 9L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs migrate = value(ACTOR, WORLD, DEFINITION, REQUEST, 9L, PAYLOAD,
                migrateKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String saveText = expectedCanonicalText(save);
        final String migrateText = expectedCanonicalText(migrate);
        assertTrue(saveText.contains("\"operationKind\":\"SAVE_MAP\""));
        assertTrue(migrateText.contains("\"operationKind\":\"MIGRATE_MAP\""));
        assertNotEquals(saveText, migrateText);
        assertMatchesOracle(save, saveText);
        assertMatchesOracle(migrate, migrateText);
    }

    @Test
    void payloadDigestSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, OTHER_PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"payloadDigest\":\"" + PAYLOAD + "\""));
        assertTrue(afterText.contains("\"payloadDigest\":\"" + OTHER_PAYLOAD + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void migrationDigestSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                migrateKind(), MIGRATION, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                migrateKind(), OTHER_MIGRATION, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"migrationDigest\":\"" + MIGRATION + "\""));
        assertTrue(afterText.contains("\"migrationDigest\":\"" + OTHER_MIGRATION + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void actorUuidSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(OTHER_ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"actorUuid\":\"" + ACTOR + "\""));
        assertTrue(afterText.contains("\"actorUuid\":\"" + OTHER_ACTOR + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void requestIdSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 3L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(ACTOR, WORLD, DEFINITION, OTHER_REQUEST, 3L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"requestId\":\"" + REQUEST + "\""));
        assertTrue(afterText.contains("\"requestId\":\"" + OTHER_REQUEST + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void expectedRevisionSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(100L);
        final PersistenceValues.FingerprintInputs after = value(101L);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"expectedRevision\":1E+2,"));
        assertTrue(afterText.contains("\"expectedRevision\":101,"));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void targetWorldIdSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(ACTOR, OTHER_WORLD, DEFINITION, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"worldId\":\"" + WORLD + "\""));
        assertTrue(afterText.contains("\"worldId\":\"" + OTHER_WORLD + "\""));
        assertTrue(afterText.contains("\"definitionId\":\"" + DEFINITION + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void targetDefinitionIdSingleChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs after = value(ACTOR, WORLD, OTHER_DEFINITION, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String afterText = expectedCanonicalText(after);
        assertTrue(beforeText.contains("\"definitionId\":\"" + DEFINITION + "\""));
        assertTrue(afterText.contains("\"definitionId\":\"" + OTHER_DEFINITION + "\""));
        assertNotEquals(beforeText, afterText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(after, afterText);
    }

    @Test
    void targetReferenceSwapMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs before = value(ACTOR, WORLD, DEFINITION, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs swapped = value(ACTOR, DEFINITION, WORLD, REQUEST, 4L, PAYLOAD,
                saveKind(), null, EMPTY_OBJECT, EMPTY_OBJECT);
        final String beforeText = expectedCanonicalText(before);
        final String swappedText = expectedCanonicalText(swapped);
        assertTrue(beforeText.contains("\"definitionId\":\"" + DEFINITION + "\",\"worldId\":\"" + WORLD + "\""));
        assertTrue(swappedText.contains("\"definitionId\":\"" + WORLD + "\",\"worldId\":\"" + DEFINITION + "\""));
        assertNotEquals(beforeText, swappedText);
        assertMatchesOracle(before, beforeText);
        assertMatchesOracle(swapped, swappedText);
    }

    // ------------------------------------------------------------------
    // 两个 owner 的 unknown 成员：排序、语义边界与互不干扰
    // ------------------------------------------------------------------

    @Test
    void rootUnknownMembersMergeByDecodedCompareToAroundTheKnownKeys() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown("{\"zeta\":1,\"Alpha\":\"x\",\"mu\":null}",
                EMPTY_OBJECT);
        final String text = expectedCanonicalText(value);
        assertTrue(text.indexOf("\"Alpha\"") < text.indexOf("\"actorUuid\""));
        assertTrue(text.indexOf("\"migrationDigest\"") < text.indexOf("\"mu\""));
        assertTrue(text.indexOf("\"mu\"") < text.indexOf("\"operationKind\""));
        assertTrue(text.indexOf("\"target\"") < text.indexOf("\"zeta\""));
        assertTrue(text.endsWith("\"zeta\":1}"));
        assertMatchesOracle(value, text);
    }

    @Test
    void targetUnknownMembersMergeIndependentlyOfTheRootOwner() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown("{\"rootOnly\":1}", "{\"Beta\":2,\"alpha\":\"y\"}");
        final String text = expectedCanonicalText(value);
        assertTrue(text.indexOf("\"target\":{") < text.indexOf("\"Beta\""));
        assertTrue(text.indexOf("\"Beta\"") < text.indexOf("\"alpha\""));
        assertTrue(text.indexOf("\"alpha\"") < text.indexOf("\"definitionId\""));
        assertTrue(text.indexOf("\"definitionId\"") < text.indexOf("\"worldId\""));
        assertTrue(text.indexOf("\"rootOnly\"") < text.indexOf("\"target\":{"));
        assertMatchesOracle(value, text);
    }

    @Test
    void sharedMembersObjectAppearsOncePerOwnerWithoutDeduplication() throws Exception {
        final ExactJson.FrozenJson shared = ExactJson.parse("{\"shared\":1}");
        final PersistenceValues.FingerprintInputs value = new PersistenceValues.FingerprintInputs(uuid(ACTOR),
                new PersistenceValues.FingerprintTarget(reference(WORLD, DEFINITION), shared),
                new Ids.RequestId(uuid(REQUEST)), 7L, PAYLOAD, saveKind(), Optional.empty(), shared);
        final String text = expectedCanonicalText(value);
        assertEquals(2, occurrences(text, "\"shared\":1"));
        assertTrue(text.indexOf("\"shared\":1") < text.indexOf("\"target\":{"));
        assertTrue(text.lastIndexOf("\"shared\":1") > text.indexOf("\"target\":{"));
        assertMatchesOracle(value, text);
    }

    @Test
    void emptyUnknownKeyIsLegalAtBothOwners() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown("{\"\":1}", "{\"\":2}");
        final String text = expectedCanonicalText(value);
        assertEquals(2, occurrences(text, "\"\":"));
        assertTrue(text.indexOf("\"\":1") < text.indexOf("\"actorUuid\""));
        assertMatchesOracle(value, text);
    }

    @Test
    void controlQuoteAndBackslashUnknownKeysAreLegalAtBothOwners() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"\\u0000\":1,\"\\u001f\":2,\"a\\\"b\":3,\"a\\\\b\":4}", "{\"\\u0007\":5}");
        final String text = expectedCanonicalText(value);
        assertTrue(text.contains("\"a\\\"b\":3"));
        assertTrue(text.contains("\"a\\\\b\":4"));
        assertTrue(text.indexOf("\"a\\\"b\":3") < text.indexOf("\"a\\\\b\":4"));
        assertTrue(text.indexOf("\"a\\\\b\":4") < text.indexOf("\"actorUuid\""));
        assertMatchesOracle(value, text);
    }

    @Test
    void unicodeNfcNfdSupplementaryUnknownKeysAreLegalAtBothOwners() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"\u00e9\":1,\"e\u0301\":2,\"\uD83D\uDE00\":3,\"\uFFFD\":4}",
                "{\"\uE000\":5,\"\u07FF\":6,\"\u0800\":7}");
        final String text = expectedCanonicalText(value);
        assertEquals(4, memberTokens(value.members().canonical(), rootReservedKeys()).size());
        assertEquals(3, memberTokens(value.target().members().canonical(), targetReservedKeys()).size());
        assertTrue(text.indexOf("\"e\u0301\":2") < text.indexOf("\"target\":{"));
        assertTrue(text.indexOf("\"target\":{") < text.indexOf("\"\u00e9\":1"));
        assertTrue(text.indexOf("\"\u00e9\":1") < text.indexOf("\"\uD83D\uDE00\":3"));
        assertTrue(text.indexOf("\"\uD83D\uDE00\":3") < text.indexOf("\"\uFFFD\":4"));
        assertTrue(text.indexOf("\"\u07FF\":6") < text.indexOf("\"\u0800\":7"));
        assertTrue(text.indexOf("\"\u0800\":7") < text.indexOf("\"\uE000\":5"));
        assertNotEquals(text.indexOf("\"\u00e9\":1"), text.indexOf("\"e\u0301\":2"));
        assertMatchesOracle(value, text);
    }

    @Test
    void stringCompareToBoundariesDecideTheUnknownMemberOrder() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"a\":1,\"aa\":2,\"a\\u0000\":3,\"\\u0000\":4,\"A\":5,\"Z\":6,\"z\":7,\"\uD83D\uDE00\":8,\"\uE000\":9,\"\uFFFD\":101}",
                EMPTY_OBJECT);
        final String text = expectedCanonicalText(value);
        final TreeMap<String, List<String>> ordered =
                memberTokens(value.members().canonical(), rootReservedKeys());
        final List<String> keys = new ArrayList<>(ordered.keySet());
        assertEquals(10, keys.size());
        int previous = -1;
        for (String key : keys) {
            final int at = text.indexOf(jsonString(key) + ":");
            assertTrue(at > previous);
            previous = at;
        }
        assertTrue(text.indexOf("\"\\u0000\":4") < text.indexOf("\"A\":5"));
        assertTrue(text.indexOf("\"z\":7") < text.indexOf("\"\uD83D\uDE00\":8"));
        assertTrue(text.indexOf("\"\uD83D\uDE00\":8") < text.indexOf("\"\uE000\":9"));
        assertTrue(text.indexOf("\"\uE000\":9") < text.indexOf("\"\uFFFD\":101"));
        assertMatchesOracle(value, text);
    }

    @Test
    void extremeUnknownValuesStayLegalAtBothOwners() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"n50\":12345678901234567890123456789012345678901234567890,\"hexp\":1e999999,\"neg\":-0,\"nil\":null,\"arr\":[3,1,2],\"obj\":{\"k\":1}}",
                "{\"big\":-9223372036854775808,\"tnull\":null,\"tarr\":[],\"tobj\":{}}");
        final String text = expectedCanonicalText(value);
        assertTrue(text.contains("\"neg\":0"));
        assertTrue(text.contains("\"nil\":null"));
        assertTrue(text.contains("\"arr\":[3,1,2]"));
        assertTrue(text.contains("\"tarr\":[]"));
        assertTrue(text.contains("\"tobj\":{}"));
        final String n50 = new BigDecimal("12345678901234567890123456789012345678901234567890")
                .stripTrailingZeros().toString();
        assertEquals(n50, ExactJsonNumber.parse("12345678901234567890123456789012345678901234567890", "/n50")
                .canonical());
        assertTrue(text.contains("\"n50\":" + n50));
        assertTrue(text.contains("\"hexp\":" + ExactJsonNumber.parse("1e999999", "/hexp").canonical()));
        assertTrue(text.contains("\"big\":" + ExactJsonNumber.parse("-9223372036854775808", "/big").canonical()));
        assertMatchesOracle(value, text);
    }

    @Test
    void unknownMemberSpellingOrderAndNumericSpellingDoNotChangeTheFingerprint() throws Exception {
        final PersistenceValues.FingerprintInputs plain = unknown("{\"alpha\":1,\"beta\":2.0,\"gamma\":\"x\"}",
                EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs respelled = unknown(
                "{\"\\u0062eta\":2,\"\\u0067amma\":\"\\u0078\",\"\\u0061lpha\":1}", EMPTY_OBJECT);
        final String plainText = expectedCanonicalText(plain);
        final String respelledText = expectedCanonicalText(respelled);
        assertEquals(plainText, respelledText);
        assertEquals(sha256Of(plainText), sha256Of(respelledText));
        assertMatchesOracle(plain, plainText);
        assertMatchesOracle(respelled, respelledText);
        assertEquals(fingerprint(plain), fingerprint(respelled));
    }

    @Test
    void arrayOrderChangeMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs ascending = unknown("{\"arr\":[1,2,3]}", EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs descending = unknown("{\"arr\":[3,2,1]}", EMPTY_OBJECT);
        final String ascendingText = expectedCanonicalText(ascending);
        final String descendingText = expectedCanonicalText(descending);
        assertTrue(ascendingText.contains("\"arr\":[1,2,3]"));
        assertTrue(descendingText.contains("\"arr\":[3,2,1]"));
        assertNotEquals(ascendingText, descendingText);
        assertMatchesOracle(ascending, ascendingText);
        assertMatchesOracle(descending, descendingText);
    }

    @Test
    void ownershipChangeOfOneUnknownMemberMatchesItsOwnFullExpectation() throws Exception {
        final PersistenceValues.FingerprintInputs atRoot = unknown("{\"moved\":1}", EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs atTarget = unknown(EMPTY_OBJECT, "{\"moved\":1}");
        final String atRootText = expectedCanonicalText(atRoot);
        final String atTargetText = expectedCanonicalText(atTarget);
        assertTrue(atRootText.indexOf("\"moved\":1") < atRootText.indexOf("\"target\":{"));
        assertTrue(atTargetText.indexOf("\"moved\":1") > atTargetText.indexOf("\"target\":{"));
        assertNotEquals(atRootText, atTargetText);
        assertMatchesOracle(atRoot, atRootText);
        assertMatchesOracle(atTarget, atTargetText);
    }

    @Test
    void unknownRootKeyNamedExtensionsStaysAtTheRootOwner() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown("{\"extensions\":{\"a\":1},\"targets\":2}",
                EMPTY_OBJECT);
        final String text = expectedCanonicalText(value);
        assertTrue(text.indexOf("\"expectedRevision\"") < text.indexOf("\"extensions\":{\"a\":1}"));
        assertTrue(text.indexOf("\"extensions\":{\"a\":1}") < text.indexOf("\"migrationDigest\""));
        assertTrue(text.indexOf("\"schemaVersion\":1") < text.indexOf("\"target\":{"));
        assertTrue(text.indexOf("\"targets\":2") > text.indexOf("\"target\":{"));
        assertEquals(1, occurrences(text, "\"targets\":"));
        assertTrue(text.endsWith("\"targets\":2}"));
        assertEquals(2, memberTokens(value.members().canonical(), rootReservedKeys()).size());
        assertMatchesOracle(value, text);
    }

    // ------------------------------------------------------------------
    // 跨 owner 的合法向量：一个 owner 的保留字是另一个 owner 的普通未知成员
    // ------------------------------------------------------------------

    @Test
    void crossOwnerReservedNamesAreOrdinaryUnknownMembersAtTheOtherOwner() throws Exception {
        // root 上的 definitionId/worldId 是 target 的保留字，但在 root 上是普通未知成员；
        // target 上的 actorUuid/expectedRevision/migrationDigest/operationKind/payloadDigest/
        // protocolVersion/requestId/schemaVersion/target 是 root 的保留字，但在 target 上是普通未知成员。
        // 两边都必须被当作未知成员逐字参与摘要，未知成员的原值（含嵌套 object 与嵌套同名 key）原样保留。
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"definitionId\":{\"worldId\":\"not-a-uuid\"},"
                        + "\"worldId\":[1,{\"definitionId\":null}]}",
                "{\"actorUuid\":\"a\","
                        + "\"expectedRevision\":7,"
                        + "\"migrationDigest\":\"b\","
                        + "\"operationKind\":\"c\","
                        + "\"payloadDigest\":\"d\","
                        + "\"protocolVersion\":\"e\","
                        + "\"requestId\":\"f\","
                        + "\"schemaVersion\":\"g\","
                        + "\"target\":\"h\"}");
        final String text = expectedCanonicalText(value);

        // 1) 两个 owner 的成员表各自独立：root 2 个跨 owner 未知成员，target 9 个跨 owner 未知成员。
        final int rootExtras = memberTokens(value.members().canonical(), rootReservedKeys()).size();
        final int targetExtras = memberTokens(value.target().members().canonical(), targetReservedKeys()).size();
        assertEquals(2, rootExtras);
        assertEquals(9, targetExtras);
        System.out.println("[cross-owner] root_extras=" + rootExtras);
        System.out.println("[cross-owner] target_extras=" + targetExtras);
        System.out.println("[cross-owner] legal=true");

        // 2) 跨 owner 名字出现在各自的未知位置，原值逐字保留。
        assertTrue(text.contains("\"definitionId\":{\"worldId\":\"not-a-uuid\"}"));
        assertTrue(text.contains("\"worldId\":[1,{\"definitionId\":null}]"));
        assertTrue(text.contains("\"actorUuid\":\"a\""));
        assertTrue(text.contains("\"schemaVersion\":\"g\""));
        assertTrue(text.contains("\"target\":\"h\""));

        // 3) 全文三处 definitionId：root 的未知成员（值内还嵌套一个同名 key，原样保留）、
        //    以及 target 的 known definitionId。同名 key 分别按所属 object 判定，不构成保留字碰撞。
        assertEquals(3, occurrences(text, "\"definitionId\":"));

        // 4) 排序按所属 object 的字典序：root 的 definitionId 未知成员在 known expectedRevision 之前
        //    （本向量 revision=0，其 canonical 为 "0"），root 的 worldId 未知成员在 known target 之后；
        //    target 的 target 未知成员在 target 的 schemaVersion 未知成员之后。
        assertTrue(text.indexOf("\"definitionId\":{\"worldId\"") < text.indexOf("\"expectedRevision\":0"));
        assertTrue(text.indexOf("\"target\":{") < text.lastIndexOf("\"worldId\":[1,"));
        assertTrue(text.indexOf("\"schemaVersion\":\"g\"") < text.indexOf("\"target\":\"h\""));
        assertMatchesOracle(value, text);
    }

    // ------------------------------------------------------------------
    // reserved 碰撞、错误形状与 Java null
    // ------------------------------------------------------------------

    @Test
    void allNineRootReservedKeysCollideAtTheRootOwner() throws Exception {
        for (String reserved : ROOT_RESERVED) {
            final PersistenceValues.FingerprintInputs value = unknown("{\"" + reserved + "\":1}", EMPTY_OBJECT);
            assertReservedCollision(value, "", "/" + reserved);
        }
        assertEquals(9, ROOT_RESERVED.length);
    }

    @Test
    void bothTargetReservedKeysCollideAtTheTargetOwner() throws Exception {
        for (String reserved : TARGET_RESERVED) {
            final PersistenceValues.FingerprintInputs value = unknown(EMPTY_OBJECT, "{\"" + reserved + "\":1}");
            assertReservedCollision(value, "/target", "/" + reserved);
        }
        assertEquals(2, TARGET_RESERVED.length);
    }

    @Test
    void escapedReservedSpellingsStillCollideAfterDecoding() throws Exception {
        assertReservedCollision(unknown("{\"\\u0074arget\":1}", EMPTY_OBJECT), "", "/target");
        assertReservedCollision(unknown("{\"\\u0061ctorUuid\":1}", EMPTY_OBJECT), "", "/actorUuid");
        assertReservedCollision(unknown("{\"\\u0073chemaVersion\":1}", EMPTY_OBJECT), "", "/schemaVersion");
        assertReservedCollision(unknown(EMPTY_OBJECT, "{\"\\u0077orldId\":1}"), "/target", "/worldId");
        assertReservedCollision(unknown(EMPTY_OBJECT, "{\"definition\\u0049d\":1}"), "/target", "/definitionId");
    }

    @Test
    void nestedSameNameAndCaseDifferentKeysAreLegal() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"Target\":{\"worldId\":1},\"ACTORUUID\":1,\"SchemaVersion\":{\"requestId\":2},\"targets\":3}",
                "{\"DefinitionId\":{\"definitionId\":1}}");
        final String text = expectedCanonicalText(value);
        assertTrue(text.contains("\"Target\":{\"worldId\":1}"));
        assertTrue(text.contains("\"ACTORUUID\":1"));
        assertTrue(text.contains("\"SchemaVersion\":{\"requestId\":2}"));
        assertTrue(text.contains("\"DefinitionId\":{\"definitionId\":1}"));
        assertMatchesOracle(value, text);
    }

    @Test
    void rootReservedCollisionWinsWhenBothOwnersCollide() throws Exception {
        assertReservedCollision(unknown("{\"schemaVersion\":1}", "{\"worldId\":1}"), "", "/schemaVersion");
        assertReservedCollision(unknown("{\"target\":1}", "{\"definitionId\":1}"), "", "/target");
    }

    @Test
    void reservedCollisionCarriesNoCauseAndNoDigest() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown("{\"payloadDigest\":1}", EMPTY_OBJECT);
        final EnvelopeException first = assertReservedCollision(value, "", "/payloadDigest");
        final EnvelopeException second = assertReservedCollision(value, "", "/payloadDigest");
        assertTrue(first instanceof IllegalArgumentException);
        assertEquals(first.code(), second.code());
        assertEquals(first.owner().pointer(), second.owner().pointer());
        assertEquals(first.relativePath().pointer(), second.relativePath().pointer());
        assertEquals(first.getMessage(), second.getMessage());
    }

    @Test
    void javaNullInputIsRejectedBeforeAnyDigest() throws Exception {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> fingerprint(null));
        assertFalse(failure instanceof EnvelopeException);
        assertNull(failure.getCause());
    }

    // ------------------------------------------------------------------
    // 重复、并发、互异输入集合与调用方输入不变
    // ------------------------------------------------------------------

    @Test
    void sameInputRepeatedHundredTimesKeepsOneOracleDigest() throws Exception {
        final PersistenceValues.FingerprintInputs value = unknown(
                "{\"a\":1,\"b\":[1,2],\"c\":{\"d\":null}}", "{\"e\":-0}");
        final String expected = expectedCanonicalText(value);
        final String oracleDigest = expectedDigest(value);
        final ExactJson.FrozenJson rootBefore = value.members();
        final ExactJson.FrozenJson targetBefore = value.target().members();
        final String rootCanonicalBefore = rootBefore.canonical();
        final String targetCanonicalBefore = targetBefore.canonical();
        assertEquals(REPEATS, 100);
        for (int repeat = 0; repeat < REPEATS; repeat++) {
            assertEquals(oracleDigest, fingerprint(value));
        }
        assertSame(rootBefore, value.members());
        assertSame(targetBefore, value.target().members());
        assertEquals(rootCanonicalBefore, value.members().canonical());
        assertEquals(targetCanonicalBefore, value.target().members().canonical());
        assertEquals(expected, expectedCanonicalText(value));
    }

    @Test
    void twoConcurrentCallsStayIndependent() throws Exception {
        final PersistenceValues.FingerprintInputs firstValue = unknown("{\"k\":\"one\"}", EMPTY_OBJECT);
        final PersistenceValues.FingerprintInputs secondValue = unknown("{\"k\":\"two\"}", "{\"j\":1}");
        final String firstExpected = expectedDigest(firstValue);
        final String secondExpected = expectedDigest(secondValue);
        assertNotEquals(firstExpected, secondExpected);
        final String[] results = new String[2];
        final Thread first = new Thread(() -> {
            for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
                results[0] = fingerprint(firstValue);
            }
        });
        final Thread second = new Thread(() -> {
            for (int round = 0; round < CONCURRENT_ROUNDS; round++) {
                results[1] = fingerprint(secondValue);
            }
        });
        first.start();
        second.start();
        first.join();
        second.join();
        assertEquals(firstExpected, results[0]);
        assertEquals(secondExpected, results[1]);
        assertEquals(firstExpected, fingerprint(firstValue));
        assertEquals(secondExpected, fingerprint(secondValue));
        assertNotEquals(results[0], results[1]);
    }

    @Test
    void thousandFixedSeedInputsAreDistinctAndAllMatchTheirOracle() throws Exception {
        // 阶段一：只构造与事前登记，绝不调用生产 sha256。
        // 一次性建出全部 1000 个 DTO，保存每项的 inputs / target / root.members / target.members 引用、
        // 两个 owner 成员表的完整 canonical 文本、完整 wire canonical 文本，以及出题侧独立的 JDK 摘要。
        // 逐项 HashSet.add 事先确认完整 wire 1000 项互异；任何一项重复或失败都直接抛出，不筛不补，
        // 也绝不为“不变性”复制大对象：这里保存的只是原 immutable 引用与它们的文本。
        final Random random = new Random(SEED);
        final Set<String> expectations = new HashSet<>();
        final Set<String> oracleDigests = new HashSet<>();
        final PersistenceValues.FingerprintInputs[] inputs = new PersistenceValues.FingerprintInputs[GENERATED_CASES];
        final Object[] targets = new Object[GENERATED_CASES];
        final Object[] rootContainers = new Object[GENERATED_CASES];
        final Object[] targetContainers = new Object[GENERATED_CASES];
        final String[] rootTexts = new String[GENERATED_CASES];
        final String[] targetTexts = new String[GENERATED_CASES];
        final String[] canonicals = new String[GENERATED_CASES];
        final String[] jdkDigests = new String[GENERATED_CASES];
        int trailingZeroRevision = 0;
        int absentMigration = 0;
        int presentMigration = 0;
        int saveKind = 0;
        int migrateKind = 0;
        int rootUnknownMembers = 0;
        int targetUnknownMembers = 0;
        for (int index = 0; index < GENERATED_CASES; index++) {
            final long revision = generatedRevision(random);
            final boolean migrate = random.nextBoolean();
            final boolean present = random.nextBoolean();
            final String rootMembers = generatedMembers(random, "r", index);
            final String targetMembers = generatedMembers(random, "t", index);
            final PersistenceValues.FingerprintInputs value = value(randomUuid(random), randomUuid(random),
                    randomUuid(random), randomUuid(random), revision, randomDigest(random),
                    migrate ? migrateKind() : saveKind(), present ? randomDigest(random) : null,
                    rootMembers, targetMembers);
            final String expected = expectedCanonicalText(value);
            if (!expectations.add(expected)) {
                throw new IllegalStateException("generated case " + index + " repeated an expected canonical");
            }
            final String jdkDigest = sha256Of(expected);
            if (!oracleDigests.add(jdkDigest)) {
                throw new IllegalStateException("generated case " + index + " repeated an oracle digest");
            }
            inputs[index] = value;
            targets[index] = value.target();
            rootContainers[index] = value.members();
            targetContainers[index] = value.target().members();
            rootTexts[index] = value.members().canonical();
            targetTexts[index] = value.target().members().canonical();
            canonicals[index] = expected;
            jdkDigests[index] = jdkDigest;
            if (revision % 10L == 0L) {
                trailingZeroRevision++;
            }
            if (present) {
                presentMigration++;
            } else {
                absentMigration++;
            }
            if (migrate) {
                migrateKind++;
            } else {
                saveKind++;
            }
            if (!EMPTY_OBJECT.equals(rootMembers)) {
                rootUnknownMembers++;
            }
            if (!EMPTY_OBJECT.equals(targetMembers)) {
                targetUnknownMembers++;
            }
        }
        assertEquals(GENERATED_CASES, expectations.size());
        assertEquals(GENERATED_CASES, oracleDigests.size());
        System.out.println("[pre-registration] phase1_cases=" + GENERATED_CASES);
        System.out.println("[pre-registration] phase1_distinct_wire=" + expectations.size());
        System.out.println("[pre-registration] phase1_distinct_oracle_digests=" + oracleDigests.size());
        System.out.println("[pre-registration] phase1_root_member_texts=" + GENERATED_CASES);
        System.out.println("[pre-registration] phase1_target_member_texts=" + GENERATED_CASES);
        System.out.println("[pre-registration] phase1_production_calls=0");

        // 阶段二：逐项事中核对。先复核阶段一登记的全部引用仍是同一个对象、所有 canonical 文本一字未变，
        // 然后才调用生产 sha256，逐项与事前登记的 JDK 摘要比较；调用后再核引用、成员和完整 wire。
        // 事前与事后计数分别登记，不筛不补。
        int referencesSame = 0;
        int memberTextsUnchanged = 0;
        int wireUnchanged = 0;
        int oracleMatches = 0;
        int productionMatches = 0;
        int postReferencesSame = 0;
        int postMemberTextsUnchanged = 0;
        int postWireUnchanged = 0;
        for (int index = 0; index < GENERATED_CASES; index++) {
            final PersistenceValues.FingerprintInputs value = inputs[index];
            assertSame(targets[index], value.target());
            assertSame(rootContainers[index], value.members());
            assertSame(targetContainers[index], value.target().members());
            referencesSame++;
            assertEquals(rootTexts[index], value.members().canonical());
            assertEquals(targetTexts[index], value.target().members().canonical());
            memberTextsUnchanged++;
            assertEquals(canonicals[index], expectedCanonicalText(value));
            wireUnchanged++;
            assertEquals(jdkDigests[index], expectedDigest(value));
            oracleMatches++;
            assertEquals(jdkDigests[index], fingerprint(value));
            productionMatches++;
            assertSame(inputs[index], value);
            assertSame(targets[index], value.target());
            assertSame(rootContainers[index], value.members());
            assertSame(targetContainers[index], value.target().members());
            postReferencesSame++;
            assertEquals(rootTexts[index], value.members().canonical());
            assertEquals(targetTexts[index], value.target().members().canonical());
            postMemberTextsUnchanged++;
            assertEquals(canonicals[index], expectedCanonicalText(value));
            postWireUnchanged++;
        }
        System.out.println("[pre-registration] phase2_cases=" + GENERATED_CASES);
        System.out.println("[pre-registration] phase2_pre_references_same=" + referencesSame);
        System.out.println("[pre-registration] phase2_pre_member_texts_unchanged=" + memberTextsUnchanged);
        System.out.println("[pre-registration] phase2_pre_wire_unchanged=" + wireUnchanged);
        System.out.println("[pre-registration] phase2_post_references_same=" + postReferencesSame);
        System.out.println("[pre-registration] phase2_post_member_texts_unchanged=" + postMemberTextsUnchanged);
        System.out.println("[pre-registration] phase2_post_wire_unchanged=" + postWireUnchanged);
        System.out.println("[pre-registration] phase2_oracle_matches=" + oracleMatches);
        System.out.println("[pre-registration] phase2_production_matches=" + productionMatches);
        assertEquals(GENERATED_CASES, referencesSame);
        assertEquals(GENERATED_CASES, memberTextsUnchanged);
        assertEquals(GENERATED_CASES, wireUnchanged);
        assertEquals(GENERATED_CASES, oracleMatches);
        assertEquals(GENERATED_CASES, productionMatches);
        assertEquals(GENERATED_CASES, postReferencesSame);
        assertEquals(GENERATED_CASES, postMemberTextsUnchanged);
        assertEquals(GENERATED_CASES, postWireUnchanged);

        System.out.println("[case-counters] cases=" + GENERATED_CASES);
        System.out.println("[case-counters] distinctCanonicals=" + expectations.size());
        System.out.println("[case-counters] distinctDigests=" + oracleDigests.size());
        System.out.println("[case-counters] trailingZeroRevision=" + trailingZeroRevision);
        System.out.println("[case-counters] absentMigration=" + absentMigration);
        System.out.println("[case-counters] presentMigration=" + presentMigration);
        System.out.println("[case-counters] saveKind=" + saveKind);
        System.out.println("[case-counters] migrateKind=" + migrateKind);
        System.out.println("[case-counters] rootUnknownMembers=" + rootUnknownMembers);
        System.out.println("[case-counters] targetUnknownMembers=" + targetUnknownMembers);
        assertEquals(GENERATED_CASES, expectations.size());
        assertTrue(trailingZeroRevision > 0);
        assertTrue(absentMigration > 0);
        assertTrue(presentMigration > 0);
        assertTrue(saveKind > 0);
        assertTrue(migrateKind > 0);
        assertTrue(rootUnknownMembers > 0);
        assertTrue(targetUnknownMembers > 0);
    }

    @Test
    void memberContainerReferencesStaySharedAndUnchangedAfterTheCall() throws Exception {
        final ExactJson.FrozenJson shared = ExactJson.parse("{\"s\":1}");
        final PersistenceValues.FingerprintInputs value = new PersistenceValues.FingerprintInputs(uuid(ACTOR),
                new PersistenceValues.FingerprintTarget(reference(WORLD, DEFINITION), shared),
                new Ids.RequestId(uuid(REQUEST)), 11L, PAYLOAD, saveKind(), Optional.empty(), shared);
        final String expected = expectedCanonicalText(value);
        assertSame(shared, value.members());
        assertSame(shared, value.target().members());
        assertEquals(expectedDigest(value), fingerprint(value));
        assertSame(shared, value.members());
        assertSame(shared, value.target().members());
        assertEquals("{\"s\":1}", value.members().canonical());
        assertEquals(value.members().canonical(), value.target().members().canonical());
        assertEquals(sha256Of(expected), fingerprint(value));
    }

    // ------------------------------------------------------------------
    // 真实大输入：8388608 ASCII bytes 复用两 owner、两个不同大 owner
    // ------------------------------------------------------------------

    @Test
    @Tag("large-heap")
    void largeSharedMembersObjectReusedAtBothOwnersExceedsSixteenMebibytes() throws Exception {
        final String sharedText = largeMembersObject(SHARED_MEMBERS_BYTES, "s");
        assertEquals(SHARED_MEMBERS_BYTES, sharedText.length());
        assertTrue(isAscii(sharedText));
        final int memberCount = occurrences(sharedText, "\":\"");
        assertTrue(memberCount > 100);
        final ExactJson.FrozenJson shared = ExactJson.parse(sharedText);
        final PersistenceValues.FingerprintInputs value = new PersistenceValues.FingerprintInputs(uuid(ACTOR),
                new PersistenceValues.FingerprintTarget(reference(WORLD, DEFINITION), shared),
                new Ids.RequestId(uuid(REQUEST)), 0L, PAYLOAD, saveKind(), Optional.empty(), shared);

        final List<String> shards = expectedShards(value);
        final long canonicalBytes = totalShardBytes(shards);
        final String oracleDigest = digestOfShards(shards);
        int largeShards = 0;
        for (String shard : shards) {
            if (shard.length() >= 4096) {
                largeShards++;
            }
        }
        System.out.println("[large-pin] sharedMembersBytes=" + sharedText.length());
        System.out.println("[large-pin] sharedMemberCount=" + memberCount);
        System.out.println("[large-pin] canonicalBytes=" + canonicalBytes);
        System.out.println("[large-pin] largeShards=" + largeShards);
        assertEquals(2 * memberCount, largeShards);
        assertTrue(canonicalBytes > 16L * MEBI);
        assertTrue(canonicalBytes > SHARED_MEMBERS_BYTES);

        assertEquals(oracleDigest, fingerprint(value));
        assertEquals(oracleDigest, fingerprint(value));
        assertEquals(sharedText, value.members().canonical());
        assertSame(shared, value.members());
        assertSame(shared, value.target().members());
    }

    @Test
    @Tag("large-heap")
    void largeTwoDifferentOwnersKeepOrderAndNoDedupAboveEightMebibytes() throws Exception {
        final String rootText = largeMembersObject(PAIR_ROOT_BYTES, "a");
        final String targetText = largeMembersObject(PAIR_TARGET_BYTES, "b");
        assertTrue(isAscii(rootText) && isAscii(targetText));
        assertTrue(rootText.length() > 4L * MEBI);
        assertTrue(targetText.length() > 4L * MEBI);
        assertTrue(rootText.length() <= SHARED_MEMBERS_BYTES);
        assertTrue(targetText.length() <= SHARED_MEMBERS_BYTES);
        assertNotEquals(rootText, targetText);
        final PersistenceValues.FingerprintInputs value = value(ACTOR, WORLD, DEFINITION, REQUEST, 1230000L,
                PAYLOAD, migrateKind(), MIGRATION, rootText, targetText);

        final List<String> shards = expectedShards(value);
        final long canonicalBytes = totalShardBytes(shards);
        final String oracleDigest = digestOfShards(shards);
        final String text = String.join("", shards);
        assertEquals((long) text.length(), canonicalBytes);
        final int rootMarker = text.indexOf("\"a0000\":\"");
        final int targetMarker = text.indexOf("\"target\":{");
        final int targetOwnerMarker = text.indexOf("\"b0000\":\"");
        System.out.println("[large-pin] pairRootBytes=" + rootText.length());
        System.out.println("[large-pin] pairTargetBytes=" + targetText.length());
        System.out.println("[large-pin] pairCanonicalBytes=" + canonicalBytes);
        assertTrue(rootMarker >= 0);
        assertTrue(targetMarker > rootMarker);
        assertTrue(targetOwnerMarker > targetMarker);
        assertTrue(canonicalBytes > 8L * MEBI);
        assertEquals(1, occurrences(text, "\"a0000\":\""));
        assertEquals(1, occurrences(text, "\"b0000\":\""));

        assertEquals(oracleDigest, fingerprint(value));
        assertEquals(oracleDigest, fingerprint(value));
        assertEquals(rootText, value.members().canonical());
        assertEquals(targetText, value.target().members().canonical());
    }
}
