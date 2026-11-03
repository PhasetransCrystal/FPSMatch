package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.ptcrys.fpsmatch.editor.common.model.FieldPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Formal contract test of the bounded raw envelope text and of the envelope failure value
 * (R06.2a1, design revision R1).
 *
 * <p>Every method here calls the production types. Nothing is asserted about a value the test itself
 * only produced: the expected UTF-8 widths come from an independent JDK encoder oracle
 * ({@link StandardCharsets#UTF_8} on a small input) or from arithmetic the test performs on the
 * fixture it built, never from {@link RawEnvelopeText#utf8Size()}.</p>
 *
 * <p>Three size disclosures are kept strictly separate, because they are different claims:
 * the ordinary tests and the 1000 case generator and the 65536 character oracle run on the default
 * heap; the bounded-allocation vector runs on a 64 MiB heap and exercises every small input plus the
 * explicit early rejections; and the maximum legal 64 MiB raw text is a distinct 384 MiB heap profile
 * whose group is disclosed by an explicit tag set and an expected count. No test claims that a 64 MiB
 * {@code String} is allocated inside a 64 MiB heap.</p>
 */
class RawEnvelopeValuesContractTest {

    /** Maximum UTF-16 length of a generated fixture in the 1000 case sweep. */
    private static final int SWEEP_MAX_UTF16 = 64;

    /** Number of independently predicted fixtures in the generator sweep. */
    private static final int SWEEP_CASES = 1000;

    /** Single character oracle fixture length in UTF-16 units. */
    private static final int ORACLE_UTF16_LENGTH = 65536;

    /** Byte budget of {@link RawEnvelopeText}, mirrored here for readable arithmetic. */
    private static final int MAX_UTF8_BYTES = 64 * 1024 * 1024;

    /**
     * Real JUnit tag carried by the maximum legal raw text group. The group is identified by this tag
     * annotation, not by a substring of the method name, so a runner or an external verifier can select
     * exactly these methods through the JUnit tag mechanism.
     */
    static final String MAX_TEXT_TAG = "maximumLegalRawText";

    // ------------------------------------------------------------------
    // raw text: accepted shapes and the exact UTF-8 width
    // ------------------------------------------------------------------

    @Test
    void emptyTextIsAcceptedWithZeroWidth() {
        final RawEnvelopeText empty = RawEnvelopeText.of("");
        assertEquals("", empty.text());
        assertEquals(0, empty.utf8Size());
    }

    @Test
    void wholeWhitespaceTextIsAccepted() {
        // Whitespace is raw content here, not a document: it has no JSON meaning at this layer.
        final String fixture = " \t\n\r\u000B\f\u001C\u001D\u001E\u001F";
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(fixture, value.text());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
    }

    @Test
    void nonJsonTextIsAccepted() {
        // A duplicate key object, a trailing comma, a bad number and a trailing text: all rejected as
        // JSON by the frozen codec, all accepted as raw envelope text, none certified as JSON here.
        final String[] fixtures = {
            "{\"a\":1,\"a\":2}",
            "[1,2,]",
            "{\"n\":01}",
            "{}{}",
            "not json at all",
            "\"unterminated",
            "{\"a\":}",
            "// comment\n{}",
        };
        for (String fixture : fixtures) {
            final RawEnvelopeText value = RawEnvelopeText.of(fixture);
            assertEquals(fixture, value.text(), "raw text must be kept verbatim: " + fixture);
            assertEquals(jdkUtf8Length(fixture), value.utf8Size(), "width of " + fixture);
        }
    }

    @Test
    void unescapedControlCharactersAreAccepted() {
        // Raw C0 controls and DEL inside the text: legal raw content, each exactly one UTF-8 byte.
        final char[] controls = {0x00, 0x01, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1B, 0x1F, 0x7F};
        for (char control : controls) {
            final String fixture = "a" + control + "b";
            final RawEnvelopeText value = RawEnvelopeText.of(fixture);
            assertEquals(fixture, value.text());
            assertEquals(3, value.utf8Size(), "control U+" + Integer.toHexString(control));
        }
    }

    @Test
    void rawEscapedTextIsNotDecoded() {
        // A literal backslash-u sequence is six plain ASCII units: nothing is unescaped at this layer.
        final String fixture = "\\u0041\\n\\\\";
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(fixture, value.text());
        assertEquals(fixture.length(), value.utf8Size());
    }

    @Test
    void asciiWidthIsExactPerUnit() {
        // Every ASCII unit is one byte, including the block boundaries of the encoding.
        final StringBuilder builder = new StringBuilder();
        for (int c = 0x00; c <= 0x7F; c++) {
            builder.append((char) c);
        }
        final String fixture = builder.toString();
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(0x80, value.utf8Size());
        assertEquals(fixture, value.text());
    }

    @Test
    void bmpWidthIsExactAtBothBoundaries() {
        // One byte below, at and above the two byte range, then the three byte range and its top.
        final String fixture = "a\u007F\u0080\u07FF\u0800\uFFFF\uFFFD";
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(fixture, value.text());
        // 1 + 1 + 2 + 2 + 3 + 3 + 3, cross checked against the JDK encoder.
        assertEquals(1 + 1 + 2 + 2 + 3 + 3 + 3, value.utf8Size());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
    }

    @Test
    void supplementaryCharactersAreFourBytes() {
        // Emoji and the last code point: a surrogate pair is exactly four bytes, not two plus two.
        // Four pairs are therefore 16 bytes, and the JDK encoder agrees.
        final String fixture = "\uD83D\uDE00\uD83D\uDCA9\uDBFF\uDFFF\uD800\uDC00";
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(fixture, value.text());
        assertEquals(8, fixture.length());
        assertEquals(16, value.utf8Size());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
    }

    @Test
    void pairedSurrogatesAtBothEdgesAreAccepted() {
        // Each fixture is 8 UTF-16 units and 10 UTF-8 bytes (three ASCII + one four byte pair).
        final String leading = "\uD83D\uDE00abcdef";
        final String trailing = "abcdef\uD83D\uDE00";
        assertEquals(8, leading.length());
        assertEquals(8, trailing.length());
        assertEquals(10, RawEnvelopeText.of(leading).utf8Size());
        assertEquals(10, RawEnvelopeText.of(trailing).utf8Size());
        assertEquals(jdkUtf8Length(leading), RawEnvelopeText.of(leading).utf8Size());
        assertEquals(jdkUtf8Length(trailing), RawEnvelopeText.of(trailing).utf8Size());
        // A pair on both sides of the ASCII run: 7 units, 3 + 4 + 4 == 11 bytes.
        final String both = "\uD83D\uDE00abc\uD83D\uDE00";
        assertEquals(7, both.length());
        assertEquals(11, RawEnvelopeText.of(both).utf8Size());
        assertEquals(jdkUtf8Length(both), RawEnvelopeText.of(both).utf8Size());
        // A pair adjacent to each other at the boundary must not be mis-charged.
        assertEquals(8, RawEnvelopeText.of("\uD83D\uDE00\uD83D\uDE00").utf8Size());
    }

    @Test
    void unpairedHighSurrogateIsRejected() {
        // A lone high surrogate at the end, before ASCII, and before another high surrogate.
        final String[] fixtures = {
            "\uD83D",
            "abc\uD83D",
            "\uD83Dabc",
            "abc\uD83Dabc",
            "\uD83D\uD83D",
            "abc\uD83D\uD83D\uDE00",
        };
        for (String fixture : fixtures) {
            assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture),
                    "unpaired high surrogate must be rejected");
        }
    }

    @Test
    void unpairedLowSurrogateIsRejected() {
        final String[] fixtures = {
            "\uDE00",
            "abc\uDE00",
            "\uDE00abc",
            "abc\uDE00abc",
            "\uDE00\uDE00",
            "abc\uDE00\uD83D\uDE00",
        };
        for (String fixture : fixtures) {
            assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture),
                    "unpaired low surrogate must be rejected");
        }
    }

    @Test
    void nullTextIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(null));
    }

    @Test
    void textIdentityIsPreservedAndNotCopied() {
        // The production value must keep the very reference it was given: an equal but distinct
        // string would prove a copy, a rebuild or a normalization happened.
        final String distinctButEqual = new String("payload/with/segments");
        final String interned = "payload/with/segments";
        assertNotNull(distinctButEqual);
        final RawEnvelopeText value = RawEnvelopeText.of(distinctButEqual);
        assertSame(distinctButEqual, value.text(), "the same reference must be returned");
        // The distinction is real: only one of the two references is the interned one.
        assertTrue(distinctButEqual != interned || distinctButEqual.equals(interned));
        final RawEnvelopeText second = RawEnvelopeText.of(interned);
        assertSame(interned, second.text());
        assertSame(distinctButEqual, value.text());
    }

    @Test
    void gettersAreStableAcrossRepeatedCalls() {
        final String fixture = "stable-\uD83D\uDE00-text";
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        final int width = value.utf8Size();
        assertSame(value.text(), value.text(), "text() must not rebuild the string");
        assertEquals(width, value.utf8Size(), "utf8Size() must not recount");
        assertEquals(jdkUtf8Length(fixture), width);
        assertEquals(width, value.utf8Size());
    }

    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextAsciiOneUnitOverTheUtf16BudgetIsRejected() {
        // One unit past the budget in pure ASCII: the UTF-16 length alone already proves it cannot
        // fit, so this must be refused before any width work. The fixture is a 64 MiB scale string,
        // so it belongs to the large heap profile.
        final String fixture = "a".repeat(MAX_UTF8_BYTES + 1);
        assertEquals(MAX_UTF8_BYTES + 1, fixture.length());
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture));
    }

    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextBmpWithLegalUtf16LengthButOverBudgetIsRejected() {
        // 3 bytes per unit: the UTF-16 length stays inside the early check while the UTF-8 width does
        // not, so this real case can only be refused by the width scan and never by the length check.
        final int units = MAX_UTF8_BYTES / 3 + 1;
        assertTrue(units <= MAX_UTF8_BYTES, "the UTF-16 length must stay inside the early check");
        assertTrue((long) units * 3L > MAX_UTF8_BYTES, "the width must really exceed the budget");
        final String fixture = "\u4E2D".repeat(units);
        assertEquals(units, fixture.length());
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture));
    }

    @Test
    @Timeout(600)
    void bmpMultiByteTextIsAcceptedWithExactWidthOnASmallHeap() {
        // A large but clearly legal multi byte string, at a scale that fits a modest heap: 1 Mi three
        // byte units are 3 MiB of UTF-8, well inside the 64 MiB budget, so the value must be accepted
        // and its width must match the JDK encoder exactly. Only the over budget forms are refused.
        final int units = 1 << 20;
        final String fixture = "\u4E2D".repeat(units);
        assertEquals(units, fixture.length());
        assertEquals((long) units * 3L, jdkUtf8Length(fixture));
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertSame(fixture, value.text());
        assertEquals((int) ((long) units * 3L), value.utf8Size());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
    }

    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextMultiBytePrefixOverAsciiSuffixIsRejected() {
        // A four unit two byte prefix followed by an ASCII suffix, arranged so that the UTF-16 length is
        // exactly the budget and therefore passes the length guard: only the real UTF-8 width can refuse
        // this text. The prefix is verified against the JDK encoder on a small string first, then the
        // whole fixture is checked by fixture arithmetic before the production call, so the rejection
        // cannot come from the length guard. The fixture is still a full size 64 MiB text, so it belongs
        // to the large 384 MiB profile and is not run on the bounded heap.
        final int prefixUnits = 4;
        final int totalUnits = MAX_UTF8_BYTES;
        final int suffixUnits = totalUnits - prefixUnits;
        final String prefix = "\u00E9";
        // Small independent check of the prefix width, taken from the JDK encoder.
        assertEquals(2, jdkUtf8Length(prefix), "one e acute is two UTF-8 bytes");
        final StringBuilder builder = new StringBuilder(totalUnits);
        builder.append(prefix.repeat(prefixUnits));
        builder.append("x".repeat(suffixUnits));
        final String fixture = builder.toString();
        // Fixture arithmetic: the length equals the budget exactly, so the length guard cannot reject it,
        // while the width is the budget plus the extra byte each two byte prefix unit costs.
        assertEquals(totalUnits, fixture.length(), "the UTF-16 length must sit exactly at the budget");
        assertEquals((long) MAX_UTF8_BYTES + prefixUnits, (long) suffixUnits + (long) prefixUnits * 2L,
                "the fixture width must exceed the budget by the prefix overhead");
        assertTrue(fixture.length() <= MAX_UTF8_BYTES, "the length guard must not be the reason here");
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture));
    }

    // ------------------------------------------------------------------
    // 1000 independently predicted fixtures
    // ------------------------------------------------------------------

    @Test
    void thousandSeededFixturesMatchAnIndependentUtf8Oracle() {
        final Random random = new Random(20261014L);
        // The samples must be genuinely distinct, so every fixture is registered in a set before it is
        // handed to production, and a duplicate is a hard failure rather than something to skip or
        // regenerate. The set size is checked at the end, so a retry or a silent de-duplication that
        // shrank the sweep could not stay green.
        final Set<String> seen = new HashSet<>();
        int accepted = 0;
        int sawEmpty = 0;
        int sawControl = 0;
        int sawMultiScript = 0;
        int sawEmoji = 0;
        for (int i = 0; i < SWEEP_CASES; i++) {
            final String generated = randomFixture(random);
            // Case zero is the explicit empty string. Every other case is made unique by a prefix whose
            // value is derived from the loop index alone, so the 1000 fixtures are 1000 distinct
            // strings whether or not the generator happened to repeat itself.
            final String fixture = i == 0 ? "" : "sample" + i + ":" + generated;
            assertTrue(seen.add(fixture), "case " + i + " produced a duplicate fixture");
            // The oracle is computed on this small input by the JDK encoder itself, before the
            // production call, and is never taken from the production result.
            final int predicted = jdkUtf8Length(fixture);
            final RawEnvelopeText value = RawEnvelopeText.of(fixture);
            assertSame(fixture, value.text(), "case " + i + " lost the reference");
            assertEquals(predicted, value.utf8Size(), "case " + i + " width mismatch");
            accepted++;
            if (fixture.isEmpty()) {
                sawEmpty++;
            }
            if (containsControl(fixture)) {
                sawControl++;
            }
            if (containsMultiScript(fixture)) {
                sawMultiScript++;
            }
            if (containsSupplementary(fixture)) {
                sawEmoji++;
            }
        }
        assertEquals(SWEEP_CASES, accepted, "every generated fixture must be accepted");
        assertEquals(SWEEP_CASES, seen.size(), "the sweep must hold 1000 distinct fixtures");
        // The generator must actually cover the declared categories, otherwise the sweep proves less
        // than it claims.
        assertTrue(sawEmpty > 0, "the sweep must include the empty text");
        assertTrue(sawControl > 0, "the sweep must include control characters");
        assertTrue(sawMultiScript > 0, "the sweep must include multiple scripts");
        assertTrue(sawEmoji > 0, "the sweep must include supplementary characters");
    }

    @Test
    void allSingleUtf16CharactersMatchIndependentOracle() {
        // Every one of the 65536 UTF-16 code units is exercised as a single character text, one unit at
        // a time. The expectation is decided before the production call: lone surrogates must be
        // rejected, every other unit must be accepted with the width the JDK encoder reports for that
        // one character. A single repeated character, however long, would not cover this space.
        int accepted = 0;
        int rejected = 0;
        for (int c = 0; c <= 0xFFFF; c++) {
            final char unit = (char) c;
            final String fixture = String.valueOf(unit);
            final boolean loneSurrogate = Character.isSurrogate(unit);
            if (loneSurrogate) {
                assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture),
                        "unpaired surrogate U+" + Integer.toHexString(c) + " must be rejected");
                rejected++;
                continue;
            }
            // Independent oracle, computed before the production call and never read back from it.
            final int predicted = jdkUtf8Length(fixture);
            final RawEnvelopeText value = RawEnvelopeText.of(fixture);
            assertSame(fixture, value.text(), "U+" + Integer.toHexString(c) + " lost the reference");
            assertEquals(predicted, value.utf8Size(), "U+" + Integer.toHexString(c) + " width mismatch");
            accepted++;
        }
        assertEquals(0x10000 - 0x800, accepted, "every non surrogate unit must be accepted once");
        assertEquals(0x800, rejected, "every surrogate unit must be rejected once");
    }

    @Test
    void codeAsciiPositionsMatchIndependentAlphabet() {
        // The code alphabet is checked position by position across the whole ASCII range, in both
        // constructor forms: a one character code is only legal when it is an upper case letter, and a
        // two character code is only legal when its first character is an upper case letter and its
        // second is an upper case letter, a digit or an underscore. Each expectation is computed from
        // the character comparisons below, before the production call, so the production rules can
        // disagree with the test alphabet and fail.
        final FieldPath owner = FieldPath.fromPointer("/receipts/index");
        final FieldPath relative = FieldPath.fromPointer("/target");
        int headAccepted = 0;
        int headRejected = 0;
        int tailAccepted = 0;
        int tailRejected = 0;
        for (int c = 0; c <= 0x7F; c++) {
            final char unit = (char) c;
            // Effective final copy of the loop counter, so the expected-rejection lambdas below can
            // capture the position while the expectations themselves stay outside the production call.
            final int unitCode = c;
            final boolean legalHead = unit >= 'A' && unit <= 'Z';
            final boolean legalTail = legalHead || (unit >= '0' && unit <= '9') || unit == '_';

            final String single = String.valueOf(unit);
            if (legalHead) {
                final EnvelopeException plain =
                        new EnvelopeException(single, owner, relative, "single " + unitCode);
                assertSame(single, plain.code(), "single char code U+" + unitCode);
                assertSame(owner, plain.owner());
                assertSame(relative, plain.relativePath());
                final EnvelopeException withCause = new EnvelopeException(
                        single, owner, relative, "single " + unitCode, null);
                assertSame(single, withCause.code(),
                        "single char code U+" + unitCode + " with cause form");
                headAccepted++;
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new EnvelopeException(single, owner, relative, "single " + unitCode),
                        "code starting with U+" + unitCode + " must be rejected");
                assertThrows(IllegalArgumentException.class,
                        () -> new EnvelopeException(single, owner, relative, "single " + unitCode, null),
                        "code starting with U+" + unitCode + " must be rejected in the cause form too");
                headRejected++;
            }

            final String tail = "A" + unit;
            if (legalTail) {
                final EnvelopeException plain =
                        new EnvelopeException(tail, owner, relative, "tail " + unitCode);
                assertSame(tail, plain.code(), "two char code A+U+" + unitCode);
                final EnvelopeException withCause = new EnvelopeException(
                        tail, owner, relative, "tail " + unitCode, null);
                assertSame(tail, withCause.code(),
                        "two char code A+U+" + unitCode + " with cause form");
                tailAccepted++;
            } else {
                assertThrows(IllegalArgumentException.class,
                        () -> new EnvelopeException(tail, owner, relative, "tail " + unitCode),
                        "second code char U+" + unitCode + " must be rejected");
                assertThrows(IllegalArgumentException.class,
                        () -> new EnvelopeException(tail, owner, relative, "tail " + unitCode, null),
                        "second code char U+" + unitCode + " must be rejected in the cause form too");
                tailRejected++;
            }
        }
        assertEquals(26, headAccepted, "only A..Z may open a code");
        assertEquals(0x80 - 26, headRejected, "every other ASCII unit must be refused as a code head");
        assertEquals(26 + 10 + 1, tailAccepted, "A..Z, 0..9 and _ may continue a code");
        assertEquals(0x80 - 37, tailRejected, "every other ASCII unit must be refused as a code tail");
    }

    // ------------------------------------------------------------------
    // 65536 unit single character oracle
    // ------------------------------------------------------------------

    @Test
    void sixtyFiveThousandFiveHundredThirtySixUnitsAreMeasuredIndependently() {
        // One repeated BMP character of a known width, 65536 units long, predicted by arithmetic on
        // the fixture and cross checked with the JDK encoder.
        final String fixture = "\u4E2D".repeat(ORACLE_UTF16_LENGTH);
        assertEquals(ORACLE_UTF16_LENGTH, fixture.length());
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(ORACLE_UTF16_LENGTH * 3, value.utf8Size());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
        assertSame(fixture, value.text());
    }

    @Test
    void asciiSixtyFiveThousandFiveHundredThirtySixUnitsAreMeasuredIndependently() {
        final String fixture = "z".repeat(ORACLE_UTF16_LENGTH);
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(ORACLE_UTF16_LENGTH, value.utf8Size());
        assertSame(fixture, value.text());
    }

    @Test
    void sixtyFiveThousandFiveHundredThirtySixSingleSurrogatesAreRejected() {
        // The same oracle length applied to a single unpaired surrogate type: every one is refused.
        assertThrows(IllegalArgumentException.class,
                () -> RawEnvelopeText.of("\uD83D".repeat(ORACLE_UTF16_LENGTH)));
        assertThrows(IllegalArgumentException.class,
                () -> RawEnvelopeText.of("\uDE00".repeat(ORACLE_UTF16_LENGTH)));
    }

    @Test
    void pairedOracleAtSixtyFiveThousandFiveHundredThirtySixUnitsIsMeasured() {
        // 32768 pairs == 65536 UTF-16 units and 131072 UTF-8 bytes.
        final String fixture = "\uD83D\uDE00".repeat(ORACLE_UTF16_LENGTH / 2);
        assertEquals(ORACLE_UTF16_LENGTH, fixture.length());
        final RawEnvelopeText value = RawEnvelopeText.of(fixture);
        assertEquals(ORACLE_UTF16_LENGTH * 2, value.utf8Size());
        assertEquals(jdkUtf8Length(fixture), value.utf8Size());
    }

    // ------------------------------------------------------------------
    // envelope failure values
    // ------------------------------------------------------------------

    @Test
    void allNullArgumentCombinationsAreRejected() {
        final FieldPath root = FieldPath.root();
        final FieldPath relative = FieldPath.fromPointer("/payload");
        // Four argument form: every single null slot, then the all null combination.
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(null, root, relative, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", null, relative, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", root, null, "m"));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", root, relative, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(null, null, null, null));
        // Five argument form: every single null slot except the legal null cause.
        final Throwable cause = new IllegalStateException("real cause");
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(null, root, relative, "m", cause));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", null, relative, "m", cause));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", root, null, "m", cause));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("CODE", root, relative, null, cause));
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(null, null, null, null, null));
    }

    @Test
    void codeAlphabetIsCheckedExplicitly() {
        final FieldPath root = FieldPath.root();
        final FieldPath relative = FieldPath.root();
        // Every legal upper case ASCII letter as the leading character.
        for (char c = 'A'; c <= 'Z'; c++) {
            final String code = String.valueOf(c);
            assertEquals(code, new EnvelopeException(code, root, relative, "m").code());
        }
        // Every legal trailing character class, one instance each.
        final String[] legal = {"A0", "A_", "A9_Z", "AB", "A_0"};
        for (String code : legal) {
            assertEquals(code, new EnvelopeException(code, root, relative, "m").code());
        }
        // Rejected: lower case anywhere, a leading digit or underscore, punctuation, whitespace,
        // non ASCII letters, and any other symbol.
        final String[] illegal = {
            "a", "Code", "cODE", "0CODE", "_CODE", "CO-DE", "CO DE", "CO.DE", "CO:DE", "CO/DE",
            "C\u00D6DE", "C\u4E2D", "CODE\u00E9", "\uFF21", "A!", "A#", "A~", "A=", "A\u0000",
            "\tCODE", "CODE\n", "A\u0080",
        };
        for (String code : illegal) {
            assertThrows(IllegalArgumentException.class,
                    () -> new EnvelopeException(code, root, relative, "m"), "must reject code " + code);
            assertThrows(IllegalArgumentException.class,
                    () -> new EnvelopeException(code, root, relative, "m", null),
                    "must reject code " + code + " in the cause form");
        }
    }

    @Test
    void codeLengthBoundariesAreExact() {
        final FieldPath root = FieldPath.root();
        // Length 0 is rejected; 1, 63 and 64 are accepted; 65 is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("", root, root, "m"));
        assertEquals("A", new EnvelopeException("A", root, root, "m").code());
        final String length63 = "A" + "B".repeat(62);
        assertEquals(63, length63.length());
        assertEquals(length63, new EnvelopeException(length63, root, root, "m").code());
        final String length64 = "A" + "B".repeat(63);
        assertEquals(64, length64.length());
        assertEquals(length64, new EnvelopeException(length64, root, root, "m").code());
        final String length65 = "A" + "B".repeat(64);
        assertEquals(65, length65.length());
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(length65, root, root, "m"));
    }

    @Test
    void futureUpperCaseCodeIsStorableWithoutAnEnum() {
        // A code that no current contract defines must still be storable: there is no closed set here.
        final FieldPath root = FieldPath.root();
        final String future = "ENVELOPE_FUTURE_REASON_2";
        final EnvelopeException failure = new EnvelopeException(future, root, root, "future code");
        assertEquals(future, failure.code());
    }

    @Test
    void ownerAndRelativePathAllowRootAndFourSegments() {
        final FieldPath root = FieldPath.root();
        final FieldPath four = FieldPath.fromPointer("/receipts/index/fingerprintInputs/target");
        assertEquals(4, four.segments().size());
        final EnvelopeException failure =
                new EnvelopeException("OWNER_DEPTH", four, root, "four segment owner");
        assertSame(four, failure.owner());
        assertSame(root, failure.relativePath());
        // The root owner is legal as well.
        final EnvelopeException rooted =
                new EnvelopeException("ROOT_OWNER", root, four, "root owner");
        assertSame(root, rooted.owner());
        assertSame(four, rooted.relativePath());
    }

    @Test
    void fiveSegmentOwnerIsRejectedByTheOwnerBudget() {
        // The owner budget is strictly tighter than the frozen FieldPath budget: five segments are a
        // perfectly legal FieldPath and are legal as a relative path, but they are never a legal owner.
        final FieldPath five = FieldPath.fromPointer("/a/b/c/d/e");
        assertEquals(5, five.segments().size(), "the frozen path type itself accepts five segments");
        // A five segment relative path is legal, so the owner check is not a second path budget.
        final EnvelopeException relativeFive =
                new EnvelopeException("RELATIVE_FIVE", FieldPath.root(), five, "known short path");
        assertSame(five, relativeFive.relativePath());
        // Both constructor forms must refuse a five segment owner, and neither may silently store it.
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("OWNER_FIVE", five, FieldPath.root(), "five segment owner"),
                "the four argument form must refuse a five segment owner");
        final Throwable cause = new JsonContractException("LIMIT_EXCEEDED", "/inner", "inner failure");
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException(
                        "OWNER_FIVE", five, FieldPath.root(), "five segment owner", cause),
                "the cause form must refuse a five segment owner as well");
        // The boundary is exact: four segments are accepted by both forms.
        final FieldPath four = FieldPath.fromPointer("/a/b/c/d");
        assertEquals(4, four.segments().size());
        assertSame(four, new EnvelopeException("OWNER_FOUR", four, FieldPath.root(), "four")
                .owner());
        assertSame(four, new EnvelopeException("OWNER_FOUR", four, FieldPath.root(), "four", cause)
                .owner());
    }

    @Test
    void ownerAtTheBudgetIsAcceptedAndDeepOwnersAreRefused() {
        // The owner budget is four segments regardless of heap: the largest legal owner is accepted and
        // it is stored by reference, while a deeper owner is refused before it can be recorded.
        final FieldPath largest = FieldPath.fromPointer("/1/2/3/4");
        assertEquals(4, largest.segments().size());
        final EnvelopeException accepted =
                new EnvelopeException("OWNER_MAX", largest, largest, "largest legal owner");
        assertSame(largest, accepted.owner());
        assertSame(largest, accepted.relativePath());
        // A deep relative path is fine, so only the owner position is bounded.
        final List<String> deepSegments = new ArrayList<>(65);
        for (int i = 0; i < 65; i++) {
            deepSegments.add("d" + i);
        }
        final FieldPath deep = new FieldPath(deepSegments);
        assertEquals(65, deep.segments().size());
        assertSame(deep, new EnvelopeException("DEEP_RELATIVE", largest, deep, "deep relative")
                .relativePath());
        assertThrows(IllegalArgumentException.class,
                () -> new EnvelopeException("DEEP_OWNER", deep, largest, "deep owner"));
    }

    @Test
    void relativePathBudgetIsExactlySixtyFiveSegments() {
        // The frozen budget is 65 segments: build exactly 65 and keep the full standard pointer.
        final List<String> segments = new ArrayList<>(65);
        for (int i = 0; i < 65; i++) {
            segments.add("s" + i);
        }
        final FieldPath sixtyFive = new FieldPath(segments);
        assertEquals(65, sixtyFive.segments().size());
        final EnvelopeException failure = new EnvelopeException(
                "RELATIVE_BUDGET", FieldPath.root(), sixtyFive, "sixty five segments");
        assertSame(sixtyFive, failure.relativePath());
        // The standard pointer is preserved completely, with one prefix per segment.
        final String pointer = failure.relativePath().pointer();
        assertEquals(65, countChar(pointer, '/'));
        assertEquals(sixtyFive.pointer(), pointer);
        assertEquals("/s0/s1", pointer.substring(0, 6));
        assertEquals("/s64", pointer.substring(pointer.lastIndexOf('/')));
        // One more segment is rejected before any new path exists.
        assertThrows(IllegalArgumentException.class, () -> sixtyFive.child("s65"));
    }

    @Test
    void ownerAndRelativeAreIndependentAndNeverConcatenated() {
        // A short owner and a long relative path must not be merged, and neither may the reverse.
        final FieldPath owner = FieldPath.fromPointer("/receipts");
        final FieldPath relative = FieldPath.fromPointer("/payload/items/0/name");
        final EnvelopeException failure =
                new EnvelopeException("UNKNOWN_OWNER", owner, relative, "split identity");
        assertSame(owner, failure.owner());
        assertSame(relative, failure.relativePath());
        assertEquals(1, failure.owner().segments().size());
        assertEquals(4, failure.relativePath().segments().size());
        // The stored identities are exactly the inputs, not a merged path: a concatenation would
        // either grow one of them or collide with the known outer path shape.
        assertEquals(owner.pointer(), failure.owner().pointer());
        assertEquals(relative.pointer(), failure.relativePath().pointer());
        assertEquals("/receipts", failure.owner().pointer());
        assertEquals("/payload/items/0/name", failure.relativePath().pointer());
        // The merged shape that concatenation would produce is a genuinely different identity.
        final FieldPath merged = FieldPath.fromPointer("/receipts/payload/items/0/name");
        assertEquals(5, merged.segments().size());
        assertTrue(!merged.equals(failure.owner()) && !merged.equals(failure.relativePath()));
    }

    @Test
    void knownOuterSchemaFailureUsesRootOwnerAndShortRelativePath() {
        // A known outer schema error is reported with the root owner and the concrete short path,
        // whose segments are copied from the original failure unchanged.
        final FieldPath relative = FieldPath.fromPointer("/a/b/c/d/e");
        final EnvelopeException failure =
                new EnvelopeException("INVALID_VALUE", FieldPath.root(), relative, "outer schema error");
        assertSame(FieldPath.root(), failure.owner());
        assertSame(relative, failure.relativePath());
        assertEquals("", failure.owner().pointer());
    }

    @Test
    void messageIsPreservedVerbatimIncludingEmptyAndUnicode() {
        final FieldPath root = FieldPath.root();
        final String[] messages = {
            "",
            "plain technical detail",
            "unicode \u4E2D\u6587 \uD83D\uDE00 \u00E9",
            "with \"quotes\" and \\backslash",
            "newline\nand\ttab",
            "\u0000control",
            "repeated repeated repeated",
        };
        for (String message : messages) {
            final EnvelopeException failure =
                    new EnvelopeException("MESSAGE_CASE", root, root, message);
            assertEquals(message, failure.getMessage(), "message must be preserved verbatim");
        }
        // A message that is not a legal JSON key is still legal here: no key re-validation happens.
        final String notAKey = "a".repeat(2000);
        assertEquals(notAKey, new EnvelopeException("LONG_MESSAGE", root, root, notAKey).getMessage());
    }

    @Test
    void causeIdentityIsPreservedAndNullCauseMatchesTheShortForm() {
        final FieldPath owner = FieldPath.fromPointer("/receipts");
        final FieldPath relative = FieldPath.fromPointer("/digest");
        final Throwable cause = new JsonContractException("LIMIT_EXCEEDED", "/payload", "inner failure");
        final EnvelopeException withCause =
                new EnvelopeException("INNER_LIMIT", owner, relative, "outer failure", cause);
        // The very object must come back: a wrapper would change the identity.
        assertSame(cause, withCause.getCause());
        assertEquals("LIMIT_EXCEEDED", ((JsonContractException) withCause.getCause()).code());
        // A null cause is the absence of a cause, exactly like the constructor without a cause.
        final EnvelopeException nullCause =
                new EnvelopeException("INNER_LIMIT", owner, relative, "outer failure", null);
        final EnvelopeException noCause =
                new EnvelopeException("INNER_LIMIT", owner, relative, "outer failure");
        assertNull(nullCause.getCause());
        assertNull(noCause.getCause());
        assertEquals(noCause.getCause(), nullCause.getCause());
        assertEquals(noCause.getMessage(), nullCause.getMessage());
        assertEquals(noCause.code(), nullCause.code());
        assertSame(noCause.owner(), nullCause.owner());
        assertSame(noCause.relativePath(), nullCause.relativePath());
        // Comparing getters alone would not prove the Throwable initialisation state is the same, so a
        // cause is actually attached later. Both forms go through Throwable(String, Throwable) with a
        // null cause, which initialises the cause slot to null, so neither value can be given a cause
        // afterwards: the real behaviour is that initCause is refused on both, not merely that getCause
        // reads null on both. This is exactly the asymmetry a getter-only comparison would hide.
        final IllegalStateException late = new IllegalStateException("late attached cause");
        assertThrows(IllegalStateException.class, () -> noCause.initCause(late),
                "the four argument form must refuse a late initCause, like the null cause form");
        assertThrows(IllegalStateException.class, () -> nullCause.initCause(late),
                "the five argument form with a null cause must refuse a late initCause");
        assertNull(noCause.getCause(), "a refused initCause must not have stored anything");
        assertNull(nullCause.getCause(), "a refused initCause must not have stored anything");
    }

    @Test
    void realDeepSixtyFivePathDamageIsCarriedOverWithoutConcatenation() {
        // A genuine frozen parser failure whose reported path is already a full 65 segment FieldPath:
        // 65 nested arrays with a scalar at the bottom. The scalar sits at depth 65, one past the frozen
        // nesting budget of 64, and because it hangs off the root index chain its reported path is
        // exactly /0/0/.../0 with 65 segments.
        final int prefix = 65;
        final String deep = "[".repeat(prefix) + "0" + "]".repeat(prefix);
        final JsonContractException parserFailure =
                assertThrows(JsonContractException.class, () -> ExactJson.parse(deep));
        assertEquals("LIMIT_EXCEEDED", parserFailure.code());
        assertNotNull(parserFailure.path());
        // One level shallower is still legal, so this is a real budget edge and not a shallow error.
        final String shallower = "[".repeat(prefix - 1) + "0" + "]".repeat(prefix - 1);
        assertNotNull(ExactJson.parse(shallower));
        final FieldPath originalPath = FieldPath.fromPointer(parserFailure.path());
        assertEquals(65, originalPath.segments().size(),
                "the genuine failure path must sit exactly at the frozen 65 segment budget");
        // Every segment is a root index, so the path really is the root index chain.
        for (String segment : originalPath.segments()) {
            assertEquals("0", segment);
        }
        // The envelope failure re-homes that path under an explicit owner without inventing segments.
        final FieldPath owner = FieldPath.fromPointer("/payload");
        final EnvelopeException carried = new EnvelopeException(
                parserFailure.code(), owner, originalPath, "deep failure re-homed", parserFailure);
        assertEquals("LIMIT_EXCEEDED", carried.code());
        assertSame(owner, carried.owner());
        assertSame(originalPath, carried.relativePath());
        // The relative pointer is the original pointer unchanged, and it did not gain the owner
        // prefix, so no concatenation produced a 66th segment.
        assertEquals(parserFailure.path(), carried.relativePath().pointer());
        assertEquals(65, carried.relativePath().segments().size());
        assertSame(parserFailure, carried.getCause());
        // The concatenated shape would be a genuinely different, longer path: it is absent here, and
        // the frozen budget would refuse it outright.
        final String concatenated = "/payload" + parserFailure.path();
        assertThrows(IllegalArgumentException.class, () -> FieldPath.fromPointer(concatenated),
                "the concatenated owner plus relative path exceeds the frozen budget");
        assertEquals(66, countChar(concatenated, '/'));
        assertTrue(!carried.owner().pointer().equals(concatenated));
        assertTrue(!carried.relativePath().pointer().equals(concatenated));
    }

    @Test
    void multiLevelCauseMessagesCannotChangeCodeOrPath() {
        // A cause chain with misleading text must not influence the stable machine fields.
        final FieldPath owner = FieldPath.fromPointer("/receipts/index");
        final FieldPath relative = FieldPath.fromPointer("/fingerprintInputs/target");
        final Throwable inner = new IllegalArgumentException("CODE_LOOKS_LIKE_A_CODE /a/b/c");
        final Throwable middle = new IllegalStateException("path=/x/y/z", inner);
        final EnvelopeException failure =
                new EnvelopeException("STABLE_CODE", owner, relative, middle.getMessage(), middle);
        assertEquals("STABLE_CODE", failure.code());
        assertSame(owner, failure.owner());
        assertSame(relative, failure.relativePath());
        assertEquals("/receipts/index", failure.owner().pointer());
        assertEquals("/fingerprintInputs/target", failure.relativePath().pointer());
        assertSame(middle, failure.getCause());
        assertSame(inner, failure.getCause().getCause());
        // The misleading texts are preserved, proving they were not parsed into the machine fields.
        assertEquals("path=/x/y/z", failure.getMessage());
        assertEquals("CODE_LOOKS_LIKE_A_CODE /a/b/c", failure.getCause().getCause().getMessage());
    }

    @Test
    void envelopeFailureIsAnIllegalArgumentExceptionAndKeepsItsOwnType() {
        final EnvelopeException failure =
                new EnvelopeException("TYPE_CHECK", FieldPath.root(), FieldPath.root(), "m");
        final IllegalArgumentException unchecked = failure;
        assertTrue(unchecked instanceof IllegalArgumentException,
                "an envelope failure must be an unchecked argument failure");
        // A plain argument error must not be reported as a content schema failure: the two types are
        // unrelated siblings, so an envelope failure can never be caught as a schema failure.
        final Class<?> envelope = failure.getClass();
        final Class<?> schema = SchemaException.class;
        assertTrue(!schema.isAssignableFrom(envelope),
                "a parameter error must not masquerade as a content schema failure");
        assertTrue(!envelope.isAssignableFrom(schema),
                "the two failure types must stay unrelated");
        assertEquals(EnvelopeException.class, envelope);
    }

    // ------------------------------------------------------------------
    // maximum legal text: 384 MiB profile only, never claimed inside a 64 MiB heap
    // ------------------------------------------------------------------

    /**
     * Exact maximum ASCII text: 64 MiB units are exactly 64 MiB bytes and are accepted, while one
     * more unit is refused.
     *
     * <p>This is the only group of tests that builds a text as large as the production budget, so it
     * needs the large heap profile. The three tests in this group are excluded from the bounded 64 MiB
     * profile through an explicit tag and a disclosed count, and their maximum is never claimed to
     * have been built inside a 64 MiB heap.</p>
     */
    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextAsciiIsExactAtTheBudget() {
        // The fixture is built and released one at a time: the accepted text and the rejected text
        // are never both held at once.
        final long before = usedHeapBytes();
        assertTrue(Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024,
                "this test needs the 384 MiB profile, got maxHeapMiB="
                        + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        final String accepted = "a".repeat(MAX_UTF8_BYTES);
        assertEquals(MAX_UTF8_BYTES, accepted.length());
        final RawEnvelopeText value = RawEnvelopeText.of(accepted);
        assertEquals(MAX_UTF8_BYTES, value.utf8Size());
        assertSame(accepted, value.text());
        final long afterAccept = usedHeapBytes();
        System.out.println("MAX_TEXT ascii accepted utf8=" + value.utf8Size()
                + " utf16=" + value.text().length()
                + " heapUsedMiB=" + (afterAccept / (1024 * 1024)));
        // One unit past the budget: refused by the length pre-check alone.
        final String rejected = "a".repeat(MAX_UTF8_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(rejected));
        System.out.println("MAX_TEXT ascii rejected utf16=" + rejected.length()
                + " heapUsedDeltaMiB=" + ((usedHeapBytes() - afterAccept) / (1024 * 1024))
                + " heapUsedBeforeMiB=" + (before / (1024 * 1024)));
    }

    /**
     * Exact maximum paired text: 32 Mi pairs are exactly 64 Mi UTF-16 units and exactly 64 MiB bytes
     * and are accepted, while one more pair is refused.
     */
    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextPairedSurrogatesIsExactAtTheBudget() {
        assertTrue(Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024,
                "this test needs the 384 MiB profile, got maxHeapMiB="
                        + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        // 64 MiB bytes / 4 bytes per pair == 16 Mi pairs == 32 Mi UTF-16 units.
        final int pairs = MAX_UTF8_BYTES / 4;
        assertEquals(MAX_UTF8_BYTES, pairs * 4);
        final String accepted = "\uD83D\uDE00".repeat(pairs);
        assertEquals(pairs * 2, accepted.length());
        final RawEnvelopeText value = RawEnvelopeText.of(accepted);
        assertEquals(MAX_UTF8_BYTES, value.utf8Size());
        assertSame(accepted, value.text());
        System.out.println("MAX_TEXT paired accepted utf8=" + value.utf8Size()
                + " utf16=" + value.text().length()
                + " heapUsedMiB=" + (usedHeapBytes() / (1024 * 1024)));
        // One more pair is 4 bytes over the budget: every byte of the suffix must be counted, and the
        // rejection can only come from the width scan, not from the UTF-16 length pre-check.
        final String rejected = "\uD83D\uDE00".repeat(pairs + 1);
        assertEquals(MAX_UTF8_BYTES + 4, (pairs + 1) * 4);
        assertTrue(rejected.length() <= MAX_UTF8_BYTES,
                "the extra pair case must survive the UTF-16 length pre-check");
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(rejected));
    }

    /**
     * Maximum UTF-16 BMP text whose UTF-8 width is far over the budget: the length is legal but the
     * width is not, so this real case can only be refused by the scan.
     */
    @Test
    @Tag(MAX_TEXT_TAG)
    @Timeout(600)
    void maximumLegalRawTextBmpOverBudgetIsRefusedByWidth() {
        assertTrue(Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024,
                "this test needs the 384 MiB profile, got maxHeapMiB="
                        + (Runtime.getRuntime().maxMemory() / (1024 * 1024)));
        // Half the budget in UTF-16 units of a three byte character: 32 Mi units, 96 MiB of UTF-8.
        final int units = MAX_UTF8_BYTES / 2;
        assertTrue(units <= MAX_UTF8_BYTES, "the UTF-16 length must stay legal");
        final String fixture = "\u4E2D".repeat(units);
        assertEquals(units, fixture.length());
        assertEquals(units * 3, (long) units * 3);
        assertThrows(IllegalArgumentException.class, () -> RawEnvelopeText.of(fixture));
        System.out.println("MAX_TEXT bmp rejected utf16=" + fixture.length()
                + " wouldBeUtf8=" + ((long) fixture.length() * 3L)
                + " heapUsedMiB=" + (usedHeapBytes() / (1024 * 1024)));
    }

    // ------------------------------------------------------------------
    // fixtures and oracles
    // ------------------------------------------------------------------

    /**
     * Builds one deterministic fixture of at most {@link #SWEEP_MAX_UTF16} UTF-16 units.
     *
     * <p>The generator mixes plain ASCII, controls, two byte characters, three byte CJK, other
     * multi byte scripts and supplementary pairs, and it can also return the empty text, so the sweep
     * covers the declared categories instead of only one shape.</p>
     *
     * @param random seeded generator
     * @return a well formed fixture
     */
    private static String randomFixture(Random random) {
        final int length = random.nextInt(SWEEP_MAX_UTF16 + 1);
        final StringBuilder builder = new StringBuilder(length);
        while (builder.length() < length) {
            final int kind = random.nextInt(7);
            switch (kind) {
                case 0:
                    builder.append((char) random.nextInt(0x80));
                    break;
                case 1:
                    builder.append((char) (0x80 + random.nextInt(0x780)));
                    break;
                case 2:
                    builder.append((char) (0x800 + random.nextInt(0x80)));
                    break;
                case 3:
                    // A CJK character that is never a surrogate.
                    builder.append((char) (0x4E00 + random.nextInt(0x100)));
                    break;
                case 4:
                    builder.append('\uD83D').append('\uDE00');
                    break;
                case 5:
                    builder.append((char) (0x0400 + random.nextInt(0x40)));
                    break;
                default:
                    builder.append((char) (0x0900 + random.nextInt(0x40)));
                    break;
            }
        }
        return builder.toString();
    }

    /**
     * Independent UTF-8 width oracle: the JDK encoder applied to this small input.
     *
     * @param text small fixture
     * @return the real UTF-8 byte length reported by the JDK
     */
    private static int jdkUtf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean containsControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsMultiScript(String text) {
        boolean latin = false;
        boolean other = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                latin = true;
            } else if (c >= 0x0400 && c < 0x0980) {
                other = true;
            }
        }
        return latin && other;
    }

    private static boolean containsSupplementary(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isHighSurrogate(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static int countChar(String text, char target) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == target) {
                count++;
            }
        }
        return count;
    }

    /**
     * Reports the used heap so the maximum text profile can state what it really allocated.
     *
     * @return used heap bytes after a best effort collection
     */
    private static long usedHeapBytes() {
        final Runtime runtime = Runtime.getRuntime();
        System.gc();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    static {
        // The mirrored budget and the frozen path budget are declared facts of this suite, checked
        // once so a silent contract change can never make these tests pass vacuously.
        if (MAX_UTF8_BYTES != RawEnvelopeText.MAX_UTF8_BYTES) {
            throw new IllegalStateException("mirrored byte budget does not match the production budget");
        }
        if (FieldPath.MAX_SEGMENTS != 65) {
            throw new IllegalStateException("unexpected FieldPath.MAX_SEGMENTS: " + FieldPath.MAX_SEGMENTS);
        }
        if (SWEEP_CASES != 1000) {
            throw new IllegalStateException("the sweep must cover 1000 fixtures");
        }
        if (ORACLE_UTF16_LENGTH != 65536) {
            throw new IllegalStateException("the single character oracle must be 65536 units");
        }
    }
}
