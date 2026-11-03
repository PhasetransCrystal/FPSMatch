package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Formal contract test of R06.2b2: {@link Utf8Sha256}, the bounded UTF-8 sink for the container
 * digest.
 *
 * <p>The test proves the frozen card contract, not the implementation's own view of it. Every
 * expected digest is built by this test from the given fragments: the UTF-8 bytes come from this
 * test's own JDK encoder and the hash from this test's own JDK {@link MessageDigest}. No
 * {@link Utf8Sha256} output is ever used as the oracle of another call, no expectation is reverse
 * engineered from the sink, and no assertion depends on the wording of a technical message.</p>
 *
 * <p>Every rejection vector is a real contract violation (a Java {@code null}, a range out of
 * bounds, a reversed range, an unpaired surrogate inside the selected range, or an append that
 * exceeds the budget). A failure is never simulated with an internal error, and no test accepts an
 * {@link OutOfMemoryError} as a legal rejection.</p>
 *
 * <p>The whole group is executed twice by the author-side runner: once under a real {@code -Xmx64m}
 * and once under a real {@code -Xmx384m}. Passing the group under a 64 MiB heap while streaming a
 * whole 64 MiB stream is the evidence that the sink itself is constant memory; it does not claim
 * that a complete envelope could be materialised in that heap.</p>
 *
 * <p>Large vectors never build a 64 MiB {@code String} or a 64 MiB {@code byte[]}: each fixture
 * fragment is at most 65536 UTF-8 bytes and is replayed occurrence by occurrence, and the
 * independent expectation is produced by repeated small {@link MessageDigest#update(byte[])}
 * calls over the same fragment.</p>
 */
class Utf8Sha256ContractTest {

    /** The card budget ceiling, restated here as an independent constant. */
    private static final long CAP = 67108864L;

    /** SHA-256 of the empty input, spelled out literally so the empty stream has its own oracle. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /** Fixed seed: the thousand generated cases are the same on every run. */
    private static final long SEED = 20261015L;

    /** The card requires at least one thousand distinct generated selections. */
    private static final int GENERATED_CASES = 1000;

    /** Every generated case registers exactly this many selections, so the total is pinned. */
    private static final int SELECTIONS_PER_CASE = 3;

    /** The expected number of registered selections of the generated pass, pinned exactly. */
    private static final int GENERATED_SELECTIONS = GENERATED_CASES * SELECTIONS_PER_CASE;

    /** Every large fixture fragment stays at or below this many UTF-8 bytes. */
    private static final int FRAGMENT_BYTES = 65536;

    /** Occurrences of one fragment that fill the whole 64 MiB budget exactly. */
    private static final int OCCURRENCES = (int) (CAP / FRAGMENT_BYTES);

    /**
     * The fixed alphabet of the generated cases: ASCII, NUL, control, DEL, newline, quote,
     * backslash, two-byte BMP, three-byte BMP, the surrogate-adjacent BMP edges and two astral
     * pairs. Every entry is one whole UTF-16 unit sequence, so any boundary between entries keeps a
     * pair intact.
     */
    private static final String[] UNITS = {
        "a", "\u0000", "\u001f", "\u007f", "\n", "\"", "\\",
        "\u00e9", "\u07ff", "\u0800", "\u4e2d", "\uffff",
        "\uD83D\uDE00", "\uDBFF\uDFFF",
    };

    // ------------------------------------------------------------------ construction

    @Test
    void constructorAcceptsZeroAndMaximumBudget() throws Exception {
        final Utf8Sha256 zero = new Utf8Sha256(0L);
        assertEquals(0L, zero.utf8Bytes());
        assertEquals(EMPTY_SHA256, zero.finish());

        final Utf8Sha256 maximum = new Utf8Sha256(CAP);
        assertEquals(0L, maximum.utf8Bytes());
        maximum.append("ok");
        assertEquals(2L, maximum.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("ok"), maximum.finish());
    }

    @Test
    void constructorRejectsNegativeAndOversizedBudgets() {
        final long[] rejected = {-1L, -2L, Long.MIN_VALUE, CAP + 1L, CAP + 2L, Long.MAX_VALUE};
        for (long budget : rejected) {
            assertThrows(IllegalArgumentException.class, () -> new Utf8Sha256(budget),
                    "budget must be rejected: " + budget);
        }
    }

    // ------------------------------------------------------------------ argument shape

    @Test
    void nullTextIsRejectedByBothOverloads() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        assertThrows(IllegalArgumentException.class, () -> sink.append((String) null));
        assertThrows(IllegalArgumentException.class, () -> sink.append(null, 0, 0));
        assertEquals(0L, sink.utf8Bytes(), "a null append must not cost a byte");
        sink.append("a");
        assertEquals(1L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append(null, 0, 0));
        assertEquals(1L, sink.utf8Bytes(), "the null rejection must keep the accepted byte");
        assertEquals(jdkSha256OfUtf8("a"), sink.finish());
    }

    @Test
    void outOfBoundsAndReversedRangesAreRejected() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append("abc");
        final int[][] rejected = {
            {-1, 0}, {0, 4}, {4, 4}, {4, 5}, {-1, -1}, {2, 1}, {3, 2}, {1, 0},
            {0, Integer.MAX_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE},
        };
        for (int[] range : rejected) {
            assertThrows(IllegalArgumentException.class, () -> sink.append("abc", range[0], range[1]),
                    "range [" + range[0] + "," + range[1] + ") must be rejected");
            assertEquals(3L, sink.utf8Bytes(), "a rejected range must keep the count");
        }
        assertSuffixRecoversPrefixState(sink, "abc", "de");
    }

    @Test
    void emptyRangesAreAlwaysLegalAndFree() throws Exception {
        final String[] everyText = {"", "a", "\uD83D\uDE00", "\uD83D", "\uDE00", "abc\uD83Ddef"};
        final String[] legalTexts = {"", "a", "\uD83D\uDE00", "abc\uD83D\uDE00def"};
        final String[] brokenTexts = {"\uD83D", "\uDE00", "abc\uD83Ddef"};
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        for (String text : everyText) {
            sink.append(text, 0, 0);
            sink.append(text, text.length(), text.length());
            sink.append(text, text.length() / 2, text.length() / 2);
            assertEquals(0L, sink.utf8Bytes(),
                    "empty ranges must stay free for a text of length " + text.length());
        }
        for (String text : brokenTexts) {
            assertThrows(IllegalArgumentException.class, () -> sink.append(text, 0, text.length()),
                    "a broken whole string of length " + text.length() + " must be rejected");
            assertEquals(0L, sink.utf8Bytes(), "a rejected whole string must stay free");
        }
        final StringBuilder expected = new StringBuilder();
        for (String text : legalTexts) {
            sink.append(text, 0, text.length());
            expected.append(text);
        }
        assertEquals(utf8Length(expected.toString()), sink.utf8Bytes(),
                "only the whole legal strings may cost bytes");
        assertEquals(jdkSha256OfUtf8(expected.toString()), sink.finish());
    }

    @Test
    void emptyRangeInsideSurrogatePairIsLegal() throws Exception {
        final String emoji = "\uD83D\uDE00";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append(emoji, 1, 1);
        sink.append(emoji, 2, 2);
        assertEquals(0L, sink.utf8Bytes(), "an empty range inside a pair is free");
        sink.append(emoji, 0, 2);
        assertEquals(4L, sink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8(emoji), sink.finish());
        assertThrows(IllegalArgumentException.class, () -> new Utf8Sha256(CAP).append(emoji, 1, 2));
    }

    // ------------------------------------------------------------------ UTF-8 widths

    @Test
    void asciiRangeUsesOneByteAndKeepsNulAndControls() throws Exception {
        final String ascii = "\u0000\u0001\u001f\u007f\"\\A~";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        for (int index = 0; index < ascii.length(); index++) {
            sink.append(ascii, index, index + 1);
            assertEquals(index + 1, sink.utf8Bytes(),
                    "every ASCII character, including NUL and controls, costs one byte");
        }
        assertEquals(jdkSha256OfUtf8(ascii), sink.finish());
        final Utf8Sha256 nul = new Utf8Sha256(CAP);
        nul.append("\u0000");
        assertEquals(1L, nul.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("\u0000"), nul.finish());
        assertNotEquals(jdkSha256OfUtf8(""), jdkSha256OfUtf8("\u0000"),
                "NUL is a real encoded byte, not an empty range");
    }

    @Test
    void twoByteRangeUsesTwoBytes() throws Exception {
        final String[] units = {"\u0080", "\u00e9", "\u07ff"};
        for (String unit : units) {
            final Utf8Sha256 sink = new Utf8Sha256(CAP);
            sink.append(unit);
            assertEquals(2, utf8Length(unit), "the oracle must agree on the width");
            assertEquals(2L, sink.utf8Bytes());
            assertEquals(jdkSha256OfUtf8(unit), sink.finish());
        }
    }

    @Test
    void threeByteRangeUsesThreeBytes() throws Exception {
        final String[] units = {"\u0800", "\u4e2d", "\ud7ff", "\ue000", "\uffff"};
        for (String unit : units) {
            final Utf8Sha256 sink = new Utf8Sha256(CAP);
            sink.append(unit);
            assertEquals(3, utf8Length(unit), "the oracle must agree on the width");
            assertEquals(3L, sink.utf8Bytes());
            assertEquals(jdkSha256OfUtf8(unit), sink.finish());
        }
    }

    @Test
    void fourByteRangeUsesFourBytesUpToMaxCodePoint() throws Exception {
        final String[] units = {"\uD800\uDC00", "\uD83D\uDE00", "\uDBFF\uDFFF"};
        for (String unit : units) {
            final Utf8Sha256 sink = new Utf8Sha256(CAP);
            sink.append(unit);
            assertEquals(4, utf8Length(unit), "the oracle must agree on the width");
            assertEquals(4L, sink.utf8Bytes());
            assertEquals(jdkSha256OfUtf8(unit), sink.finish());
        }
    }

    @Test
    void widthBoundaryMatrixMatchesJdkBytes() throws Exception {
        final String[] units = {
            "\u0000", "\u0001", "\u001f", "\u007f",
            "\u0080", "\u07ff",
            "\u0800", "\ud7ff", "\ue000", "\uffff",
            "\uD800\uDC00", "\uDBFF\uDFFF",
        };
        final int[] widths = {1, 1, 1, 1, 2, 2, 3, 3, 3, 3, 4, 4};
        assertEquals(units.length, widths.length);
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        final StringBuilder expected = new StringBuilder();
        long bytes = 0L;
        for (int index = 0; index < units.length; index++) {
            final String unit = units[index];
            assertEquals(widths[index], utf8Length(unit),
                    "independent JDK width of unit " + index);
            sink.append(unit);
            bytes += widths[index];
            assertEquals(bytes, sink.utf8Bytes(), "cumulative bytes after unit " + index);
            expected.append(unit);
        }
        assertEquals(jdkSha256OfUtf8(expected.toString()), sink.finish());
    }

    @Test
    void quoteAndBackslashAreNotJsonEscaped() throws Exception {
        final String quoted = "\"";
        final String escapedQuote = "\\\"";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append(quoted);
        assertEquals(1L, sink.utf8Bytes(), "a quote is one raw byte, never two");
        assertEquals(jdkSha256OfUtf8(quoted), sink.finish());

        final Utf8Sha256 backslash = new Utf8Sha256(CAP);
        backslash.append("\\");
        assertEquals(1L, backslash.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("\\"), backslash.finish());

        final Utf8Sha256 pair = new Utf8Sha256(CAP);
        pair.append(escapedQuote);
        assertEquals(2L, pair.utf8Bytes());
        assertEquals(jdkSha256OfUtf8(escapedQuote), pair.finish());
        assertNotEquals(jdkSha256OfUtf8(quoted), jdkSha256OfUtf8(escapedQuote));
    }

    @Test
    void nfcAndNfdStayDistinctAndUnnormalized() throws Exception {
        final String composed = "\u00e9";
        final String decomposed = "e\u0301";
        final Utf8Sha256 composedSink = new Utf8Sha256(CAP);
        final Utf8Sha256 decomposedSink = new Utf8Sha256(CAP);
        composedSink.append(composed);
        decomposedSink.append(decomposed);
        assertEquals(2L, composedSink.utf8Bytes());
        assertEquals(3L, decomposedSink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8(composed), composedSink.finish());
        assertEquals(jdkSha256OfUtf8(decomposed), decomposedSink.finish());
        assertNotEquals(jdkSha256OfUtf8(composed), jdkSha256OfUtf8(decomposed),
                "NFC and NFD must keep their own raw bytes");
    }

    @Test
    void emojiStreamMatchesJdkBytes() throws Exception {
        final String[] parts = {"\uD83D\uDE00", "\u4e2d", "A", "\uD83D\uDE00\uDBFF\uDFFF", "\u0000"};
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        final StringBuilder expected = new StringBuilder();
        long bytes = 0L;
        for (String part : parts) {
            sink.append(part);
            expected.append(part);
            bytes += utf8Length(part);
            assertEquals(bytes, sink.utf8Bytes(), "cumulative bytes after " + expected);
        }
        assertEquals(jdkSha256OfUtf8(expected.toString()), sink.finish());
    }

    // ------------------------------------------------------------------ range semantics

    @Test
    void rangeConsumesOnlyTheSelectedWindow() throws Exception {
        final String text = "head\uD83D\uDE00\u4E2Dtail";
        final int emojiStart = text.indexOf("\uD83D\uDE00");
        final int emojiEnd = emojiStart + 2;
        final int bmpStart = emojiEnd;
        final int bmpEnd = bmpStart + 1;
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append(text, emojiStart, emojiEnd);
        assertEquals(4L, sink.utf8Bytes());
        sink.append(text, bmpStart, bmpEnd);
        assertEquals(7L, sink.utf8Bytes());
        sink.append(text, 0, "head".length());
        assertEquals(11L, sink.utf8Bytes());
        final String expected = "\uD83D\uDE00\u4E2Dhead";
        assertEquals(jdkSha256OfUtf8(expected), sink.finish());
        assertNotEquals(jdkSha256OfUtf8(text), jdkSha256OfUtf8(expected),
                "the selected windows must not be widened to the whole text");

        final Utf8Sha256 tail = new Utf8Sha256(CAP);
        tail.append("aaaa", 2, 4);
        assertEquals(2L, tail.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("aa"), tail.finish());
        assertNotEquals(jdkSha256OfUtf8("aaa"), jdkSha256OfUtf8("aa"),
                "the character before start must stay out of the hash");
    }

    @Test
    void brokenSurrogatesOutsideTheRangeAreNeverRead() throws Exception {
        final String loneHighFirst = "\uD83D" + "abc" + "\uDE00";
        final Utf8Sha256 first = new Utf8Sha256(CAP);
        first.append(loneHighFirst, 1, 4);
        assertEquals(3L, first.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("abc"), first.finish());

        final String pairStraddling = "\uD83D\uDE00" + "xy" + "\uD83D\uDE00";
        final Utf8Sha256 second = new Utf8Sha256(CAP);
        second.append(pairStraddling, 2, 4);
        assertEquals(2L, second.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("xy"), second.finish());

        final String loneLowFirst = "\uDE00" + "ok";
        final Utf8Sha256 third = new Utf8Sha256(CAP);
        third.append(loneLowFirst, 1, 3);
        assertEquals(2L, third.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("ok"), third.finish());
    }

    @Test
    void truncatedPairInsideRangeIsRejected() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append("ab");
        assertThrows(IllegalArgumentException.class,
                () -> sink.append("\uD83D\uDE00", 0, 1), "a high surrogate at the end of the range");
        assertEquals(2L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class,
                () -> sink.append("\uD83Dabc", 0, 2), "a high surrogate followed by a BMP character");
        assertEquals(2L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class,
                () -> sink.append("a\uD83Db", 1, 3), "a high surrogate in the middle of the range");
        assertEquals(2L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class,
                () -> sink.append("\uD83D", 0, 1), "a lone high surrogate is not a code point");
        assertEquals(2L, sink.utf8Bytes());
        assertSuffixRecoversPrefixState(sink, "ab", "\uD83D\uDE00");
    }

    @Test
    void loneLowSurrogateAtRangeStartIsRejected() throws Exception {
        final String emoji = "\uD83D\uDE00";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append("z");
        assertThrows(IllegalArgumentException.class, () -> sink.append(emoji, 1, 2),
                "a low surrogate at the start of the range is truncated");
        assertEquals(1L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uDE00xyz", 0, 3),
                "a lone low surrogate at index zero");
        assertEquals(1L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("ab\uDE00cd", 2, 5),
                "a lone low surrogate with a BMP character after it");
        assertEquals(1L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uDE00", 0, 1),
                "a lone low surrogate is never replaced");
        assertEquals(1L, sink.utf8Bytes());
        assertSuffixRecoversPrefixState(sink, "z", "Z");
    }

    // ------------------------------------------------------------------ failure atomicity

    @Test
    void rejectedAppendKeepsCountThenSuffixRecovers() throws Exception {
        final String prefix = "abc\u4E2D";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append(prefix);
        assertEquals(utf8Length(prefix), sink.utf8Bytes());

        assertThrows(IllegalArgumentException.class, () -> sink.append("\uD83D", 0, 1));
        assertEquals(utf8Length(prefix), sink.utf8Bytes(), "Unicode rejection must keep the count");
        assertThrows(IllegalArgumentException.class, () -> sink.append((String) null));
        assertEquals(utf8Length(prefix), sink.utf8Bytes(), "null rejection must keep the count");
        assertThrows(IllegalArgumentException.class, () -> sink.append("x", 2, 2));
        assertEquals(utf8Length(prefix), sink.utf8Bytes(), "range rejection must keep the count");
        assertThrows(IllegalArgumentException.class, () -> sink.append("xy", 1, 0));
        assertEquals(utf8Length(prefix), sink.utf8Bytes(), "reversed range must keep the count");

        assertSuffixRecoversPrefixState(sink, prefix, "\uD83D\uDE00z");
    }

    @Test
    void rejectedAppendOnSecondInstanceKeepsPrefixDigest() throws Exception {
        final String prefix = "prefix-\u00e9\u4e2d";
        final Utf8Sha256 rejected = new Utf8Sha256(CAP);
        final Utf8Sha256 clean = new Utf8Sha256(CAP);
        rejected.append(prefix);
        clean.append(prefix);
        assertThrows(IllegalArgumentException.class, () -> rejected.append("\uDBFF", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> rejected.append("y", 5, 5));
        assertThrows(IllegalArgumentException.class, () -> rejected.append("y", -1, 0));
        assertEquals(utf8Length(prefix), rejected.utf8Bytes());
        final String rejectedDigest = rejected.finish();
        assertEquals(jdkSha256OfUtf8(prefix), rejectedDigest,
                "the rejected instance must hash exactly its clean prefix");
        assertEquals(clean.finish(), rejectedDigest,
                "the clean twin must reach the same prefix digest");
        assertNotEquals(jdkSha256OfUtf8(prefix + "y"), rejectedDigest);
    }

    @Test
    void emptyRangeIsNotRecoveryEvidenceForARejection() throws Exception {
        final String prefix = "abc";
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append(prefix);
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uDE00", 0, 1));
        sink.append("", 0, 0);
        sink.append(prefix, 1, 1);
        sink.append(prefix, prefix.length(), prefix.length());
        assertEquals(3L, sink.utf8Bytes(), "empty ranges cannot repair or advance the count");
        assertEquals(jdkSha256OfUtf8(prefix), sink.finish(),
                "empty ranges leave the prefix digest untouched");

        final Utf8Sha256 recovering = new Utf8Sha256(CAP);
        recovering.append(prefix);
        assertThrows(IllegalArgumentException.class, () -> recovering.append("\uDE00", 0, 1));
        recovering.append("Z");
        assertEquals(4L, recovering.utf8Bytes());
        assertEquals(jdkSha256OfUtf8(prefix + "Z"), recovering.finish(),
                "a non-empty legal suffix is the real recovery evidence");
        assertNotEquals(jdkSha256OfUtf8(prefix), jdkSha256OfUtf8(prefix + "Z"));
    }

    @Test
    void budgetRejectionKeepsDigestAndCount() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(3L);
        sink.append("ab");
        assertThrows(IllegalArgumentException.class, () -> sink.append("\u4e2d"));
        assertEquals(2L, sink.utf8Bytes(), "an over-budget append must keep the count");
        assertThrows(IllegalArgumentException.class, () -> sink.append("bc"));
        assertEquals(2L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("abc"));
        assertEquals(2L, sink.utf8Bytes());
        sink.append("c");
        assertEquals(3L, sink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("abc"), sink.finish(),
                "only the accepted bytes may reach the digest");
        assertNotEquals(jdkSha256OfUtf8("ab"), jdkSha256OfUtf8("abc"));
    }

    @Test
    void oversizedAppendIsRejectedBeforeAnyWrite() throws Exception {
        final String huge = "x".repeat(10000);
        assertTrue(utf8Length(huge) > 8192, "the vector must exceed one whole staging buffer");

        final Utf8Sha256 sink = new Utf8Sha256(4096L);
        sink.append("head");
        assertThrows(IllegalArgumentException.class, () -> sink.append(huge));
        assertEquals(4L, sink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("head"), sink.finish(),
                "no byte of the rejected append may reach the digest");
        assertNotEquals(jdkSha256OfUtf8("head"), jdkSha256OfUtf8("head" + huge));
        assertNotEquals(jdkSha256OfUtf8("head"), jdkSha256OfUtf8("headx"));

        final Utf8Sha256 recovering = new Utf8Sha256(4096L);
        recovering.append("head");
        assertThrows(IllegalArgumentException.class, () -> recovering.append(huge));
        recovering.append("tail");
        assertEquals(8L, recovering.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("headtail"), recovering.finish(),
                "stale bytes of a rejected append must not leak into a later append");
    }

    // ------------------------------------------------------------------ sealing

    @Test
    void finishSealsAgainstRepeatFinishAndAnyAppend() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        sink.append("sealed");
        final String first = sink.finish();
        assertTrue(first.matches("[0-9a-f]{64}"), "finish must be lowercase hex: " + first);
        assertEquals(jdkSha256OfUtf8("sealed"), first);
        assertThrows(IllegalStateException.class, sink::finish);
        assertThrows(IllegalStateException.class, () -> sink.append("x"));
        assertThrows(IllegalStateException.class, () -> sink.append("x", 0, 1));
        assertThrows(IllegalStateException.class, () -> sink.append(""));
        assertThrows(IllegalStateException.class, () -> sink.append("abc", 1, 1));
        assertThrows(IllegalStateException.class, () -> sink.append((String) null));
        assertThrows(IllegalStateException.class, () -> sink.append(null, 0, 0));
        assertEquals(6L, sink.utf8Bytes(), "the count stays readable after sealing");
    }

    @Test
    void utf8BytesIsRepeatableAndNeverFinishesTheStream() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        assertEquals(0L, sink.utf8Bytes());
        assertEquals(0L, sink.utf8Bytes());
        sink.append("abc");
        assertEquals(3L, sink.utf8Bytes());
        assertEquals(3L, sink.utf8Bytes());
        assertEquals(3L, sink.utf8Bytes());
        sink.append("d");
        assertEquals(4L, sink.utf8Bytes());
        assertEquals(4L, sink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8("abcd"), sink.finish(),
                "reading the count must not change the digest");
        assertEquals(4L, sink.utf8Bytes(), "the count stays readable after finish");
    }

    @Test
    void sameContentOnDistinctInstancesFinishesIdentically() throws Exception {
        final String text = "\u4e2d\uD83D\uDE00A\u0000";
        final Utf8Sha256 first = new Utf8Sha256(CAP);
        final Utf8Sha256 second = new Utf8Sha256(CAP);
        first.append(text);
        second.append(text, 0, text.length());
        final String expected = jdkSha256OfUtf8(text);
        assertEquals(expected, first.finish());
        assertEquals(expected, second.finish());
        assertTrue(expected.matches("[0-9a-f]{64}"));
        assertEquals(expected, expected.toLowerCase(java.util.Locale.ROOT));
    }

    // ------------------------------------------------------------------ budget edges

    @Test
    void exactBudgetIsAcceptedAndTheNextByteRejected() throws Exception {
        final String exact = "ab\u4e2d";
        assertEquals(5L, utf8Length(exact));
        final Utf8Sha256 sink = new Utf8Sha256(5L);
        sink.append(exact);
        assertEquals(5L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("c"));
        assertEquals(5L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uD83D\uDE00"));
        assertEquals(5L, sink.utf8Bytes());
        assertEquals(jdkSha256OfUtf8(exact), sink.finish());

        final Utf8Sha256 twoByteExact = new Utf8Sha256(4L);
        twoByteExact.append("\u00e9\u00e9");
        assertEquals(4L, twoByteExact.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> twoByteExact.append("\u0080"));
        assertEquals(jdkSha256OfUtf8("\u00e9\u00e9"), twoByteExact.finish());
    }

    @Test
    void zeroBudgetAcceptsEmptyRangesAndFinishesEmpty() throws Exception {
        final Utf8Sha256 sink = new Utf8Sha256(0L);
        sink.append("");
        sink.append("abc", 2, 2);
        sink.append("\uD83D\uDE00", 1, 1);
        assertEquals(0L, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("a"));
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uD83D\uDE00"));
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uD83D", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> sink.append(null, 0, 0));
        assertEquals(0L, sink.utf8Bytes());
        assertEquals(EMPTY_SHA256, sink.finish());
    }

    @Test
    void untouchedSinkFinishesWithJdkSha256OfEmptyInput() throws Exception {
        assertEquals(EMPTY_SHA256, jdkSha256OfUtf8(""), "the literal empty digest must be right");
        assertEquals(EMPTY_SHA256, new Utf8Sha256(0L).finish());
        assertEquals(EMPTY_SHA256, new Utf8Sha256(CAP).finish());
        final Utf8Sha256 appended = new Utf8Sha256(CAP);
        appended.append("");
        appended.append("a", 0, 0);
        assertEquals(EMPTY_SHA256, appended.finish());
        assertNotEquals(EMPTY_SHA256, jdkSha256OfUtf8("a"));
        assertNotEquals(EMPTY_SHA256, new Utf8Sha256(CAP).finish() + "0");
    }

    // ------------------------------------------------------------------ instance independence

    @Test
    void interleavedInstancesStayIndependent() throws Exception {
        final String[] firstParts = {"A", "\u4e2d", "x"};
        final String[] secondParts = {"B", "\uD83D\uDE00"};
        final Utf8Sha256 first = new Utf8Sha256(CAP);
        final Utf8Sha256 second = new Utf8Sha256(CAP);
        final StringBuilder firstExpected = new StringBuilder();
        final StringBuilder secondExpected = new StringBuilder();
        final int rounds = Math.max(firstParts.length, secondParts.length);
        for (int round = 0; round < rounds; round++) {
            if (round < firstParts.length) {
                first.append(firstParts[round]);
                firstExpected.append(firstParts[round]);
            }
            if (round < secondParts.length) {
                second.append(secondParts[round]);
                secondExpected.append(secondParts[round]);
            }
        }
        assertEquals(jdkSha256OfUtf8(firstExpected.toString()), first.finish());
        assertEquals(jdkSha256OfUtf8(secondExpected.toString()), second.finish());
        assertNotEquals(jdkSha256OfUtf8(firstExpected.toString()),
                jdkSha256OfUtf8(secondExpected.toString()));
    }

    @Test
    void manyInstancesShareNoState() throws Exception {
        final int instances = 8;
        final String[] chunks = {"A", "\u4e2d", "\uD83D\uDE00", "\u0000", "z\u00e9"};
        final Utf8Sha256[] sinks = new Utf8Sha256[instances];
        final StringBuilder[] expected = new StringBuilder[instances];
        for (int index = 0; index < instances; index++) {
            sinks[index] = new Utf8Sha256(CAP);
            expected[index] = new StringBuilder();
        }
        for (int round = 0; round < 5; round++) {
            for (int index = 0; index < instances; index++) {
                final String chunk = chunks[index % chunks.length] + round + "@" + index;
                sinks[index].append(chunk);
                expected[index].append(chunk);
            }
        }
        final Set<String> digests = new HashSet<>();
        for (int index = 0; index < instances; index++) {
            final String digest = sinks[index].finish();
            assertEquals(utf8Length(expected[index].toString()), sinks[index].utf8Bytes(),
                    "byte count of instance " + index);
            assertEquals(jdkSha256OfUtf8(expected[index].toString()), digest,
                    "digest of instance " + index);
            assertTrue(digests.add(digest), "instance digests must be distinct at " + index);
        }
        assertEquals(instances, digests.size());
    }

    // ------------------------------------------------------------------ thousand generated cases

    @Test
    void thousandSeededSelectionsMatchJdkHashAndBytePrefixes() throws Exception {
        final Random random = new Random(SEED);
        final Set<String> distinctDigests = new HashSet<>();
        final boolean[] alphabetUsed = new boolean[UNITS.length];
        long registered = 0L;
        for (int caseIndex = 0; caseIndex < GENERATED_CASES; caseIndex++) {
            final List<Selection> selections = givenSelections(random, caseIndex, alphabetUsed);
            assertEquals(SELECTIONS_PER_CASE, selections.size());
            final StringBuilder expectedText = new StringBuilder();
            final List<Long> expectedPrefixBytes = new ArrayList<>();
            long bytes = 0L;
            for (Selection selection : selections) {
                final String window =
                        selection.text().substring(selection.start(), selection.end());
                expectedText.append(window);
                bytes += utf8Length(window);
                expectedPrefixBytes.add(bytes);
            }
            final String expectedDigest = jdkSha256OfUtf8(expectedText.toString());
            assertTrue(distinctDigests.add(expectedDigest),
                    "generated digest must be distinct at case " + caseIndex);

            final Utf8Sha256 sink = new Utf8Sha256(CAP);
            for (int index = 0; index < selections.size(); index++) {
                final Selection selection = selections.get(index);
                sink.append(selection.text(), selection.start(), selection.end());
                assertEquals(expectedPrefixBytes.get(index).longValue(), sink.utf8Bytes(),
                        "expected byte prefix at case " + caseIndex + " selection " + index);
            }
            assertEquals(expectedDigest, sink.finish(), "digest at case " + caseIndex);
            registered += selections.size();
        }
        assertEquals(GENERATED_CASES, distinctDigests.size());
        assertEquals(GENERATED_SELECTIONS, registered, "the registered selection count is pinned");
        for (int index = 0; index < alphabetUsed.length; index++) {
            assertTrue(alphabetUsed[index], "character class never generated: " + index);
        }
        assertTrue(registered > 1000L, "the pass must really register more than one selection each");
    }

    @Test
    void thousandSeededSelectionsProduceDistinctDigests() throws Exception {
        final Random random = new Random(SEED);
        final Set<String> oracleDigests = new HashSet<>();
        final Set<String> sinkDigests = new HashSet<>();
        for (int caseIndex = 0; caseIndex < GENERATED_CASES; caseIndex++) {
            final List<Selection> selections = givenSelections(random, caseIndex, null);
            final StringBuilder expectedText = new StringBuilder();
            for (Selection selection : selections) {
                expectedText.append(selection.text(), selection.start(), selection.end());
            }
            final String expectedDigest = jdkSha256OfUtf8(expectedText.toString());
            assertTrue(oracleDigests.add(expectedDigest), "oracle digest repeated at " + caseIndex);

            final Utf8Sha256 sink = new Utf8Sha256(CAP);
            for (Selection selection : selections) {
                sink.append(selection.text(), selection.start(), selection.end());
            }
            final String sinkDigest = sink.finish();
            assertEquals(expectedDigest, sinkDigest, "sink digest at case " + caseIndex);
            assertTrue(sinkDigests.add(sinkDigest), "sink digest repeated at " + caseIndex);
        }
        assertEquals(GENERATED_CASES, oracleDigests.size());
        assertEquals(GENERATED_CASES, sinkDigests.size());
        assertEquals(oracleDigests, sinkDigests);
    }

    // ------------------------------------------------------------------ large accumulation

    @Test
    void asciiFragmentFillsExactlyCapThenRejectsOneByte() throws Exception {
        final String fragment = "A".repeat(FRAGMENT_BYTES);
        assertEquals(FRAGMENT_BYTES, utf8Length(fragment));
        assertTrue(fragment.indexOf('\u4e2d') < 0, "the ASCII vector must stay one byte per character");
        final String expected = jdkSha256OfOccurrences(fragment, OCCURRENCES);
        assertEquals(CAP, (long) OCCURRENCES * FRAGMENT_BYTES, "the occurrences must fill the cap");

        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        for (int index = 0; index < OCCURRENCES; index++) {
            sink.append(fragment);
            assertEquals((long) (index + 1) * FRAGMENT_BYTES, sink.utf8Bytes(),
                    "cumulative bytes after occurrence " + index);
        }
        assertEquals(CAP, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("A"));
        assertEquals(CAP, sink.utf8Bytes(), "the rejected byte must not be counted");
        assertThrows(IllegalArgumentException.class, () -> sink.append("\uD83D\uDE00"));
        assertEquals(CAP, sink.utf8Bytes());
        assertEquals(expected, sink.finish(), "the rejected bytes must not reach the digest");
        assertNotEquals(expected, jdkSha256OfOccurrences(fragment, OCCURRENCES - 1));
    }

    @Test
    void fourByteUnicodeFragmentFillsExactlyCapThenRejectsOneByte() throws Exception {
        final String emoji = "\uD83D\uDE00";
        final String fragment = emoji.repeat(FRAGMENT_BYTES / 4);
        assertEquals(FRAGMENT_BYTES, utf8Length(fragment));
        assertEquals(FRAGMENT_BYTES / 4, fragment.length() / 2, "one astral pair per occurrence unit");
        final String expected = jdkSha256OfOccurrences(fragment, OCCURRENCES);
        assertEquals(CAP, (long) OCCURRENCES * FRAGMENT_BYTES);

        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        for (int index = 0; index < OCCURRENCES; index++) {
            sink.append(fragment);
            assertEquals((long) (index + 1) * FRAGMENT_BYTES, sink.utf8Bytes(),
                    "cumulative bytes after occurrence " + index);
        }
        assertEquals(CAP, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append(emoji));
        assertEquals(CAP, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("A"));
        assertEquals(CAP, sink.utf8Bytes());
        assertEquals(expected, sink.finish());
        assertNotEquals(expected, jdkSha256OfOccurrences(fragment, OCCURRENCES - 1));
    }

    @Test
    void mixedBmpFragmentFillsExactlyCap() throws Exception {
        final String fragment = "\u4e2d".repeat(21845) + "A";
        assertEquals(FRAGMENT_BYTES, utf8Length(fragment),
                "21845 three-byte characters plus one ASCII byte");
        assertEquals(3L * 21845L + 1L, utf8Length(fragment));
        final String expected = jdkSha256OfOccurrences(fragment, OCCURRENCES);
        assertEquals(CAP, (long) OCCURRENCES * FRAGMENT_BYTES);

        final Utf8Sha256 sink = new Utf8Sha256(CAP);
        long bytes = 0L;
        for (int index = 0; index < OCCURRENCES; index++) {
            sink.append(fragment);
            bytes += utf8Length(fragment);
            assertEquals(bytes, sink.utf8Bytes(), "cumulative bytes after occurrence " + index);
        }
        assertEquals(CAP, sink.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> sink.append("\u4e2d"),
                "one more three-byte character must not fit the filled cap");
        assertEquals(CAP, sink.utf8Bytes(), "a rejected append must keep the count");
        assertEquals(expected, sink.finish(), "the rejected character must leave the digest alone");
        assertThrows(IllegalStateException.class, sink::finish, "the first finish must seal the sink");
        assertEquals(CAP, sink.utf8Bytes(), "utf8Bytes stays readable after finish");
    }

    @Test
    void largeFragmentsStayBoundedAndOccurrenceCounted() throws Exception {
        final String ascii = "A".repeat(FRAGMENT_BYTES);
        final String emoji = "\uD83D\uDE00".repeat(FRAGMENT_BYTES / 4);
        final String bmp = "\u4e2d".repeat(21845) + "A";
        final String[] fragments = {ascii, emoji, bmp};
        for (String fragment : fragments) {
            assertTrue(fragment.length() <= FRAGMENT_BYTES,
                    "no fixture fragment may reach the cap in characters");
            assertEquals(FRAGMENT_BYTES, utf8Length(fragment),
                    "every fixture fragment is exactly one buffer-multiple of bytes");
            assertEquals(CAP, (long) OCCURRENCES * utf8Length(fragment),
                    "the occurrences of one fragment must fill the cap exactly");
        }
        final Utf8Sha256 twoOccurrences = new Utf8Sha256(2L * FRAGMENT_BYTES);
        twoOccurrences.append(emoji);
        twoOccurrences.append(emoji);
        assertEquals(2L * FRAGMENT_BYTES, twoOccurrences.utf8Bytes());
        assertThrows(IllegalArgumentException.class, () -> twoOccurrences.append(emoji));
        assertEquals(2L * FRAGMENT_BYTES, twoOccurrences.utf8Bytes());
        assertEquals(jdkSha256OfOccurrences(emoji, 2), twoOccurrences.finish());
    }

    // ------------------------------------------------------------------ helpers

    /** One given selection: the whole text, the half-open window and nothing derived from the sink. */
    private record Selection(String text, int start, int end) {
    }

    /**
     * Registers the selections of one generated case. The text is a concatenation of whole
     * alphabet units, and every window is expressed in unit boundaries, so a window can never split
     * a surrogate pair; the first window always covers the unique case tag, which is why the
     * thousand concatenations are pairwise different. When {@code alphabetUsed} is given, it is
     * marked with every unit that ends up inside a window.
     */
    private static List<Selection> givenSelections(Random random, int caseIndex, boolean[] alphabetUsed) {
        final List<String> units = new ArrayList<>();
        final List<Integer> classes = new ArrayList<>();
        units.add("case" + caseIndex + ":");
        classes.add(-1);
        final int extra = 3 + random.nextInt(5);
        for (int index = 0; index < extra; index++) {
            final int pick = random.nextInt(UNITS.length);
            units.add(UNITS[pick]);
            classes.add(pick);
        }
        final int[] boundaries = new int[units.size() + 1];
        int offset = 0;
        for (int index = 0; index < units.size(); index++) {
            boundaries[index] = offset;
            offset += units.get(index).length();
        }
        boundaries[units.size()] = offset;
        final String text = String.join("", units);

        final int firstEnd = 1 + random.nextInt(units.size());
        final List<int[]> windows = new ArrayList<>();
        windows.add(new int[] {0, firstEnd});
        for (int index = 1; index < SELECTIONS_PER_CASE; index++) {
            final int start = random.nextInt(units.size());
            final int end = start + random.nextInt(units.size() - start + 1);
            windows.add(new int[] {start, end});
        }
        final List<Selection> selections = new ArrayList<>();
        for (int[] window : windows) {
            for (int unit = window[0]; unit < window[1]; unit++) {
                final int classIndex = classes.get(unit);
                if (alphabetUsed != null && classIndex >= 0) {
                    alphabetUsed[classIndex] = true;
                }
            }
            selections.add(new Selection(text, boundaries[window[0]], boundaries[window[1]]));
        }
        return selections;
    }

    /**
     * Proves that the state is still exactly the accepted prefix: a legal suffix costs exactly its
     * own bytes and the finished digest equals the JDK hash of prefix plus suffix.
     */
    private static void assertSuffixRecoversPrefixState(Utf8Sha256 sink, String prefix, String suffix)
            throws NoSuchAlgorithmException {
        assertEquals(utf8Length(prefix), sink.utf8Bytes(), "the accepted prefix must still be there");
        sink.append(suffix);
        assertEquals(utf8Length(prefix) + utf8Length(suffix), sink.utf8Bytes(),
                "a legal suffix must cost exactly its own bytes");
        assertEquals(jdkSha256OfUtf8(prefix + suffix), sink.finish(),
                "the finished stream must be prefix plus suffix and nothing else");
    }

    /** Independent JDK SHA-256 oracle over the UTF-8 bytes of a given text. */
    private static String jdkSha256OfUtf8(String text) throws NoSuchAlgorithmException {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return hexLower(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Independent JDK oracle for a fragment repeated occurrence by occurrence, without one big input. */
    private static String jdkSha256OfOccurrences(String fragment, int occurrences)
            throws NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] bytes = fragment.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < occurrences; index++) {
            digest.update(bytes);
        }
        return hexLower(digest.digest());
    }

    /** Independent JDK UTF-8 length of a legal text, used as the expected byte cost. */
    private static long utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Lowercase hexadecimal spelling of a digest. */
    private static String hexLower(byte[] bytes) {
        final char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            final int value = bytes[index] & 0xFF;
            hex[index * 2] = hexDigit(value >>> 4);
            hex[index * 2 + 1] = hexDigit(value & 0xF);
        }
        return new String(hex);
    }

    private static char hexDigit(int nibble) {
        return (char) (nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }
}
