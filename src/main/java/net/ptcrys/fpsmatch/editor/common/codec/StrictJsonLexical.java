package net.ptcrys.fpsmatch.editor.common.codec;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict JSON lexical pre-scan.
 *
 * <p>
 * This type only decides whether the characters of a document are <em>lexically</em> legal JSON.
 * A successful {@link #validate(String)} call does <strong>not</strong> mean the document is
 * structurally valid JSON. Structural concerns are deliberately left to the later reader stage:
 * punctuation ordering, the single-root rule, trailing or missing commas, bracket balancing,
 * duplicate keys, and the legality of surrogate pairs produced by {@code \\uXXXX} escapes.
 * </p>
 *
 * <p>
 * Behaviour is a pure function of the input text: no parsing tree is built, no
 * {@code JsonParser} is used, and no structural repair is attempted.
 * </p>
 *
 * <p>
 * Cost is O(n) and the input is traversed twice: a linear UTF-16 pass that verifies directly
 * present surrogate pairing and accounts the strict UTF-8 size, then a scanner pass. The UTF-8
 * budget is evaluated before any byte allocation, so oversized input is rejected without first
 * materialising a {@code byte[]}.
 * </p>
 */
final class StrictJsonLexical {

    /** JSON number grammar: optional sign, no leading zeros, optional fraction, optional exponent. */
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    private StrictJsonLexical() {}

    /**
     * Validates the lexical form of the given text.
     *
     * <p>
     * Empty and whitespace-only text is accepted here; rejecting such input is the reader's
     * responsibility.
     * </p>
     *
     * @param text document text, null is treated as an invalid document
     * @throws JsonContractException with {@code INVALID_JSON} on a lexical violation,
     *                               {@code INVALID_UNICODE} on an unpaired surrogate in the original text, and
     *                               {@code LIMIT_EXCEEDED} when the UTF-8 byte budget or the number literal length
     *                               limit is
     *                               exceeded
     */
    static void validate(String text) {
        if (text == null) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "", "text must not be null");
        }
        final int length = text.length();
        verifySurrogatesAndBudget(text, length);
        scan(text, length);
    }

    private static void verifySurrogatesAndBudget(String text, int length) {
        long utf8Bytes = 0L;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                utf8Bytes += 1L;
            } else if (c < 0x800) {
                utf8Bytes += 2L;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(text.charAt(i + 1))) {
                    utf8Bytes += 4L;
                    i++;
                } else {
                    throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, "",
                            "unpaired high surrogate at index " + i);
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, "",
                        "unpaired low surrogate at index " + i);
            } else {
                utf8Bytes += 3L;
            }
            if (utf8Bytes > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "document exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
        }
    }

    private static void scan(String text, int length) {
        final Matcher numberMatcher = NUMBER.matcher(text);
        int i = 0;
        while (i < length) {
            final char c = text.charAt(i);
            if (isWhitespace(c)) {
                i++;
                continue;
            }
            if (isPunctuation(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                i = scanString(text, length, i);
                continue;
            }
            i = scanToken(text, numberMatcher, length, i);
        }
    }

    private static int scanString(String text, int length, int start) {
        int i = start + 1;
        while (true) {
            if (i >= length) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                        "unterminated string starting at index " + start);
            }
            final char c = text.charAt(i);
            if (c == '"') {
                return i + 1;
            }
            if (c == '\\') {
                i++;
                if (i >= length) {
                    throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                            "dangling backslash at end of text");
                }
                final char escape = text.charAt(i);
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                    case 'b':
                    case 'f':
                    case 'n':
                    case 'r':
                    case 't':
                        i++;
                        break;
                    case 'u':
                        i++;
                        i = requireFourHexDigits(text, length, i);
                        break;
                    default:
                        throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                                "invalid escape sequence at index " + (i - 1));
                }
                continue;
            }
            if (c <= 0x1F) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                        "unescaped control character at index " + i);
            }
            // Any other character, including a directly present surrogate pair verified above, is
            // legal raw string content and is skipped as-is.
            i++;
        }
    }

    private static int requireFourHexDigits(String text, int length, int start) {
        if (start + 4 > length) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                    "incomplete \\u escape at index " + (start - 2));
        }
        for (int k = 0; k < 4; k++) {
            final char h = text.charAt(start + k);
            final boolean hex = (h >= '0' && h <= '9') || (h >= 'a' && h <= 'f') || (h >= 'A' && h <= 'F');
            if (!hex) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                        "invalid hex digit in \\u escape at index " + (start + k));
            }
        }
        return start + 4;
    }

    private static int scanToken(String text, Matcher numberMatcher, int length, int start) {
        int i = start;
        while (i < length) {
            final char c = text.charAt(i);
            if (isWhitespace(c) || isPunctuation(c) || c == '"') {
                break;
            }
            i++;
        }
        final int end = i;
        if (matchesLiteral(text, start, end)) {
            return end;
        }
        numberMatcher.region(start, end);
        if (numberMatcher.matches()) {
            if (end - start > JsonLimits.MAX_NUMBER_LITERAL_CHARS) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "number literal of " + (end - start) + " characters exceeds " + JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            }
            return end;
        }
        throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, "",
                "invalid token at index " + start);
    }

    private static boolean matchesLiteral(String text, int start, int end) {
        switch (end - start) {
            case 4:
                return text.charAt(start) == 't' && text.charAt(start + 1) == 'r' && text.charAt(start + 2) == 'u' && text.charAt(start + 3) == 'e' || text.charAt(start) == 'n' && text.charAt(start + 1) == 'u' && text.charAt(start + 2) == 'l' && text.charAt(start + 3) == 'l';
            case 5:
                return text.charAt(start) == 'f' && text.charAt(start + 1) == 'a' && text.charAt(start + 2) == 'l' && text.charAt(start + 3) == 's' && text.charAt(start + 4) == 'e';
            default:
                return false;
        }
    }

    private static boolean isWhitespace(char c) {
        return c == 0x20 || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isPunctuation(char c) {
        switch (c) {
            case '{':
            case '}':
            case '[':
            case ']':
            case ':':
            case ',':
                return true;
            default:
                return false;
        }
    }
}
