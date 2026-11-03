package net.ptcrys.fpsmatch.editor.common.model;

import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Structured field path: an ordered list of exact field segments (R06.0, design revision R1.1).
 *
 * <p>
 * A segment is a complete, opaque field identity. It is never split again on {@code '.'},
 * {@code ':'} or any other character, never trimmed and never normalized, so a key that literally
 * contains {@code '.'} or {@code ':'} stays one segment and can never collide with a nested path.
 * Empty segments, numeric segments and segments containing separator characters are all legal,
 * because every one of them is a legal JSON object key or array index.
 * </p>
 *
 * <p>
 * The wire form is the JSON pointer of RFC 6901: the root is the empty string, every segment is
 * prefixed with {@code '/'}, and inside a segment every reserved character is escaped ({@code '~'} to
 * {@code "~0"} and {@code '/'} to {@code "~1"}) with every other character kept in its original order
 * and never normalized. {@code '='} is an ordinary character: it is never a separator and is never
 * escaped, so {@code ["=","a=b","c~2d"]} has the independent pointer {@code "/=/a=b/c~02d"}.
 * {@link #pointer()} and {@link #fromPointer(String)} are exact inverses for every path this class
 * can represent, and no other escape letter exists: {@code "/~2"} is rejected.
 * </p>
 *
 * <p>
 * Budgets: at most {@link #MAX_SEGMENTS} segments, each at most
 * {@link ExactJson#MAX_KEY_UTF8_BYTES} UTF-8 bytes when decoded, with well formed surrogate pairs
 * only. A segment is the identity of one JSON object member, so it obeys exactly the key budget of
 * the frozen codec. The largest pointer this class can produce therefore holds at most
 * {@code MAX_SEGMENTS * (2 * MAX_KEY_UTF8_BYTES + 1) == 133185} UTF-16 characters; a longer pointer
 * is rejected before it is scanned, and no method here ever builds an unbounded escaped string.
 * </p>
 *
 * <p>
 * Construction is an immutable snapshot: the given list is validated element by element while it
 * is actually iterated, bounded by the segment budget, and only then copied. An input list that
 * reports a small {@code size()} but iterates forever fails on its 66th element instead of being
 * copied, and a lazily enormous list is never materialised. An input list that lies about its size
 * cannot cause an out of bounds read either, because no element is ever fetched by index or through
 * {@code toArray}.
 * </p>
 *
 * @param segments the exact field segments, never null and never containing null
 */
public record FieldPath(List<String> segments) {

    /** Maximum number of segments one path may contain. */
    public static final int MAX_SEGMENTS = 65;

    /** Maximum UTF-16 character length of a pointer this class can produce or accept. */
    private static final int MAX_POINTER_CHARS = MAX_SEGMENTS * (2 * ExactJson.MAX_KEY_UTF8_BYTES + 1);

    private static final FieldPath ROOT = new FieldPath(Collections.emptyList());

    /**
     * Validates and snapshots the segments.
     *
     * @param segments exact field segments; may be an arbitrary iterable list, never null
     * @throws IllegalArgumentException if {@code segments} is null, holds more than
     *                                  {@link #MAX_SEGMENTS} elements, holds a null element, or holds an element whose
     *                                  text
     *                                  has an unpaired surrogate or exceeds {@link ExactJson#MAX_KEY_UTF8_BYTES} UTF-8
     *                                  bytes
     */
    public FieldPath {
        segments = snapshot(segments);
    }

    /**
     * Returns the root path, the path of the document itself: zero segments and the empty pointer.
     *
     * @return the immutable root path, never null
     */
    public static FieldPath root() {
        return ROOT;
    }

    /**
     * Parses a JSON pointer into a structured path.
     *
     * <p>
     * The empty string is the root and every non empty pointer must start with {@code '/'}.
     * {@code "/"} yields one empty segment and {@code "//"} yields two empty segments. Escapes are
     * decoded left to right without recursion: only {@code "~0"} and {@code "~1"} are accepted, so a
     * dangling {@code '~'} or any other {@code "~x"} is rejected, while {@code "/~01"} decodes to the
     * single segment {@code "~1"} and re-encodes to {@code "/~01"}.
     * </p>
     *
     * <p>
     * The pointer length is rejected before any scan, and each segment is decoded with its decoded
     * UTF-8 budget and its Unicode shape checked while that segment is read, so an oversized segment
     * is rejected without ever being copied out of the input, and a 66th segment is rejected before
     * it is appended.
     * </p>
     *
     * @param pointer the JSON pointer text, never null
     * @return the decoded path, never null
     * @throws IllegalArgumentException if {@code pointer} is null, is longer than 133185 UTF-16
     *                                  characters, does not start with {@code '/'}, holds an
     *                                  illegal escape, holds more than {@link #MAX_SEGMENTS} segments, or holds a
     *                                  segment with
     *                                  an unpaired surrogate or more than {@link ExactJson#MAX_KEY_UTF8_BYTES} UTF-8
     *                                  bytes
     */
    public static FieldPath fromPointer(String pointer) {
        if (pointer == null) {
            throw new IllegalArgumentException("pointer must not be null");
        }
        if (pointer.isEmpty()) {
            return ROOT;
        }
        if (pointer.length() > MAX_POINTER_CHARS) {
            throw new IllegalArgumentException("pointer of " + pointer.length() + " UTF-16 characters exceeds the maximum of " + MAX_POINTER_CHARS);
        }
        if (pointer.charAt(0) != '/') {
            throw new IllegalArgumentException("pointer must be empty or start with '/': " + pointer);
        }
        final List<String> decoded = new ArrayList<>(MAX_SEGMENTS);
        final int length = pointer.length();
        int cursor = 0;
        while (cursor < length) {
            // The budget is enforced before the next segment is parsed, so a 66th segment is rejected
            // without decoding it, validating it, or copying any part of it.
            if (decoded.size() == MAX_SEGMENTS) {
                throw new IllegalArgumentException(
                        "a field path holds at most " + MAX_SEGMENTS + " segments, more were supplied");
            }
            cursor = decodeSegment(pointer, cursor, ExactJson.MAX_KEY_UTF8_BYTES, decoded);
        }
        return new FieldPath(decoded);
    }

    /**
     * Returns this path with one segment appended.
     *
     * @param segment the exact new field segment
     * @return a new path whose parent is unchanged, never null
     * @throws IllegalArgumentException if {@code segment} is null or illegal, or if this path already
     *                                  holds {@link #MAX_SEGMENTS} segments
     */
    public FieldPath child(String segment) {
        if (this.segments.size() >= MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "cannot add a child segment: already at the maximum of " + MAX_SEGMENTS + " segments");
        }
        // The new segment's identity is only meaningful once it is legal, so it is validated before the
        // copy is started and before the new list exists.
        requireSegment(segment);
        final List<String> extended = new ArrayList<>(this.segments.size() + 1);
        extended.addAll(this.segments);
        extended.add(segment);
        return new FieldPath(extended);
    }

    /**
     * Returns the JSON pointer of this path: {@code ""} for the root, otherwise one {@code '/'} per
     * segment with only {@code '~'} and {@code '/'} escaped.
     *
     * @return the pointer text, never null
     */
    public String pointer() {
        if (this.segments.isEmpty()) {
            return "";
        }
        final StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < this.segments.size(); i++) {
            builder.append('/');
            appendEscaped(builder, this.segments.get(i));
        }
        return builder.toString();
    }

    /**
     * Returns the segments in order.
     *
     * @return an immutable list; mutation attempts fail rather than corrupting this path
     */
    @Override
    public List<String> segments() {
        return this.segments;
    }

    // ------------------------------------------------------------------
    // segment validation: UTF-16 shape plus decoded UTF-8 budget
    // ------------------------------------------------------------------

    /**
     * Snapshots an arbitrary input list into an immutable list of at most {@link #MAX_SEGMENTS}
     * validated segments.
     *
     * <p>
     * The reported size is used only as an early rejection: it is never trusted as the iteration
     * bound. The iterator is advanced at most {@code MAX_SEGMENTS + 1} times, so an endless or
     * enormous list stops at the 66th element before any copy happens, and every element is validated
     * as it is read. The result is an immutable copy, so later changes to the input, and the input
     * itself, are both isolated from this path.
     * </p>
     *
     * @param input candidate segments, never null
     * @return an immutable validated list, never null
     */
    private static List<String> snapshot(List<String> input) {
        if (input == null) {
            throw new IllegalArgumentException("segments must not be null");
        }
        // The reported size is read exactly once and then trusted no further than its own upper bound:
        // a second read could answer differently (or allocate) and would make this method, not the
        // caller, the place where an unbounded allocation happens.
        final int reported = input.size();
        if (reported < 0 || reported > MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "a field path holds at most " + MAX_SEGMENTS + " segments, got at least " + reported);
        }
        final List<String> validated = new ArrayList<>(MAX_SEGMENTS);
        int seen = 0;
        for (String segment : input) {
            // The iteration bound does not depend on the captured size: a list that under-reports is
            // stopped at the 66th element, which is rejected before it is copied.
            if (seen == MAX_SEGMENTS) {
                throw new IllegalArgumentException(
                        "a field path holds at most " + MAX_SEGMENTS + " segments, more were supplied");
            }
            requireSegment(segment);
            validated.add(segment);
            seen++;
        }
        return List.copyOf(validated);
    }

    /**
     * Validates one segment as a complete field identity.
     *
     * <p>
     * The UTF-16 shape and the decoded UTF-8 size are checked character by character, and the
     * budget is enforced as soon as the running total passes it, so an oversized segment never turns
     * into an encoded byte array and no substring of it is ever copied.
     * </p>
     *
     * @param segment candidate segment
     * @throws IllegalArgumentException if {@code segment} is null, holds an unpaired surrogate, or
     *                                  exceeds {@link ExactJson#MAX_KEY_UTF8_BYTES} UTF-8 bytes
     */
    private static void requireSegment(String segment) {
        if (segment == null) {
            throw new IllegalArgumentException("field path segment must not be null");
        }
        final int length = segment.length();
        int decoded = 0;
        for (int i = 0; i < length; i++) {
            final char c = segment.charAt(i);
            if (c < 0x80) {
                decoded += 1;
            } else if (c < 0x800) {
                decoded += 2;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(segment.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            "field path segment has an unpaired high surrogate at index " + i);
                }
                decoded += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException(
                        "field path segment has an unpaired low surrogate at index " + i);
            } else {
                decoded += 3;
            }
            if (decoded > ExactJson.MAX_KEY_UTF8_BYTES) {
                throw new IllegalArgumentException("field path segment exceeds " + ExactJson.MAX_KEY_UTF8_BYTES + " UTF-8 bytes");
            }
        }
    }

    // ------------------------------------------------------------------
    // pointer decoding: bounded, non recursive, one segment at a time
    // ------------------------------------------------------------------

    /**
     * Decodes the pointer segment that starts at the given {@code '/'} and appends it.
     *
     * <p>
     * The scan never recurses and never copies the remaining text: characters are appended one by
     * one, escape sequences are decoded in place, and the decoded UTF-8 budget passed in (the smaller
     * of the key budget and the characters actually available) is enforced while scanning, so an
     * oversized or malformed segment is rejected before it becomes a string.
     * </p>
     *
     * @param pointer       full pointer text
     * @param slash         index of the {@code '/'} that starts this segment
     * @param decodedBudget maximum decoded UTF-8 bytes this segment may occupy
     * @param out           destination list of decoded segments
     * @return the index just past this segment, either the next {@code '/'} or {@code pointer.length()}
     */
    private static int decodeSegment(String pointer, int slash, int decodedBudget, List<String> out) {
        final int length = pointer.length();
        final StringBuilder decoded = new StringBuilder(Math.min(length - slash - 1, 64));
        int cursor = slash + 1;
        int bytes = 0;
        while (cursor < length) {
            final char c = pointer.charAt(cursor);
            if (c == '/') {
                break;
            }
            final char unit;
            if (c == '~') {
                if (cursor + 1 >= length) {
                    throw new IllegalArgumentException("dangling '~' escape at index " + cursor + " in " + pointer);
                }
                final char escaped = pointer.charAt(cursor + 1);
                if (escaped == '0') {
                    unit = '~';
                } else if (escaped == '1') {
                    unit = '/';
                } else {
                    throw new IllegalArgumentException("illegal pointer escape at index " + cursor + " in " + pointer + ": only ~0 and ~1 are accepted");
                }
                cursor += 2;
            } else {
                unit = c;
                cursor++;
            }
            final int width;
            if (unit < 0x80) {
                width = 1;
            } else if (unit < 0x800) {
                width = 2;
            } else if (Character.isHighSurrogate(unit)) {
                if (cursor >= length || !Character.isLowSurrogate(pointer.charAt(cursor))) {
                    throw new IllegalArgumentException(
                            "unpaired high surrogate at index " + (cursor - 1) + " in " + pointer);
                }
                decoded.append(unit).append(pointer.charAt(cursor));
                cursor++;
                bytes += 4;
                if (bytes > decodedBudget) {
                    throw new IllegalArgumentException("pointer segment exceeds " + decodedBudget + " decoded UTF-8 bytes in " + pointer);
                }
                continue;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException(
                        "unpaired low surrogate at index " + (cursor - 1) + " in " + pointer);
            } else {
                width = 3;
            }
            bytes += width;
            if (bytes > decodedBudget) {
                throw new IllegalArgumentException("pointer segment exceeds " + decodedBudget + " decoded UTF-8 bytes in " + pointer);
            }
            decoded.append(unit);
        }
        if (out.size() >= MAX_SEGMENTS) {
            throw new IllegalArgumentException(
                    "pointer holds more than " + MAX_SEGMENTS + " segments: " + pointer);
        }
        out.add(decoded.toString());
        return cursor;
    }

    /**
     * Appends one segment in pointer escaped form, charging exactly one character per emitted
     * character into the caller's builder.
     *
     * @param builder destination
     * @param segment segment to escape; an empty segment appends nothing
     */
    private static void appendEscaped(StringBuilder builder, String segment) {
        final int length = segment.length();
        for (int i = 0; i < length; i++) {
            final char c = segment.charAt(i);
            if (c == '~') {
                builder.append("~0");
            } else if (c == '/') {
                builder.append("~1");
            } else {
                builder.append(c);
            }
        }
    }

    static {
        // The declared pointer budget must be the exact worst case of the escaping rule, otherwise a
        // legal path could be rejected or an oversized pointer could be scanned.
        if (MAX_POINTER_CHARS != MAX_SEGMENTS * (2 * ExactJson.MAX_KEY_UTF8_BYTES + 1)) {
            throw new IllegalStateException("MAX_POINTER_CHARS does not match the pointer escaping budget");
        }
        // The key budget is shared with the frozen codec, not copied: a segment is a JSON key.
        if (ExactJson.MAX_KEY_UTF8_BYTES != 1024) {
            throw new IllegalStateException(
                    "unexpected ExactJson.MAX_KEY_UTF8_BYTES: " + ExactJson.MAX_KEY_UTF8_BYTES);
        }
    }
}
