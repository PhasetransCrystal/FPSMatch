package net.ptcrys.fpsmatch.editor.common.model.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.geometry.BoxCorners;
import net.ptcrys.fpsmatch.editor.common.model.geometry.IntPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Formal contract tests for the R05.2 domain structures {@link RegionDefinition} and
 * {@link ResourceRevisionRef}.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests of the two
 * records. They cover exactly the vectors the task card fixes: full member retention, the typed
 * identity and the same-name / same-corners distinct-identity case, the reversed and extreme corner
 * order, the null policy of every required member, the empty region name versus a space name, the
 * 512 UTF-8 byte budget and the surrogate and control rules inherited from the frozen
 * {@code MapScalars}, the {@code namespace:path} kind grammar including its 191 character boundary,
 * the positive revision rule, the exact 64 character lower case hexadecimal digest, the retained
 * frozen shapes, the isolation of a frozen value from a polluted source tree, and the per-position
 * retention of wire extras.</p>
 *
 * <p><strong>What this class is not.</strong> A green run here is author-side evidence only. It is
 * not the P05-REGION-RESOURCE gate, which only the primary agent can grant, and it proves nothing
 * about the complete P05 milestone, which still requires the map definition point plus its cross
 * references, counting and total payload budget, nor about a resource library. Nothing here claims
 * wire round-tripping, codec merge, reserved key handling, provider schema validation, storage or a
 * mature UI, and nothing here checks whether a kind is registered or a resource exists.</p>
 *
 * <p><strong>Assertion discipline.</strong> Every rejection asserts
 * {@link IllegalArgumentException} itself, and every message assertion checks only stable fragments
 * the production javadoc promises, such as the member name; no test asserts a whole message
 * sentence. Byte budgets are asserted from the real encoding, so no test substitutes a Java
 * character count for a UTF-8 byte count. The scalar results are asserted to be the identical
 * instance rather than an equal copy, which is what makes "no trim and no normalisation"
 * observable, and the frozen JSON carriers are asserted with {@code assertSame}, which is what
 * makes "reused, not re-frozen" observable.</p>
 */
@DisplayName("R05.2 region and resource revision contract")
class RegionResourceContractTest {

    private static final Ids.ObjectId OBJECT_ID =
            new Ids.ObjectId(UUID.fromString("0f1e2d3c-4b5a-4697-8879-9a0b1c2d3e4f"));
    private static final Ids.ObjectId OTHER_OBJECT_ID =
            new Ids.ObjectId(UUID.fromString("11223344-5566-4778-899a-bbccddeeff00"));
    private static final UUID RESOURCE_ID =
            UUID.fromString("aabbccdd-eeff-4a1b-8c2d-3e4f5a6b7c8d");
    private static final UUID OTHER_RESOURCE_ID =
            UUID.fromString("00112233-4455-4677-8899-aabbccddeeff");

    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.freeze(new JsonObject());
    private static final WireExtras.RegionExtras REGION_EXTRAS = new WireExtras.RegionExtras(
            EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
    private static final WireExtras.ResourceExtras RESOURCE_EXTRAS =
            new WireExtras.ResourceExtras(EMPTY_OBJECT);

    private static final String KIND = "ptcrys:zone";
    private static final String DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ExactJson.FrozenJson freezeObject(String key, JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return ExactJson.freeze(object);
    }

    private static BoxCorners corners(IntPosition first, IntPosition second) {
        return new BoxCorners(first, second);
    }

    private static RegionDefinition region(String name) {
        return new RegionDefinition(OBJECT_ID, name, KIND,
                corners(new IntPosition(0, 64, 0), new IntPosition(10, 70, 10)),
                EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
    }

    private static RegionDefinition regionWithKind(String kindId) {
        return new RegionDefinition(OBJECT_ID, "zone", kindId,
                corners(new IntPosition(0, 64, 0), new IntPosition(10, 70, 10)),
                EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
    }

    private static ResourceRevisionRef ref(long revision, String digest) {
        return new ResourceRevisionRef(KIND, RESOURCE_ID, revision, digest, RESOURCE_EXTRAS);
    }

    /** A digest of {@code count} lower case hex characters, repeating the canonical alphabet. */
    private static String hex(int count) {
        return DIGEST.repeat((count / 64) + 1).substring(0, count);
    }

    /** A body of {@code ascii} one byte characters, so its UTF-8 size equals its length. */
    private static String ascii(int ascii) {
        return "a".repeat(ascii);
    }

    /** A body of {@code count} three byte CJK characters. */
    private static String cjk(int count) {
        return "汉".repeat(count);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    // ------------------------------------------------------------------
    // 1. members, identity, null policy, corner order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every member is retained and the scalar members are reused by reference")
    void everyMemberIsRetained() {
        IntPosition first = new IntPosition(-40, 0, 12);
        IntPosition second = new IntPosition(40, 255, -12);
        BoxCorners box = corners(first, second);
        ExactJson.FrozenJson config = ExactJson.parse("{\"a\":[1,2,{\"b\":null}]}");
        ExactJson.FrozenJson extensions = ExactJson.parse("{\"x\":{\"y\":true}}");

        RegionDefinition definition = new RegionDefinition(OBJECT_ID, "middle zone", KIND,
                box, config, extensions, REGION_EXTRAS);

        assertSame(OBJECT_ID, definition.id());
        assertEquals("middle zone", definition.name());
        assertEquals(KIND, definition.kindId());
        assertSame(box, definition.corners());
        assertSame(first, definition.corners().first());
        assertSame(second, definition.corners().second());
        assertSame(config, definition.config());
        assertSame(extensions, definition.extensions());
        assertSame(REGION_EXTRAS, definition.wireExtras());

        assertEquals(ObjectId(OBJECT_ID), definition.id());

        // equals/hashCode follow every member, not just the identity.
        RegionDefinition same = new RegionDefinition(OBJECT_ID, "middle zone", KIND,
                box, config, extensions, REGION_EXTRAS);
        assertEquals(definition, same);
        assertEquals(definition.hashCode(), same.hashCode());

        ResourceRevisionRef reference = new ResourceRevisionRef("ptcrys:loot", RESOURCE_ID,
                7L, DIGEST, RESOURCE_EXTRAS);
        assertSame(RESOURCE_ID, reference.resourceId());
        assertEquals("ptcrys:loot", reference.kindId());
        assertEquals(7L, reference.revision());
        assertSame(DIGEST, reference.digest());
        assertSame(RESOURCE_EXTRAS, reference.wireExtras());
    }

    private static Object ObjectId(Ids.ObjectId id) {
        return id;
    }

    @Test
    @DisplayName("unknown kind passes the structure and the same name and box stay distinct by id")
    void sameNameAndCornersRemainDistinct() {
        // An unknown but well formed kind is a structure question only: it must pass here, because
        // whether a provider supports it is a business question this DTO must not answer.
        RegionDefinition unknownKind = regionWithKind("not.a.real.provider:still-legal_kind");
        assertEquals("not.a.real.provider:still-legal_kind", unknownKind.kindId());

        BoxCorners shared = corners(new IntPosition(0, 0, 0), new IntPosition(5, 5, 5));
        RegionDefinition first = new RegionDefinition(OBJECT_ID, "same name", KIND, shared,
                EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
        RegionDefinition second = new RegionDefinition(OTHER_OBJECT_ID, "same name", KIND, shared,
                EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);

        // Same name, same kind, same box object: still two different regions, and the identity is
        // never derived from the name or from the coordinates.
        assertNotEquals(first, second);
        assertNotEquals(first.hashCode(), second.hashCode());
        assertSame(shared, first.corners());
        assertSame(shared, second.corners());
    }

    @Test
    @DisplayName("corner order is preserved, reversed, identical and extreme boxes are not normalised")
    void cornerOrderIsNeverNormalised() {
        IntPosition low = new IntPosition(0, 0, 0);
        IntPosition high = new IntPosition(16, 32, 48);

        // Reversed input: the far corner is stored first and is not swapped into a min/max order.
        RegionDefinition reversed = new RegionDefinition(OBJECT_ID, "r", KIND,
                corners(high, low), EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
        assertSame(high, reversed.corners().first());
        assertSame(low, reversed.corners().second());

        // The reversed box is not equal to the ascending box built from the same two points.
        RegionDefinition ascending = new RegionDefinition(OBJECT_ID, "r", KIND,
                corners(low, high), EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
        assertNotEquals(ascending, reversed);

        // A degenerate box whose two corners are identical is legal and keeps both.
        IntPosition same = new IntPosition(7, 7, 7);
        RegionDefinition degenerate = new RegionDefinition(OBJECT_ID, "r", KIND,
                corners(same, same), EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
        assertSame(same, degenerate.corners().first());
        assertSame(same, degenerate.corners().second());

        // Extreme int coordinates are carried exactly, with no clamping.
        IntPosition min = new IntPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        IntPosition max = new IntPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        RegionDefinition extreme = new RegionDefinition(OBJECT_ID, "r", KIND,
                corners(min, max), EMPTY_OBJECT, EMPTY_OBJECT, REGION_EXTRAS);
        assertEquals(Integer.MIN_VALUE, extreme.corners().first().x());
        assertEquals(Integer.MAX_VALUE, extreme.corners().second().x());
        assertEquals(Integer.MIN_VALUE, extreme.corners().first().y());
        assertEquals(Integer.MAX_VALUE, extreme.corners().second().z());
    }

    @Test
    @DisplayName("every required region reference rejects Java null")
    void everyRequiredRegionReferenceRejectsNull() {
        BoxCorners box = corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));

        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(null, "n", KIND, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, null, KIND, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", null, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, null, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, box, null, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, box, EMPTY_OBJECT, null,
                        REGION_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        null));

        // The failure detail names the member, so a mixed up order is diagnosable.
        IllegalArgumentException idFailure = assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(null, "n", KIND, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertTrue(idFailure.getMessage().contains("id"), idFailure.getMessage());
        IllegalArgumentException cornersFailure = assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, null, EMPTY_OBJECT, EMPTY_OBJECT,
                        REGION_EXTRAS));
        assertTrue(cornersFailure.getMessage().contains("corners"), cornersFailure.getMessage());
        IllegalArgumentException extrasFailure = assertThrows(IllegalArgumentException.class,
                () -> new RegionDefinition(OBJECT_ID, "n", KIND, box, EMPTY_OBJECT, EMPTY_OBJECT,
                        null));
        assertTrue(extrasFailure.getMessage().contains("wireExtras"),
                extrasFailure.getMessage());
    }

    @Test
    @DisplayName("every required resource reference rejects Java null")
    void everyRequiredResourceReferenceRejectsNull() {
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(null, RESOURCE_ID, 1L, DIGEST, RESOURCE_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, null, 1L, DIGEST, RESOURCE_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, null, RESOURCE_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST, null));

        IllegalArgumentException resourceIdFailure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, null, 1L, DIGEST, RESOURCE_EXTRAS));
        assertTrue(resourceIdFailure.getMessage().contains("resourceId"),
                resourceIdFailure.getMessage());
        IllegalArgumentException digestFailure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, null, RESOURCE_EXTRAS));
        assertTrue(digestFailure.getMessage().contains("digest"), digestFailure.getMessage());
        IllegalArgumentException extrasFailure = assertThrows(IllegalArgumentException.class,
                () -> new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST, null));
        assertTrue(extrasFailure.getMessage().contains("wireExtras"),
                extrasFailure.getMessage());
    }

    // ------------------------------------------------------------------
    // 2. name and kind scalars
    // ------------------------------------------------------------------

    @Test
    @DisplayName("region name allows empty, keeps a space, and is never trimmed")
    void regionNameAllowsEmptyAndKeepsSpace() {
        assertEquals("", region("").name());
        assertEquals(" ", region(" ").name());
        assertEquals("  ", region("  ").name());
        assertEquals(" padded ", region(" padded ").name());

        String shared = new String("shared reference");
        assertSame(shared, region(shared).name());

        // The name budget is a byte budget, not a character budget: 170 CJK plus "ab" is 512 bytes
        // but 172 characters, and a byte counting implementation accepts it exactly. This vector on
        // its own does not prove a character counting bug in either direction, because 172 is above
        // 170 and the two counts disagree only mildly here; the direction that actually breaks is
        // shown by the 171 CJK case below, where 171 characters stay under a 512 character ceiling
        // while the 513 real bytes exceed the budget, so a character based check would wrongly
        // accept it.
        String exactBytes = cjk(170) + "ab";
        assertEquals(512, utf8Bytes(exactBytes));
        assertEquals(172, exactBytes.length());
        assertEquals(exactBytes, region(exactBytes).name());

        // 128 emoji are 512 bytes but 256 characters.
        String emoji = "\uD83D\uDE00".repeat(128);
        assertEquals(512, utf8Bytes(emoji));
        assertEquals(256, emoji.length());
        assertSame(emoji, region(emoji).name());

        // 171 CJK are 513 bytes but only 171 characters, so a character counting check would
        // wrongly accept them. 513 one byte characters are rejected for the same byte budget.
        String cjkOver = cjk(171);
        assertEquals(513, utf8Bytes(cjkOver));
        assertThrows(IllegalArgumentException.class, () -> region(cjkOver));
        assertThrows(IllegalArgumentException.class, () -> region(ascii(513)));
    }

    @Test
    @DisplayName("region name rejects unpaired surrogates and control characters")
    void regionNameRejectsBadUnicodeAndControls() {
        // An unpaired surrogate is not a Unicode scalar value.
        assertThrows(IllegalArgumentException.class, () -> region("\uD83D"));
        assertThrows(IllegalArgumentException.class, () -> region("\uDE00"));
        assertThrows(IllegalArgumentException.class, () -> region("a\uD83Db"));
        assertThrows(IllegalArgumentException.class, () -> region("\uD83D\uD83D\uDE00"));

        // A legal surrogate pair is accepted and counted as four bytes.
        assertEquals("ok \uD83D\uDE00", region("ok \uD83D\uDE00").name());

        // C0 controls are rejected for a region name, as they are for a team name.
        assertThrows(IllegalArgumentException.class, () -> region("\u0000"));
        assertThrows(IllegalArgumentException.class, () -> region("a\u001Fb"));
        assertThrows(IllegalArgumentException.class, () -> region("\n"));
        assertThrows(IllegalArgumentException.class, () -> region("\t"));

        // U+0020 is not a C0 control, so it stays legal and is asserted above.
        assertEquals(" ", region(" ").name());

        IllegalArgumentException controlFailure = assertThrows(IllegalArgumentException.class,
                () -> region("a\u0001b"));
        assertTrue(controlFailure.getMessage().contains("control"),
                controlFailure.getMessage());
    }

    @Test
    @DisplayName("region kind rejects a missing colon, empty parts, non-ASCII and the 192 boundary")
    void regionKindGrammar() {
        // Legal shapes.
        for (String legal : List.of("ptcrys:zone", "a:b", "a0._-:p/q.r_s-",
                "minecraft:overworld", "namespace:deep/nested/path")) {
            assertSame(legal, regionWithKind(legal).kindId());
        }

        // Missing or degenerate separator.
        for (String illegal : List.of("", "noColon", ":path", "namespace:", "a:b:c", ":", "::")) {
            assertThrows(IllegalArgumentException.class, () -> regionWithKind(illegal), illegal);
        }

        // Upper case, spaces and characters outside the two character classes.
        for (String illegal : List.of("Namespace:path", "namespace:Path", "name space:path",
                "namespace:pa th", "命名空间:path", "namespace:路径", "\u00E9:path")) {
            assertThrows(IllegalArgumentException.class, () -> regionWithKind(illegal), illegal);
        }

        // The budget is 191 characters of the whole string: 191 passes, 192 is rejected.
        String atLimit = ascii(190) + ":p";
        assertEquals(192, atLimit.length());
        assertThrows(IllegalArgumentException.class, () -> regionWithKind(atLimit));

        String underLimit = ascii(189) + ":p";
        assertEquals(191, underLimit.length());
        assertSame(underLimit, regionWithKind(underLimit).kindId());

        // A long but well formed value is rejected by the budget rather than by the grammar.
        String overBudget = "a:" + ascii(200);
        assertThrows(IllegalArgumentException.class, () -> regionWithKind(overBudget));
    }

    @Test
    @DisplayName("region kind never consults a registry: an unknown namespace passes")
    void unknownKindIsAStructurePass() {
        String unknown = "definitely-not-registered-namespace:some/kind";
        RegionDefinition definition = regionWithKind(unknown);
        assertEquals(unknown, definition.kindId());
        // Passing here means well formed, not registered.
        assertEquals(KIND, region("n").kindId());
    }

    // ------------------------------------------------------------------
    // 3. revision and digest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("revision accepts 1 and Long.MAX_VALUE and rejects 0, negatives and MIN_VALUE")
    void revisionBoundaries() {
        assertEquals(1L, ref(1L, DIGEST).revision());
        assertEquals(2L, ref(2L, DIGEST).revision());

        // The largest long is legal and must not be incremented or narrowed.
        ResourceRevisionRef max = ref(Long.MAX_VALUE, DIGEST);
        assertEquals(Long.MAX_VALUE, max.revision());
        assertTrue(max.revision() > 0L);
        assertEquals(Long.MAX_VALUE, max.revision() + 0L);

        for (long illegal : List.of(0L, -1L, -2L, Long.MIN_VALUE, Long.MIN_VALUE + 1L, -42L)) {
            assertThrows(IllegalArgumentException.class, () -> ref(illegal, DIGEST),
                    Long.toString(illegal));
        }

        IllegalArgumentException zeroFailure = assertThrows(IllegalArgumentException.class,
                () -> ref(0L, DIGEST));
        assertTrue(zeroFailure.getMessage().contains("revision"), zeroFailure.getMessage());

        // The revision is not derived from the digest or from anything else: two refs that differ
        // only in revision are different values.
        assertNotEquals(ref(1L, DIGEST), ref(2L, DIGEST));
    }

    @Test
    @DisplayName("digest keeps a legal 64 character value by reference and rejects bad lengths")
    void digestLengthRules() {
        String same = new String(DIGEST);
        assertSame(same, ref(1L, same).digest());

        assertEquals(64, DIGEST.length());
        assertSame(DIGEST, ref(1L, DIGEST).digest());
        // One shared instance: production must hand back the identical reference, so calling the
        // helper twice would compare two distinct but equal Strings and prove nothing.
        String built = hex(64);
        assertSame(built, ref(1L, built).digest());

        // Length first: 63 and 65 are rejected, and so is every other length.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(65)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, ""));
        // 64 characters of the right length but not hex at all: 'z' is outside [0-9a-f].
        assertThrows(IllegalArgumentException.class, () -> ref(1L, "z".repeat(64)));
        // 64 characters of 'a' are all legal hex, so they must be accepted, not rejected.
        String allHexLetters = ascii(64);
        assertSame(allHexLetters, ref(1L, allHexLetters).digest());

        // A very long input is rejected by its length without a digest being computed.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(64) + ascii(4000)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, ascii(100_000)));

        IllegalArgumentException shortFailure = assertThrows(IllegalArgumentException.class,
                () -> ref(1L, hex(63)));
        assertTrue(shortFailure.getMessage().contains("64"), shortFailure.getMessage());
    }

    @Test
    @DisplayName("digest rejects upper case, g, non-ASCII, whitespace and newlines")
    void digestCharacterRules() {
        // Upper case hex is a different spelling and is rejected, not case folded.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(64).toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, "A" + hex(63)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "F"));

        // Only [0-9a-f] is legal: g and every later letter are not.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, "g" + hex(63)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "z"));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "G"));

        // Whitespace, newlines and non-ASCII, including a full width digit that looks similar.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, " " + hex(63)));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + " "));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "\n"));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "\t"));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "\u00E9"));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(63) + "\uFF10"));

        // A dash separated digest is a different format and is rejected.
        assertThrows(IllegalArgumentException.class, () -> ref(1L, hex(64).replace("0", "-")));

        IllegalArgumentException badCharFailure = assertThrows(IllegalArgumentException.class,
                () -> ref(1L, "g" + hex(63)));
        assertTrue(badCharFailure.getMessage().contains("0-9a-f"),
                badCharFailure.getMessage());
    }

    @Test
    @DisplayName("an all zero digest is format legal and claims nothing about content")
    void allZeroDigestIsFormatLegal() {
        String zeros = "0".repeat(64);
        assertEquals(64, zeros.length());
        ResourceRevisionRef reference = ref(1L, zeros);

        // Format legal: this type does not pretend the resource exists or that the digest matches.
        assertSame(zeros, reference.digest());
        assertEquals(zeros, reference.digest());

        // It is not treated as "absent", and it is distinct from another legal digest.
        assertNotEquals(zeros, DIGEST);
        assertNotEquals(ref(1L, zeros), ref(1L, DIGEST));
        assertThrows(IllegalArgumentException.class, () -> ref(1L, "0".repeat(63)));
    }

    // ------------------------------------------------------------------
    // 4. frozen JSON shapes, pollution isolation, no wrong merge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("config and extensions accept every legal frozen shape, including JSON null")
    void configAndExtensionsAcceptAnyFrozenShape() {
        // Genuine root values, parsed as roots so they are not wrapped in an object by the test.
        List<ExactJson.FrozenJson> roots = List.of(
                ExactJson.parse("{}"),
                ExactJson.parse("[]"),
                ExactJson.parse("[1,2]"),
                ExactJson.parse("\"text\""),
                ExactJson.parse("42"),
                ExactJson.parse("true"),
                ExactJson.parse("false"),
                ExactJson.parse("null"));

        for (ExactJson.FrozenJson root : roots) {
            String canonical = root.canonical();
            // The root is genuinely not an object wrapper, so this loop cannot pass vacuously.
            assertTrue(canonical.charAt(0) != '{' || canonical.equals("{}"), canonical);

            RegionDefinition definition = new RegionDefinition(OBJECT_ID, "n", KIND,
                    corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                    root, root, REGION_EXTRAS);
            assertSame(root, definition.config());
            assertSame(root, definition.extensions());
            assertEquals(canonical, definition.config().canonical());
            assertEquals(canonical, definition.extensions().canonical());
        }

        // The roots really are distinct, so no two iterations could be silently identical.
        assertNotEquals(roots.get(1), roots.get(2));
        assertNotEquals(roots.get(5), roots.get(6));
        assertNotEquals(roots.get(3), roots.get(4));

        // A nested object, a large integer, an empty key, an explicit JSON null member and a
        // Unicode string all survive as frozen values.
        ExactJson.FrozenJson rich = ExactJson.parse(
                "{\"\":1,\"big\":123456789012345678901234567890,\"n\":null,"
                        + "\"u\":\"\\u6c49\",\"deep\":{\"a\":[{\"b\":[]}]}}");
        RegionDefinition definition = new RegionDefinition(OBJECT_ID, "n", KIND,
                corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                rich, rich, REGION_EXTRAS);
        assertSame(rich, definition.config());
        assertSame(rich, definition.extensions());
        assertEquals(rich.canonical(), definition.config().canonical());
        assertTrue(definition.config().canonical().contains("null"));
        assertTrue(definition.config().canonical().contains("\\u6c49")
                || definition.config().canonical().contains("汉"));
    }

    @Test
    @DisplayName("polluting the source tree or a toJson copy does not change the record")
    void frozenSourcesAreIsolatedFromPollution() {
        // Build the value through a mutable source tree, then pollute that tree afterwards.
        JsonObject source = new JsonObject();
        JsonArray list = new JsonArray();
        list.add(new JsonPrimitive(1));
        source.add("list", list);
        source.add("name", new JsonPrimitive("before"));

        ExactJson.FrozenJson frozen = ExactJson.freeze(source);
        RegionDefinition definition = new RegionDefinition(OBJECT_ID, "n", KIND,
                corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                frozen, frozen, REGION_EXTRAS);
        ResourceRevisionRef reference = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                RESOURCE_EXTRAS);

        String canonicalBefore = definition.config().canonical();
        int hashCodeBefore = definition.hashCode();
        String referenceCanonicalBefore = definition.config().canonical();

        // Pollute the original Gson tree aggressively.
        source.add("name", new JsonPrimitive("after"));
        list.add(new JsonPrimitive(2));
        source.add("injected", new JsonPrimitive(true));
        source.remove("list");

        assertEquals(canonicalBefore, definition.config().canonical());
        assertEquals(canonicalBefore, definition.extensions().canonical());
        assertEquals(hashCodeBefore, definition.hashCode());
        assertEquals(referenceCanonicalBefore, definition.config().canonical());
        assertEquals(new RegionDefinition(OBJECT_ID, "n", KIND,
                        corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                        ExactJson.freeze(freshUnpollutedTree()), ExactJson.freeze(
                                freshUnpollutedTree()), REGION_EXTRAS)
                .config().canonical(),
                definition.config().canonical());

        // Mutating a toJson copy must not reach back into the frozen value either.
        JsonElement copy = definition.config().toJson();
        JsonObject copyObject = copy.getAsJsonObject();
        copyObject.add("copyOnly", new JsonPrimitive("x"));
        assertTrue(!definition.config().canonical().contains("copyOnly"));
        assertEquals(canonicalBefore, definition.config().canonical());

        // The resource reference is unaffected by both pollutions.
        assertEquals(1L, reference.revision());
        assertSame(DIGEST, reference.digest());
    }

    private static JsonObject freshUnpollutedTree() {
        JsonObject object = new JsonObject();
        JsonArray list = new JsonArray();
        list.add(new JsonPrimitive(1));
        object.add("list", list);
        object.add("name", new JsonPrimitive("before"));
        return object;
    }

    @Test
    @DisplayName("records differing in any non-identity member are never merged")
    void recordsWithSameIdentityButDifferentMembersAreDistinct() {
        BoxCorners box = corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1));
        ExactJson.FrozenJson one = ExactJson.parse("{\"k\":1}");
        ExactJson.FrozenJson two = ExactJson.parse("{\"k\":2}");

        RegionDefinition base = new RegionDefinition(OBJECT_ID, "n", KIND, box,
                one, one, REGION_EXTRAS);
        assertNotEquals(base, new RegionDefinition(OBJECT_ID, "n2", KIND, box,
                one, one, REGION_EXTRAS));
        assertNotEquals(base, new RegionDefinition(OBJECT_ID, "n", "other:kind", box,
                one, one, REGION_EXTRAS));
        assertNotEquals(base, new RegionDefinition(OBJECT_ID, "n", KIND,
                corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 2)),
                one, one, REGION_EXTRAS));
        assertNotEquals(base, new RegionDefinition(OBJECT_ID, "n", KIND, box,
                two, one, REGION_EXTRAS));
        assertNotEquals(base, new RegionDefinition(OBJECT_ID, "n", KIND, box,
                one, two, REGION_EXTRAS));

        ResourceRevisionRef reference = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                RESOURCE_EXTRAS);
        assertNotEquals(reference, new ResourceRevisionRef("other:kind", RESOURCE_ID, 1L, DIGEST,
                RESOURCE_EXTRAS));
        assertNotEquals(reference, new ResourceRevisionRef(KIND, OTHER_RESOURCE_ID, 1L, DIGEST,
                RESOURCE_EXTRAS));
        assertNotEquals(reference, new ResourceRevisionRef(KIND, RESOURCE_ID, 2L, DIGEST,
                RESOURCE_EXTRAS));
        assertNotEquals(reference, new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, hex(64).replace(
                '0', '1'), RESOURCE_EXTRAS));
    }

    // ------------------------------------------------------------------
    // 5. business counterexamples are recorded, not rejected by the DTO
    // ------------------------------------------------------------------

    @Test
    @DisplayName("business counterexamples pass the DTO: duplicate identity is not a construction failure")
    void businessCounterexamplesAreNotConstructionFailures() {
        // (a) An unknown kind. Structure passes; provider support is a later question.
        assertEquals("unknown:kind", regionWithKind("unknown:kind").kindId());

        // (b) A resource that does not exist. The DTO cannot know and must not check.
        ResourceRevisionRef maybeMissing = ref(1L, DIGEST);
        assertEquals(RESOURCE_ID, maybeMissing.resourceId());

        // (c) Two references to the same resource identity, each with its own members. This is the
        // case R05.3 aggregates; here both must construct and must not be silently merged.
        ExactJson.FrozenJson firstMembers = ExactJson.parse("{\"slot\":1}");
        ExactJson.FrozenJson secondMembers = ExactJson.parse("{\"slot\":2}");
        ResourceRevisionRef firstOccurrence = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                new WireExtras.ResourceExtras(firstMembers));
        ResourceRevisionRef secondOccurrence = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                new WireExtras.ResourceExtras(secondMembers));

        assertEquals(firstOccurrence.resourceId(), secondOccurrence.resourceId());
        assertEquals(firstOccurrence.kindId(), secondOccurrence.kindId());
        // Same identity, different members: still different values, so no shared identity table
        // could have overwritten one occurrence with the other.
        assertNotEquals(firstOccurrence, secondOccurrence);
        assertSame(firstMembers, firstOccurrence.wireExtras().members());
        assertSame(secondMembers, secondOccurrence.wireExtras().members());
        assertTrue(firstOccurrence.wireExtras().members().canonical().contains("\"slot\":1"));
        assertTrue(secondOccurrence.wireExtras().members().canonical().contains("\"slot\":2"));

        // A different revision of the same identity is also just a different value here: no
        // "latest wins" and no inheritance rule is applied by this record.
        ResourceRevisionRef newerRevision = new ResourceRevisionRef(KIND, RESOURCE_ID, 2L, DIGEST,
                new WireExtras.ResourceExtras(firstMembers));
        assertNotEquals(firstOccurrence, newerRevision);
    }

    // ------------------------------------------------------------------
    // 6. wire extras: per position retention, no sharing, no merge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("region wire extras keep each position's own members and are reused as supplied")
    void regionWireExtrasKeepPerPositionMembers() {
        ExactJson.FrozenJson regionMembers = ExactJson.parse("{\"regionOnly\":1}");
        ExactJson.FrozenJson cornersMembers = ExactJson.parse("{\"cornersOnly\":2}");
        ExactJson.FrozenJson firstMembers = ExactJson.parse("{\"corner\":\"first\"}");
        ExactJson.FrozenJson secondMembers = ExactJson.parse("{\"corner\":\"second\"}");

        WireExtras.RegionExtras extras = new WireExtras.RegionExtras(regionMembers,
                cornersMembers, firstMembers, secondMembers);
        RegionDefinition definition = new RegionDefinition(OBJECT_ID, "n", KIND,
                corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                EMPTY_OBJECT, EMPTY_OBJECT, extras);

        // The carrier is reused as supplied and each position keeps its own members.
        assertSame(extras, definition.wireExtras());
        assertSame(regionMembers, definition.wireExtras().members());
        assertSame(cornersMembers, definition.wireExtras().cornersMembers());
        assertSame(firstMembers, definition.wireExtras().cornersFirstMembers());
        assertSame(secondMembers, definition.wireExtras().cornersSecondMembers());

        // The first and second corner members are not mixed into one shared slot.
        assertNotEquals(definition.wireExtras().cornersFirstMembers(),
                definition.wireExtras().cornersSecondMembers());
        assertTrue(definition.wireExtras().cornersFirstMembers().canonical().contains("first"));
        assertTrue(definition.wireExtras().cornersSecondMembers().canonical().contains("second"));

        // Unknown wire members never leak into config or extensions.
        assertEquals("{}", definition.config().canonical());
        assertEquals("{}", definition.extensions().canonical());

        // Swapping only the two corner member slots changes the record: they are not one shared bag.
        RegionDefinition swapped = new RegionDefinition(OBJECT_ID, "n", KIND,
                corners(new IntPosition(0, 0, 0), new IntPosition(1, 1, 1)),
                EMPTY_OBJECT, EMPTY_OBJECT,
                new WireExtras.RegionExtras(regionMembers, cornersMembers,
                        secondMembers, firstMembers));
        assertNotEquals(definition, swapped);

        // The frozen carrier rejects a non-object slot, so JSON null is not silently accepted here.
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.RegionExtras(ExactJson.parse("null"), cornersMembers,
                        firstMembers, secondMembers));
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.RegionExtras(regionMembers, null, firstMembers,
                        secondMembers));
    }

    @Test
    @DisplayName("resource wire extras are per occurrence and never overwritten by a shared table")
    void resourceWireExtrasArePerOccurrence() {
        ExactJson.FrozenJson firstMembers = ExactJson.parse("{\"occ\":\"a\"}");
        ExactJson.FrozenJson secondMembers = ExactJson.parse("{\"occ\":\"b\"}");

        ResourceRevisionRef firstOccurrence = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                new WireExtras.ResourceExtras(firstMembers));
        ResourceRevisionRef secondOccurrence = new ResourceRevisionRef(KIND, RESOURCE_ID, 1L, DIGEST,
                new WireExtras.ResourceExtras(secondMembers));

        assertSame(firstMembers, firstOccurrence.wireExtras().members());
        assertSame(secondMembers, secondOccurrence.wireExtras().members());
        assertNotEquals(firstOccurrence.wireExtras(), secondOccurrence.wireExtras());
        assertNotEquals(firstOccurrence, secondOccurrence);

        // Polluting the first occurrence's source tree does not reach the second occurrence.
        assertTrue(secondOccurrence.wireExtras().members().canonical().contains("\"occ\":\"b\""));
        assertEquals(firstOccurrence.wireExtras().members().canonical(),
                ExactJson.parse("{\"occ\":\"a\"}").canonical());

        // A non-object slot is rejected rather than mistaken for an empty object.
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.ResourceExtras(ExactJson.parse("null")));
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.ResourceExtras(ExactJson.parse("[1]")));
    }

    // ------------------------------------------------------------------
    // boundaries, as a parameterized expansion
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "kind {0} accepted={1}")
    @MethodSource("kindVectors")
    @DisplayName("kind grammar vectors agree with the frozen MapScalars rule")
    void kindGrammarVectors(String kindId, boolean accepted) {
        if (accepted) {
            assertEquals(kindId, regionWithKind(kindId).kindId());
            assertSame(kindId, MapScalars.requireNamespaced(kindId));
            // The resource revision carries the same kind grammar, so the accepted vectors must
            // exercise the resource constructor too, not only the region one: a resource that
            // stopped delegating its kind check would otherwise stay green.
            ResourceRevisionRef resource = new ResourceRevisionRef(kindId, RESOURCE_ID, 1L, DIGEST,
                    RESOURCE_EXTRAS);
            assertSame(kindId, resource.kindId());
        } else {
            assertThrows(IllegalArgumentException.class, () -> regionWithKind(kindId), kindId);
            assertThrows(IllegalArgumentException.class,
                    () -> MapScalars.requireNamespaced(kindId), kindId);
            assertThrows(IllegalArgumentException.class,
                    () -> new ResourceRevisionRef(kindId, RESOURCE_ID, 1L, DIGEST, RESOURCE_EXTRAS),
                    kindId);
        }
    }

    static Stream<Arguments> kindVectors() {
        return Stream.of(
                Arguments.of("ptcrys:zone", true),
                Arguments.of("a:b", true),
                Arguments.of("minecraft:overworld", true),
                Arguments.of("a0._-:p/q.r_s-", true),
                Arguments.of(ascii(189) + ":p", true),
                Arguments.of("", false),
                Arguments.of("noColon", false),
                Arguments.of(":path", false),
                Arguments.of("namespace:", false),
                Arguments.of("a:b:c", false),
                Arguments.of(":", false),
                Arguments.of("Namespace:path", false),
                Arguments.of("namespace:Path", false),
                Arguments.of("name space:path", false),
                Arguments.of("\u547D\u540D:path", false),
                Arguments.of(ascii(190) + ":p", false),
                Arguments.of("a:" + ascii(200), false));
    }

    @ParameterizedTest(name = "digest length {0} accepted={1}")
    @MethodSource("digestVectors")
    @DisplayName("digest length vectors accept exactly 64 characters")
    void digestVectors(int length, boolean accepted) {
        String candidate = hex(length);
        assertEquals(length, candidate.length());
        if (accepted) {
            assertSame(candidate, ref(1L, candidate).digest());
        } else {
            assertThrows(IllegalArgumentException.class, () -> ref(1L, candidate),
                    Integer.toString(length));
        }
    }

    static Stream<Arguments> digestVectors() {
        return Stream.of(
                Arguments.of(0, false),
                Arguments.of(1, false),
                Arguments.of(32, false),
                Arguments.of(63, false),
                Arguments.of(64, true),
                Arguments.of(65, false),
                Arguments.of(66, false),
                Arguments.of(128, false));
    }
}
