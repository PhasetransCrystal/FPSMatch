package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link CanonicalObjectMembers} (R06.2b1).
 *
 * <p>Every fixture is certified by the frozen R02 codec before a cursor may look at it, and every
 * expectation is built independently of the cursor: the canonical texts are either given literally
 * or produced by the local {@link Oracle}, which registers the key range, the value range and the
 * decoded key while it writes the text. Ranges are never discovered by searching the canonical text
 * and never taken from cursor output, so a cursor that returned wrong offsets cannot make a test
 * pass by agreeing with itself.</p>
 *
 * <p>Tests carry the {@code small} or {@code large} tag; the author-side runner selects a profile by
 * tag and pins the discovered test count for each profile.</p>
 */
class CanonicalObjectMembersContractTest {

    /** All printable ASCII, used to fill large values without any escape sequence. */
    private static final String FILLER =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    /** One astral code point as the two UTF-16 units the canonical text stores. */
    private static final String ASTRAL = "\uD83D\uDE00";

    /** Independently chosen canonical number spellings: no trailing zero, so no normalization. */
    private static final String[] CANONICAL_LITERALS = {
        "0", "1", "-1", "42", "7", "19", "-23", "1234567", "3", "99", "null", "true", "false"
    };

    // ------------------------------------------------------------------ rejection and empty object

    @Test
    @Tag("small")
    void nullFrozenIsRejectedWithIae() {
        final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> CanonicalObjectMembers.cursor(null));
        assertTrue(failure.getMessage() != null && !failure.getMessage().isEmpty());
    }

    @Test
    @Tag("small")
    void allFiveNonObjectShapesAreRejectedWithIae() {
        final List<ExactJson.FrozenJson> shapes = new ArrayList<>();
        shapes.add(ExactJson.parse("null"));
        shapes.add(ExactJson.parse("[]"));
        shapes.add(ExactJson.parse("\"text\""));
        shapes.add(ExactJson.parse("42"));
        shapes.add(ExactJson.parse("true"));
        for (ExactJson.FrozenJson shape : shapes) {
            assertThrows(IllegalArgumentException.class, () -> CanonicalObjectMembers.cursor(shape),
                    "shape must be rejected: " + shape.canonical());
        }
    }

    @Test
    @Tag("small")
    void emptyObjectHasNoMemberAndRealNsee() {
        final ExactJson.FrozenJson frozen = certified("{}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        assertSame(frozen.canonical(), cursor.canonical());
        assertFalse(cursor.hasNext());
        assertFalse(cursor.hasNext());
        assertThrows(NoSuchElementException.class, cursor::next);
        assertFalse(cursor.hasNext());
    }

    // -------------------------------------------------------------- peeking, order and independence

    @Test
    @Tag("small")
    void hasNextIsRepeatableAndNeverAdvances() {
        final ExactJson.FrozenJson frozen = certified("{\"a\":1,\"b\":2}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        assertTrue(cursor.hasNext());
        assertTrue(cursor.hasNext());
        assertTrue(cursor.hasNext());
        assertEquals("a", cursor.next().key());
        assertTrue(cursor.hasNext());
        assertTrue(cursor.hasNext());
        assertEquals("b", cursor.next().key());
        assertFalse(cursor.hasNext());
        assertFalse(cursor.hasNext());
        assertFalse(cursor.hasNext());
    }

    @Test
    @Tag("small")
    void twoCursorsOverOneFreezeAdvanceIndependently() {
        final ExactJson.FrozenJson frozen = certified("{\"a\":1,\"b\":2,\"c\":3}");
        final CanonicalObjectMembers.Cursor first = CanonicalObjectMembers.cursor(frozen);
        final CanonicalObjectMembers.Cursor second = CanonicalObjectMembers.cursor(frozen);
        assertEquals("a", first.next().key());
        assertEquals("a", second.next().key());
        assertEquals("b", first.next().key());
        assertEquals("c", first.next().key());
        assertEquals("b", second.next().key());
        assertFalse(first.hasNext());
        assertTrue(second.hasNext());
        assertEquals("c", second.next().key());
        assertFalse(second.hasNext());
        final ExactJson.FrozenJson other = certified("{\"z\":9}");
        final CanonicalObjectMembers.Cursor third = CanonicalObjectMembers.cursor(other);
        assertTrue(second.hasNext() == false);
        assertEquals("z", third.next().key());
        assertFalse(third.hasNext());
    }

    // -------------------------------------------------------------- value shapes, depth and content

    @Test
    @Tag("small")
    void everyScalarShapeAndBothContainerKindsAreRanged() {
        final String expected =
                "{\"arr\":[],\"bool\":true,\"nested\":{\"in\":[1,2]},\"nul\":null,\"num\":-23,"
                        + "\"str\":\"s\"}";
        final Oracle oracle = new Oracle()
                .member("arr", "[]")
                .member("bool", "true")
                .member("nested", "{\"in\":[1,2]}")
                .member("nul", "null")
                .member("num", "-23")
                .member("str", quote("s"));
        assertEquals(expected, oracle.text(), "fixture must be the given canonical text");
        oracle.verifyAll();
    }

    @Test
    @Tag("small")
    void sixtyFourLevelNestedValueIsSkippedWithoutRecursion() {
        final String deep = repeat('[', 64) + repeat(']', 64);
        final String text = "{" + quote("deep") + ":" + deep + "}";
        final ExactJson.FrozenJson frozen = certified(text);
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        final CanonicalObjectMembers.Member member = cursor.next();
        assertEquals("deep", member.key());
        assertEquals(1, member.keyStart());
        assertEquals(7, member.keyEnd());
        assertEquals(8, member.valueStart());
        assertEquals(136, member.valueEnd());
        assertEquals(text.substring(8, 136), frozen.canonical().substring(8, 136));
        assertEquals(128, member.valueEnd() - member.valueStart());
        assertEquals(137, frozen.canonical().length());
        assertFalse(cursor.hasNext());
        assertThrows(NoSuchElementException.class, cursor::next);
    }

    @Test
    @Tag("small")
    void structuralCharactersInsideStringsNeverTerminateAValue() {
        final String tricky = "{}[],:\"\\/";
        final String nested = "[1,2,{\"x\":[]}]";
        final String text = "{" + quote("k") + ":" + quote(tricky) + "," + quote("k2") + ":"
                + quote(nested) + "}";
        final Oracle oracle = new Oracle()
                .member("k", quote(tricky))
                .member("k2", quote(nested));
        assertEquals(text, oracle.text());
        oracle.verifyAll();
    }

    @Test
    @Tag("small")
    void everyShortEscapeDecodesInKeyAndValue() {
        final String decoded = "\"\\/\b\f\n\r\t";
        final Oracle oracle = new Oracle().member(decoded, quote(decoded));
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Member member =
                CanonicalObjectMembers.cursor(frozen).next();
        assertEquals(decoded, member.key());
        assertEquals(quote(decoded), frozen.canonical()
                .substring(member.valueStart(), member.valueEnd()));
        assertTrue(quote(decoded).length() > decoded.length(), "escapes must be visible in the range");
        // The canonical writer never escapes the solidus, so the cursor only ever meets a raw '/'
        // even when the original document wrote the optional \/ escape.
        final ExactJson.FrozenJson escaped = ExactJson.parse("{\"a\\/b\":\"x\\/y\"}");
        assertEquals("{\"a/b\":\"x/y\"}", escaped.canonical(), "canonical unescapes the solidus");
        final CanonicalObjectMembers.Cursor solidus =
                CanonicalObjectMembers.cursor(escaped);
        assertEquals("a/b", solidus.next().key());
        assertFalse(solidus.hasNext());
    }

    @Test
    @Tag("small")
    void everyControlEscapeDecodesInKeyAndValue() {
        final String key = "\u0000\u0001\u001f";
        final String value = "\u0002\u0003\u001e\u001f\u007f";
        final Oracle oracle = new Oracle().member(key, quote(value));
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        final CanonicalObjectMembers.Member member = cursor.next();
        assertEquals(3, member.key().length());
        assertEquals('\u0000', member.key().charAt(0));
        assertEquals('\u001f', member.key().charAt(2));
        assertEquals(key, member.key());
        assertEquals(quote(value), frozen.canonical()
                .substring(member.valueStart(), member.valueEnd()));
        assertFalse(cursor.hasNext());
    }

    @Test
    @Tag("small")
    void unicodeEmojiAndNormalizationFormsArePreserved() {
        final TreeMap<String, String> members = new TreeMap<>();
        members.put("e\u0301", quote("nfd"));
        members.put("\u00e9", quote("nfc"));
        members.put("\u952e", quote("\u952e\u503c"));
        members.put(ASTRAL, quote(ASTRAL + ASTRAL));
        members.put("key\u00e9", quote("plain"));
        final Oracle oracle = new Oracle();
        for (Map.Entry<String, String> entry : members.entrySet()) {
            oracle.member(entry.getKey(), entry.getValue());
        }
        oracle.verifyAll();
        final List<String> seen = keysOf(oracle.freeze());
        assertEquals(new ArrayList<>(members.keySet()), seen);
        assertNotEquals(seen.get(0), seen.get(1), "NFC and NFD must stay distinct");
    }

    @Test
    @Tag("small")
    void emptyKeyAndEmptyStringValueAreSupported() {
        final Oracle oracle = new Oracle()
                .member("", quote(""))
                .member("a", quote(""));
        assertEquals("{\"\":\"\",\"a\":\"\"}", oracle.text());
        oracle.verifyAll();
    }

    // ------------------------------------------------------------------- range unit and token sizes

    @Test
    @Tag("small")
    void rangeUnitIsUtf16CharactersNotUtf8Bytes() {
        final String key = "\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9";
        final String value = "caf\u00e9\u00e9";
        final Oracle oracle = new Oracle().member(key, quote(value));
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Member member =
                CanonicalObjectMembers.cursor(frozen).next();
        assertEquals(key.length() + 2, member.keyEnd() - member.keyStart());
        assertEquals(quote(key).length(), member.keyEnd() - member.keyStart());
        assertTrue(quote(key).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                > member.keyEnd() - member.keyStart(), "UTF-8 is longer than the UTF-16 range");
        assertEquals(1, member.keyStart());
        assertEquals(quote(value).length(), member.valueEnd() - member.valueStart());
        assertEquals(frozen.canonical().length() - 1, member.valueEnd());
    }

    @Test
    @Tag("small")
    void keyAtMaximumUtf8BudgetStaysABoundedSmallString() {
        final String key = "\u00e9".repeat(512);
        assertEquals(1024, key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        final String value = "v";
        final Oracle oracle = new Oracle().member(key, quote(value));
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        final CanonicalObjectMembers.Member member = cursor.next();
        assertEquals(512, member.key().length());
        assertEquals(key, member.key());
        assertEquals(quote(key).length(), member.keyEnd() - member.keyStart());
        assertFalse(cursor.hasNext());
        assertThrows(NoSuchElementException.class, cursor::next);
        assertTrue(frozen.utf8Size() > 1024, "the quoted key token is charged to the document budget");
    }

    @Test
    @Tag("small")
    void valueAtMaximumStringBudgetIsReturnedAsRangeOnly() {
        final int contentBytes = 65536;
        final String value = "a".repeat(contentBytes);
        final Oracle oracle = new Oracle().member("big", quote(value));
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Member member =
                CanonicalObjectMembers.cursor(frozen).next();
        assertEquals(contentBytes + 2, member.valueEnd() - member.valueStart());
        assertEquals(7, member.valueStart());
        assertEquals(frozen.canonical().length() - 1, member.valueEnd());
        final String token = quote(value);
        assertEquals("\"" + "a".repeat(15), frozen.canonical()
                .substring(member.valueStart(), member.valueStart() + 16));
        assertEquals("a".repeat(15) + "\"", frozen.canonical()
                .substring(member.valueEnd() - 16, member.valueEnd()));
        assertEquals(fnv(token, 0, token.length()),
                fnv(frozen.canonical(), member.valueStart(), member.valueEnd()));
    }

    @Test
    @Tag("small")
    void severalLargeValuesAreReturnedAsRangesOnly() {
        final int[] sizes = {4096, 8192, 16384, 32768, 12288};
        final Oracle oracle = new Oracle();
        for (int index = 0; index < sizes.length; index++) {
            oracle.member("v" + index, fillerToken("v" + index, sizes[index]));
        }
        final ExactJson.FrozenJson frozen = oracle.freeze();
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        long scanned = 0L;
        for (int index = 0; index < sizes.length; index++) {
            final CanonicalObjectMembers.Member member = cursor.next();
            assertEquals(sizes[index], member.valueEnd() - member.valueStart());
            assertEquals(oracle.valueToken(index).substring(0, 12), frozen.canonical()
                    .substring(member.valueStart(), member.valueStart() + 12));
            scanned += member.valueEnd() - member.valueStart();
        }
        assertFalse(cursor.hasNext());
        assertEquals(73728L, scanned);
        assertEquals(20 + 5 + 73728 + 4 + 2, frozen.canonical().length());
    }

    // --------------------------------------------------------------------- boundaries of each range

    @Test
    @Tag("small")
    void scalarValueRangeEndsAtCommaOrOwnerBrace() {
        final ExactJson.FrozenJson frozen = certified("{\"a\":1,\"b\":true}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        final CanonicalObjectMembers.Member first = cursor.next();
        assertEquals(',', frozen.canonical().charAt(first.valueEnd()));
        assertEquals("1", frozen.canonical().substring(first.valueStart(), first.valueEnd()));
        final CanonicalObjectMembers.Member second = cursor.next();
        assertEquals('}', frozen.canonical().charAt(second.valueEnd()));
        assertEquals("true", frozen.canonical().substring(second.valueStart(), second.valueEnd()));
        final ExactJson.FrozenJson single = certified("{\"a\":null}");
        final CanonicalObjectMembers.Member only =
                CanonicalObjectMembers.cursor(single).next();
        assertEquals(single.canonical().length() - 1, only.valueEnd());
        assertEquals("null", single.canonical().substring(only.valueStart(), only.valueEnd()));
    }

    @Test
    @Tag("small")
    void syntaxIsNeverInsideAKeyOrValueRange() {
        final ExactJson.FrozenJson frozen = certified("{\"a\":1,\"b\":[1],\"c\":{\"d\":2}}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        while (cursor.hasNext()) {
            final CanonicalObjectMembers.Member member = cursor.next();
            final char beforeKey = frozen.canonical().charAt(member.keyStart() - 1);
            assertTrue(beforeKey == '{' || beforeKey == ',', "key must start after { or ,");
            assertEquals('"', frozen.canonical().charAt(member.keyStart()));
            assertEquals('"', frozen.canonical().charAt(member.keyEnd() - 1));
            assertEquals(':', frozen.canonical().charAt(member.keyEnd()));
            final char afterValue = frozen.canonical().charAt(member.valueEnd());
            assertTrue(afterValue == ',' || afterValue == '}', "value must end before , or }");
            assertEquals(quote(member.key()),
                    frozen.canonical().substring(member.keyStart(), member.keyEnd()));
            assertTrue(member.keyEnd() - member.keyStart() > member.key().length(),
                    "the quoted key token is longer than the decoded key");
        }
    }

    @Test
    @Tag("small")
    void nextPastLastMemberThrowsRealNoSuchElement() {
        final ExactJson.FrozenJson frozen = certified("{\"only\":\"value\"}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        assertEquals("only", cursor.next().key());
        final NoSuchElementException failure =
                assertThrows(NoSuchElementException.class, cursor::next);
        assertTrue(failure.getMessage() != null && !failure.getMessage().isEmpty());
        assertThrows(NoSuchElementException.class, cursor::next);
        assertFalse(cursor.hasNext());
    }

    @Test
    @Tag("small")
    void canonicalReferenceIsPreservedEndToEnd() {
        final ExactJson.FrozenJson frozen = certified("{\"a\":1,\"b\":2}");
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        assertSame(frozen.canonical(), cursor.canonical());
        final CanonicalObjectMembers.Member first = cursor.next();
        assertSame(frozen.canonical(), cursor.canonical());
        final CanonicalObjectMembers.Member second = cursor.next();
        assertSame(frozen.canonical(), cursor.canonical());
        assertNotEquals(first, second);
        final CanonicalObjectMembers.Member copy = new CanonicalObjectMembers.Member(
                first.key(), first.keyStart(), first.keyEnd(), first.valueStart(), first.valueEnd());
        assertEquals(first, copy);
        assertEquals(first.hashCode(), copy.hashCode());
        assertTrue(first.toString().contains("keyStart="), "record semantics are kept");
        assertEquals("a", first.key());
    }

    @Test
    @Tag("small")
    void astralValueRangeLengthIsUtf16Length() {
        final ExactJson.FrozenJson frozen = certified("{" + quote("k") + ":" + quote(ASTRAL + ASTRAL)
                + "}");
        final CanonicalObjectMembers.Member member =
                CanonicalObjectMembers.cursor(frozen).next();
        assertEquals(6, member.valueEnd() - member.valueStart());
        assertEquals(quote(ASTRAL + ASTRAL), frozen.canonical()
                .substring(member.valueStart(), member.valueEnd()));
        assertEquals(10, quote(ASTRAL + ASTRAL).getBytes(
                java.nio.charset.StandardCharsets.UTF_8).length);
    }

    @Test
    @Tag("small")
    void nestedContainerAndScalarMixIsRangedExactly() {
        final Oracle oracle = new Oracle()
                .member("deep", "[[[]]]")
                .member("empty", "{}")
                .member("mix", "{\"a\":[1,{\"b\":\"x\"}],\"c\":[]}")
                .member("plain", "7");
        oracle.verifyAll();
    }

    @Test
    @Tag("small")
    void memberOrderFollowsStringCompareToForMixedScripts() {
        final TreeMap<String, String> members = new TreeMap<>();
        members.put("", "0");
        members.put("Z", "1");
        members.put("a", "2");
        members.put("~", "3");
        members.put("\u952e", "4");
        members.put(ASTRAL, "5");
        final Oracle oracle = new Oracle();
        for (Map.Entry<String, String> entry : members.entrySet()) {
            oracle.member(entry.getKey(), entry.getValue());
        }
        oracle.verifyAll();
        assertEquals(new ArrayList<>(members.keySet()), keysOf(oracle.freeze()));
    }

    @Test
    @Tag("small")
    void cursorIgnoresMutableCopiesOfTheFreeze() {
        final String text = "{\"a\":1,\"b\":[true,null]}";
        final ExactJson.FrozenJson frozen = certified(text);
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        com.google.gson.JsonObject copy = (com.google.gson.JsonObject) frozen.toJson();
        copy.addProperty("injected", "later");
        copy.remove("a");
        final ExactJson.FrozenJson reprinted = certified(text);
        final CanonicalObjectMembers.Cursor other = CanonicalObjectMembers.cursor(reprinted);
        assertEquals(2, cursorValueCount(cursor));
        assertEquals(2, cursorValueCount(other));
        assertEquals(2, cursorValueCount(CanonicalObjectMembers.cursor(certified(text))));
    }

    // ------------------------------------------------------------------------- seeded oracle sweep

    @Test
    @Tag("small")
    void thousandSeededObjectsMatchIndependentOracle() {
        final Random random = new Random(20261015L);
        final Set<String> distinctTexts = new HashSet<>();
        int memberTotal = 0;
        for (int index = 0; index < 1000; index++) {
            final TreeMap<String, String> entries = new TreeMap<>();
            entries.put("idx" + index, randomValueText(random, 1));
            final int extra = random.nextInt(4);
            for (int slot = 0; slot < extra; slot++) {
                entries.put(randomKeyText(random), randomValueText(random, 1));
            }
            final Oracle oracle = new Oracle();
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                oracle.member(entry.getKey(), entry.getValue());
            }
            distinctTexts.add(oracle.text());
            oracle.verifyAll();
            assertTrue(oracle.size() >= 1);
            memberTotal += oracle.size();
        }
        assertEquals(1000, distinctTexts.size(), "every seeded object must be distinct");
        assertEquals(2461, memberTotal, "the seeded member total must be reproducible");
        System.out.println("[evidence] seeded_objects=1000 seeded_members=" + memberTotal);
    }

    // ------------------------------------------------------------------------------- large profiles

    @Test
    @Tag("large")
    void nearEightMebibyteObjectIsFullyScannedAsRangesOnly() {
        final int mediumCount = 100;
        final int mediumToken = 40000;
        final int largeCount = 64;
        final int largeToken = 65000;
        final StringBuilder text = new StringBuilder("{");
        final List<String> keys = new ArrayList<>();
        final List<int[]> keyRanges = new ArrayList<>();
        final List<int[]> valueRanges = new ArrayList<>();
        final List<Long> valueDigests = new ArrayList<>();
        final List<String> valueHeads = new ArrayList<>();
        final List<String> valueTails = new ArrayList<>();
        long totalValueChars = 0L;
        int largestStringBytes = 0;
        final List<String> plannedKeys = new ArrayList<>();
        final List<Integer> plannedTokens = new ArrayList<>();
        for (int index = 0; index < largeCount; index++) {
            plannedKeys.add("large-" + pad(index, 3));
            plannedTokens.add(largeToken);
        }
        for (int index = 0; index < mediumCount; index++) {
            plannedKeys.add("medium-" + pad(index, 3));
            plannedTokens.add(mediumToken);
        }
        assertTrue(isSortedAscending(plannedKeys),
                "hand written fixture keys must already be in canonical String order");
        for (int index = 0; index < plannedKeys.size(); index++) {
            if (index > 0) {
                text.append(',');
            }
            final String key = plannedKeys.get(index);
            final int tokenLength = plannedTokens.get(index);
            final int keyStart = text.length();
            text.append(quote(key));
            keyRanges.add(new int[] {keyStart, text.length()});
            text.append(':');
            final int valueStart = text.length();
            final String token = fillerToken(key, tokenLength);
            text.append(token);
            valueRanges.add(new int[] {valueStart, text.length()});
            valueDigests.add(fnv(token, 0, token.length()));
            valueHeads.add(token.substring(0, 16));
            valueTails.add(token.substring(token.length() - 16));
            totalValueChars += token.length();
            largestStringBytes = Math.max(largestStringBytes, tokenLength - 2);
            keys.add(key);
        }
        text.append('}');
        final String canonicalText = text.toString();
        final ExactJson.FrozenJson frozen = ExactJson.parse(canonicalText);
        assertSameText(canonicalText, frozen.canonical(), "large fixture must already be canonical");
        assertEquals(canonicalText.length(), frozen.utf8Size(), "fixture is ASCII only");
        assertTrue(frozen.utf8Size() > 8000000,
                "fixture must be near the 8 MiB budget, was " + frozen.utf8Size());
        assertTrue(frozen.utf8Size() <= ExactJson.MAX_UTF8_BYTES);
        assertTrue(largestStringBytes <= ExactJson.MAX_STRING_UTF8_BYTES);
        System.out.println("[evidence] large1_canonical_utf8_bytes=" + frozen.utf8Size()
                + " large1_chars=" + canonicalText.length()
                + " large1_members=" + keys.size()
                + " large1_largest_string_bytes=" + largestStringBytes);

        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        assertSame(frozen.canonical(), cursor.canonical());
        long scannedValueChars = 0L;
        int seen = 0;
        while (cursor.hasNext()) {
            final CanonicalObjectMembers.Member member = cursor.next();
            assertEquals(keys.get(seen), member.key(), "key at " + seen);
            assertArrayEquals(keyRanges.get(seen),
                    new int[] {member.keyStart(), member.keyEnd()}, "key range at " + seen);
            assertArrayEquals(valueRanges.get(seen),
                    new int[] {member.valueStart(), member.valueEnd()}, "value range at " + seen);
            assertEquals(valueDigests.get(seen).longValue(),
                    fnv(frozen.canonical(), member.valueStart(), member.valueEnd()),
                    "value digest at " + seen);
            assertEquals(valueHeads.get(seen), frozen.canonical()
                    .substring(member.valueStart(), member.valueStart() + 16), "head at " + seen);
            assertEquals(valueTails.get(seen), frozen.canonical()
                    .substring(member.valueEnd() - 16, member.valueEnd()), "tail at " + seen);
            assertSame(frozen.canonical(), cursor.canonical());
            scannedValueChars += member.valueEnd() - member.valueStart();
            seen++;
        }
        assertEquals(keys.size(), seen, "cursor stopped early");
        assertEquals(totalValueChars, scannedValueChars, "value ranges must tile the fixture");
        assertFalse(cursor.hasNext());
        assertThrows(NoSuchElementException.class, cursor::next);
        System.out.println("[evidence] large1_scanned_value_chars=" + scannedValueChars);
    }

    @Test
    @Tag("large")
    void nearFiveMebibyteAstralMatrixIsScannedBoundedly() {
        final int memberCount = 60;
        final int itemsPerMember = 20;
        final StringBuilder text = new StringBuilder("{");
        final List<String> keys = new ArrayList<>();
        final List<int[]> keyRanges = new ArrayList<>();
        final List<int[]> valueRanges = new ArrayList<>();
        final List<Long> valueDigests = new ArrayList<>();
        final List<String> valueHeads = new ArrayList<>();
        final List<String> valueTails = new ArrayList<>();
        long totalValueChars = 0L;
        int largestItemBytes = 0;
        for (int index = 0; index < memberCount; index++) {
            if (index > 0) {
                text.append(',');
            }
            final String key = "matrix-" + pad(index, 2);
            final int keyStart = text.length();
            text.append(quote(key));
            keyRanges.add(new int[] {keyStart, text.length()});
            text.append(':');
            final int valueStart = text.length();
            final StringBuilder token = new StringBuilder("[");
            for (int item = 0; item < itemsPerMember; item++) {
                if (item > 0) {
                    token.append(',');
                }
                final String content = "i" + index + "-j" + item + ASTRAL.repeat(1000);
                largestItemBytes = Math.max(largestItemBytes, content.getBytes(
                        java.nio.charset.StandardCharsets.UTF_8).length);
                token.append(quote(content));
            }
            token.append(']');
            final String valueToken = token.toString();
            text.append(valueToken);
            valueRanges.add(new int[] {valueStart, text.length()});
            valueDigests.add(fnv(valueToken, 0, valueToken.length()));
            valueHeads.add(valueToken.substring(0, 12));
            valueTails.add(valueToken.substring(valueToken.length() - 12));
            totalValueChars += valueToken.length();
            keys.add(key);
        }
        text.append('}');
        final String canonicalText = text.toString();
        final ExactJson.FrozenJson frozen = ExactJson.parse(canonicalText);
        assertSameText(canonicalText, frozen.canonical(), "astral fixture must already be canonical");
        assertTrue(largestItemBytes <= ExactJson.MAX_STRING_UTF8_BYTES);
        assertTrue(frozen.utf8Size() > 4000000,
                "fixture must stay large, was " + frozen.utf8Size());
        assertEquals(frozen.canonicalUtf8().length, frozen.utf8Size());
        System.out.println("[evidence] large2_canonical_utf8_bytes=" + frozen.utf8Size()
                + " large2_chars=" + canonicalText.length()
                + " large2_members=" + keys.size()
                + " large2_items_per_member=" + itemsPerMember
                + " large2_largest_item_bytes=" + largestItemBytes);

        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        long scannedValueChars = 0L;
        int seen = 0;
        while (cursor.hasNext()) {
            final CanonicalObjectMembers.Member member = cursor.next();
            assertEquals(keys.get(seen), member.key(), "key at " + seen);
            assertArrayEquals(keyRanges.get(seen),
                    new int[] {member.keyStart(), member.keyEnd()}, "key range at " + seen);
            assertArrayEquals(valueRanges.get(seen),
                    new int[] {member.valueStart(), member.valueEnd()}, "value range at " + seen);
            assertEquals(valueDigests.get(seen).longValue(),
                    fnv(frozen.canonical(), member.valueStart(), member.valueEnd()),
                    "value digest at " + seen);
            assertEquals(valueHeads.get(seen), frozen.canonical()
                    .substring(member.valueStart(), member.valueStart() + 12), "head at " + seen);
            assertEquals(valueTails.get(seen), frozen.canonical()
                    .substring(member.valueEnd() - 12, member.valueEnd()), "tail at " + seen);
            scannedValueChars += member.valueEnd() - member.valueStart();
            seen++;
        }
        assertEquals(keys.size(), seen, "cursor stopped early");
        assertEquals(totalValueChars, scannedValueChars);
        assertThrows(NoSuchElementException.class, cursor::next);
        System.out.println("[evidence] large2_scanned_value_chars=" + scannedValueChars);
    }

    // ------------------------------------------------------------------------------------ utilities

    /** Certifies one hand written canonical text and asserts that the codec keeps it verbatim. */
    private static ExactJson.FrozenJson certified(String text) {
        final ExactJson.FrozenJson frozen = ExactJson.parse(text);
        assertSameText(text, frozen.canonical(), "fixture must already be canonical");
        return frozen;
    }

    /** Left pads a decimal index so the emitted key order equals the canonical String order. */
    private static String pad(int value, int width) {
        final String digits = Integer.toString(value);
        final StringBuilder padded = new StringBuilder();
        for (int index = digits.length(); index < width; index++) {
            padded.append('0');
        }
        return padded.append(digits).toString();
    }

    /** True when the list is already in strict {@link String#compareTo} order. */
    private static boolean isSortedAscending(List<String> values) {
        for (int index = 1; index < values.size(); index++) {
            if (values.get(index - 1).compareTo(values.get(index)) >= 0) {
                return false;
            }
        }
        return true;
    }

    /** Compares two possibly huge texts without ever printing them whole into a log. */
    private static void assertSameText(String expected, String actual, String what) {
        if (expected.equals(actual)) {
            return;
        }
        final int shared = Math.min(expected.length(), actual.length());
        int difference = 0;
        while (difference < shared && expected.charAt(difference) == actual.charAt(difference)) {
            difference++;
        }
        throw new AssertionError(what + ": expected " + expected.length() + " chars, actual "
                + actual.length() + " chars, first difference at char " + difference
                + ", expected[" + window(expected, difference) + "] actual["
                + window(actual, difference) + "]");
    }

    /** A short bounded window of one text around one offset, safe to print. */
    private static String window(String text, int offset) {
        final int start = Math.max(0, offset - 8);
        final int end = Math.min(text.length(), offset + 24);
        return text.substring(start, end);
    }

    /** Independent canonical quoting, built from the documented escape rules, never from the codec. */
    private static String quote(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            final char c = text.charAt(index);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append("0123456789abcdef".charAt((c >> 4) & 0xF))
                                .append("0123456789abcdef".charAt(c & 0xF));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.append('"').toString();
    }

    /** One ASCII string value token of exactly {@code tokenLength} characters. */
    private static String fillerToken(String seed, int tokenLength) {
        final StringBuilder out = new StringBuilder(tokenLength).append('"').append(seed).append(':');
        while (out.length() < tokenLength - 1) {
            out.append(FILLER);
        }
        out.setLength(tokenLength - 1);
        return out.append('"').toString();
    }

    private static String repeat(char c, int count) {
        final StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            out.append(c);
        }
        return out.toString();
    }

    /** Independent 64-bit FNV-1a over one half-open UTF-16 range; used to compare large values. */
    private static long fnv(String text, int start, int end) {
        long hash = 0xcbf29ce484222325L;
        for (int index = start; index < end; index++) {
            hash ^= text.charAt(index);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static List<String> keysOf(ExactJson.FrozenJson frozen) {
        final List<String> keys = new ArrayList<>();
        final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
        while (cursor.hasNext()) {
            keys.add(cursor.next().key());
        }
        return keys;
    }

    private static int cursorValueCount(CanonicalObjectMembers.Cursor cursor) {
        int count = 0;
        while (cursor.hasNext()) {
            cursor.next();
            count++;
        }
        return count;
    }

    private static String randomKeyText(Random random) {
        switch (random.nextInt(8)) {
            case 0:
                return "";
            case 1:
                return "\u0000ctl" + random.nextInt(10);
            case 2:
                return "e\u0301nfd" + random.nextInt(10);
            case 3:
                return "\u00e9nfc" + random.nextInt(10);
            case 4:
                return ASTRAL + random.nextInt(10);
            case 5:
                return "\u952e" + random.nextInt(10);
            case 6:
                return "k" + random.nextInt(50);
            default:
                return "key/" + random.nextInt(20) + "\t";
        }
    }

    private static String randomValueText(Random random, int depth) {
        final int pick = depth >= 3 ? random.nextInt(2) : random.nextInt(4);
        switch (pick) {
            case 0:
                return CANONICAL_LITERALS[random.nextInt(CANONICAL_LITERALS.length)];
            case 1:
                return quote(randomStringText(random));
            case 2: {
                final StringBuilder out = new StringBuilder("[");
                final int count = random.nextInt(3);
                for (int index = 0; index < count; index++) {
                    if (index > 0) {
                        out.append(',');
                    }
                    out.append(randomValueText(random, depth + 1));
                }
                return out.append(']').toString();
            }
            default: {
                final TreeMap<String, String> entries = new TreeMap<>();
                final int count = random.nextInt(3);
                for (int index = 0; index < count; index++) {
                    entries.put(randomKeyText(random), randomValueText(random, depth + 1));
                }
                final StringBuilder out = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append(quote(entry.getKey())).append(':').append(entry.getValue());
                }
                return out.append('}').toString();
            }
        }
    }

    private static String randomStringText(Random random) {
        switch (random.nextInt(6)) {
            case 0:
                return "";
            case 1:
                return "line\nbreak\\slash\"quote";
            case 2:
                return "\u0001\u0002\u001f";
            case 3:
                return ASTRAL + ASTRAL + "\u952e";
            case 4:
                return "e\u0301/nfd";
            default:
                return "text" + random.nextInt(1000) + "{}[],:";
        }
    }

    /**
     * Independent expectation builder for one canonical object.
     *
     * <p>It writes the canonical text itself and registers every expected key range, value range and
     * decoded key while writing, so the expectations exist before any cursor runs and are derived
     * from the fixture construction, not from cursor output. {@link #freeze()} additionally proves
     * that the text is already canonical by asking the frozen codec for its canonical text, and
     * {@link #verifyAll()} walks a cursor over that freeze and compares all five fields of every
     * member.</p>
     */
    private static final class Oracle {

        private final StringBuilder builder = new StringBuilder("{");
        private final List<String> keys = new ArrayList<>();
        private final List<String> valueTokens = new ArrayList<>();
        private final List<Integer> keyStarts = new ArrayList<>();
        private final List<Integer> keyEnds = new ArrayList<>();
        private final List<Integer> valueStarts = new ArrayList<>();
        private final List<Integer> valueEnds = new ArrayList<>();
        private String text;

        Oracle member(String key, String valueToken) {
            if (!this.keys.isEmpty()) {
                this.builder.append(',');
            }
            this.keyStarts.add(this.builder.length());
            this.builder.append(quote(key));
            this.keyEnds.add(this.builder.length());
            this.builder.append(':');
            this.valueStarts.add(this.builder.length());
            this.builder.append(valueToken);
            this.valueEnds.add(this.builder.length());
            this.keys.add(key);
            this.valueTokens.add(valueToken);
            return this;
        }

        int size() {
            return this.keys.size();
        }

        String valueToken(int index) {
            return this.valueTokens.get(index);
        }

        String text() {
            if (this.text == null) {
                this.text = this.builder.append('}').toString();
            }
            return this.text;
        }

        ExactJson.FrozenJson freeze() {
            return certified(text());
        }

        void verifyAll() {
            final ExactJson.FrozenJson frozen = freeze();
            final CanonicalObjectMembers.Cursor cursor = CanonicalObjectMembers.cursor(frozen);
            assertSame(frozen.canonical(), cursor.canonical());
            int index = 0;
            while (cursor.hasNext()) {
                assertTrue(index < this.keys.size(), "cursor returned more members than registered");
                final CanonicalObjectMembers.Member member = cursor.next();
                assertEquals(this.keys.get(index), member.key(), "decoded key at " + index);
                assertEquals(this.keyStarts.get(index).intValue(), member.keyStart(),
                        "keyStart at " + index);
                assertEquals(this.keyEnds.get(index).intValue(), member.keyEnd(),
                        "keyEnd at " + index);
                assertEquals(this.valueStarts.get(index).intValue(), member.valueStart(),
                        "valueStart at " + index);
                assertEquals(this.valueEnds.get(index).intValue(), member.valueEnd(),
                        "valueEnd at " + index);
                assertEquals(quote(this.keys.get(index)), frozen.canonical()
                        .substring(member.keyStart(), member.keyEnd()), "key token at " + index);
                assertEquals(this.valueTokens.get(index), frozen.canonical()
                        .substring(member.valueStart(), member.valueEnd()), "value token at " + index);
                assertSame(frozen.canonical(), cursor.canonical());
                index++;
            }
            assertEquals(this.keys.size(), index, "cursor stopped early");
            assertThrows(NoSuchElementException.class, cursor::next);
            assertFalse(cursor.hasNext());
        }
    }
}
