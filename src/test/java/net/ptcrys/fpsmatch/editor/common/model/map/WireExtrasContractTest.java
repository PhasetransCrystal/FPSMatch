package net.ptcrys.fpsmatch.editor.common.model.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Formal contract tests for the R05.0 {@link WireExtras} unknown-member carrier.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests for the five
 * nested records of {@link WireExtras}. They cover the whole surface the task card fixes: object
 * shape rejection per member slot, acceptance of empty and nested objects, verbatim retention of
 * boundary Unicode/key/string/number text, position separation, canonical-text equality, isolation
 * from polluted Gson/toJson/canonicalUtf8 copies, and identity reuse of the supplied immutable
 * {@link ExactJson.FrozenJson} instance instead of any clone or re-parse.</p>
 *
 * <p><strong>What this class is not.</strong> A green run here is author-side evidence only, and it
 * is not the complete P05-WIRE-EXTRAS gate, which only the primary agent can grant. Nothing here
 * claims wire merge, codec, provider, budget or reserved-key-collision behaviour: those belong to
 * R06, and this carrier deliberately accepts keys that may currently be reserved.</p>
 *
 * <p><strong>Assertions.</strong> Every rejection asserts the exception type, and the failure detail
 * is checked for the component name and the offending canonical text that the production javadoc
 * promises. No test asserts a whole localized message sentence, and no test passes merely because
 * "some exception" was thrown.</p>
 *
 * <p><strong>Cost of the failure path.</strong> Only the legal success path of
 * {@code requireObjectMember} is constant time. An illegal shape is reported with a message that
 * echoes the whole canonical text, so building that message allocates O(N) characters and copies
 * them; for a multi-megabyte member this is observable (a large legal array was measured at
 * 8323504 canonical characters and rejected by {@code IllegalArgumentException} under
 * {@code -Xmx64m}). That echo is technical failure detail for developers, not player facing text,
 * so it introduces no localization key, and this revision neither requires nor forbids the echo:
 * the production behaviour is unchanged.</p>
 *
 * <p><strong>Heap discipline.</strong> The one large fixture (a 16 member object whose members share
 * one 65536 byte string, about one mebibyte of canonical text) is built inside its own test and
 * released with it, and the ten thousand iteration test runs under {@code -Xmx64m}, so the large
 * member cannot be duplicated once per iteration <em>and retained</em>.</p>
 *
 * <p><strong>What the heap limit does and does not prove.</strong> A surviving {@code -Xmx64m} run
 * is <em>not</em> by itself proof that the constructor performs no copying. A per-iteration copy
 * that becomes garbage immediately can be reclaimed by the collector during the loop, so a cloning
 * implementation may still fit in 64 MiB. The real evidence that no copy happens is therefore
 * (a) the {@code assertSame} assertions, which require the very instance supplied by the caller to
 * come back out of every getter, plus the identity check on the large member, and (b) source review
 * of the constructor, which only reads {@code canonical().charAt(0)}. The heap limit is a
 * supporting guard against <em>retained</em> duplication, not the primary proof.</p>
 */
class WireExtrasContractTest {

    /** The canonical text of a JSON object that carries no unknown member at all. */
    private static final ExactJson.FrozenJson EMPTY_OBJECT = ExactJson.parse("{}");

    /**
     * The six non-object JSON shapes the task card requires to be rejected at every member slot:
     * JSON null, an empty array, an array holding an element, an empty string, a number and a
     * boolean. A JSON object is the only shape whose canonical text starts with {@code '{'}.
     */
    private static final List<String> NON_OBJECT_SHAPES = List.of(
            "null",
            "[]",
            "[\"a\"]",
            "\"\"",
            "0",
            "true");

    /** Creates one nested record from a fully populated member array. */
    @FunctionalInterface
    private interface ExtrasFactory {
        Object create(ExactJson.FrozenJson[] members);
    }

    /** Reads one member slot back out of a nested record, so the tests stay data driven. */
    @FunctionalInterface
    private interface MemberReader {
        ExactJson.FrozenJson read(Object extras, int slot);
    }

    /**
     * One of the five nested record families, described by its component names plus the exact
     * constructor and getter mapping. Keeping the mapping in one place means the null, shape and
     * identity vectors automatically cover every record and every slot.
     */
    private record ExtrasFamily(String name, String[] slotNames, ExtrasFactory factory,
            MemberReader reader) {

        int arity() {
            return this.slotNames.length;
        }

        Object build(ExactJson.FrozenJson[] members) {
            return this.factory.create(members);
        }

        ExactJson.FrozenJson member(Object extras, int slot) {
            return this.reader.read(extras, slot);
        }

        @Override
        public String toString() {
            return this.name;
        }
    }

    private static final List<ExtrasFamily> FAMILIES = List.of(
            new ExtrasFamily("MapExtras",
                    new String[] {"members", "boundaryMembers", "boundaryFirstMembers",
                            "boundarySecondMembers"},
                    members -> new WireExtras.MapExtras(members[0], members[1], members[2], members[3]),
                    (extras, slot) -> {
                        final WireExtras.MapExtras map = (WireExtras.MapExtras) extras;
                        switch (slot) {
                            case 0:
                                return map.members();
                            case 1:
                                return map.boundaryMembers();
                            case 2:
                                return map.boundaryFirstMembers();
                            default:
                                return map.boundarySecondMembers();
                        }
                    }),
            new ExtrasFamily("TeamExtras",
                    new String[] {"members"},
                    members -> new WireExtras.TeamExtras(members[0]),
                    (extras, slot) -> ((WireExtras.TeamExtras) extras).members()),
            new ExtrasFamily("SpawnExtras",
                    new String[] {"members", "positionMembers", "orientationMembers"},
                    members -> new WireExtras.SpawnExtras(members[0], members[1], members[2]),
                    (extras, slot) -> {
                        final WireExtras.SpawnExtras spawn = (WireExtras.SpawnExtras) extras;
                        switch (slot) {
                            case 0:
                                return spawn.members();
                            case 1:
                                return spawn.positionMembers();
                            default:
                                return spawn.orientationMembers();
                        }
                    }),
            new ExtrasFamily("RegionExtras",
                    new String[] {"members", "cornersMembers", "cornersFirstMembers",
                            "cornersSecondMembers"},
                    members -> new WireExtras.RegionExtras(members[0], members[1], members[2], members[3]),
                    (extras, slot) -> {
                        final WireExtras.RegionExtras region = (WireExtras.RegionExtras) extras;
                        switch (slot) {
                            case 0:
                                return region.members();
                            case 1:
                                return region.cornersMembers();
                            case 2:
                                return region.cornersFirstMembers();
                            default:
                                return region.cornersSecondMembers();
                        }
                    }),
            new ExtrasFamily("ResourceExtras",
                    new String[] {"members"},
                    members -> new WireExtras.ResourceExtras(members[0]),
                    (extras, slot) -> ((WireExtras.ResourceExtras) extras).members()));

    /** Every member slot of every family: thirteen slots in total. */
    static Stream<Arguments> allSlots() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final ExtrasFamily family : FAMILIES) {
            for (int slot = 0; slot < family.arity(); slot++) {
                arguments.add(Arguments.of(family, slot, family.slotNames()[slot]));
            }
        }
        return arguments.stream();
    }

    /** Every member slot of every family crossed with the six non-object JSON shapes. */
    static Stream<Arguments> allSlotsWithNonObjectShapes() {
        final List<Arguments> arguments = new ArrayList<>();
        for (final ExtrasFamily family : FAMILIES) {
            for (int slot = 0; slot < family.arity(); slot++) {
                for (final String shape : NON_OBJECT_SHAPES) {
                    arguments.add(Arguments.of(family, slot, family.slotNames()[slot], shape));
                }
            }
        }
        return arguments.stream();
    }

    /** One member array of the given arity where every slot holds {@code value}. */
    private static ExactJson.FrozenJson[] filled(int arity, ExactJson.FrozenJson value) {
        final ExactJson.FrozenJson[] members = new ExactJson.FrozenJson[arity];
        for (int slot = 0; slot < arity; slot++) {
            members[slot] = value;
        }
        return members;
    }

    // ------------------------------------------------------------------
    // rejection: Java null and the six non-object shapes
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} slot {1} ({2}) rejects Java null")
    @MethodSource("allSlots")
    @DisplayName("every member slot rejects Java null with IllegalArgumentException")
    void rejectsJavaNullMember(ExtrasFamily family, int slot, String slotName) {
        final ExactJson.FrozenJson[] members = filled(family.arity(), EMPTY_OBJECT);
        members[slot] = null;

        final IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> family.build(members));

        assertTrue(failure.getMessage() != null && failure.getMessage().contains(slotName),
                "failure detail must name the component: " + failure.getMessage());
    }

    @ParameterizedTest(name = "{0} slot {1} ({2}) rejects {3}")
    @MethodSource("allSlotsWithNonObjectShapes")
    @DisplayName("every member slot rejects the six non-object JSON shapes")
    void rejectsNonObjectShapeMember(ExtrasFamily family, int slot, String slotName, String shapeText) {
        final ExactJson.FrozenJson shape = ExactJson.parse(shapeText);
        assertTrue(shape.canonical().charAt(0) != '{',
                "vector must not be a JSON object: " + shape.canonical());

        final ExactJson.FrozenJson[] members = filled(family.arity(), EMPTY_OBJECT);
        members[slot] = shape;

        final IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> family.build(members));

        assertTrue(failure.getMessage() != null && failure.getMessage().contains(slotName),
                "failure detail must name the component: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(shape.canonical()),
                "failure detail must quote the rejected canonical text: " + failure.getMessage());
    }

    @ParameterizedTest(name = "{0} slot {1} ({2}) rejects a string that looks like an object")
    @MethodSource("allSlots")
    @DisplayName("a JSON string whose content is an object is still not a JSON object")
    void rejectsStringThatLooksLikeAnObject(ExtrasFamily family, int slot, String slotName) {
        final ExactJson.FrozenJson shape = ExactJson.parse("\"{}\"");
        assertEquals("\"{}\"", shape.canonical());

        final ExactJson.FrozenJson[] members = filled(family.arity(), EMPTY_OBJECT);
        members[slot] = shape;

        final IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> family.build(members));

        assertTrue(failure.getMessage().contains(slotName),
                "failure detail must name the component: " + failure.getMessage());
    }

    // ------------------------------------------------------------------
    // acceptance: empty object, nested values, reserved keys, boundary text
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MapExtras keeps each position apart and accepts an empty object in every slot")
    void mapExtrasAcceptsEmptyObjectInEverySlot() {
        final ExactJson.FrozenJson members = ExactJson.parse("{\"slot\":\"root\"}");
        final ExactJson.FrozenJson boundary = ExactJson.parse("{\"slot\":\"boundary\"}");
        final ExactJson.FrozenJson first = ExactJson.parse("{\"slot\":\"first\"}");
        final ExactJson.FrozenJson second = ExactJson.parse("{\"slot\":\"second\"}");

        final WireExtras.MapExtras extras =
                new WireExtras.MapExtras(members, boundary, first, second);

        assertSame(members, extras.members());
        assertSame(boundary, extras.boundaryMembers());
        assertSame(first, extras.boundaryFirstMembers());
        assertSame(second, extras.boundarySecondMembers());
        assertEquals("{\"slot\":\"root\"}", extras.members().canonical());
        assertEquals("{\"slot\":\"boundary\"}", extras.boundaryMembers().canonical());
        assertEquals("{\"slot\":\"first\"}", extras.boundaryFirstMembers().canonical());
        assertEquals("{\"slot\":\"second\"}", extras.boundarySecondMembers().canonical());

        // Every slot really is built from EMPTY_OBJECT in turn: same instance back, canonical "{}".
        final WireExtras.MapExtras empty =
                new WireExtras.MapExtras(EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
        assertSame(EMPTY_OBJECT, empty.members());
        assertSame(EMPTY_OBJECT, empty.boundaryMembers());
        assertSame(EMPTY_OBJECT, empty.boundaryFirstMembers());
        assertSame(EMPTY_OBJECT, empty.boundarySecondMembers());
        assertEquals("{}", empty.members().canonical());
        assertEquals("{}", empty.boundaryMembers().canonical());
        assertEquals("{}", empty.boundaryFirstMembers().canonical());
        assertEquals("{}", empty.boundarySecondMembers().canonical());
    }

    @Test
    @DisplayName("TeamExtras and ResourceExtras accept an empty object and keep the instance")
    void singleMemberFamiliesAcceptEmptyObject() {
        final ExactJson.FrozenJson team = ExactJson.parse("{\"slot\":\"team\"}");
        final ExactJson.FrozenJson resource = ExactJson.parse("{\"slot\":\"resource\"}");

        final WireExtras.TeamExtras teamExtras = new WireExtras.TeamExtras(team);
        final WireExtras.ResourceExtras resourceExtras = new WireExtras.ResourceExtras(resource);

        assertSame(team, teamExtras.members());
        assertSame(resource, resourceExtras.members());
        assertEquals("{\"slot\":\"team\"}", teamExtras.members().canonical());
        assertEquals("{\"slot\":\"resource\"}", resourceExtras.members().canonical());

        assertSame(EMPTY_OBJECT, new WireExtras.TeamExtras(EMPTY_OBJECT).members());
        assertSame(EMPTY_OBJECT, new WireExtras.ResourceExtras(EMPTY_OBJECT).members());
        assertEquals("{}", EMPTY_OBJECT.canonical());
    }

    @Test
    @DisplayName("SpawnExtras keeps each position apart and accepts an empty object in every slot")
    void spawnExtrasAcceptsEmptyObjectInEverySlot() {
        final ExactJson.FrozenJson members = ExactJson.parse("{\"slot\":\"root\"}");
        final ExactJson.FrozenJson position = ExactJson.parse("{\"slot\":\"position\"}");
        final ExactJson.FrozenJson orientation = ExactJson.parse("{\"slot\":\"orientation\"}");

        final WireExtras.SpawnExtras extras =
                new WireExtras.SpawnExtras(members, position, orientation);

        assertSame(members, extras.members());
        assertSame(position, extras.positionMembers());
        assertSame(orientation, extras.orientationMembers());
        assertEquals("{\"slot\":\"position\"}", extras.positionMembers().canonical());
        assertEquals("{\"slot\":\"orientation\"}", extras.orientationMembers().canonical());

        // Every slot really is built from EMPTY_OBJECT in turn: same instance back, canonical "{}".
        final WireExtras.SpawnExtras empty =
                new WireExtras.SpawnExtras(EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
        assertSame(EMPTY_OBJECT, empty.members());
        assertSame(EMPTY_OBJECT, empty.positionMembers());
        assertSame(EMPTY_OBJECT, empty.orientationMembers());
        assertEquals("{}", empty.members().canonical());
        assertEquals("{}", empty.positionMembers().canonical());
        assertEquals("{}", empty.orientationMembers().canonical());
    }

    @Test
    @DisplayName("RegionExtras keeps each position apart and accepts an empty object in every slot")
    void regionExtrasAcceptsEmptyObjectInEverySlot() {
        final ExactJson.FrozenJson members = ExactJson.parse("{\"slot\":\"root\"}");
        final ExactJson.FrozenJson corners = ExactJson.parse("{\"slot\":\"corners\"}");
        final ExactJson.FrozenJson first = ExactJson.parse("{\"slot\":\"first\"}");
        final ExactJson.FrozenJson second = ExactJson.parse("{\"slot\":\"second\"}");

        final WireExtras.RegionExtras extras =
                new WireExtras.RegionExtras(members, corners, first, second);

        assertSame(members, extras.members());
        assertSame(corners, extras.cornersMembers());
        assertSame(first, extras.cornersFirstMembers());
        assertSame(second, extras.cornersSecondMembers());
        assertEquals("{\"slot\":\"corners\"}", extras.cornersMembers().canonical());
        assertEquals("{\"slot\":\"first\"}", extras.cornersFirstMembers().canonical());
        assertEquals("{\"slot\":\"second\"}", extras.cornersSecondMembers().canonical());

        // Every slot really is built from EMPTY_OBJECT in turn: same instance back, canonical "{}".
        final WireExtras.RegionExtras empty =
                new WireExtras.RegionExtras(EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);
        assertSame(EMPTY_OBJECT, empty.members());
        assertSame(EMPTY_OBJECT, empty.cornersMembers());
        assertSame(EMPTY_OBJECT, empty.cornersFirstMembers());
        assertSame(EMPTY_OBJECT, empty.cornersSecondMembers());
        assertEquals("{}", empty.members().canonical());
        assertEquals("{}", empty.cornersMembers().canonical());
        assertEquals("{}", empty.cornersFirstMembers().canonical());
        assertEquals("{}", empty.cornersSecondMembers().canonical());
    }

    @Test
    @DisplayName("every family accepts a multi field nested object, including an empty key")
    void everyFamilyAcceptsMultiFieldNestedObject() {
        final String text = "{\"\":0,\"a\":[1,2,3],\"b\":{\"c\":\"x\"},\"d\":null,\"e\":true,\"f\":-0.5}";
        final ExactJson.FrozenJson member = ExactJson.parse(text);
        assertEquals(text, member.canonical());

        for (final ExtrasFamily family : FAMILIES) {
            final Object extras = family.build(filled(family.arity(), member));
            for (int slot = 0; slot < family.arity(); slot++) {
                assertSame(member, family.member(extras, slot),
                        family + " slot " + slot + " must keep the supplied instance");
            }
        }
    }

    @Test
    @DisplayName("a reserved key is accepted at the shape layer, without claiming encodability")
    void acceptsReservedKeyIdAtShapeLayer() {
        final String text = "{\"id\":\"map-1\"}";
        final ExactJson.FrozenJson member = ExactJson.parse(text);

        final WireExtras.MapExtras extras =
                new WireExtras.MapExtras(member, EMPTY_OBJECT, EMPTY_OBJECT, EMPTY_OBJECT);

        assertSame(member, extras.members());
        assertEquals(text, extras.members().canonical());
    }

    @Test
    @DisplayName("boundary Unicode, escaped control characters, empty key and a 50 digit integer survive")
    void preservesUnicodeControlCharactersAndFiftyDigitInteger() {
        // The input uses JSON escapes; the canonical text keeps non-ASCII verbatim and re-escapes
        // the control character, so the expected literal below is deliberately not the input text.
        final String escapedInput = "{\"ctrl\":\"\\u0001\",\"emoji\":\"\\uD83D\\uDE00\","
                + "\"key\\u65e5\":\"\\u65e5\\u672c\\u8a9e\\u00e9\"}";
        final String expectedCanonical = "{\"ctrl\":\"\\u0001\",\"emoji\":\"\uD83D\uDE00\","
                + "\"key\u65e5\":\"\u65e5\u672c\u8a9e\u00e9\"}";

        final ExactJson.FrozenJson member = ExactJson.parse(escapedInput);
        assertEquals(expectedCanonical, member.canonical());
        assertSame(member, new WireExtras.TeamExtras(member).members());
        assertEquals(expectedCanonical, new WireExtras.TeamExtras(member).members().canonical());

        final String digits = "12345678901234567890123456789012345678901234567897";
        assertEquals(50, digits.length());
        final String bigIntegerText = "{\"big\":" + digits + "}";
        final ExactJson.FrozenJson bigInteger = ExactJson.parse(bigIntegerText);
        assertEquals(bigIntegerText, bigInteger.canonical());
        assertEquals(bigIntegerText, new WireExtras.ResourceExtras(bigInteger).members().canonical());

        final String emptyKeyText = "{\"\":1}";
        assertEquals(emptyKeyText, ExactJson.parse(emptyKeyText).canonical());
        assertEquals(emptyKeyText,
                new WireExtras.TeamExtras(ExactJson.parse(emptyKeyText)).members().canonical());
    }

    @Test
    @DisplayName("a maximum size key and a maximum size string are retained verbatim")
    void preservesBoundaryKeyAndStringSizes() {
        final String key = "k".repeat(ExactJson.MAX_KEY_UTF8_BYTES);
        final String keyText = "{\"" + key + "\":1}";
        final ExactJson.FrozenJson keyMember = ExactJson.parse(keyText);
        assertEquals(keyText, keyMember.canonical());
        assertEquals(keyText, new WireExtras.TeamExtras(keyMember).members().canonical());

        final String value = "s".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
        final String stringText = "{\"v\":\"" + value + "\"}";
        final ExactJson.FrozenJson stringMember = ExactJson.parse(stringText);
        assertEquals(stringText, stringMember.canonical());
        assertEquals(stringText, new WireExtras.TeamExtras(stringMember).members().canonical());
        assertSame(stringMember, new WireExtras.TeamExtras(stringMember).members());
    }

    // ------------------------------------------------------------------
    // position separation, equality and hash
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same unknown name with different values never crosses a geometry position")
    void sameUnknownNameAtDifferentPositionsDoesNotCross() {
        final ExactJson.FrozenJson root = ExactJson.parse("{\"gridHint\":\"root\"}");
        final ExactJson.FrozenJson first = ExactJson.parse("{\"gridHint\":\"legacy\"}");
        final ExactJson.FrozenJson second = ExactJson.parse("{\"gridHint\":\"modern\"}");

        final WireExtras.MapExtras map = new WireExtras.MapExtras(root, EMPTY_OBJECT, first, second);
        assertSame(root, map.members());
        assertSame(first, map.boundaryFirstMembers());
        assertSame(second, map.boundarySecondMembers());
        assertEquals("{\"gridHint\":\"root\"}", map.members().canonical());
        assertEquals("{\"gridHint\":\"legacy\"}", map.boundaryFirstMembers().canonical());
        assertEquals("{\"gridHint\":\"modern\"}", map.boundarySecondMembers().canonical());
        assertNotEquals(map, new WireExtras.MapExtras(root, EMPTY_OBJECT, second, first));
        assertNotEquals(map, new WireExtras.MapExtras(first, EMPTY_OBJECT, first, second));

        final WireExtras.SpawnExtras spawn =
                new WireExtras.SpawnExtras(root, first, second);
        assertEquals("{\"gridHint\":\"legacy\"}", spawn.positionMembers().canonical());
        assertEquals("{\"gridHint\":\"modern\"}", spawn.orientationMembers().canonical());
        assertNotEquals(spawn, new WireExtras.SpawnExtras(root, second, first));

        final WireExtras.RegionExtras region =
                new WireExtras.RegionExtras(root, EMPTY_OBJECT, first, second);
        assertEquals("{\"gridHint\":\"legacy\"}", region.cornersFirstMembers().canonical());
        assertEquals("{\"gridHint\":\"modern\"}", region.cornersSecondMembers().canonical());
        assertNotEquals(region, new WireExtras.RegionExtras(root, EMPTY_OBJECT, second, first));
    }

    @Test
    @DisplayName("equal canonical text makes equal records even from distinct frozen instances")
    void equalCanonicalTextMakesEqualRecords() {
        final ExactJson.FrozenJson one = ExactJson.parse("{\"a\":1}");
        final ExactJson.FrozenJson oneAsDecimal = ExactJson.parse("{\"a\":1.0}");
        final ExactJson.FrozenJson oneAgain = ExactJson.parse("{\"a\":1}");

        assertNotSame(one, oneAsDecimal);
        assertEquals(one, oneAsDecimal);

        final WireExtras.TeamExtras fromOne = new WireExtras.TeamExtras(one);
        final WireExtras.TeamExtras fromDecimal = new WireExtras.TeamExtras(oneAsDecimal);
        final WireExtras.TeamExtras fromAgain = new WireExtras.TeamExtras(oneAgain);

        assertNotSame(fromOne, fromDecimal);
        assertEquals(fromOne, fromDecimal);
        assertEquals(fromOne.hashCode(), fromDecimal.hashCode());
        assertEquals(fromOne, fromAgain);
        assertEquals(fromOne.hashCode(), fromAgain.hashCode());

        assertEquals(new WireExtras.MapExtras(one, EMPTY_OBJECT, one, one),
                new WireExtras.MapExtras(oneAgain, EMPTY_OBJECT, oneAsDecimal, oneAgain));
    }

    @Test
    @DisplayName("different unknown values, including true against false, are not equal")
    void differentUnknownValuesAreNotEqual() {
        final WireExtras.TeamExtras flagTrue =
                new WireExtras.TeamExtras(ExactJson.parse("{\"futureFlag\":true}"));
        final WireExtras.TeamExtras flagFalse =
                new WireExtras.TeamExtras(ExactJson.parse("{\"futureFlag\":false}"));

        assertNotEquals(flagTrue, flagFalse);
        assertNotEquals(flagTrue.hashCode(), flagFalse.hashCode());
        assertNotEquals(flagTrue, new WireExtras.TeamExtras(EMPTY_OBJECT));
        assertNotEquals(flagTrue, new WireExtras.TeamExtras(ExactJson.parse("{\"futureFlag\":1}")));
    }

    @Test
    @DisplayName("every single component difference breaks equality and is visible through getters")
    void everyComponentDifferenceBreaksEquality() {
        final ExactJson.FrozenJson base = ExactJson.parse("{\"k\":1}");
        final ExactJson.FrozenJson other = ExactJson.parse("{\"k\":2}");

        final WireExtras.MapExtras map = new WireExtras.MapExtras(base, base, base, base);
        assertEquals(map, new WireExtras.MapExtras(base, base, base, base));
        assertEquals(map.hashCode(), new WireExtras.MapExtras(base, base, base, base).hashCode());
        for (int slot = 0; slot < 4; slot++) {
            final ExactJson.FrozenJson[] members = filled(4, base);
            members[slot] = other;
            final WireExtras.MapExtras changed = new WireExtras.MapExtras(members[0], members[1],
                    members[2], members[3]);
            assertNotEquals(map, changed, "MapExtras slot " + slot + " must take part in equals");
            assertSame(other, mapReader(changed, slot));
        }

        final WireExtras.SpawnExtras spawn = new WireExtras.SpawnExtras(base, base, base);
        assertEquals(spawn, new WireExtras.SpawnExtras(base, base, base));
        for (int slot = 0; slot < 3; slot++) {
            final ExactJson.FrozenJson[] members = filled(3, base);
            members[slot] = other;
            final WireExtras.SpawnExtras changed =
                    new WireExtras.SpawnExtras(members[0], members[1], members[2]);
            assertNotEquals(spawn, changed, "SpawnExtras slot " + slot + " must take part in equals");
            assertSame(other, spawnReader(changed, slot));
        }

        final WireExtras.RegionExtras region = new WireExtras.RegionExtras(base, base, base, base);
        assertEquals(region, new WireExtras.RegionExtras(base, base, base, base));
        for (int slot = 0; slot < 4; slot++) {
            final ExactJson.FrozenJson[] members = filled(4, base);
            members[slot] = other;
            final WireExtras.RegionExtras changed = new WireExtras.RegionExtras(members[0],
                    members[1], members[2], members[3]);
            assertNotEquals(region, changed, "RegionExtras slot " + slot + " must take part in equals");
            assertSame(other, regionReader(changed, slot));
        }

        assertNotEquals(new WireExtras.TeamExtras(base), new WireExtras.TeamExtras(other));
        assertNotEquals(new WireExtras.ResourceExtras(base), new WireExtras.ResourceExtras(other));
    }

    private static ExactJson.FrozenJson mapReader(WireExtras.MapExtras extras, int slot) {
        switch (slot) {
            case 0:
                return extras.members();
            case 1:
                return extras.boundaryMembers();
            case 2:
                return extras.boundaryFirstMembers();
            default:
                return extras.boundarySecondMembers();
        }
    }

    private static ExactJson.FrozenJson spawnReader(WireExtras.SpawnExtras extras, int slot) {
        switch (slot) {
            case 0:
                return extras.members();
            case 1:
                return extras.positionMembers();
            default:
                return extras.orientationMembers();
        }
    }

    private static ExactJson.FrozenJson regionReader(WireExtras.RegionExtras extras, int slot) {
        switch (slot) {
            case 0:
                return extras.members();
            case 1:
                return extras.cornersMembers();
            case 2:
                return extras.cornersFirstMembers();
            default:
                return extras.cornersSecondMembers();
        }
    }

    // ------------------------------------------------------------------
    // isolation from polluted sources and returned copies
    // ------------------------------------------------------------------

    @Test
    @DisplayName("polluting the frozen Gson source tree leaves every extras member unchanged")
    void pollutingSourceGsonTreeKeepsExtras() {
        final JsonObject source = new JsonObject();
        source.addProperty("futureFlag", true);
        final JsonObject nested = new JsonObject();
        nested.addProperty("a", 1);
        source.add("nested", nested);

        final ExactJson.FrozenJson member = ExactJson.freeze(source);
        final String before = member.canonical();
        assertEquals("{\"futureFlag\":true,\"nested\":{\"a\":1}}", before);

        final WireExtras.MapExtras extras =
                new WireExtras.MapExtras(member, member, member, member);

        source.addProperty("later", "polluted");
        source.remove("futureFlag");
        nested.addProperty("a", 999);
        source.add("array", new JsonArray());
        source.add("nested", new JsonPrimitive("replaced"));

        for (int slot = 0; slot < 4; slot++) {
            assertSame(member, mapReader(extras, slot));
            assertEquals(before, mapReader(extras, slot).canonical());
        }
        assertEquals(before, new WireExtras.TeamExtras(member).members().canonical());
    }

    @Test
    @DisplayName("polluting toJson and canonicalUtf8 copies leaves every extras member unchanged")
    void pollutingReturnedCopiesKeepsExtras() {
        final ExactJson.FrozenJson member = ExactJson.parse("{\"futureFlag\":true,\"nested\":{\"a\":1}}");
        final String before = member.canonical();
        final WireExtras.RegionExtras extras =
                new WireExtras.RegionExtras(member, member, member, member);

        final JsonElement copy = member.toJson();
        final JsonObject copyObject = copy.getAsJsonObject();
        copyObject.addProperty("polluted", true);
        copyObject.getAsJsonObject("nested").addProperty("a", 999);
        copyObject.remove("futureFlag");
        final JsonArray injected = new JsonArray();
        injected.add("injected");
        copyObject.add("array", injected);

        final byte[] bytes = member.canonicalUtf8();
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) 'x';
        }

        for (int slot = 0; slot < 4; slot++) {
            assertSame(member, regionReader(extras, slot));
            assertEquals(before, regionReader(extras, slot).canonical());
        }
        assertEquals(before, ExactJson.freeze(member.toJson()).canonical());
        assertArrayEquals(before.getBytes(StandardCharsets.UTF_8), member.canonicalUtf8());
        assertNotSame(bytes, member.canonicalUtf8());
    }

    // ------------------------------------------------------------------
    // identity reuse: no clone, no re-parse, no full node walk
    //
    // Identity, not the heap limit, is the evidence: every getter below must return the exact
    // instance passed in. The -Xmx64m run only rules out duplication that is retained.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a large member is reused by identity in every family and every slot")
    void largeMemberIsReusedByIdentity() {
        final String shared = "z".repeat(65536);
        final JsonPrimitive sharedValue = new JsonPrimitive(shared);
        final JsonObject root = new JsonObject();
        for (int index = 0; index < 16; index++) {
            root.add("k" + (char) ('a' + index), sharedValue);
        }

        final ExactJson.FrozenJson member = ExactJson.freeze(root);
        assertTrue(member.utf8Size() > 1000000,
                "the fixture must be large enough to make cloning observable: " + member.utf8Size());
        final String before = member.canonical();
        final int beforeSize = member.utf8Size();

        for (final ExtrasFamily family : FAMILIES) {
            final Object extras = family.build(filled(family.arity(), member));
            for (int slot = 0; slot < family.arity(); slot++) {
                assertSame(member, family.member(extras, slot));
                assertEquals(beforeSize, family.member(extras, slot).utf8Size());
            }
        }
        assertEquals(before, member.canonical());
        assertEquals(beforeSize, member.utf8Size());
    }

    @Test
    @DisplayName("10000 constructions of all five families reuse the immutable reference")
    void reusesImmutableReferencesOverTenThousandConstructions() {
        final String shared = "z".repeat(65536);
        final JsonPrimitive sharedValue = new JsonPrimitive(shared);
        final JsonObject root = new JsonObject();
        for (int index = 0; index < 16; index++) {
            root.add("k" + (char) ('a' + index), sharedValue);
        }

        final ExactJson.FrozenJson member = ExactJson.freeze(root);
        final String before = member.canonical();
        final int beforeSize = member.utf8Size();
        assertTrue(beforeSize > 1000000, "fixture canonical size: " + beforeSize);

        int constructed = 0;
        for (int iteration = 0; iteration < 10000; iteration++) {
            final WireExtras.MapExtras map =
                    new WireExtras.MapExtras(member, member, member, member);
            final WireExtras.TeamExtras team = new WireExtras.TeamExtras(member);
            final WireExtras.SpawnExtras spawn = new WireExtras.SpawnExtras(member, member, member);
            final WireExtras.RegionExtras region =
                    new WireExtras.RegionExtras(member, member, member, member);
            final WireExtras.ResourceExtras resource = new WireExtras.ResourceExtras(member);

            for (int slot = 0; slot < 4; slot++) {
                assertSame(member, mapReader(map, slot));
                assertSame(member, regionReader(region, slot));
            }
            for (int slot = 0; slot < 3; slot++) {
                assertSame(member, spawnReader(spawn, slot));
            }
            assertSame(member, team.members());
            assertSame(member, resource.members());
            constructed += 5;
        }

        assertEquals(50000, constructed);
        assertEquals(before, member.canonical());
        assertEquals(beforeSize, member.utf8Size());
    }
}
