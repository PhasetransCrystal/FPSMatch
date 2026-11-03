package net.ptcrys.fpsmatch.editor.common.codec;

import java.util.NoSuchElementException;

/**
 * Bounded single-pass range cursor over the members of one certified canonical JSON object
 * (R06.2b1).
 *
 * <p>
 * The cursor is a read-only view over the canonical {@link String} that an
 * {@link ExactJson.FrozenJson} already holds. It never re-reads the original document, never builds
 * a member list or map, never copies a value, never recurses and never re-validates the text: one
 * full consumption touches every canonical character a bounded number of times and keeps only the
 * current position plus the decoded key of the member that is being returned. Callers that retain
 * the returned {@link Member} values own that memory; the cursor itself does not.
 * </p>
 *
 * <p>
 * All ranges are half-open UTF-16 character ranges into {@link Cursor#canonical()}. The key range
 * covers the complete quoted key token, the value range covers the complete value token, and neither
 * includes the separating colon, the separating comma or the owner braces. They are
 * <em>not</em> UTF-8 byte offsets, so a range length equals the token length in {@code char} units.
 * </p>
 *
 * <p>
 * The type has no public API: it is a package-private implementation detail of the world editor
 * codec package and is expected to be consumed by a later member walk, not by product code.
 * </p>
 */
final class CanonicalObjectMembers {

    private CanonicalObjectMembers() {}

    /**
     * Opens a fresh cursor positioned before the first member of one certified object.
     *
     * @param members frozen value that has already been certified by {@link ExactJson}
     * @return an independent cursor over the canonical text of {@code members}
     * @throws IllegalArgumentException if {@code members} is {@code null} or its frozen value is not
     *                                  a JSON object
     */
    static Cursor cursor(ExactJson.FrozenJson members) {
        if (members == null) {
            throw new IllegalArgumentException("members must not be null");
        }
        final String canonical = members.canonical();
        if (canonical.isEmpty() || canonical.charAt(0) != '{') {
            throw new IllegalArgumentException("frozen value is not a JSON object");
        }
        return new Cursor(canonical);
    }

    /**
     * One member of the certified object, expressed as ranges into {@link Cursor#canonical()}.
     *
     * <p>
     * The record keeps the standard record semantics: component order is
     * {@code key, keyStart, keyEnd, valueStart, valueEnd}, accessors are named after the components,
     * and equality, hash code and {@code toString} are the generated ones. {@code key} is the fully
     * decoded key text, while {@code keyStart}/{@code keyEnd} still describe the quoted key token as
     * it appears in the canonical text, so escapes are visible in the range but not in the key.
     * </p>
     *
     * @param key        decoded key text, exactly as the canonical text decodes, never null
     * @param keyStart   inclusive UTF-16 index of the opening quote of the key token
     * @param keyEnd     exclusive UTF-16 index just after the closing quote of the key token
     * @param valueStart inclusive UTF-16 index of the first character of the value token
     * @param valueEnd   exclusive UTF-16 index just after the last character of the value token
     */
    record Member(String key, int keyStart, int keyEnd, int valueStart, int valueEnd) {}

    /**
     * One independent forward position over the canonical text of a certified object.
     *
     * <p>
     * Every instance owns its own position; nothing about a cursor is static, so any number of
     * cursors over the same freeze (or over different freezes) may be advanced in any interleaving
     * without influencing each other. {@link #hasNext()} is a pure peek and never advances,
     * {@link #next()} consumes exactly one member, and calling {@link #next()} past the last member
     * raises a real {@link NoSuchElementException}.
     * </p>
     */
    static final class Cursor {

        /** The immutable canonical text, saved by reference and never copied or rewritten. */
        private final String canonical;

        /** Index of the first character that has not been consumed yet; set after the opening brace. */
        private int position;

        private Cursor(String canonical) {
            this.canonical = canonical;
            this.position = 1;
        }

        /**
         * The exact canonical text this cursor walks.
         *
         * @return the same {@link String} instance that the freeze holds, never a copy
         */
        String canonical() {
            return this.canonical;
        }

        /**
         * Reports whether one more member can be consumed, without moving the cursor.
         *
         * @return {@code true} if the next significant character opens a key token
         */
        boolean hasNext() {
            final int next = skipInsignificant(this.position);
            return next < this.canonical.length() && this.canonical.charAt(next) == '"';
        }

        /**
         * Consumes exactly one member and returns its decoded key together with both token ranges.
         *
         * @return the next member, never null
         * @throws NoSuchElementException if no member is left
         * @throws IllegalStateException  if the certified text is internally impossible, which cannot
         *                                happen for a freeze produced by {@link ExactJson}
         */
        Member next() {
            final int keyStart = skipInsignificant(this.position);
            if (keyStart >= this.canonical.length() || this.canonical.charAt(keyStart) != '"') {
                throw new NoSuchElementException("no further member in the certified object");
            }
            final int keyEnd = quotedEnd(keyStart);
            final String key = decodeKey(keyStart, keyEnd);
            final int colon = skipInsignificant(keyEnd);
            if (colon >= this.canonical.length() || this.canonical.charAt(colon) != ':') {
                throw new IllegalStateException("certified member has no colon at " + colon);
            }
            final int valueStart = skipInsignificant(colon + 1);
            if (valueStart >= this.canonical.length()) {
                throw new IllegalStateException("certified member has no value at " + colon);
            }
            final int valueEnd = valueEnd(valueStart);
            this.position = afterValue(valueEnd);
            return new Member(key, keyStart, keyEnd, valueStart, valueEnd);
        }

        /**
         * Skips the insignificant characters of canonical text, which is a no-op on output that the
         * writer emits without any whitespace, and returns {@code from} unchanged for every certified
         * input.
         */
        private int skipInsignificant(int from) {
            int index = from;
            final int length = this.canonical.length();
            while (index < length && this.canonical.charAt(index) <= ' ') {
                index++;
            }
            return index;
        }

        /**
         * Returns the exclusive end of the quoted token that starts at {@code start}, honouring
         * backslash escapes so that an escaped quote never terminates the token.
         */
        private int quotedEnd(int start) {
            final int length = this.canonical.length();
            int index = start + 1;
            while (index < length) {
                final char c = this.canonical.charAt(index);
                if (c == '\\') {
                    index += 2;
                    continue;
                }
                if (c == '"') {
                    return index + 1;
                }
                index++;
            }
            throw new IllegalStateException("certified string is not closed at " + start);
        }

        /**
         * Decodes one quoted key token into its text, bounded by the token itself.
         *
         * <p>
         * The builder grows only with this single key, so the extra resident memory of a member is
         * proportional to the decoded key and never to the object or to any value. Only the eight
         * short escapes and {@code \\uXXXX} can occur in certified text; a surrogate pair is appended
         * as the two units the canonical text spells, so astral keys keep their exact code units.
         * </p>
         */
        private String decodeKey(int start, int end) {
            final StringBuilder key = new StringBuilder(32);
            int index = start + 1;
            final int last = end - 1;
            while (index < last) {
                final char c = this.canonical.charAt(index);
                if (c != '\\') {
                    key.append(c);
                    index++;
                    continue;
                }
                final char escape = this.canonical.charAt(index + 1);
                switch (escape) {
                    case '"':
                        key.append('"');
                        index += 2;
                        break;
                    case '\\':
                        key.append('\\');
                        index += 2;
                        break;
                    case '/':
                        key.append('/');
                        index += 2;
                        break;
                    case 'b':
                        key.append('\b');
                        index += 2;
                        break;
                    case 'f':
                        key.append('\f');
                        index += 2;
                        break;
                    case 'n':
                        key.append('\n');
                        index += 2;
                        break;
                    case 'r':
                        key.append('\r');
                        index += 2;
                        break;
                    case 't':
                        key.append('\t');
                        index += 2;
                        break;
                    case 'u':
                        key.append((char) ((hex(index + 2) << 12) | (hex(index + 3) << 8) | (hex(index + 4) << 4) | hex(index + 5)));
                        index += 6;
                        break;
                    default:
                        throw new IllegalStateException(
                                "certified key has an invalid escape at " + index);
                }
            }
            return key.toString();
        }

        /** Decodes one hexadecimal digit of a {@code \\uXXXX} escape in certified text. */
        private int hex(int index) {
            final char c = this.canonical.charAt(index);
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            throw new IllegalStateException("certified escape has a non-hex digit at " + index);
        }

        /**
         * Returns the exclusive end of the value token that starts at {@code start}, in a single
         * forward pass and without recursion.
         */
        private int valueEnd(int start) {
            final char c = this.canonical.charAt(start);
            if (c == '"') {
                return quotedEnd(start);
            }
            if (c == '{' || c == '[') {
                return containerEnd(start);
            }
            return scalarEnd(start);
        }

        /**
         * Skips a complete object or array value with a finite depth counter.
         *
         * <p>
         * Nested strings are skipped as tokens, so braces, brackets, colons and commas inside
         * quoted text are content and never change the depth. Iteration is bounded by the canonical
         * length; there is no recursion, no intermediate tree and no rescan from the start.
         * </p>
         */
        private int containerEnd(int start) {
            final int length = this.canonical.length();
            int depth = 0;
            int index = start;
            while (index < length) {
                final char c = this.canonical.charAt(index);
                if (c == '"') {
                    index = quotedEnd(index);
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                    index++;
                    continue;
                }
                if (c == '}' || c == ']') {
                    depth--;
                    index++;
                    if (depth == 0) {
                        return index;
                    }
                    continue;
                }
                index++;
            }
            throw new IllegalStateException("certified container is not closed at " + start);
        }

        /**
         * Returns the exclusive end of a scalar value token: a number, {@code true}, {@code false} or
         * {@code null} ends at the following top-level comma, at the closing brace of its owner or at
         * the end of the canonical text.
         */
        private int scalarEnd(int start) {
            final int length = this.canonical.length();
            int index = start;
            while (index < length) {
                final char c = this.canonical.charAt(index);
                if (c == ',' || c == '}' || c == ']' || c <= ' ') {
                    break;
                }
                index++;
            }
            return index;
        }

        /**
         * Positions the cursor after one member: past the separating comma when one follows, and at
         * the closing brace or at the end of the text otherwise. Whitespace is tolerated because it
         * costs nothing on canonical text.
         */
        private int afterValue(int valueEnd) {
            final int next = skipInsignificant(valueEnd);
            if (next < this.canonical.length() && this.canonical.charAt(next) == ',') {
                return skipInsignificant(next + 1);
            }
            return next;
        }
    }
}
