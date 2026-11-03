package net.ptcrys.fpsmatch.editor.common.codec;

/**
 * Bounded raw envelope text: an uninterpreted payload string together with the exact number of UTF-8
 * bytes it occupies (R06.2a1, design revision R1).
 *
 * <p>
 * This type proves exactly two things about the text it carries: that its UTF-16 form is Unicode
 * well formed (every surrogate is paired) and that its UTF-8 encoding fits the container budget
 * {@link #MAX_UTF8_BYTES}. It proves nothing else. The text may be empty, may be whitespace, may be
 * a control character, may be a duplicate key object, a bad number or any other text that is not
 * legal JSON: all of those construct successfully here, because this type is the <em>raw</em> text of
 * an envelope, not a JSON document. Nothing in this class parses, canonicalizes or validates JSON, so
 * a successfully constructed value must never be treated as JSON certified.
 * </p>
 *
 * <p>
 * The budget is the container source budget and is deliberately independent of the codec string
 * budget {@link ExactJson#MAX_STRING_UTF8_BYTES}: the frozen codec keeps its own 8 MiB limit, and this
 * class neither reads nor changes it.
 * </p>
 *
 * <p>
 * Instances are immutable and hold the caller's {@code String} reference without copying it. The
 * byte count is computed once, in a single left to right pass that never encodes the text to a
 * {@code byte[]}, never normalizes it and never extracts a substring, so the memory this class needs
 * is constant regardless of how large the accepted text is.
 * </p>
 */
public final class RawEnvelopeText {

    /** Maximum UTF-8 byte size of one raw envelope text: 64 MiB. */
    public static final int MAX_UTF8_BYTES = 64 * 1024 * 1024;

    private final String text;

    private final int utf8Size;

    /**
     * Stores a text that has already been validated.
     *
     * <p>
     * This constructor is the internal capture point of the single validating pass in
     * {@link #of(String)}: it must only be reached with a non null text whose measured UTF-8 width
     * already fits {@link #MAX_UTF8_BYTES}. It is private so that no caller can hand over an
     * unmeasured text and no second validation policy can exist.
     * </p>
     *
     * @param text     already validated raw text, never null
     * @param utf8Size measured UTF-8 byte count of {@code text}
     */
    private RawEnvelopeText(String text, int utf8Size) {
        this.text = text;
        this.utf8Size = utf8Size;
    }

    /**
     * Validates the Unicode shape and the UTF-8 budget of the given text and captures it.
     *
     * <p>
     * The UTF-16 length is rejected against the budget before anything else happens, because one
     * UTF-16 unit is at least one UTF-8 byte: a text longer than {@link #MAX_UTF8_BYTES} characters
     * cannot fit, and this early rejection means an oversized input is refused without a scan, a
     * {@code getBytes} call, a normalization, a clone or a substring.
     * </p>
     *
     * <p>
     * Otherwise the text is scanned exactly once, from index zero to the end, charging the real
     * UTF-8 width of each unit: one byte for ASCII (including every control and whitespace
     * character), two bytes for {@code U+0080..U+07FF}, three bytes for every other non surrogate BMP
     * character, and four bytes for a high surrogate that is immediately followed by a low surrogate,
     * which is consumed together. A high surrogate at the end of the text, a high surrogate followed
     * by anything other than a low surrogate, and a low surrogate on its own are all rejected as
     * unpaired. The running total is compared after every addition, so a text that passes the budget
     * is refused the moment it exceeds it instead of scanning an arbitrarily long suffix, and no
     * {@code byte[]} is ever allocated.
     * </p>
     *
     * <p>
     * On success the same {@code String} reference and the checked width are stored, with the width
     * narrowed to {@code int} only after the scan proved it is at most {@code MAX_UTF8_BYTES}.
     * </p>
     *
     * @param text the raw envelope text, never null
     * @return a value holding the very same text reference, never null
     * @throws IllegalArgumentException if {@code text} is null, is longer than
     *                                  {@link #MAX_UTF8_BYTES} UTF-16 units, holds an unpaired surrogate, or exceeds
     *                                  {@link #MAX_UTF8_BYTES} UTF-8 bytes
     */
    public static RawEnvelopeText of(String text) {
        if (text == null) {
            throw new IllegalArgumentException("raw envelope text must not be null");
        }
        final int length = text.length();
        // One UTF-16 unit is one UTF-8 byte at the very least, so this bound is exact in the sense
        // that matters here: everything rejected by it is genuinely over budget, and nothing that
        // passes it can be declared over budget by length alone.
        if (length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("raw envelope text of " + length + " UTF-16 units exceeds the maximum of " + MAX_UTF8_BYTES + " UTF-8 bytes");
        }
        long utf8 = 0L;
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c < 0x80) {
                // ASCII, including every control and whitespace character: still exactly one byte.
                utf8 += 1L;
            } else if (c < 0x800) {
                utf8 += 2L;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "raw envelope text has an unpaired high surrogate at index " + i);
                }
                // The pair is charged as one four byte character and its low unit is consumed here,
                // so a low surrogate can never be seen again as an unpaired unit.
                utf8 += 4L;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "raw envelope text has an unpaired low surrogate at index " + i);
            } else {
                utf8 += 3L;
            }
            if (utf8 > MAX_UTF8_BYTES) {
                // Rejected as soon as the budget is passed: the remaining text is never scanned.
                throw new IllegalArgumentException("raw envelope text exceeds " + MAX_UTF8_BYTES + " UTF-8 bytes");
            }
        }
        // The scan proved utf8 <= MAX_UTF8_BYTES, so this narrowing is always safe.
        return new RawEnvelopeText(text, (int) utf8);
    }

    /**
     * Returns the captured text.
     *
     * <p>
     * The very reference given to {@link #of(String)} is returned: it is never copied, re-encoded
     * or normalized. The result is never null.
     * </p>
     *
     * @return the raw text, never null
     */
    public String text() {
        return this.text;
    }

    /**
     * Returns the measured UTF-8 byte size of {@link #text()}.
     *
     * <p>
     * The value was computed during the validating pass and is returned as stored: this method
     * never re-encodes the text and never recounts it.
     * </p>
     *
     * @return the UTF-8 byte count, between 0 and {@link #MAX_UTF8_BYTES} inclusive
     */
    public int utf8Size() {
        return this.utf8Size;
    }
}
