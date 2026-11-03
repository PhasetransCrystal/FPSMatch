package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for the R02.1 strict JSON lexical layer.
 *
 * <p><strong>Scope.</strong> {@link StrictJsonLexical#validate(String)} decides only whether the
 * characters of a document are lexically legal JSON. It is not a JSON validator: required structural
 * rules (single root, trailing comma, punctuation ordering, bracket balancing, duplicate keys) are
 * checked by the later reader stage. Several tests below therefore assert that structurally
 * <em>invalid</em> input passes this layer, and that is intentional. A pass here must not be read as
 * full strict-JSON acceptance, and this batch does not claim P02.</p>
 *
 * <p>Decoded surrogate legality is also delegated: the escape {@code \\uD800} is lexically well
 * formed and is accepted here, while rejecting it (if the contract so requires) belongs to the
 * reader/freeze stage.</p>
 */
class StrictJsonLexicalContractTest {

    private static JsonContractException validateFails(String text) {
        return assertThrows(JsonContractException.class, () -> StrictJsonLexical.validate(text));
    }

    private static void assertCode(JsonContractException failure, String expectedCode) {
        assertEquals(expectedCode, failure.code(), "failure code");
        assertEquals("", failure.path(), "scanner failures report the document root");
    }

    @Nested
    @DisplayName("legal lexical input is accepted")
    class LegalInput {

        @Test
        @DisplayName("the four legal whitespace characters are accepted")
        void fourLegalWhitespaceCharacters() {
            assertDoesNotThrow(() -> StrictJsonLexical.validate(" \t\r\n"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\t[ 1 ,\r\n2 ]\n"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("{\r\n\t\"a\" : [ true , false , null ]\n}"));
        }

        @Test
        @DisplayName("lowercase literals are accepted")
        void lowercaseLiterals() {
            assertDoesNotThrow(() -> StrictJsonLexical.validate("true"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("false"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("null"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("[true,false,null]"));
        }

        @Test
        @DisplayName("number tokens are accepted")
        void numberTokens() {
            String[] tokens = {
                "0", "-0", "1", "-1", "1234567890", "0.5", "-0.5", "0.0", "1e10", "1E10", "1e+10",
                "1e-10", "1E+10", "-1.5e-7", "0e0", "0.0e0", "12345678901234567890.123456789e123"
            };
            for (String token : tokens) {
                assertDoesNotThrow(() -> StrictJsonLexical.validate(token), "legal number token: " + token);
            }
            assertDoesNotThrow(() -> StrictJsonLexical.validate("{\"a\":[0,-1.5e+3,2]}"));
        }

        @Test
        @DisplayName("escaped n, t, u0000 and a directly written surrogate pair are accepted")
        void escapesAndPairedUnicode() {
            // backslash+n is an escape; backslash+u0000 decodes to U+0000 and is lexically legal.
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"a\\nb\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"a\\tb\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\u0000\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\u001f\\u0020\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
            // U+1F600 written directly as a well-formed surrogate pair in the original text.
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"" + new String(Character.toChars(0x1F600)) + "\""));
        }

        @Test
        @DisplayName("empty and whitespace-only text is accepted by the scanner (the reader must reject it)")
        void emptyAndWhitespaceOnlyAcceptedByLexer() {
            assertDoesNotThrow(() -> StrictJsonLexical.validate(""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("   \t\r\n  "));
        }

        @Test
        @DisplayName("structurally invalid input such as [true,] and two roots is lexically allowed")
        void structureIsNotVerifiedHere() {
            // Structure is deliberately out of scope: the reader rejects these, the lexer may not.
            assertDoesNotThrow(() -> StrictJsonLexical.validate("[true,]"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("{\"a\":1,}"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("true false"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("{\"a\":1}{\"a\":2}"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"a\"\"b\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("{\"a\":1,\"a\":2}"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("}"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate(",,"));
        }

        @Test
        @DisplayName("a decoded \\uD800 escape is accepted by the lexer and must be handled by the reader")
        void decodedSurrogateAllowedHere() {
            // Deferred on purpose: this batch does not decode escapes, so it cannot judge pairing.
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\uD800\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\uDC00\""));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("\"\\uD800\\uDC00\""));
        }

        @Test
        @DisplayName("exponent range and number normalization are deferred to R02.2")
        void exponentRangeIsNotDecidedHere() {
            // The lexer only matches the number grammar; it never interprets the value, so an
            // exponent far outside MAX_ABS_EXPONENT is still lexically legal and the range verdict
            // belongs to the number stage (R02.2).
            assertDoesNotThrow(() -> StrictJsonLexical.validate("1e" + (JsonLimits.MAX_ABS_EXPONENT + 1)));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("1e999999999999999999999"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("1e-999999999999999999999"));
            // Value-equivalent spellings are likewise not merged here.
            assertDoesNotThrow(() -> StrictJsonLexical.validate("1.0"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("1e1"));
            assertDoesNotThrow(() -> StrictJsonLexical.validate("10"));
        }

        @Test
        @DisplayName("text at exactly the 8 MiB UTF-8 budget is accepted")
        void exactlyEightMebibytes() {
            assertDoesNotThrow(() -> StrictJsonLexical.validate(" ".repeat(JsonLimits.MAX_UTF8_BYTES)));
        }

        @Test
        @DisplayName("a valid number token of exactly 1024 characters is accepted")
        void maximumNumberTokenLength() {
            String token = "1" + "0".repeat(JsonLimits.MAX_NUMBER_LITERAL_CHARS - 1);
            assertEquals(JsonLimits.MAX_NUMBER_LITERAL_CHARS, token.length());
            assertDoesNotThrow(() -> StrictJsonLexical.validate(token));
        }
    }

    @Nested
    @DisplayName("illegal lexical input is rejected")
    class IllegalInput {

        @Test
        @DisplayName("raw LF, TAB and NUL inside a string are rejected")
        void rawControlCharactersInsideString() {
            assertCode(validateFails("\"a\nb\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\tb\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\u0000b\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\rb\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\u001fb\""), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("an escaped single quote is rejected")
        void escapedSingleQuote() {
            assertCode(validateFails("\"a\\'b\""), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("a backslash followed by a real LF is rejected")
        void backslashFollowedByRealLineFeed() {
            // Distinct from the legal backslash+n escape tested above.
            assertCode(validateFails("\"a\\\nb\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\\\tb\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"a\\\""), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("uppercase or mixed-case literals are rejected")
        void uppercaseLiterals() {
            assertCode(validateFails("TRUE"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("False"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("NULL"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("nul"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("tru"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("formfeed outside a string and NBSP whitespace are rejected")
        void formfeedAndNonBreakingSpace() {
            assertCode(validateFails("true\f"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\f"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("true\u00a0"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\u00a0true"), JsonContractException.CODE_INVALID_JSON);
            // Unicode line separators are not JSON whitespace either.
            assertCode(validateFails("true\u2028"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("single quotes and comments are rejected")
        void singleQuotesAndComments() {
            assertCode(validateFails("'a'"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("{'a':1}"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("// comment"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("/* comment */"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("[1,2] // trailing"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("leading zeros are rejected")
        void leadingZeros() {
            assertCode(validateFails("01"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("-01"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("00"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("[012]"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("exponents missing digits or fraction digits are rejected")
        void malformedExponentsAndFractions() {
            assertCode(validateFails("1e"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("1e+"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("1e-"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("1."), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("1.e5"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails(".5"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("+1"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("NaN and Infinity are rejected")
        void nanAndInfinity() {
            assertCode(validateFails("NaN"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("Infinity"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("-Infinity"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("[NaN]"), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("broken string forms are rejected")
        void brokenStrings() {
            assertCode(validateFails("\"unterminated"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("{\"a\":\"unterminated}"), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"\\u12\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"\\u12g4\""), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\"\\x\""), JsonContractException.CODE_INVALID_JSON);
        }

        @Test
        @DisplayName("null text is rejected")
        void nullText() {
            assertCode(validateFails(null), JsonContractException.CODE_INVALID_JSON);
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {

        @Test
        @DisplayName("a whitespace-only document of 8 MiB + 1 bytes exceeds the UTF-8 budget")
        void oneByteOverBudget() {
            assertCode(validateFails(" ".repeat(JsonLimits.MAX_UTF8_BYTES + 1)),
                    JsonContractException.CODE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("strict UTF-8 accounting covers 2-byte, 3-byte and 4-byte characters")
        void multibyteBudgetAccounting() {
            // 2-byte character (U+00A0) inside a string: 1 + 2 * count + 1 bytes.
            assertDoesNotThrow(() -> StrictJsonLexical.validate(
                    "\"" + "\u00a0".repeat((JsonLimits.MAX_UTF8_BYTES - 2) / 2) + "\""));
            assertCode(validateFails("\"" + "\u00a0".repeat((JsonLimits.MAX_UTF8_BYTES - 2) / 2 + 1) + "\""),
                    JsonContractException.CODE_LIMIT_EXCEEDED);
            // 3-byte character (U+4E00) inside a string: 1 + 3 * count + 1 bytes.
            int threeByteAtLimit = (JsonLimits.MAX_UTF8_BYTES - 2) / 3;
            String threeByteLegal = "\"" + "\u4e00".repeat(threeByteAtLimit) + "\"";
            assertEquals(JsonLimits.MAX_UTF8_BYTES, 1 + threeByteAtLimit * 3 + 1);
            assertDoesNotThrow(() -> StrictJsonLexical.validate(threeByteLegal));
            assertCode(validateFails("\"" + "\u4e00".repeat(threeByteAtLimit + 1) + "\""),
                    JsonContractException.CODE_LIMIT_EXCEEDED);
            // 2-byte characters are accounted as 2 UTF-8 bytes each, not as 1 UTF-16 unit:
            // MAX_UTF8_BYTES/2 of them are still within budget, one more is not.
            String twoByteAtLimit = "\u00a0".repeat(JsonLimits.MAX_UTF8_BYTES / 2);
            assertEquals(JsonLimits.MAX_UTF8_BYTES, twoByteAtLimit.length() * 2);
            assertTrue(twoByteAtLimit.length() < JsonLimits.MAX_UTF8_BYTES);
            // Within budget, so the budget check passes; U+00A0 is not JSON whitespace, so the
            // scanner must reject it as a lexical failure.
            assertCode(validateFails(twoByteAtLimit), JsonContractException.CODE_INVALID_JSON);
            assertCode(validateFails("\u00a0".repeat(JsonLimits.MAX_UTF8_BYTES / 2 + 1)),
                    JsonContractException.CODE_LIMIT_EXCEEDED);
            // 4-byte surrogate pair inside a string: 1 + 4 * count + 1 bytes.
            String pair = new String(Character.toChars(0x1F600));
            assertCode(validateFails("\"" + pair.repeat((JsonLimits.MAX_UTF8_BYTES - 2) / 4 + 1) + "\""),
                    JsonContractException.CODE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("a directly written lone surrogate is an INVALID_UNICODE failure")
        void loneSurrogates() {
            String high = String.valueOf((char) 0xD800);
            String low = String.valueOf((char) 0xDC00);
            assertCode(validateFails(high), JsonContractException.CODE_INVALID_UNICODE);
            assertCode(validateFails(low), JsonContractException.CODE_INVALID_UNICODE);
            assertCode(validateFails("\"a" + high + "\""), JsonContractException.CODE_INVALID_UNICODE);
            assertCode(validateFails("\"" + high + "\"\"x\""), JsonContractException.CODE_INVALID_UNICODE);
            // Reversed pair is two unpaired surrogates, not a valid pair.
            assertCode(validateFails(low + high), JsonContractException.CODE_INVALID_UNICODE);
            // A pair split by another character leaves the first surrogate unpaired.
            assertCode(validateFails(high + "a" + low), JsonContractException.CODE_INVALID_UNICODE);
        }

        @Test
        @DisplayName("a valid number token of 1025 characters exceeds the literal length limit")
        void numberTokenOverLimit() {
            String token = "1" + "0".repeat(JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            assertEquals(JsonLimits.MAX_NUMBER_LITERAL_CHARS + 1, token.length());
            assertCode(validateFails(token), JsonContractException.CODE_LIMIT_EXCEEDED);
            // Literal validity is decided before the length limit: an over-long invalid token is
            // still a lexical failure rather than a limit failure.
            String invalidToken = "0" + "0".repeat(JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            assertCode(validateFails(invalidToken), JsonContractException.CODE_INVALID_JSON);
        }
    }

    @Nested
    @DisplayName("contract types")
    class ContractTypes {

        @Test
        @DisplayName("JsonLimits exposes the frozen values")
        void limitsValues() {
            assertEquals(8388608, JsonLimits.MAX_UTF8_BYTES);
            assertEquals(64, JsonLimits.MAX_DEPTH);
            assertEquals(500000, JsonLimits.MAX_NODES);
            assertEquals(65536, JsonLimits.MAX_STRING_UTF8_BYTES);
            assertEquals(1024, JsonLimits.MAX_KEY_UTF8_BYTES);
            assertEquals(1024, JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            assertEquals(1000000, JsonLimits.MAX_ABS_EXPONENT);
        }

        @Test
        @DisplayName("JsonContractException carries code, path, message and cause")
        void exceptionCarriesContractData() {
            JsonContractException failure = new JsonContractException("INVALID_JSON", "", "boom");
            assertEquals("INVALID_JSON", failure.code());
            assertEquals("", failure.path());
            assertEquals("boom", failure.getMessage());
            assertNull(failure.getCause());
            assertInstanceOf(IllegalArgumentException.class, failure);

            JsonContractException nested = new JsonContractException("LIMIT_EXCEEDED", "/a/0", "too big",
                    new IllegalStateException("root cause"));
            assertEquals("LIMIT_EXCEEDED", nested.code());
            assertEquals("/a/0", nested.path());
            assertEquals("too big", nested.getMessage());
            assertSame(IllegalStateException.class, nested.getCause().getClass());
            assertEquals("root cause", nested.getCause().getMessage());
        }

        @Test
        @DisplayName("all five documented codes are accepted and unknown codes are rejected")
        void acceptedCodes() {
            String[] codes = {"INVALID_JSON", "INVALID_NUMBER", "INVALID_UNICODE", "DUPLICATE_KEY", "LIMIT_EXCEEDED"};
            for (String code : codes) {
                assertEquals(code, new JsonContractException(code, "", "m").code());
            }
            assertThrows(IllegalArgumentException.class, () -> new JsonContractException("OTHER", "", "m"));
            assertThrows(IllegalArgumentException.class, () -> new JsonContractException(null, "", "m"));
        }

        @Test
        @DisplayName("an illegal path is rejected at construction time")
        void illegalPaths() {
            assertThrows(IllegalArgumentException.class, () -> new JsonContractException("INVALID_JSON", null, "m"));
            assertThrows(IllegalArgumentException.class, () -> new JsonContractException("INVALID_JSON", "a/b", "m"));
            assertThrows(IllegalArgumentException.class, () -> new JsonContractException("INVALID_JSON", " ", "m"));
            assertEquals("/", new JsonContractException("INVALID_JSON", "/", "m").path());
        }
    }
}