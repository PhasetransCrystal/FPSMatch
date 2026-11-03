package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the R02.2 exact number normalization layer.
 *
 * <p><strong>Scope.</strong> {@link ExactJsonNumber#parse(String, String)} decides the exact value
 * and the single canonical spelling of one already-delimited number literal. It is not a document
 * reader: it does not scan text, does not check punctuation, and does not know where a literal ends.
 * The literal handed in here is assumed to be the complete token, so a test may pass text that no
 * document scanner would ever isolate (for example a 1024 character literal).</p>
 *
 * <p>The acceptance assertions below never rely on the implementation's own output: the expected
 * canonical text is recomputed in the test from an independently built {@link BigDecimal} and the
 * expected integer relations are recomputed with {@link BigInteger}. Every accepted result must be
 * re-readable, so each acceptance also feeds the produced canonical text back into {@code parse} and
 * requires a character identical canonical text and a numerically equal value.</p>
 */
class ExactJsonNumberContractTest {

    /** Path used by most vectors; failures must report it verbatim. */
    private static final String PATH = "/n";

    private static ExactJsonNumber.ParsedNumber parse(String raw) {
        return ExactJsonNumber.parse(raw, PATH);
    }

    private static JsonContractException fails(String raw, String path) {
        return assertThrows(JsonContractException.class, () -> ExactJsonNumber.parse(raw, path));
    }

    private static void assertFails(String raw, String expectedCode) {
        JsonContractException failure = fails(raw, PATH);
        assertEquals(expectedCode, failure.code(), "failure code for " + describe(raw));
        assertEquals(PATH, failure.path(), "failure path for " + describe(raw));
        assertNotNull(failure.getMessage(), "message must be preserved for " + describe(raw));
    }

    private static String describe(String raw) {
        if (raw == null) {
            return "null";
        }
        if (raw.length() <= 48) {
            return "\"" + raw + "\"";
        }
        return "\"" + raw.substring(0, 24) + "...\" (length " + raw.length() + ")";
    }

    /** Independent canonical oracle, computed from the value alone. */
    private static String oracleCanonical(BigDecimal exact) {
        return exact.signum() == 0 ? "0" : exact.stripTrailingZeros().toString();
    }

    /**
     * Re-reads the canonical text and requires a fixed point; a canonical text that cannot be read
     * back would be an unrepresentable value and is treated as a failure of this layer.
     */
    private static void assertReRead(ExactJsonNumber.ParsedNumber parsed, String origin) {
        assertEquals(oracleCanonical(parsed.value()), parsed.canonical(), "canonical text of " + describe(origin));
        ExactJsonNumber.ParsedNumber again = ExactJsonNumber.parse(parsed.canonical(), PATH);
        assertEquals(parsed.canonical(), again.canonical(), "canonical must be a fixed point of " + describe(origin));
        assertEquals(0, parsed.value().compareTo(again.value()), "value must survive re-reading " + describe(origin));
        assertEquals(0, parsed.value().compareTo(new BigDecimal(parsed.canonical())),
                "value must match the value of its canonical text for " + describe(origin));
        if (parsed.value().signum() != 0) {
            assertEquals(parsed.value().stripTrailingZeros(), again.value(),
                    "re-read value must carry the stripped scale of " + describe(origin));
        } else {
            assertEquals("0", again.canonical(), "numeric zero keeps the single zero spelling for " + describe(origin));
        }
    }

    /** Accepts a literal, cross-checks value/canonical against an independent oracle and re-reads it. */
    private static ExactJsonNumber.ParsedNumber accepted(String raw) {
        ExactJsonNumber.ParsedNumber parsed = parse(raw);
        assertNotNull(parsed.value(), "value of " + describe(raw));
        assertNotNull(parsed.canonical(), "canonical of " + describe(raw));
        assertFalse(parsed.canonical().isEmpty(), "canonical of " + describe(raw) + " must not be empty");
        assertEquals(oracleCanonical(parsed.value()), parsed.canonical(), "canonical text of " + describe(raw));
        assertEquals(0, parsed.value().compareTo(new BigDecimal(raw)), "exact value of " + describe(raw));
        assertReRead(parsed, raw);
        return parsed;
    }

    /** Accepts a literal whose canonical text is fixed by hand, without using the implementation. */
    private static ExactJsonNumber.ParsedNumber acceptedAs(String raw, String expectedCanonical) {
        ExactJsonNumber.ParsedNumber parsed = accepted(raw);
        assertEquals(expectedCanonical, parsed.canonical(), "hand written canonical of " + describe(raw));
        return parsed;
    }

    @Nested
    @DisplayName("equivalent spellings normalize to one text")
    class EquivalentSpellings {

        @Test
        @DisplayName("1, 1.0 and 1e0 canonicalize identically while keeping their own precision")
        void oneIsOne() {
            ExactJsonNumber.ParsedNumber plain = acceptedAs("1", "1");
            ExactJsonNumber.ParsedNumber fractional = acceptedAs("1.0", "1");
            ExactJsonNumber.ParsedNumber exponential = acceptedAs("1e0", "1");
            assertEquals(plain.canonical(), fractional.canonical());
            assertEquals(plain.canonical(), exponential.canonical());
            assertEquals(0, plain.value().compareTo(fractional.value()));
            assertEquals(0, plain.value().compareTo(exponential.value()));
            assertEquals(0, plain.value().scale(), "the original precision is kept for \"1\"");
            assertEquals(1, fractional.value().scale(), "the original precision is kept for \"1.0\"");
            assertEquals(0, exponential.value().scale());
            assertEquals(plain, exponential, "identical value and canonical text make identical records");
            assertEquals(plain.hashCode(), exponential.hashCode());
        }

        @Test
        @DisplayName("more spellings of one collapse onto the same canonical text")
        void manySpellingsOfOne() {
            String[] spellings = {"1", "1.0", "1.00", "1e0", "1E0", "1e+0", "1e-0", "10e-1", "0.1e1", "100e-2"};
            for (String spelling : spellings) {
                acceptedAs(spelling, "1");
            }
        }

        @Test
        @DisplayName("numeric zero always canonicalizes to 0 regardless of sign, scale and exponent")
        void zeros() {
            String[] zeros = {"0", "-0", "0.0", "-0.0", "0e0", "0E0", "0e3", "-0e3", "0e-3", "0.000e5", "-0.0e-2",
                    "0.0E+1000000", "0e1000000"};
            for (String zero : zeros) {
                ExactJsonNumber.ParsedNumber parsed = acceptedAs(zero, "0");
                assertEquals(0, parsed.value().signum(), "zero value of " + describe(zero));
                assertEquals("0", parsed.canonical());
            }
        }

        @Test
        @DisplayName("the sign of a non zero value is carried into the canonical text")
        void signs() {
            acceptedAs("-1", "-1");
            acceptedAs("-1.5", "-1.5");
            acceptedAs("-1.5e-7", "-1.5E-7");
            acceptedAs("-0.5e1", "-5");
            acceptedAs("1.5e-7", "1.5E-7");
        }
    }

    @Nested
    @DisplayName("grammar rejections use INVALID_NUMBER")
    class GrammarRejections {

        @Test
        @DisplayName("a null literal is INVALID_NUMBER and keeps the path")
        void nullLiteral() {
            assertFails(null, JsonContractException.CODE_INVALID_NUMBER);
            JsonContractException failure = fails(null, "/a/b");
            assertEquals(JsonContractException.CODE_INVALID_NUMBER, failure.code());
            assertEquals("/a/b", failure.path());
        }

        @Test
        @DisplayName("empty, whitespace and trimmed literals are rejected; there is no trimming")
        void noTrimming() {
            String[] illegal = {"", " ", "\t", "\n", "\r", " 1", "1 ", "1\t", "\t-1", "1\n", "\n1", "\u00A01", "1\u00A0",
                    "\u20281", "1\u2028", "1\u0000"};
            for (String raw : illegal) {
                assertFails(raw, JsonContractException.CODE_INVALID_NUMBER);
            }
        }

        @Test
        @DisplayName("leading zeros, signs, fractions and exponents outside the JSON grammar are rejected")
        void brokenNumbers() {
            String[] illegal = {"01", "00", "-01", "-00", "000000000000000000000000000000001", "1.", ".5", "-.5", "1.e5",
                    "1.5.5", "+1", "+0", "--1", "-", "--", "1-", "1+1", "1e", "1E", "1e+", "1e-", "1e1e1", "1e1.5",
                    "1ee1", "e1", "E1", "1d", "1f", "1F", "1L", "1_000", "1,0", "1 0", "0.5.0", "5E+-3", "5e--3",
                    "undefined", "1e 1", "0b101", "0o17", ".", "..", "-."};
            for (String raw : illegal) {
                assertFails(raw, JsonContractException.CODE_INVALID_NUMBER);
            }
        }

        @Test
        @DisplayName("no hexadecimal, NaN, Infinity or quoted number string is accepted")
        void nonDecimalForms() {
            String[] illegal = {"0x10", "0X10", "-0x1", "0x1p3", "NaN", "-NaN", "Infinity", "-Infinity", "+Infinity",
                    "inf", "-inf", "null", "true", "\"1\"", "'1'", "\"1.0\"", "\" 1\"", "1\"", "\"1", "【1】",
                    "1;2", "1(2)", "½", "⅓", "1.5²", "1eⁿ", "∞", "-∞"};
            for (String raw : illegal) {
                assertFails(raw, JsonContractException.CODE_INVALID_NUMBER);
            }
        }

        @Test
        @DisplayName("non ASCII digits are never accepted, not even mixed with ASCII digits")
        void asciiDigitsOnly() {
            String[] illegal = {"\u0661", "\u0661\u0662\u0663", "1\u0662\u0663", "-1\u0662", "\u0661.5", "1.\u0662",
                    "\uFF11", "\uFF11\uFF12\uFF13", "1\uFF12\uFF13", "-\uFF11", "1e\uFF11", "\u0967\u0968\u0969",
                    "1e\u0661", "\u06F1\u06F2"};
            for (String raw : illegal) {
                assertFails(raw, JsonContractException.CODE_INVALID_NUMBER);
            }
        }
    }

    @Nested
    @DisplayName("the 1024 character literal budget is checked first")
    class LiteralLengthBudget {

        @Test
        @DisplayName("a 1024 character literal with a non zero tail is accepted and stays 1024")
        void exactlyAtTheBudget() {
            String fraction = "1." + "9".repeat(1022);
            assertEquals(1024, fraction.length());
            ExactJsonNumber.ParsedNumber parsed = acceptedAs(fraction, fraction);
            assertEquals(1024, parsed.canonical().length());

            String integer = "1" + "2".repeat(1023);
            assertEquals(1024, integer.length());
            acceptedAs(integer, integer);

            String negative = "-0." + "7".repeat(1021);
            assertEquals(1024, negative.length());
            ExactJsonNumber.ParsedNumber negated = accepted(negative);
            assertEquals(1024, negated.canonical().length());
            assertTrue(negated.canonical().startsWith("-0.7"));
        }

        @Test
        @DisplayName("a 1025 character literal is LIMIT_EXCEEDED even when the grammar is legal")
        void oneOverTheBudget() {
            assertFails("1." + "9".repeat(1023), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1" + "2".repeat(1024), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("-" + "9".repeat(1024), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e1" + "0".repeat(1022), JsonContractException.CODE_LIMIT_EXCEEDED);

            /* The budget precedes the grammar: an illegal 1025 character text is a limit failure. */
            assertFails("x".repeat(1025), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("\u0661".repeat(1025), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("07" + "7".repeat(1023), JsonContractException.CODE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("the budget is not applied to shorter illegal literals")
        void budgetDoesNotMaskGrammar() {
            assertFails("x".repeat(1024), JsonContractException.CODE_INVALID_NUMBER);
            assertFails("07" + "7".repeat(1022), JsonContractException.CODE_INVALID_NUMBER);
        }
    }

    @Nested
    @DisplayName("a written exponent is accounted digit by digit")
    class WrittenExponentBudget {

        @Test
        @DisplayName("the exact boundary is accepted in both directions")
        void boundaryAccepted() {
            acceptedAs("1e1000000", "1E+1000000");
            acceptedAs("1E+1000000", "1E+1000000");
            acceptedAs("1e-1000000", "1E-1000000");
            acceptedAs("-1e1000000", "-1E+1000000");
            acceptedAs("1e" + "0".repeat(29) + "1", "1E+1");
            acceptedAs("1e0001000000", "1E+1000000");
            acceptedAs("0e1000000", "0");
            acceptedAs("0.0e-1000000", "0");
        }

        @Test
        @DisplayName("one over the boundary is LIMIT_EXCEEDED and keeps the path")
        void boundaryRejected() {
            assertFails("1e1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e-1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("-1e1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e9999999", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e10000000", JsonContractException.CODE_LIMIT_EXCEEDED);

            JsonContractException failure = fails("1e1000001", "/numbers/3");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/numbers/3", failure.path());
        }

        @Test
        @DisplayName("zero cannot bypass the written exponent budget")
        void zeroIsNotSpecial() {
            assertFails("0e1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("-0e1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("0.0e1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("0e-1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("0E+1000001", JsonContractException.CODE_LIMIT_EXCEEDED);
            acceptedAs("0e1000000", "0");
            acceptedAs("0e-1000000", "0");
        }

        @Test
        @DisplayName("1015 leading zeros in front of 1000000 stay inside the budget and are accepted")
        void longLeadingZeroExponent() {
            String leadingZeros = "1e" + "0".repeat(1015) + "1000000";
            assertEquals(1024, leadingZeros.length());
            acceptedAs(leadingZeros, "1E+1000000");

            String oneTooLarge = "1e" + "0".repeat(1015) + "1000001";
            assertEquals(1024, oneTooLarge.length());
            JsonContractException failure = fails(oneTooLarge, "/deep/path");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/deep/path", failure.path());

            String negativeTail = "1e-" + "0".repeat(1014) + "1000001";
            assertEquals(1024, negativeTail.length());
            assertFails(negativeTail, JsonContractException.CODE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("an absurdly long exponent digit run is rejected without any int overflow")
        void veryLongExponent() {
            assertFails("1e" + "9".repeat(1000), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e" + "9".repeat(1021), JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e" + "9".repeat(1015) + "0", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e99999999999999999999999999999999", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e-99999999999999999999999999999999", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("1e" + "0".repeat(1010) + "99999999999", JsonContractException.CODE_LIMIT_EXCEEDED);
            /* At exactly the budget with a settled accumulator the value is still accepted. */
            acceptedAs("1e" + "0".repeat(1014) + "1000000", "1E+1000000");
        }
    }

    @Nested
    @DisplayName("the canonical text is held to the same budget")
    class CanonicalRebudget {

        @Test
        @DisplayName("a shift produced by normalizing is rejected in both directions")
        void shiftedExponentRejected() {
            assertFails("10e1000000", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("0.1e-1000000", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("100e1000000", JsonContractException.CODE_LIMIT_EXCEEDED);
            assertFails("0.01e-1000000", JsonContractException.CODE_LIMIT_EXCEEDED);

            JsonContractException failure = fails("10e1000000", "/a/0");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/a/0", failure.path());
        }

        @Test
        @DisplayName("the same shift staying inside the budget is accepted and re-readable")
        void shiftedExponentAccepted() {
            acceptedAs("0.1e1000000", "1E+999999");
            acceptedAs("10e-1000000", "1E-999999");
            acceptedAs("100e-1000000", "1E-999998");
            acceptedAs("0.01e1000000", "1E+999998");
        }

        @Test
        @DisplayName("a trailing zero run is removed from the canonical text but not from the value")
        void trailingZeros() {
            ExactJsonNumber.ParsedNumber parsed = acceptedAs("1." + "0".repeat(1022), "1");
            assertEquals(1024, parsed.value().toPlainString().length(), "the literal was 1024 characters");
            assertEquals(1022, parsed.value().scale(), "the original scale is kept by the value");
            assertEquals(1, parsed.canonical().length());

            ExactJsonNumber.ParsedNumber longTail = acceptedAs("1" + "0".repeat(1022), "1E+1022");
            assertEquals(1023, longTail.value().precision());
            assertEquals(1023, longTail.value().toBigInteger().toString().length());
            /* The canonical text of 10^1022 is the scientific spelling, whose length is that of the
               hand written expectation rather than a guessed number. */
            assertEquals("1E+1022".length(), longTail.canonical().length());
            assertEquals("1E+1022", longTail.canonical());
            assertNotNull(new BigDecimal("1E+1022").toBigIntegerExact());
        }

        @Test
        @DisplayName("a 1024 character literal whose canonical text would exceed the budget is rejected")
        void canonicalLengthRejected() {
            String tooLong = "1" + "2".repeat(1017) + "e99999";
            assertEquals(1024, tooLong.length(), "the literal itself is inside the literal budget");
            /* Independent oracle: the unconstrained canonical text of this value is over the budget. */
            int oracleLength = new BigDecimal(tooLong).stripTrailingZeros().toString().length();
            assertTrue(oracleLength > JsonLimits.MAX_NUMBER_LITERAL_CHARS,
                    "oracle canonical length " + oracleLength + " must exceed the budget");
            JsonContractException failure = fails(tooLong, "/bignum");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/bignum", failure.path());

            /* The longest sibling whose canonical text still fits is found from the oracle, not by
               assuming a printed form, and must be accepted and re-readable. */
            String fits = null;
            for (int tail = 1016; tail >= 1000; tail--) {
                String candidate = "1" + "2".repeat(tail) + "e99999";
                int length = new BigDecimal(candidate).stripTrailingZeros().toString().length();
                if (length <= JsonLimits.MAX_NUMBER_LITERAL_CHARS) {
                    fits = candidate;
                    assertEquals(JsonLimits.MAX_NUMBER_LITERAL_CHARS, length,
                            "the boundary candidate must sit exactly on the budget");
                    break;
                }
            }
            assertNotNull(fits, "a canonical text of exactly 1024 characters must exist in this family");
            assertTrue(fits.length() <= JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            ExactJsonNumber.ParsedNumber parsed = parse(fits);
            assertEquals(oracleCanonical(parsed.value()), parsed.canonical(), "oracle canonical of the boundary sibling");
            assertEquals(JsonLimits.MAX_NUMBER_LITERAL_CHARS, parsed.canonical().length());
            assertReRead(parsed, fits);
        }
    }

    @Nested
    @DisplayName("values stay exact")
    class Exactness {

        @Test
        @DisplayName("two 50 digit integers differing in the last digit stay 1 apart")
        void fiftyDigitIntegers() {
            String first = "12345678901234567890123456789012345678901234567890";
            String second = "12345678901234567890123456789012345678901234567891";
            assertEquals(50, first.length());
            assertEquals(50, second.length());
            BigInteger a = new BigInteger(first);
            BigInteger b = new BigInteger(second);
            assertEquals(BigInteger.ONE, b.subtract(a));

            /* Hand written scientific spelling: one digit, one point, the remaining 49 digits, then the
               exponent of the adjusted value. The oracle is asserted next to it. */
            String canonicalFirst = "1.234567890123456789012345678901234567890123456789E+49";
            String canonicalSecond = "12345678901234567890123456789012345678901234567891";
            assertEquals(canonicalFirst, oracleCanonical(new BigDecimal(first)),
                    "the hand written scientific spelling must be the canonical one");
            assertEquals(canonicalSecond, oracleCanonical(new BigDecimal(second)),
                    "a value whose last digit is not zero keeps its plain spelling");
            ExactJsonNumber.ParsedNumber parsedA = acceptedAs(first, canonicalFirst);
            ExactJsonNumber.ParsedNumber parsedB = acceptedAs(second, canonicalSecond);
            assertNotSame(parsedA.canonical(), parsedB.canonical());
            assertEquals(new BigInteger("1"), parsedB.value().toBigIntegerExact().subtract(parsedA.value().toBigIntegerExact()));
            assertEquals(b, parsedB.value().toBigIntegerExact());
            assertEquals(a, parsedA.value().toBigIntegerExact());

            String carry = "99999999999999999999999999999999999999999999999999";
            String rolled = "100000000000000000000000000000000000000000000000000";
            ExactJsonNumber.ParsedNumber parsedCarry = accepted(carry);
            ExactJsonNumber.ParsedNumber parsedRolled = accepted(rolled);
            assertEquals(new BigInteger(carry).add(BigInteger.ONE), parsedRolled.value().toBigIntegerExact());
            assertEquals(new BigInteger(rolled), parsedCarry.value().add(BigDecimal.ONE).toBigIntegerExact());
            assertEquals("1E+50", parsedRolled.canonical(), "trailing zeros collapse to the exact value");
        }

        @Test
        @DisplayName("decimal fractions keep their exact value and never touch double")
        void decimalsAreExact() {
            ExactJsonNumber.ParsedNumber tenth = acceptedAs("0.1", "0.1");
            ExactJsonNumber.ParsedNumber fifth = acceptedAs("0.2", "0.2");
            assertEquals(new BigDecimal("0.3"), tenth.value().add(fifth.value()), "0.1 + 0.2 is exactly 0.3 here");
            assertEquals(0, new BigDecimal("0.3").compareTo(tenth.value().add(fifth.value())));

            ExactJsonNumber.ParsedNumber longTail = acceptedAs("1.0000000000000000000000000001", "1.0000000000000000000000000001");
            assertEquals(1.0d, longTail.value().doubleValue(), "a double view would lose the tail");
            assertFalse(new BigDecimal(longTail.value().doubleValue()).equals(longTail.value()),
                    "the exact value is not the double value");

            ExactJsonNumber.ParsedNumber scaled = acceptedAs("1.50", "1.5");
            assertEquals(2, scaled.value().scale(), "the original scale survives in the value");
            acceptedAs("0.000000000000000000001", "1E-21");
            acceptedAs("123.456e-3", "0.123456");
            acceptedAs("-123.456E+3", "-123456");
            /* The classic double artifact exists only on the double path: 0.1 + 0.2 in exact decimal
               arithmetic is 0.3, and the layer must never reproduce the binary artifact. */
            assertTrue(new BigDecimal(0.1 + 0.2).compareTo(new BigDecimal("0.3")) > 0,
                    "double arithmetic is known to overshoot here");
            assertEquals(0, tenth.value().add(fifth.value()).compareTo(new BigDecimal("0.3")));
            assertEquals(0, tenth.value().add(fifth.value()).compareTo(new BigDecimal("0.30")));
            assertFalse(tenth.value().add(fifth.value()).equals(new BigDecimal(0.1 + 0.2)));
        }

        @Test
        @DisplayName("the value equals an independently constructed BigDecimal for every accepted spelling")
        void valueMatchesBigDecimal() {
            String[] raw = {"0", "-0", "1", "-1", "12345678901234567890.123456789e123", "9.99e-999999",
                    "1e1000000", "1e-1000000", "7.5", "-7.5e+2", "0.00000000000000000000000000000000000001"};
            for (String literal : raw) {
                ExactJsonNumber.ParsedNumber parsed = parse(literal);
                assertEquals(0, new BigDecimal(literal).compareTo(parsed.value()), "value of " + describe(literal));
                assertEquals(0, parsed.value().compareTo(new BigDecimal(parsed.canonical())),
                        "canonical value of " + describe(literal));
                assertReRead(parsed, literal);
            }
        }
    }

    @Nested
    @DisplayName("re-read closure holds for generated inputs")
    class ReReadClosure {

        private static final BigInteger TEN = BigInteger.TEN;

        @Test
        @DisplayName("2000 different signed big integers agree with a BigInteger oracle in 5 spellings each")
        void bigIntegerOracle() {
            Random random = new Random(20261014L);
            int acceptedCount = 0;
            int rejectedCount = 0;
            for (int i = 0; i < 2000; i++) {
                BigInteger integer = new BigInteger(1 + random.nextInt(4000), random);
                if (random.nextBoolean()) {
                    integer = integer.negate();
                }
                String digits = integer.toString();
                String[] spellings = {digits, digits + ".0", digits + "e0", digits + "E0", digits + "e+0"};
                for (String raw : spellings) {
                    if (raw.length() > JsonLimits.MAX_NUMBER_LITERAL_CHARS) {
                        JsonContractException failure = fails(raw, PATH);
                        assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code(),
                                "over long generated literal " + describe(raw));
                        assertEquals(PATH, failure.path());
                        rejectedCount++;
                        continue;
                    }
                    ExactJsonNumber.ParsedNumber parsed = accepted(raw);
                    assertEquals(0, parsed.value().compareTo(new BigDecimal(digits)),
                            "exact value of " + describe(raw));
                    assertEquals(oracleCanonical(new BigDecimal(digits)), parsed.canonical(),
                            "canonical of " + describe(raw));
                    if (integer.signum() != 0 && integer.mod(TEN).signum() != 0) {
                        assertEquals(digits, parsed.canonical(), "a non multiple of ten keeps its digits");
                    }
                    assertEquals(integer, parsed.value().toBigIntegerExact());
                    acceptedCount++;
                }
            }
            assertEquals(10000, acceptedCount + rejectedCount, "every generated spelling is classified");
            assertTrue(acceptedCount > 8000, "most generated spellings are inside the budget");
            assertTrue(rejectedCount > 0, "some generated spellings must exercise the budget rejection");
        }

        @Test
        @DisplayName("finite BigDecimal values written in scientific and plain form re-read exactly")
        void bigDecimalFamily() {
            Random random = new Random(20261015L);
            int checked = 0;
            for (int i = 0; i < 1500; i++) {
                int digits = 1 + random.nextInt(40);
                StringBuilder unscaled = new StringBuilder();
                for (int d = 0; d < digits; d++) {
                    unscaled.append((char) ('0' + random.nextInt(10)));
                }
                if (unscaled.length() > 1 && unscaled.charAt(0) == '0') {
                    unscaled.setCharAt(0, (char) ('1' + random.nextInt(9)));
                }
                int scale = random.nextInt(81) - 40;
                BigDecimal exact = new BigDecimal(new BigInteger(unscaled.toString()), scale);
                String plain = exact.toPlainString();
                String scientific = unscaled + "e" + (-scale);
                for (String raw : new String[] {plain, scientific}) {
                    ExactJsonNumber.ParsedNumber parsed = accepted(raw);
                    assertEquals(0, exact.compareTo(parsed.value()), "value of " + describe(raw));
                    assertEquals(oracleCanonical(exact), parsed.canonical(), "canonical of " + describe(raw));
                    checked++;
                }
            }
            assertEquals(3000, checked);
        }

        @Test
        @DisplayName("generated scientific spellings with large exponents either re-read or keep code and path")
        void largeExponentFamily() {
            Random random = new Random(20261016L);
            int acceptedCount = 0;
            int rejectedCount = 0;
            for (int i = 0; i < 4000; i++) {
                String digits = Integer.toString(1 + random.nextInt(9));
                /* Both directions are forced rather than left to chance: every eighth vector steps just
                   past the accepted written exponent budget, the rest stay inside it. */
                boolean overBudget = (i % 8) == 0;
                int magnitude = overBudget
                        ? JsonLimits.MAX_ABS_EXPONENT + 1 + random.nextInt(200)
                        : random.nextInt(JsonLimits.MAX_ABS_EXPONENT + 1);
                String exponent = (random.nextBoolean() ? "" : "-") + magnitude;
                String raw = digits + "e" + exponent;
                try {
                    ExactJsonNumber.ParsedNumber parsed = ExactJsonNumber.parse(raw, PATH);
                    assertEquals(oracleCanonical(parsed.value()), parsed.canonical(), "canonical of " + describe(raw));
                    assertReRead(parsed, raw);
                    acceptedCount++;
                } catch (JsonContractException failure) {
                    assertNotNull(failure.code());
                    assertEquals(PATH, failure.path(), "path preserved by " + describe(raw));
                    if (JsonContractException.CODE_LIMIT_EXCEEDED.equals(failure.code())) {
                        assertTrue(magnitude > JsonLimits.MAX_ABS_EXPONENT
                                        || parsedWouldExceed(raw),
                                "limit rejection must come from a real budget break: " + describe(raw));
                        rejectedCount++;
                    } else {
                        assertEquals(JsonContractException.CODE_INVALID_NUMBER, failure.code(),
                                "unexpected code for " + describe(raw));
                    }
                }
            }
            assertEquals(4000, acceptedCount + rejectedCount, "no failure may be swallowed as success");
            assertTrue(acceptedCount > 0);
            assertTrue(rejectedCount > 0);
        }

        private static boolean parsedWouldExceed(String raw) {
            BigDecimal value = new BigDecimal(raw);
            String canonical = oracleCanonical(value);
            return canonical.length() > JsonLimits.MAX_NUMBER_LITERAL_CHARS
                    || new BigDecimal(canonical).scale() < -JsonLimits.MAX_ABS_EXPONENT
                    || new BigDecimal(canonical).scale() > JsonLimits.MAX_ABS_EXPONENT;
        }

        @Test
        @DisplayName("a literal at the budget accepted by parse is always re-readable")
        void boundaryLiteralsAreReReadable() {
            String[] raw = {"1e1000000", "1e-1000000", "0.1e1000000", "10e-1000000",
                    "1e" + "0".repeat(1015) + "1000000",
                    "1." + "9".repeat(1022),
                    "1" + "2".repeat(1014) + "e99999"};
            for (String literal : raw) {
                assertTrue(literal.length() <= JsonLimits.MAX_NUMBER_LITERAL_CHARS,
                        "the vector must stay inside the literal budget: " + describe(literal));
                ExactJsonNumber.ParsedNumber parsed = parse(literal);
                assertTrue(parsed.canonical().length() <= JsonLimits.MAX_NUMBER_LITERAL_CHARS,
                        "canonical of " + describe(literal) + " must fit");
                assertReRead(parsed, literal);
            }
        }
    }

    @Nested
    @DisplayName("the fixed API surface")
    class ApiSurface {

        @Test
        @DisplayName("the type stays final, package private, non instantiable and without public members")
        void noNewPublicApi() {
            assertFalse(Modifier.isPublic(ExactJsonNumber.class.getModifiers()), "ExactJsonNumber must not be public");
            assertTrue(Modifier.isFinal(ExactJsonNumber.class.getModifiers()), "ExactJsonNumber must be final");
            Constructor<?>[] constructors = ExactJsonNumber.class.getDeclaredConstructors();
            assertEquals(1, constructors.length, "exactly one declared constructor");
            assertTrue(Modifier.isPrivate(constructors[0].getModifiers()), "the constructor must be private");
            assertEquals(0, constructors[0].getParameterCount());
            for (Method method : ExactJsonNumber.class.getDeclaredMethods()) {
                assertFalse(Modifier.isPublic(method.getModifiers()), "public method: " + method);
                assertFalse(Modifier.isProtected(method.getModifiers()), "protected method: " + method);
            }
            for (Field field : ExactJsonNumber.class.getDeclaredFields()) {
                assertFalse(Modifier.isPublic(field.getModifiers()), "public field: " + field);
            }
        }

        @Test
        @DisplayName("ParsedNumber exposes exactly the value and canonical components")
        void recordComponents() {
            RecordComponent[] components = ExactJsonNumber.ParsedNumber.class.getRecordComponents();
            assertEquals(2, components.length);
            assertEquals("value", components[0].getName());
            assertEquals(BigDecimal.class, components[0].getType());
            assertEquals("canonical", components[1].getName());
            assertEquals(String.class, components[1].getType());
            ExactJsonNumber.ParsedNumber parsed = parse("2.5");
            assertEquals(new BigDecimal("2.5"), parsed.value());
            assertEquals("2.5", parsed.canonical());
            assertNotNull(parsed.toString());
            assertEquals(parsed, parse("2.5e0"), "equal value and canonical text imply equal records");
        }

        @Test
        @DisplayName("a bad path is a plain IllegalArgumentException, before any literal is inspected")
        void badPath() {
            String[] badPaths = {null, "a", "n", " ", " a", "#", "1", "x/n", ".", "\t/"};
            for (String bad : badPaths) {
                assertThrows(IllegalArgumentException.class, () -> ExactJsonNumber.parse("1", bad),
                        "bad path " + describe(bad));
                assertThrows(IllegalArgumentException.class, () -> ExactJsonNumber.parse(null, bad),
                        "bad path " + describe(bad) + " with a null literal");
                assertThrows(IllegalArgumentException.class, () -> ExactJsonNumber.parse("x".repeat(2000), bad),
                        "bad path " + describe(bad) + " with an over long literal");
            }
            assertThrows(IllegalArgumentException.class, () -> ExactJsonNumber.parse(null, null));
        }

        @Test
        @DisplayName("the accepted paths are used verbatim by every failure code")
        void pathIsPreserved() {
            String[] paths = {"", "/", "/a", "/a/b", "/0", "/0/1", "/~1", "/ ", "/a b", "/a/b/c/d/e/f/g"};
            for (String path : paths) {
                JsonContractException invalid = fails("nope", path);
                assertEquals(JsonContractException.CODE_INVALID_NUMBER, invalid.code());
                assertEquals(path, invalid.path(), "INVALID_NUMBER path");

                JsonContractException limited = fails("1e1000001", path);
                assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, limited.code());
                assertEquals(path, limited.path(), "LIMIT_EXCEEDED path");

                JsonContractException tooLong = fails("9".repeat(1025), path);
                assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, tooLong.code());
                assertEquals(path, tooLong.path(), "literal budget path");

                JsonContractException canonical = fails("1" + "2".repeat(1017) + "e99999", path);
                assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, canonical.code());
                assertEquals(path, canonical.path(), "canonical budget path");
            }
        }

        @Test
        @DisplayName("a null literal never reports a limit and never invents a path")
        void nullLiteralOrdering() {
            JsonContractException failure = assertThrows(JsonContractException.class,
                    () -> ExactJsonNumber.parse(null, "/x"));
            assertEquals(JsonContractException.CODE_INVALID_NUMBER, failure.code());
            assertEquals("/x", failure.path());
            assertTrue(failure.getMessage().contains("null"), "message should state the cause: " + failure.getMessage());
        }
    }
}