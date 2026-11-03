package net.ptcrys.fpsmatch.editor.common.codec;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Bounded UTF-8 sink that feeds one JDK SHA-256 digest without ever materialising the appended text
 * (R06.2b2).
 *
 * <p>
 * The sink owns exactly one fixed {@value #BUFFER_BYTES} byte staging buffer, one
 * {@link MessageDigest} created fresh per instance and the scalar counters of this contract. It never
 * builds a full-text {@code StringBuilder}, a text-sized {@code byte[]}, an encoder output or any
 * collection of the appended ranges, so both the cost of an append and the resident memory of the
 * instance stay independent of the total length of the stream.
 * </p>
 *
 * <p>
 * Each append is atomic with respect to failures. Argument validation, the Unicode walk over the
 * selected UTF-16 range and the exact UTF-8 byte cost are all computed in one read-only pass; the
 * predicted {@code cost} is compared with the remaining budget before a single byte reaches the
 * digest. A rejected append therefore leaves the digest, the accumulated byte count and every other
 * field untouched, and the caller may keep appending legal ranges afterwards. Only once the range is
 * known to be legal and inside the budget is the very same rule replayed into the fixed buffer, which
 * is flushed into {@link MessageDigest#update(byte[], int, int)} in whole chunks and drained to its
 * last byte before the call returns.
 * </p>
 *
 * <p>
 * The accepted syntax is the UTF-8 encoding of the selected UTF-16 range and nothing else: one
 * byte for {@code U+0000..U+007F} (including NUL and every control), two bytes for
 * {@code U+0080..U+07FF}, three bytes for a non-surrogate {@code U+0800..U+FFFF} and four bytes for a
 * well-formed surrogate pair. Text is never normalised, trimmed, JSON-escaped or replaced, and a
 * character outside the selected range is never read, so a broken surrogate outside the range is
 * irrelevant while a truncated pair inside the range is rejected instead of becoming
 * {@code U+FFFD}.
 * </p>
 *
 * <p>
 * One instance belongs to one owner and is used from one thread; concurrency between calls of the
 * same instance is not supported and not claimed. Distinct instances share no buffer, no digest and
 * no state, so they may be interleaved or used concurrently.
 * </p>
 */
final class Utf8Sha256 {

    /** The largest accepted budget, 64 MiB, spelled as an exact power of two. */
    private static final long MAX_UTF8_BYTES = 67108864L;

    /** The single fixed staging buffer of the sink, in bytes. */
    private static final int BUFFER_BYTES = 8192;

    private static final String ALGORITHM = "SHA-256";

    private final MessageDigest digest;

    private final byte[] buffer = new byte[BUFFER_BYTES];

    private final long maxUtf8Bytes;

    private int buffered;

    private long utf8Bytes;

    private boolean finished;

    /**
     * Creates an empty sink whose cumulative UTF-8 byte budget is {@code maxUtf8Bytes}.
     *
     * @param maxUtf8Bytes accepted inclusive range {@code 0..67108864}; a negative value or a value
     *                     above 64 MiB is rejected with {@link IllegalArgumentException}
     * @throws IllegalArgumentException if {@code maxUtf8Bytes} is outside {@code 0..67108864}
     * @throws IllegalStateException    if the JDK does not provide SHA-256, with the original
     *                                  {@link NoSuchAlgorithmException} kept as the cause
     */
    Utf8Sha256(long maxUtf8Bytes) {
        if (maxUtf8Bytes < 0L || maxUtf8Bytes > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "maxUtf8Bytes must be within 0.." + MAX_UTF8_BYTES + ": " + maxUtf8Bytes);
        }
        this.maxUtf8Bytes = maxUtf8Bytes;
        final MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(ALGORITHM + " is unavailable", missing);
        }
        this.digest = sha256;
    }

    /**
     * Appends the whole of {@code text} as its UTF-8 bytes, equivalent to
     * {@code append(text, 0, text.length())}.
     *
     * @param text text to append; a Java {@code null} is rejected with
     *             {@link IllegalArgumentException}
     * @throws IllegalStateException    if the sink was already finished
     * @throws IllegalArgumentException if {@code text} is {@code null}, if it holds an unpaired
     *                                  surrogate, or if the append would exceed the budget
     */
    void append(String text) {
        requireOpen();
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
        appendRange(text, 0, text.length());
    }

    /**
     * Appends the half-open UTF-16 range {@code [start,end)} of {@code text} as its UTF-8 bytes.
     *
     * <p>
     * Characters before {@code start} and from {@code end} onwards are never read, so they may
     * hold broken surrogates. Inside a non-empty range every surrogate must be paired by the exact
     * UTF-16 rule. An empty range is always legal and free, including a range that starts between
     * the two units of a surrogate pair.
     * </p>
     *
     * @param text  text holding the range; a Java {@code null} is rejected
     * @param start inclusive UTF-16 start index, {@code 0 <= start <= end}
     * @param end   exclusive UTF-16 end index, {@code end <= text.length()}
     * @throws IllegalStateException    if the sink was already finished
     * @throws IllegalArgumentException if {@code text} is {@code null}, if the range is out of
     *                                  bounds or reversed, if the range holds an unpaired surrogate, or if the append
     *                                  would
     *                                  exceed the budget
     */
    void append(String text, int start, int end) {
        requireOpen();
        if (text == null) {
            throw new IllegalArgumentException("text is null");
        }
        if (start < 0 || start > end || end > text.length()) {
            throw new IllegalArgumentException("range [" + start + "," + end + ") is not within 0.." + text.length());
        }
        appendRange(text, start, end);
    }

    /**
     * Returns the exact accumulated UTF-8 byte cost of every accepted append so far.
     *
     * <p>
     * The call neither completes nor changes the digest and stays readable after {@link #finish()}.
     *
     * @return the accumulated UTF-8 byte count, never above the budget of this instance
     */
    long utf8Bytes() {
        return this.utf8Bytes;
    }

    /**
     * Completes the stream and returns the lowercase hexadecimal SHA-256 of every accepted byte.
     *
     * <p>
     * The first call takes the JDK digest and seals the instance; every later {@link #finish()}
     * and every later {@code append}, including {@code null} and empty ranges, is rejected with
     * {@link IllegalStateException}. {@link #utf8Bytes()} remains readable. A sink that accepted no
     * byte returns the SHA-256 of the empty input.
     * </p>
     *
     * @return 64 lowercase hexadecimal digits of the digest of the accepted UTF-8 bytes
     * @throws IllegalStateException if the sink was already finished
     */
    String finish() {
        if (this.finished) {
            throw new IllegalStateException("sink is already finished");
        }
        final byte[] hash = this.digest.digest();
        final char[] hex = new char[hash.length * 2];
        for (int index = 0; index < hash.length; index++) {
            final int value = hash[index] & 0xFF;
            hex[index * 2] = hexDigit(value >>> 4);
            hex[index * 2 + 1] = hexDigit(value & 0xF);
        }
        this.finished = true;
        return new String(hex);
    }

    private void requireOpen() {
        if (this.finished) {
            throw new IllegalStateException("sink is already finished");
        }
    }

    /**
     * Validates one range, predicts its exact cost and only then writes it. The two passes implement
     * the same rule, so the write pass cannot discover a failure the validation pass did not.
     */
    private void appendRange(String text, int start, int end) {
        final long cost = utf8Cost(text, start, end);
        if (cost > this.maxUtf8Bytes - this.utf8Bytes) {
            throw new IllegalArgumentException("append of " + cost + " bytes exceeds the budget " + this.maxUtf8Bytes + " after " + this.utf8Bytes + " bytes");
        }
        writeUtf8(text, start, end);
        flush();
        this.utf8Bytes += cost;
    }

    /** Read-only pass: proves the range is paired Unicode and returns its exact UTF-8 byte cost. */
    private static long utf8Cost(String text, int start, int end) {
        long cost = 0L;
        int index = start;
        while (index < end) {
            final char unit = text.charAt(index);
            if (unit < 0x80) {
                cost += 1L;
                index += 1;
            } else if (unit < 0x800) {
                cost += 2L;
                index += 1;
            } else if (Character.isHighSurrogate(unit)) {
                if (index + 1 >= end || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    throw new IllegalArgumentException("unpaired high surrogate at " + index);
                }
                cost += 4L;
                index += 2;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("unpaired low surrogate at " + index);
            } else {
                cost += 3L;
                index += 1;
            }
        }
        return cost;
    }

    /** Write pass: replays the validated range as UTF-8 into the fixed buffer. Cannot fail. */
    private void writeUtf8(String text, int start, int end) {
        int index = start;
        while (index < end) {
            final char unit = text.charAt(index);
            if (unit < 0x80) {
                write((byte) unit);
                index += 1;
            } else if (unit < 0x800) {
                write((byte) (0xC0 | (unit >> 6)));
                write((byte) (0x80 | (unit & 0x3F)));
                index += 1;
            } else if (Character.isHighSurrogate(unit)) {
                final int codePoint = Character.toCodePoint(unit, text.charAt(index + 1));
                write((byte) (0xF0 | (codePoint >> 18)));
                write((byte) (0x80 | ((codePoint >> 12) & 0x3F)));
                write((byte) (0x80 | ((codePoint >> 6) & 0x3F)));
                write((byte) (0x80 | (codePoint & 0x3F)));
                index += 2;
            } else {
                write((byte) (0xE0 | (unit >> 12)));
                write((byte) (0x80 | ((unit >> 6) & 0x3F)));
                write((byte) (0x80 | (unit & 0x3F)));
                index += 1;
            }
        }
    }

    /** Stages one byte and hands the buffer to the digest in whole chunks only. */
    private void write(byte value) {
        this.buffer[this.buffered] = value;
        this.buffered += 1;
        if (this.buffered == this.buffer.length) {
            this.digest.update(this.buffer, 0, this.buffered);
            this.buffered = 0;
        }
    }

    /** Hands the tail of the buffer to the digest, leaving no pending byte behind. */
    private void flush() {
        if (this.buffered > 0) {
            this.digest.update(this.buffer, 0, this.buffered);
            this.buffered = 0;
        }
    }

    /** Lowercase hexadecimal digit of a nibble, without any static table or cache. */
    private static char hexDigit(int nibble) {
        return (char) (nibble < 10 ? '0' + nibble : 'a' + (nibble - 10));
    }
}
