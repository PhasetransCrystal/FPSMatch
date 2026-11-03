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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import net.ptcrys.fpsmatch.editor.common.model.Ids;
import net.ptcrys.fpsmatch.editor.common.model.WorldRef;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Orientation;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Formal contract tests for the R05.1 domain structures {@link TeamDefinition} and
 * {@link SpawnDefinition}.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests of the two
 * records and of the package private {@link MapScalars} rules they consume. They cover exactly the
 * vectors the task card fixes: member retention and null rejection, the typed identity equivalence
 * and the same-name / same-coordinate distinct-identity case, the empty versus space name policy,
 * the UTF-8 byte budgets and their boundaries, surrogate pairing, the C0 control rule for names and
 * its deliberate absence for descriptions, the dimension and namespaced grammar compared against
 * the frozen {@code WorldRef}, the raw-bit signed zero rule of the five geometry components and the
 * identity reuse of untouched geometry, the isolation of frozen JSON from a polluted source tree,
 * and the per-position retention of wire extras.</p>
 *
 * <p><strong>What this class is not.</strong> A green run here is author-side evidence only. It is
 * not the P05-TEAM-SPAWN gate, which only the primary agent can grant, and it proves nothing about
 * the complete P05 milestone, which still requires the region, resource and map definition points
 * plus their cross references, counting and total payload budget. Nothing here claims wire
 * round-tripping, codec merge, reserved key handling, provider schema validation, storage or a
 * mature UI.</p>
 *
 * <p><strong>Assertion discipline.</strong> Every rejection asserts {@link IllegalArgumentException}
 * itself, and every message assertion checks only stable fragments the production javadoc promises,
 * such as the member name; no test asserts a whole message sentence. Byte budgets are asserted from
 * the real encoding, so no test substitutes a Java character count for a UTF-8 byte count, and the
 * helpers are asserted to return the identical instance rather than an equal copy, which is what
 * makes "no trim and no normalisation" observable.</p>
 */
@DisplayName("R05.1 team and spawn domain contract")
class TeamSpawnContractTest {

    private static final Ids.TeamId TEAM_ID = new Ids.TeamId(UUID.fromString("1a2b3c4d-5e6f-4a8b-9c0d-1e2f3a4b5c6d"));
    private static final Ids.TeamId OTHER_TEAM_ID = new Ids.TeamId(UUID.fromString("9f8e7d6c-5b4a-4938-8271-605f4e3d2c1b"));
    private static final Ids.ObjectId OBJECT_ID = new Ids.ObjectId(UUID.fromString("0f1e2d3c-4b5a-4697-8879-9a0b1c2d3e4f"));
    private static final Ids.ObjectId OTHER_OBJECT_ID = new Ids.ObjectId(UUID.fromString("11223344-5566-4778-899a-bbccddeeff00"));

    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.freeze(new JsonObject());
    private static final WireExtras.TeamExtras TEAM_EXTRAS =
            new WireExtras.TeamExtras(EMPTY_OBJECT);
    private static final WireExtras.SpawnExtras SPAWN_EXTRAS =
            new WireExtras.SpawnExtras(EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static ExactJson.FrozenJson freezeObject(String key, JsonElement value) {
        JsonObject object = new JsonObject();
        object.add(key, value);
        return ExactJson.freeze(object);
    }

    private static TeamDefinition team(String name) {
        return new TeamDefinition(TEAM_ID, name, EMPTY_OBJECT, EMPTY_OBJECT, TEAM_EXTRAS);
    }

    private static SpawnDefinition spawn(Position position, Orientation orientation) {
        return new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID, "minecraft:overworld",
                position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS);
    }

    /** A body of {@code ascii} one byte characters, so its UTF-8 size equals its length. */
    private static String ascii(int ascii) {
        return "a".repeat(ascii);
    }

    /** A body of {@code count} three byte CJK characters. */
    private static String cjk(int count) {
        return "汉".repeat(count);
    }

    /** A body of {@code count} four byte emoji, each a legal surrogate pair. */
    private static String emoji(int count) {
        return "\uD83D\uDE00".repeat(count);
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private static long rawBits(double value) {
        return Double.doubleToRawLongBits(value);
    }

    private static int rawBits(float value) {
        return Float.floatToRawIntBits(value);
    }

    private static boolean isNegativeZero(double value) {
        return rawBits(value) == Long.MIN_VALUE;
    }

    private static boolean isNegativeZero(float value) {
        return rawBits(value) == Integer.MIN_VALUE;
    }

    // ------------------------------------------------------------------
    // 1. members, identity, null policy
    // ------------------------------------------------------------------

    @Test
    @DisplayName("team members are retained verbatim and typed ids are value equivalent")
    void teamMembersAreRetained() {
        ExactJson.FrozenJson config = freezeObject("rounds", new JsonPrimitive(3));
        ExactJson.FrozenJson extensions = freezeObject("futureFlag", new JsonPrimitive(true));
        TeamDefinition team = new TeamDefinition(TEAM_ID, "Red", config, extensions, TEAM_EXTRAS);

        assertEquals(TEAM_ID, team.id());
        assertEquals("Red", team.name());
        assertSame(config, team.config());
        assertSame(extensions, team.extensions());
        assertSame(TEAM_EXTRAS, team.wireExtras());

        // Typed identity equivalence: an independent instance of the same UUID is equal, and the
        // accessor returns the exact reference that was stored.
        Ids.TeamId sameValue = new Ids.TeamId(UUID.fromString(TEAM_ID.wire()));
        assertEquals(TEAM_ID, sameValue);
        assertNotSame(TEAM_ID, sameValue);
        assertSame(TEAM_ID, team.id());

        // Records implement value equality over every member.
        TeamDefinition copy = new TeamDefinition(sameValue, "Red", config, extensions, TEAM_EXTRAS);
        assertEquals(team, copy);
        assertEquals(team.hashCode(), copy.hashCode());
        assertEquals(team.toString(), copy.toString());
    }

    @Test
    @DisplayName("spawn members are retained verbatim and the same name may carry two identities")
    void spawnMembersAreRetainedAndIdentitiesAreIndependent() {
        Position position = new Position(1.5d, 64.0d, -2.5d);
        Orientation orientation = new Orientation(90.0f, 12.5f);
        ExactJson.FrozenJson extensions = freezeObject("note", new JsonPrimitive("hi"));
        SpawnDefinition spawn = new SpawnDefinition(OBJECT_ID, "north gate", TEAM_ID,
                "minecraft:overworld", position, orientation, extensions, SPAWN_EXTRAS);

        assertEquals(OBJECT_ID, spawn.id());
        assertEquals("north gate", spawn.name());
        assertEquals(TEAM_ID, spawn.teamId());
        assertEquals("minecraft:overworld", spawn.dimensionId());
        assertSame(position, spawn.position());
        assertSame(orientation, spawn.orientation());
        assertSame(extensions, spawn.extensions());
        assertSame(SPAWN_EXTRAS, spawn.wireExtras());

        // Same name, same coordinates, no team change: the two spawns are still distinct because
        // identity is never derived from content.
        SpawnDefinition other = new SpawnDefinition(OTHER_OBJECT_ID, "north gate", TEAM_ID,
                "minecraft:overworld", position, orientation, extensions, SPAWN_EXTRAS);
        assertNotEquals(spawn, other);
        assertEquals("north gate", other.name());
        assertEquals(position, other.position());
    }

    @Test
    @DisplayName("every required team reference rejects Java null")
    void teamNullMembersAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(null, "Red", EMPTY_OBJECT, EMPTY_OBJECT, TEAM_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, null, EMPTY_OBJECT, EMPTY_OBJECT, TEAM_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, "Red", null, EMPTY_OBJECT, TEAM_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, "Red", EMPTY_OBJECT, null, TEAM_EXTRAS));
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, "Red", EMPTY_OBJECT, EMPTY_OBJECT, null));

        // The failure detail names the offending member, which is what makes a null diagnosable
        // without a debugger.
        IllegalArgumentException idNull = assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(null, "Red", EMPTY_OBJECT, EMPTY_OBJECT, TEAM_EXTRAS));
        assertTrue(idNull.getMessage().contains("TeamDefinition.id"), idNull.getMessage());
        IllegalArgumentException nameNull = assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, null, EMPTY_OBJECT, EMPTY_OBJECT, TEAM_EXTRAS));
        assertTrue(nameNull.getMessage().contains("name"), nameNull.getMessage());
    }

    @Test
    @DisplayName("every required spawn reference rejects Java null, including empty name versus absent name")
    void spawnNullMembersAreRejected() {
        Position position = new Position(0.0d, 0.0d, 0.0d);
        Orientation orientation = new Orientation(0.0f, 0.0f);

        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(null, "s", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", null,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                null, position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                "minecraft:overworld", null, orientation, EMPTY_OBJECT, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                "minecraft:overworld", position, null, EMPTY_OBJECT, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                "minecraft:overworld", position, orientation, null, SPAWN_EXTRAS));
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, null));

        // A null name is a missing member and is rejected; an empty name is a legal, present name.
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, null, TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS));
        SpawnDefinition emptyName = new SpawnDefinition(OBJECT_ID, "", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS);
        assertEquals("", emptyName.name());
    }

    @Test
    @DisplayName("team name is non-empty but a space is a name; spawn name may be empty")
    void emptyVersusBlankNamePolicy() {
        assertThrows(IllegalArgumentException.class, () -> team(""));

        TeamDefinition blank = team(" ");
        assertEquals(" ", blank.name());

        TeamDefinition spaced = team("  Red  ");
        assertEquals("  Red  ", spaced.name());

        // A missing spawn name produced an empty string before this point; a null spawn name was
        // already rejected by spawnNullMembersAreRejected.
        SpawnDefinition emptySpawn = new SpawnDefinition(OBJECT_ID, "", TEAM_ID, "minecraft:overworld",
                new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f), EMPTY_OBJECT, SPAWN_EXTRAS);
        assertEquals("", emptySpawn.name());
    }

    @Test
    @DisplayName("MapScalars returns the identical reference and never trims or normalises")
    void scalarsReturnTheSameInstance() {
        String padded = "  Red  ";
        assertSame(padded, MapScalars.requireName(padded, true));

        // NFD: the combining ring is a separate code point and must survive byte for byte.
        String decomposed = "A\u030A";
        assertSame(decomposed, MapScalars.requireName(decomposed, true));
        assertEquals(3, utf8Bytes(decomposed));

        String description = "line one\n\tline two\r\n";
        assertSame(description, MapScalars.requireDescription(description));
        assertEquals(description, MapScalars.requireDescription(description));

        assertSame("minecraft:overworld", MapScalars.requireDimension("minecraft:overworld"));
        assertSame("custom:kind", MapScalars.requireNamespaced("custom:kind"));
        // Sizes are taken from this test's own independent counter, so the production rule is not
        // measured with its own arithmetic. The third case is a legal pair and counts as four bytes.
        assertEquals(0, utf8Bytes(""));
        assertEquals(512, utf8Bytes(ascii(512)));
        assertEquals(4, utf8Bytes("\uD83D\uDE00"));
    }

    // ------------------------------------------------------------------
    // 2. UTF-8 budgets, surrogate pairing, control characters
    // ------------------------------------------------------------------

    @Test
    @DisplayName("name UTF-8 budget accepts 512 bytes and rejects 513")
    void nameUtf8BudgetBoundary() {
        String exactly = ascii(512);
        assertEquals(512, utf8Bytes(exactly));
        assertSame(exactly, MapScalars.requireName(exactly, true));
        assertEquals(exactly, team(exactly).name());

        String over = ascii(513);
        assertEquals(513, utf8Bytes(over));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(over, false));
        assertThrows(IllegalArgumentException.class, () -> team(over));

        // 170 CJK characters are 510 bytes; two ASCII characters bring the value to exactly 512.
        String cjkBorder = cjk(170) + "ab";
        assertEquals(512, utf8Bytes(cjkBorder));
        assertSame(cjkBorder, MapScalars.requireName(cjkBorder, true));

        // The budget is bytes and not characters, so 170 CJK characters plus two ASCII characters
        // needs only 172 Java characters to reach the 512 byte limit.
        assertEquals(172, cjkBorder.length());
        assertNotEquals(cjkBorder.length(), utf8Bytes(cjkBorder));

        // 171 CJK characters are 513 bytes and must be rejected: the budget is bytes and not
        // characters, so 171 characters is already a rejection while 512 ASCII characters is not.
        String cjkOver = cjk(171);
        assertEquals(513, utf8Bytes(cjkOver));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(cjkOver, true));
        assertEquals(171, cjkOver.length());

        // 128 emoji are 512 bytes and pass; 129 are 516 bytes and are rejected.
        String emojiBorder = emoji(128);
        assertEquals(512, utf8Bytes(emojiBorder));
        assertEquals(256, emojiBorder.length());
        assertSame(emojiBorder, MapScalars.requireName(emojiBorder, true));

        String emojiOver = emoji(129);
        assertEquals(516, utf8Bytes(emojiOver));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(emojiOver, true));

        // The distance between the character count and the byte budget is asserted directly, which
        // is what forbids "count the characters instead" implementations.
        assertNotEquals(emojiBorder.length(), utf8Bytes(emojiBorder));

        // --- The budget must be decided in the same forward pass as the pairing check. ---
        //
        // Each value below has a prefix that already crossed the ceiling and a tail that also
        // carries an unpaired surrogate. Two different defects are therefore present, and the one
        // that occurs first while reading left to right is the budget. A implementation that scanned
        // the whole string for pairing before counting bytes would report the surrogate instead, and
        // would also do unbounded work on an input that is rejected as early as byte 513.
        //
        // The failure detail is matched on the local category word "budget" rather than the whole
        // sentence, so the assertion pins the category without freezing the exact wording.
        String overThenLoneHigh = ascii(513) + "\uD83D";
        IllegalArgumentException overHighFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireName(overThenLoneHigh, false));
        assertTrue(overHighFailure.getMessage().contains("budget"),
                "the budget must be reported, not the trailing surrogate: "
                        + overHighFailure.getMessage());

        String overThenLoneLow = ascii(513) + "\uDE00";
        IllegalArgumentException overLowFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireName(overThenLoneLow, false));
        assertTrue(overLowFailure.getMessage().contains("budget"),
                "the budget must be reported, not the trailing surrogate: "
                        + overLowFailure.getMessage());

        // A prefix that only reaches the ceiling exactly is still legal, so the same trailing lone
        // high surrogate must now be reported as the Unicode defect. This is the control that shows
        // the two cases really are distinguished by which defect comes first, and not by the
        // presence of a surrogate alone.
        String atThenLoneHigh = ascii(512) + "\uD83D";
        assertEquals(512, utf8Bytes(atThenLoneHigh.substring(0, 512)),
                "the prefix is exactly at the ceiling, so it does not itself break the budget");
        assertEquals(513, atThenLoneHigh.length());
        IllegalArgumentException atHighFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireName(atThenLoneHigh, false));
        assertTrue(atHighFailure.getMessage().contains("Unicode"),
                "with a prefix at the ceiling the surrogate is the first defect: "
                        + atHighFailure.getMessage());
        assertTrue(!atHighFailure.getMessage().contains("budget"), atHighFailure.getMessage());
    }

    @Test
    @DisplayName("unpaired surrogates are rejected in names and descriptions")
    void unpairedSurrogatesAreRejected() {
        String loneHigh = "a\uD83Db";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(loneHigh, false));
        String trailingHigh = "abc\uD83D";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(trailingHigh, true));
        String loneLow = "a\uDE00b";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(loneLow, false));
        String leadingLow = "\uDE00abc";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(leadingLow, false));
        String twoHigh = "\uD83D\uD83D";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(twoHigh, false));
        String reversed = "\uDE00\uD83D";
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireName(reversed, false));

        // The defect is named by index and the offending UTF-16 unit so the failure is diagnosable.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireName(loneLow, false));
        assertTrue(failure.getMessage().contains("index 1"), failure.getMessage());
        assertTrue(failure.getMessage().contains("DE00"), failure.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireDescription(loneHigh));
        assertThrows(IllegalArgumentException.class,
                () -> team("ok\uD83D"));

        // A legal emoji is a pair and passes, which proves the check is pairing and not a surrogate
        // ban. The expected value is a single instance, because the rule under test is that the
        // helper returns the very reference it was given.
        String pair = emoji(1);
        assertSame(pair, MapScalars.requireName(pair, true));
        assertEquals(pair, MapScalars.requireName(pair, true));
    }

    @Test
    @DisplayName("names reject every C0 control while DEL survives")
    void nameRejectsOnlyC0Controls() {
        for (int control = 0x00; control <= 0x1F; control++) {
            String value = "a" + (char) control + "b";
            assertThrows(IllegalArgumentException.class,
                    () -> MapScalars.requireName(value, false),
                    "name must reject U+" + Integer.toHexString(control));
        }

        // DEL is a legal Unicode scalar for a name and is kept.
        String withDel = "a\u007Fb";
        assertSame(withDel, MapScalars.requireName(withDel, true));

        // A legal emoji pair contains no C0 unit and is unaffected by the control rule.
        String pairs = emoji(2);
        assertSame(pairs, MapScalars.requireName(pairs, true));
    }

    @Test
    @DisplayName("description budget accepts 8192 bytes, rejects 8193, and keeps control characters")
    void descriptionBudgetAndControls() {
        String exactly = ascii(8192);
        assertEquals(8192, utf8Bytes(exactly));
        assertSame(exactly, MapScalars.requireDescription(exactly));

        String over = ascii(8193);
        assertEquals(8193, utf8Bytes(over));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDescription(over));

        String cjkOver = cjk(2731);
        assertEquals(8193, utf8Bytes(cjkOver));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDescription(cjkOver));

        String emojiOver = emoji(2049);
        assertEquals(8196, utf8Bytes(emojiOver));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDescription(emojiOver));

        // Line feed, carriage return and tab are legal in a description and are never replaced.
        String multiline = "first\nsecond\tindented\r\nthird";
        assertSame(multiline, MapScalars.requireDescription(multiline));
        assertEquals(multiline, MapScalars.requireDescription(multiline));

        // The remaining C0 controls are also kept in a description, unlike in a name.
        for (int control = 0x00; control <= 0x1F; control++) {
            String value = "a" + (char) control + "b";
            assertSame(value, MapScalars.requireDescription(value));
        }

        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDescription(null));
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDescription("\uD83D"));

        // --- The description budget is likewise decided before any trailing surrogate. ---
        //
        // A prefix of 8193 bytes has already crossed the 8192 byte ceiling, so that is the first
        // defect and must be the one reported even though the tail also carries an unpaired
        // surrogate. The category word "budget" is matched locally rather than the whole sentence.
        String descOverThenHigh = ascii(8193) + "\uD83D";
        IllegalArgumentException descOverFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireDescription(descOverThenHigh));
        assertTrue(descOverFailure.getMessage().contains("budget"),
                "the budget must be reported, not the trailing surrogate: "
                        + descOverFailure.getMessage());

        String descOverThenLow = ascii(8193) + "\uDE00";
        IllegalArgumentException descOverLowFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireDescription(descOverThenLow));
        assertTrue(descOverLowFailure.getMessage().contains("budget"),
                "the budget must be reported, not the trailing surrogate: "
                        + descOverLowFailure.getMessage());

        // The control again: an exact-ceiling prefix leaves the surrogate as the first defect.
        String descAtThenHigh = ascii(8192) + "\uD83D";
        IllegalArgumentException descAtFailure = assertThrows(IllegalArgumentException.class,
                () -> MapScalars.requireDescription(descAtThenHigh));
        assertTrue(descAtFailure.getMessage().contains("Unicode"),
                "with a prefix at the ceiling the surrogate is the first defect: "
                        + descAtFailure.getMessage());
        assertTrue(!descAtFailure.getMessage().contains("budget"), descAtFailure.getMessage());
    }

    // ------------------------------------------------------------------
    // 3. dimension and namespaced grammar
    // ------------------------------------------------------------------

    static Stream<Arguments> dimensionVectors() {
        // The frozen WorldRef scalar caps the whole id at 191 ASCII characters and additionally
        // bounds the namespace and the path on their own. These vectors are built from those exact
        // arithmetic limits: "n:" + 189 path characters is exactly 191, and 190 path characters is
        // 192 and must be rejected by the total budget.
        String namespace189 = ascii(189);
        String path188 = ascii(188);
        String path189 = ascii(189);
        String path190 = ascii(190);
        String path191 = ascii(191);
        return Stream.of(
                // accepted
                Arguments.of("minecraft:overworld", true),
                Arguments.of("a:b", true),
                Arguments.of("custom_dim-1.2:deep/path/leaf", true),
                Arguments.of(namespace189 + ":p", true),
                Arguments.of("n:" + path189, true),
                Arguments.of(namespace189 + ":a", true),
                // rejected: the total 191 character budget
                Arguments.of("n:" + path190, false),
                Arguments.of(ascii(190) + ":p", false),
                Arguments.of(namespace189 + ":" + "a", true),
                Arguments.of(namespace189 + ":ab", false),
                Arguments.of("n:" + path191, false),
                // rejected: no exactly-one explicit namespace
                Arguments.of("overworld", false),
                Arguments.of(":overworld", false),
                Arguments.of("minecraft:", false),
                Arguments.of("a:b:c", false),
                // rejected: character classes
                Arguments.of("Minecraft:overworld", false),
                Arguments.of("mineCraft:overworld", false),
                Arguments.of("minecraft:Overworld", false),
                Arguments.of("mine craft:overworld", false),
                Arguments.of("minecraft:over world", false),
                Arguments.of(" minecraft:overworld", false),
                Arguments.of("minecraft:overworld ", false),
                Arguments.of("minecraft:over!world", false),
                Arguments.of("minecraft#:overworld", false),
                Arguments.of("minecraft:over#world", false),
                Arguments.of("minecraft:over world/path", false),
                // rejected: non ASCII
                Arguments.of("minecraft:\u00f6verworld", false),
                Arguments.of("\u6c49:path", false),
                Arguments.of("minecraft:\uD83D\uDE00", false));
    }

    @ParameterizedTest(name = "dimension {0} accepted={1}")
    @MethodSource("dimensionVectors")
    @DisplayName("dimension grammar accepts legal explicit namespaces and rejects the rest")
    void dimensionGrammar(String value, boolean accepted) {
        if (accepted) {
            assertSame(value, MapScalars.requireDimension(value));
        } else {
            assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDimension(value));
        }
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireDimension(null));
    }

    @ParameterizedTest(name = "namespaced {0} accepted={1}")
    @MethodSource("dimensionVectors")
    @DisplayName("requireNamespaced uses the same grammar and budget as the dimension rule")
    void namespacedGrammarMatchesDimension(String value, boolean accepted) {
        if (accepted) {
            assertSame(value, MapScalars.requireNamespaced(value));
        } else {
            assertThrows(IllegalArgumentException.class, () -> MapScalars.requireNamespaced(value));
        }
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireNamespaced(null));
    }

    @Test
    @DisplayName("the 191 character budget is a real boundary and an unknown namespace still passes")
    void namespacedCharacterBudgetBoundary() {
        String exactly191 = "n:" + ascii(189);
        assertEquals(191, exactly191.length());
        assertSame(exactly191, MapScalars.requireNamespaced(exactly191));

        String over192 = "n:" + ascii(190);
        assertEquals(192, over192.length());
        assertThrows(IllegalArgumentException.class, () -> MapScalars.requireNamespaced(over192));

        // The longest accepted namespace: the namespace bound and the total bound coincide here.
        String longestNamespace = ascii(189) + ":p";
        assertEquals(191, longestNamespace.length());
        assertSame(longestNamespace, MapScalars.requireNamespaced(longestNamespace));

        // A syntactically legal namespace that nothing has registered is accepted here: syntax is
        // this layer's whole job, and registry existence is a business question.
        assertSame("definitely_not_a_registered_namespace:kind", MapScalars
                .requireNamespaced("definitely_not_a_registered_namespace:kind"));

        // The budget is reported in characters, and every accepted value is ASCII, so the character
        // count and the byte count coincide for an accepted value.
        assertEquals(191, utf8Bytes(exactly191));
    }

    @Test
    @DisplayName("dimension vectors agree with the frozen WorldRef scalar on the same inputs")
    void dimensionAgreesWithFrozenWorldRef() {
        Ids.WorldId worldId = new Ids.WorldId(UUID.fromString("abcdef01-2345-4678-89ab-cdef01234567"));
        dimensionVectors().forEach(arguments -> {
            String value = (String) arguments.get()[0];
            boolean expected = (Boolean) arguments.get()[1];

            boolean namespacedAccepted;
            try {
                MapScalars.requireDimension(value);
                namespacedAccepted = true;
            } catch (IllegalArgumentException rejected) {
                namespacedAccepted = false;
            }

            boolean worldRefAccepted;
            try {
                WorldRef ref = new WorldRef(worldId, value);
                worldRefAccepted = value.equals(ref.dimensionId());
            } catch (IllegalArgumentException rejected) {
                worldRefAccepted = false;
            }

            assertEquals(expected, namespacedAccepted, "MapScalars disagreed on: " + value);
            assertEquals(expected, worldRefAccepted, "WorldRef disagreed on: " + value);
            assertEquals(worldRefAccepted, namespacedAccepted,
                    "the two scalar rules disagreed on: " + value);
        });

        // A spawn carries the accepted value verbatim.
        SpawnDefinition spawn = new SpawnDefinition(OBJECT_ID, "s", TEAM_ID, "custom:kind/deep",
                new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f), EMPTY_OBJECT, SPAWN_EXTRAS);
        assertEquals("custom:kind/deep", spawn.dimensionId());
        assertThrows(IllegalArgumentException.class, () -> new SpawnDefinition(OBJECT_ID, "s", TEAM_ID,
                "overworld", new Position(0.0d, 0.0d, 0.0d), new Orientation(0.0f, 0.0f),
                EMPTY_OBJECT, SPAWN_EXTRAS));
    }

    // ------------------------------------------------------------------
    // 4. signed zero and untouched bits
    // ------------------------------------------------------------------

    @Test
    @DisplayName("each signed zero component is normalised individually")
    void eachSignedZeroComponentIsNormalised() {
        // Each candidate carries a negative zero in exactly one component and a distinct non-zero
        // value in the other two, so the expected outcome of every component is known.
        Position negativeZeroX = new Position(-0.0d, 1.0d, 2.0d);
        Position negativeZeroY = new Position(1.0d, -0.0d, 2.0d);
        Position negativeZeroZ = new Position(1.0d, 2.0d, -0.0d);
        Position allNegativeZeros = new Position(-0.0d, -0.0d, -0.0d);

        // The orientation carries no signed zero, so it is unaffected by normalising the position
        // and must be returned as the very same instance. One negative zero in one geometry must not
        // cause the sibling geometry to be rebuilt.
        Orientation cleanOrientation = new Orientation(1.0f, 2.0f);

        for (Position candidate : List.of(negativeZeroX, negativeZeroY, negativeZeroZ,
                allNegativeZeros)) {
            SpawnDefinition spawn = spawn(candidate, cleanOrientation);
            Position stored = spawn.position();
            assertNotSame(candidate, stored);
            assertSame(cleanOrientation, spawn.orientation());
            assertEquals(rawBits(1.0f), rawBits(spawn.orientation().yaw()));
            assertEquals(rawBits(2.0f), rawBits(spawn.orientation().pitch()));

            // Only a negative zero becomes positive zero; a non-zero component is carried through
            // bit for bit, so the untouched components are asserted as equal to the originals.
            assertEquals(rawBits(isNegativeZero(candidate.x()) ? 0.0d : candidate.x()),
                    rawBits(stored.x()), "x");
            assertEquals(rawBits(isNegativeZero(candidate.y()) ? 0.0d : candidate.y()),
                    rawBits(stored.y()), "y");
            assertEquals(rawBits(isNegativeZero(candidate.z()) ? 0.0d : candidate.z()),
                    rawBits(stored.z()), "z");

            assertTrue(!isNegativeZero(stored.x()), "x must not be a negative zero");
            assertTrue(!isNegativeZero(stored.y()), "y must not be a negative zero");
            assertTrue(!isNegativeZero(stored.z()), "z must not be a negative zero");

            // The original instance is not mutated: the caller still owns its negative zero.
            assertSame(candidate, candidate);
        }
        assertTrue(isNegativeZero(negativeZeroX.x()));
        assertTrue(isNegativeZero(negativeZeroY.y()));
        assertTrue(isNegativeZero(negativeZeroZ.z()));
        assertTrue(isNegativeZero(allNegativeZeros.x()));
        assertTrue(isNegativeZero(allNegativeZeros.y()));
        assertTrue(isNegativeZero(allNegativeZeros.z()));

        // Exactly one component: only that one changes.
        Position onlyX = spawn(new Position(-0.0d, 1.0d, 2.0d), new Orientation(1.0f, 2.0f))
                .position();
        assertTrue(rawBits(onlyX.x()) == 0L);
        assertEquals(rawBits(1.0d), rawBits(onlyX.y()));
        assertEquals(rawBits(2.0d), rawBits(onlyX.z()));

        // Combination: all three normalise and the components stay distinct.
        Position all = spawn(allNegativeZeros, new Orientation(1.0f, 2.0f)).position();
        assertTrue(rawBits(all.x()) == 0L && rawBits(all.y()) == 0L && rawBits(all.z()) == 0L);
    }

    @Test
    @DisplayName("orientation signed zero is normalised per angle")
    void orientationSignedZeroIsNormalised() {
        Orientation negativeYaw = new Orientation(-0.0f, 30.0f);
        Orientation negativePitch = new Orientation(30.0f, -0.0f);
        Orientation both = new Orientation(-0.0f, -0.0f);

        // The position carries no signed zero, so it is unaffected by normalising the orientation
        // and must come back as the very same instance. A single negative zero in one geometry must
        // not cause the other geometry to be rebuilt, which is what this pins.
        Position cleanPosition = new Position(1.0d, 2.0d, 3.0d);

        Orientation normalisedYaw = spawn(cleanPosition, negativeYaw).orientation();
        assertNotSame(negativeYaw, normalisedYaw);
        assertTrue(rawBits(normalisedYaw.yaw()) == 0);
        assertEquals(rawBits(30.0f), rawBits(normalisedYaw.pitch()));
        assertSame(cleanPosition, spawn(cleanPosition, negativeYaw).position());

        Orientation normalisedPitch = spawn(cleanPosition, negativePitch).orientation();
        assertEquals(rawBits(30.0f), rawBits(normalisedPitch.yaw()));
        assertTrue(rawBits(normalisedPitch.pitch()) == 0);
        assertSame(cleanPosition, spawn(cleanPosition, negativePitch).position());

        Orientation normalisedBoth = spawn(cleanPosition, both).orientation();
        assertTrue(rawBits(normalisedBoth.yaw()) == 0 && rawBits(normalisedBoth.pitch()) == 0);
        assertSame(cleanPosition, spawn(cleanPosition, both).position());

        // The unaffected geometry keeps its exact bits, so it was neither copied nor normalised.
        assertEquals(rawBits(1.0d), rawBits(spawn(cleanPosition, negativeYaw).position().x()));
        assertEquals(rawBits(2.0d), rawBits(spawn(cleanPosition, negativeYaw).position().y()));
        assertEquals(rawBits(3.0d), rawBits(spawn(cleanPosition, negativeYaw).position().z()));

        // The caller's instance keeps its negative zeros.
        assertTrue(isNegativeZero(negativeYaw.yaw()));
        assertTrue(isNegativeZero(both.pitch()));
    }

    @Test
    @DisplayName("negative zero and positive zero inputs produce equal spawns with equal hashes")
    void signedZeroInputsAreEquivalent() {
        SpawnDefinition negativeZeroSpawn = spawn(new Position(-0.0d, -0.0d, -0.0d),
                new Orientation(-0.0f, -0.0f));
        SpawnDefinition positiveZeroSpawn = spawn(new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f));

        assertEquals(positiveZeroSpawn, negativeZeroSpawn);
        assertEquals(positiveZeroSpawn.hashCode(), negativeZeroSpawn.hashCode());

        // Mixed inputs are equivalent too, because every zero component ends up identical.
        SpawnDefinition mixed = spawn(new Position(-0.0d, 0.0d, -0.0d),
                new Orientation(0.0f, -0.0f));
        assertEquals(positiveZeroSpawn, mixed);
        assertEquals(positiveZeroSpawn.hashCode(), mixed.hashCode());

        // A record that kept the negative zero would not be equal, so this equality is the
        // observable statement of the normalisation rule.
        Position untouchedNegativeZero = new Position(-0.0d, -0.0d, -0.0d);
        Position untouchedPositiveZero = new Position(0.0d, 0.0d, 0.0d);
        assertNotEquals(untouchedNegativeZero, untouchedPositiveZero);
    }

    @Test
    @DisplayName("geometry without a signed zero keeps its instance and its exact bits")
    void untouchedGeometryIsReusedByReference() {
        Position position = new Position(1.0d, -2.5d, 3.25d);
        Orientation orientation = new Orientation(90.0f, 120.0f);
        SpawnDefinition spawn = spawn(position, orientation);

        // Reference reuse: no copy is made when nothing needs normalising.
        assertSame(position, spawn.position());
        assertSame(orientation, spawn.orientation());

        assertEquals(rawBits(1.0d), rawBits(spawn.position().x()));
        assertEquals(rawBits(-2.5d), rawBits(spawn.position().y()));
        assertEquals(rawBits(3.25d), rawBits(spawn.position().z()));
        // A finite pitch is never clamped to [-90, 90].
        assertEquals(rawBits(120.0f), rawBits(spawn.orientation().pitch()));
        assertEquals(rawBits(90.0f), rawBits(spawn.orientation().yaw()));

        // Accessors are pure: calling them cannot mutate the stored geometry.
        long beforeX = rawBits(spawn.position().x());
        spawn.position();
        spawn.orientation();
        assertEquals(beforeX, rawBits(spawn.position().x()));
        assertSame(position, spawn.position());

        // Positive zero is already the canonical zero, so nothing needs normalising and the very
        // same instances must come back. This is the case that proves normalisation is triggered by
        // a negative zero specifically, rather than by a zero of any sign, and that the record does
        // not rebuild its geometry unconditionally.
        Position positiveZeroPosition = new Position(0.0d, 0.0d, 0.0d);
        Orientation positiveZeroOrientation = new Orientation(0.0f, 0.0f);
        SpawnDefinition positiveZeroSpawn = spawn(positiveZeroPosition, positiveZeroOrientation);
        assertSame(positiveZeroPosition, positiveZeroSpawn.position());
        assertSame(positiveZeroOrientation, positiveZeroSpawn.orientation());

        // The raw bits are the positive zero bit pattern, which is 0 for both widths: a positive
        // zero is never rewritten into a negative zero or any other value.
        assertEquals(0L, rawBits(positiveZeroSpawn.position().x()));
        assertEquals(0L, rawBits(positiveZeroSpawn.position().y()));
        assertEquals(0L, rawBits(positiveZeroSpawn.position().z()));
        assertEquals(0, rawBits(positiveZeroSpawn.orientation().yaw()));
        assertEquals(0, rawBits(positiveZeroSpawn.orientation().pitch()));
        assertTrue(!isNegativeZero(positiveZeroSpawn.position().x()));
        assertTrue(!isNegativeZero(positiveZeroSpawn.orientation().yaw()));

        // The inputs themselves are untouched and still hold positive zero.
        assertEquals(0L, rawBits(positiveZeroPosition.x()));
        assertEquals(0, rawBits(positiveZeroOrientation.yaw()));
    }

    @Test
    @DisplayName("subnormal and extreme finite components keep their exact bits")
    void subnormalAndExtremeValuesArePreserved() {
        Position position = new Position(Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE);
        Orientation orientation = new Orientation(Float.MIN_VALUE, -Float.MAX_VALUE);
        SpawnDefinition spawn = spawn(position, orientation);

        assertSame(position, spawn.position());
        assertSame(orientation, spawn.orientation());
        assertEquals(rawBits(Double.MIN_VALUE), rawBits(spawn.position().x()));
        assertEquals(rawBits(-Double.MIN_VALUE), rawBits(spawn.position().y()));
        assertEquals(rawBits(Double.MAX_VALUE), rawBits(spawn.position().z()));
        assertEquals(rawBits(Float.MIN_VALUE), rawBits(spawn.orientation().yaw()));
        assertEquals(rawBits(-Float.MAX_VALUE), rawBits(spawn.orientation().pitch()));

        // A tiny non-zero value is not a zero and must not be cleared by any epsilon comparison.
        assertTrue(!isNegativeZero(Double.MIN_VALUE));
        assertTrue(!isNegativeZero(-Double.MIN_VALUE));
        assertNotEquals(rawBits(Double.MIN_VALUE), 0L);
        assertEquals(rawBits(-Double.MIN_VALUE),
                rawBits(-Double.MIN_VALUE + 0.0d), "adding a positive zero cannot change the bits");
        assertEquals(0L, rawBits(Double.MIN_VALUE + (-Double.MIN_VALUE)),
                "the two subnormals cancel into a positive zero");

        // Even a value that is numerically indistinguishable from zero in a naive comparison keeps
        // its raw bits, which is what forbids "use an epsilon" implementations.
        Position tiny = new Position(1.0e-320d, 0.0d, 0.0d);
        assertEquals(rawBits(1.0e-320d), rawBits(spawn(tiny, orientation).position().x()));
    }

    @Test
    @DisplayName("repeated identical coordinates are never de-duplicated")
    void repeatedCoordinatesAreNotDeduplicated() {
        Position position = new Position(4.0d, 5.0d, 6.0d);
        Orientation orientation = new Orientation(10.0f, 20.0f);
        SpawnDefinition first = spawn(position, orientation);
        SpawnDefinition second = spawn(position, orientation);

        assertEquals(first, second);
        assertNotSame(first, second);

        // Equal content with a different identity stays a different record, so an implementation
        // that de-duplicated on coordinates would fail here.
        SpawnDefinition third = new SpawnDefinition(OTHER_OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT, SPAWN_EXTRAS);
        assertNotEquals(first, third);

        // A list keeps both occurrences.
        List<SpawnDefinition> spawns = new ArrayList<>(List.of(first, second, third));
        assertEquals(3, spawns.size());
    }

    // ------------------------------------------------------------------
    // 5. frozen JSON isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("freezing a mutable source then polluting it cannot change a record")
    void recordsAreIsolatedFromPollutedSourceTrees() {
        String name = "Blue";
        JsonObject configSource = new JsonObject();
        configSource.addProperty("rounds", 3);
        JsonArray extensionSource = new JsonArray();
        extensionSource.add(1);
        extensionSource.add("two");
        JsonObject extraSource = new JsonObject();
        extraSource.addProperty("futureFlag", true);

        ExactJson.FrozenJson config = ExactJson.freeze(configSource);
        JsonObject extensionRoot = new JsonObject();
        extensionRoot.add("list", extensionSource);
        ExactJson.FrozenJson extensions = ExactJson.freeze(extensionRoot);
        ExactJson.FrozenJson extrasMember = ExactJson.freeze(extraSource);

        TeamDefinition team = new TeamDefinition(TEAM_ID, name, config, extensions,
                new WireExtras.TeamExtras(extrasMember));
        TeamDefinition snapshot = new TeamDefinition(TEAM_ID, name, config, extensions,
                new WireExtras.TeamExtras(extrasMember));
        String canonicalBefore = team.extensions().canonical();
        String extrasBefore = team.wireExtras().members().canonical();
        int hashBefore = team.hashCode();

        // Pollute every mutable source that was handed to freeze.
        configSource.addProperty("rounds", 99);
        configSource.addProperty("leaked", "no");
        extensionSource.add("three");
        extraSource.addProperty("futureFlag", false);

        // A toJson copy is a fresh tree and cannot reach back into the record either.
        JsonElement copy = team.extensions().toJson();
        copy.getAsJsonObject().addProperty("injected", 1);

        assertEquals(snapshot, team);
        assertEquals(hashBefore, team.hashCode());
        assertEquals(canonicalBefore, team.extensions().canonical());
        assertEquals(extrasBefore, team.wireExtras().members().canonical());
        assertEquals(3, team.config().toJson().getAsJsonObject().get("rounds").getAsInt());
        assertEquals(canonicalBefore, snapshot.extensions().canonical());
        assertTrue(team.config().toJson().getAsJsonObject().get("leaked") == null);
    }

    @Test
    @DisplayName("config and extensions accept every legal frozen JSON shape")
    void configAndExtensionsAcceptAnyFrozenShape() {
        JsonObject unknownShape = new JsonObject();
        unknownShape.add("", new JsonPrimitive(1));
        unknownShape.add("big", new JsonPrimitive(new java.math.BigInteger(
                "123456789012345678901234567890")));

        List<ExactJson.FrozenJson> shapes = new ArrayList<>();
        shapes.add(EMPTY_OBJECT);
        shapes.add(freezeObject("nested", freezeObject("deep", new JsonPrimitive(true)).toJson()));
        shapes.add(freezeObject("array", new JsonArray()));
        shapes.add(freezeObject("ordered", arrayOf("b", "a", "c")));
        shapes.add(freezeObject("jsonNull", com.google.gson.JsonNull.INSTANCE));
        shapes.add(freezeObject("string", new JsonPrimitive("text")));
        shapes.add(freezeObject("number", new JsonPrimitive(42)));
        shapes.add(freezeObject("boolean", new JsonPrimitive(false)));
        shapes.add(ExactJson.freeze(unknownShape));

        for (ExactJson.FrozenJson shape : shapes) {
            TeamDefinition team = new TeamDefinition(TEAM_ID, "Red", shape, shape, TEAM_EXTRAS);
            assertSame(shape, team.config());
            assertSame(shape, team.extensions());

            SpawnDefinition spawn = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                    "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                    new Orientation(0.0f, 0.0f), shape, SPAWN_EXTRAS);
            assertSame(shape, spawn.extensions());
        }

        // A JSON null root is legal frozen JSON, unlike a Java null member, and it is carried.
        ExactJson.FrozenJson jsonNullRoot = ExactJson.parse("null");
        assertEquals("null", jsonNullRoot.canonical());
        TeamDefinition withJsonNull = new TeamDefinition(TEAM_ID, "Red", jsonNullRoot,
                jsonNullRoot, TEAM_EXTRAS);
        assertSame(jsonNullRoot, withJsonNull.config());
        assertThrows(IllegalArgumentException.class,
                () -> new TeamDefinition(TEAM_ID, "Red", null, jsonNullRoot, TEAM_EXTRAS));

        // An empty key is a legal JSON member name and is preserved verbatim.
        JsonObject emptyKeyRoot = new JsonObject();
        emptyKeyRoot.add("", new JsonPrimitive("value"));
        ExactJson.FrozenJson emptyKey = ExactJson.freeze(emptyKeyRoot);
        assertEquals("{\"\":\"value\"}", emptyKey.canonical());

        // A large integer literal is preserved as a value rather than rejected, and it stays a JSON
        // number after freezing. The magnitude is asserted against the frozen codec's own canonical
        // number value: canonicalising a numeric literal is the codec's business, so this record's
        // test only pins that the value survives as a number with the same magnitude and is never
        // dropped, truncated to zero or turned into a string.
        ExactJson.FrozenJson largeNumber = freezeObject("big",
                new JsonPrimitive(new java.math.BigInteger("123456789012345678901234567890")));
        JsonElement largeValue = largeNumber.toJson().getAsJsonObject().get("big");
        assertTrue(largeValue.isJsonPrimitive() && largeValue.getAsJsonPrimitive().isNumber(),
                "a large integer must stay a JSON number");
        assertEquals(0, new java.math.BigDecimal("123456789012345678901234567890")
                .compareTo(largeValue.getAsBigDecimal()));
        assertTrue(largeValue.getAsBigDecimal().signum() > 0, "the value must not be lost");

        // The same frozen content is reachable through the record and is stable across accessors.
        TeamDefinition largeTeam = new TeamDefinition(TEAM_ID, "Red", largeNumber, largeNumber,
                TEAM_EXTRAS);
        assertSame(largeNumber, largeTeam.config());
        assertEquals(largeNumber.canonical(), largeTeam.config().canonical());
        assertEquals(largeNumber.canonical(), largeTeam.config().toJson().toString());

        // Array order is significant and is not sorted or otherwise rewritten.
        ExactJson.FrozenJson ordered = freezeObject("ordered", arrayOf("b", "a", "c"));
        assertTrue(ordered.canonical().contains("[\"b\",\"a\",\"c\"]"));
        assertNotEquals(ordered, freezeObject("ordered", arrayOf("a", "b", "c")));

        // --- Genuine root values, not members of a wrapper object. ---
        //
        // The shapes above are all members reached through an object, so they do not by themselves
        // prove that a root that is not an object is accepted. A provider schema may legitimately
        // declare its config as an array or a scalar, and the record only requires a non-null frozen
        // reference, so each root is parsed and handed over directly. Wrapping these in an object
        // would test the wrapper instead of the root, so no wrapper is used here.
        List<ExactJson.FrozenJson> rootValues = new ArrayList<>();
        rootValues.add(ExactJson.parse("[]"));
        rootValues.add(ExactJson.parse("[1,2]"));
        rootValues.add(ExactJson.parse("\"text\""));
        rootValues.add(ExactJson.parse("42"));
        rootValues.add(ExactJson.parse("true"));
        rootValues.add(ExactJson.parse("false"));
        rootValues.add(ExactJson.parse("null"));

        for (ExactJson.FrozenJson root : rootValues) {
            // The root really is a non object value, which is what distinguishes this loop from the
            // member based loop above.
            char first = root.canonical().charAt(0);
            assertTrue(first != '{', "a root value must not have been wrapped: " + root.canonical());

            TeamDefinition team = new TeamDefinition(TEAM_ID, "Red", root, root, TEAM_EXTRAS);
            assertSame(root, team.config());
            assertSame(root, team.extensions());

            SpawnDefinition spawn = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                    "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                    new Orientation(0.0f, 0.0f), root, SPAWN_EXTRAS);
            assertSame(root, spawn.extensions());

            // The value is not repaired on the way in: the canonical text is the parsed root itself.
            assertEquals(root.canonical(), team.config().canonical());
        }

        // The individual roots are distinct from one another, so the loop cannot pass vacuously on
        // one shared value, and the two boolean roots in particular are not conflated.
        assertEquals("[]", rootValues.get(0).canonical());
        assertEquals("[1,2]", rootValues.get(1).canonical());
        assertEquals("\"text\"", rootValues.get(2).canonical());
        assertEquals("42", rootValues.get(3).canonical());
        assertEquals("true", rootValues.get(4).canonical());
        assertEquals("false", rootValues.get(5).canonical());
        assertEquals("null", rootValues.get(6).canonical());
        assertNotEquals(rootValues.get(4), rootValues.get(5));
        assertNotEquals(rootValues.get(0), rootValues.get(1));
    }

    private static JsonArray arrayOf(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    // ------------------------------------------------------------------
    // 6. wire extras separation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("wire extras slots are retained per position and a null slot is rejected")
    void wireExtrasSlotsAreIndependent() {
        ExactJson.FrozenJson root = freezeObject("rootUnknown", new JsonPrimitive(1));
        ExactJson.FrozenJson positionMembers = freezeObject("posUnknown", new JsonPrimitive(2));
        ExactJson.FrozenJson orientationMembers = freezeObject("rotUnknown", new JsonPrimitive(3));
        WireExtras.SpawnExtras extras =
                new WireExtras.SpawnExtras(root, positionMembers, orientationMembers);

        SpawnDefinition spawn = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f), EMPTY_OBJECT, extras);

        assertSame(extras, spawn.wireExtras());
        assertSame(root, spawn.wireExtras().members());
        assertSame(positionMembers, spawn.wireExtras().positionMembers());
        assertSame(orientationMembers, spawn.wireExtras().orientationMembers());

        // The extras are not merged into extensions and do not leak into it.
        assertEquals("{}", spawn.extensions().canonical());
        assertNotEquals(spawn.extensions().canonical(), root.canonical());

        // Each slot rejects null.
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.SpawnExtras(null, positionMembers, orientationMembers));
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.SpawnExtras(root, null, orientationMembers));
        assertThrows(IllegalArgumentException.class,
                () -> new WireExtras.SpawnExtras(root, positionMembers, null));
        assertThrows(IllegalArgumentException.class, () -> new WireExtras.TeamExtras(null));
    }

    @Test
    @DisplayName("different unknown values change equals and hash; identical typed positions do not merge")
    void differentUnknownValuesChangeRecordEquality() {
        Position position = new Position(0.0d, 0.0d, 0.0d);
        Orientation orientation = new Orientation(0.0f, 0.0f);

        SpawnDefinition base = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("u", new JsonPrimitive(1)), EMPTY_OBJECT,
                        EMPTY_OBJECT));
        SpawnDefinition sameExtras = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("u", new JsonPrimitive(1)), EMPTY_OBJECT,
                        EMPTY_OBJECT));
        SpawnDefinition otherRoot = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("u", new JsonPrimitive(2)), EMPTY_OBJECT,
                        EMPTY_OBJECT));
        SpawnDefinition otherPosition = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("u", new JsonPrimitive(1)),
                        freezeObject("p", new JsonPrimitive(1)), EMPTY_OBJECT));
        SpawnDefinition otherOrientation = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", position, orientation, EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("u", new JsonPrimitive(1)), EMPTY_OBJECT,
                        freezeObject("o", new JsonPrimitive(1))));

        // Equal frozen text means an equal record, even from a different freeze call.
        assertEquals(base, sameExtras);
        assertEquals(base.hashCode(), sameExtras.hashCode());

        // A different value in any one slot must be observable.
        assertNotEquals(base, otherRoot);
        assertNotEquals(base, otherPosition);
        assertNotEquals(base, otherOrientation);
        assertNotEquals(base.hashCode(), otherRoot.hashCode());
    }

    @Test
    @DisplayName("extensions null and a root future flag are distinguishable records")
    void extensionsAndUnknownMembersAreDistinguishable() {
        SpawnDefinition withoutFlag = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f), EMPTY_OBJECT, SPAWN_EXTRAS);
        SpawnDefinition withTrueFlag = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f), EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("futureFlag", new JsonPrimitive(true)),
                        EMPTY_OBJECT, EMPTY_OBJECT));
        SpawnDefinition withFalseFlag = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f), EMPTY_OBJECT,
                new WireExtras.SpawnExtras(freezeObject("futureFlag", new JsonPrimitive(false)),
                        EMPTY_OBJECT, EMPTY_OBJECT));

        assertNotEquals(withoutFlag, withTrueFlag);
        assertNotEquals(withTrueFlag, withFalseFlag);
        assertNotEquals(withoutFlag, withFalseFlag);

        // The same distinction holds for a team.
        TeamDefinition plain = new TeamDefinition(TEAM_ID, "Red", EMPTY_OBJECT, EMPTY_OBJECT,
                new WireExtras.TeamExtras(EMPTY_OBJECT));
        TeamDefinition flagged = new TeamDefinition(TEAM_ID, "Red", EMPTY_OBJECT, EMPTY_OBJECT,
                new WireExtras.TeamExtras(freezeObject("futureFlag", new JsonPrimitive(true))));
        assertNotEquals(plain, flagged);
        assertNotEquals(plain.hashCode(), flagged.hashCode());

        // A JSON null extension value is distinguishable from an empty object extension.
        ExactJson.FrozenJson jsonNullRoot = ExactJson.parse("null");
        SpawnDefinition withNullExtensions = new SpawnDefinition(OBJECT_ID, "spawn", TEAM_ID,
                "minecraft:overworld", new Position(0.0d, 0.0d, 0.0d),
                new Orientation(0.0f, 0.0f), jsonNullRoot, SPAWN_EXTRAS);
        assertNotEquals(withoutFlag, withNullExtensions);
    }
}
