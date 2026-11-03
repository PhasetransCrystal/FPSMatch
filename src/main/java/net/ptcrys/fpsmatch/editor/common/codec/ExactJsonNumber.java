package net.ptcrys.fpsmatch.editor.common.codec;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Exact decimal normalization of one strict JSON number literal (R02.2).
 *
 * <p>
 * The type is deliberately self contained: a literal is never converted to {@code double} or
 * {@code float}, never rounded, and never normalized into a spelling that this same method could not
 * read back. The fixed order of the contract is:
 * </p>
 *
 * <ol>
 * <li>the character budget of {@link JsonLimits#MAX_NUMBER_LITERAL_CHARS} is checked first,</li>
 * <li>the complete JSON number grammar is matched with ASCII digits only (no trimming, no default
 * radix, no hexadecimal, no {@code NaN}/{@code Infinity}),</li>
 * <li>a written exponent is accounted digit by digit with a manual budget, so an absurdly long
 * exponent is rejected instead of overflowing an {@code int},</li>
 * <li>the exact {@link BigDecimal} value is created from the literal text,</li>
 * <li>the canonical spelling is produced, and</li>
 * <li>the canonical spelling is put through the same character and exponent budget, including an
 * exponent that only appeared while normalizing.</li>
 * </ol>
 *
 * <p>
 * {@link ParsedNumber#value()} keeps the precision and the scale of the original literal;
 * {@link ParsedNumber#canonical()} is the single normalized text. Both are created only here, from
 * the literal text: no caller supplied {@link Number} is retained.
 * </p>
 *
 * <p>
 * Reported failures use code {@code LIMIT_EXCEEDED} when the literal or its canonical text breaks
 * the character or exponent budget, and code {@code INVALID_NUMBER} when the text is not a JSON
 * number literal or cannot be represented exactly. A malformed {@code path} is a plain
 * {@link IllegalArgumentException} and is rejected before any {@link JsonContractException} could be
 * built from it.
 * </p>
 */
final class ExactJsonNumber {

    /**
     * Complete JSON number grammar: ASCII digits only, no leading zeros, optional fraction, optional
     * exponent. {@code [0-9]} is used instead of {@code \d} on purpose: {@code \d} would also accept
     * non-ASCII digits.
     */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private ExactJsonNumber() {}

    /**
     * Parses and normalizes one number literal.
     *
     * @param raw  the literal text exactly as it appeared in the document; {@code null} is rejected
     * @param path JSON-pointer-like path reported by failures; must be empty or start with {@code '/'}
     * @return the exact value together with its single canonical spelling
     * @throws IllegalArgumentException if {@code path} is null or not a root/absolute JSON pointer
     * @throws JsonContractException    with {@code INVALID_NUMBER} or {@code LIMIT_EXCEEDED} as described
     *                                  on this type
     */
    static ParsedNumber parse(String raw, String path) {
        requirePath(path);
        if (raw == null) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_NUMBER, path,
                    "number literal must not be null");
        }
        verifyLiteral(raw, path, "number literal");
        final BigDecimal value = toExactValue(raw, path);
        final String canonical = canonicalize(value, path);
        verifyLiteral(canonical, path, "canonical number");
        return new ParsedNumber(value, canonical);
    }

    /**
     * The only normalization entry point: numeric zero always becomes {@code "0"}, every other value
     * becomes the shortest scientific/plain spelling of its exact stripped value.
     *
     * @param value exact value of the literal, never null
     * @param path  path reported by failures
     * @return the canonical spelling, never null and never empty
     */
    private static String canonicalize(BigDecimal value, String path) {
        if (value.signum() == 0) {
            return "0";
        }
        try {
            // toString(), never toPlainString(): the canonical text of a value whose exponent is not
            // expressible in the literal length budget must not grow into a thousand digit run.
            return value.stripTrailingZeros().toString();
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_NUMBER, path,
                    "number literal cannot be normalized exactly", failure);
        }
    }

    /**
     * Creates the exact value of the literal without any intermediate floating point step.
     *
     * @param raw  syntactically legal literal text
     * @param path path reported by failures
     * @return the exact value, never null
     */
    private static BigDecimal toExactValue(String raw, String path) {
        try {
            return new BigDecimal(raw);
        } catch (ArithmeticException | NumberFormatException failure) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_NUMBER, path,
                    "number literal cannot be represented exactly", failure);
        }
    }

    /**
     * Applies the character budget, the grammar and the exponent budget to one text.
     *
     * <p>
     * The same method serves the original literal and the canonical text, so a canonical spelling
     * is held to exactly the rules that produced it. The defensive grammar branch below is reached
     * only if a canonical spelling somehow stopped being a JSON number literal; the code, path and
     * cause style then stays consistent instead of returning a value that cannot be read back.
     * </p>
     *
     * @param text literal or canonical text
     * @param path path reported by failures
     * @param role short description of {@code text} used in messages
     */
    private static void verifyLiteral(String text, String path, String role) {
        if (text.length() > JsonLimits.MAX_NUMBER_LITERAL_CHARS) {
            throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                    role + " of " + text.length() + " characters exceeds " + JsonLimits.MAX_NUMBER_LITERAL_CHARS);
        }
        if (!NUMBER.matcher(text).matches()) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_NUMBER, path,
                    role + " is not a JSON number literal");
        }
        verifyExponentBudget(text, path, role);
    }

    /**
     * Accounts the absolute value of a written exponent digit by digit.
     *
     * <p>
     * Before every accumulation step the contract {@code accumulator > (MAX_ABS_EXPONENT - digit)
     * / 10} is evaluated, so the accumulator can never leave the accepted range and a very long digit
     * run is rejected rather than parsed into an {@code int}. Leading zeros are not skipped or
     * special-cased: they are accumulated like any other digit, which leaves the value unchanged while
     * still charging them to the character budget.
     * </p>
     *
     * @param text text whose grammar has already been verified
     * @param path path reported by failures
     * @param role short description of {@code text} used in messages
     */
    private static void verifyExponentBudget(String text, String path, String role) {
        final int marker = indexOfExponent(text);
        if (marker < 0) {
            return;
        }
        int i = marker + 1;
        if (i < text.length() && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
            i++;
        }
        long accumulator = 0L;
        for (; i < text.length(); i++) {
            final int digit = text.charAt(i) - '0';
            if (accumulator > (JsonLimits.MAX_ABS_EXPONENT - digit) / 10L) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        role + " exponent exceeds " + JsonLimits.MAX_ABS_EXPONENT);
            }
            accumulator = accumulator * 10L + digit;
        }
    }

    /**
     * @param text grammatically verified literal text
     * @return index of the exponent marker, or {@code -1} when the literal has no exponent
     */
    private static int indexOfExponent(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == 'e' || c == 'E') {
                return i;
            }
        }
        return -1;
    }

    /**
     * Rejects a malformed path before any contract failure is built.
     *
     * @param path candidate path
     * @throws IllegalArgumentException if {@code path} is null or not a root/absolute JSON pointer
     */
    private static void requirePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!path.isEmpty() && path.charAt(0) != '/') {
            throw new IllegalArgumentException("path must be empty or start with '/': " + path);
        }
    }

    /**
     * Exact value of one literal and the single text that canonicalizes it.
     *
     * @param value     exact value of the original literal, with the original precision and scale; the
     *                  scale is not normalized, so {@code "1.500"} keeps scale 3 while its canonical text is
     *                  {@code "1.5"}
     * @param canonical the only accepted canonical spelling of {@code value}; for numeric zero it is
     *                  always {@code "0"}
     */
    record ParsedNumber(BigDecimal value, String canonical) {}
}
