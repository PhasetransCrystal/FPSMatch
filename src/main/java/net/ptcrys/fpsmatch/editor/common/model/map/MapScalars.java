package net.ptcrys.fpsmatch.editor.common.model.map;

/**
 * Package private scalar rules of the world editor map model (fixed task card R05.1, revision
 * R1.3, section "fixed scalar rules").
 *
 * <p>
 * Every method here answers one question only: is this already materialised Java {@code String}
 * acceptable as the named map scalar, and if not, why not. No method repairs its input. A returned
 * value is <em>the very same reference</em> the caller passed in: there is no {@code trim}, no
 * Unicode normalisation (neither NFC nor NFD), no case folding, no truncation, no control character
 * replacement and no escaping. A caller that needs a display fallback for an empty spawn or region
 * name produces that label in the UI; it is never written back into the model.
 * </p>
 *
 * <p>
 * <strong>Why the byte budgets are checked incrementally.</strong> The UTF-8 budgets of this type
 * are ceilings (512 bytes for a name, 8192 for a description), so a value that violates them can be
 * arbitrarily larger than the ceiling, and on a hostile wire payload it can be arbitrarily larger
 * than the whole heap. Calling {@link String#getBytes(java.nio.charset.Charset)} first and comparing
 * {@code length} afterwards would therefore allocate and copy the full encoding of an input that is
 * already known to be rejected. Every budget here is instead accumulated one UTF-16 unit at a time
 * and the method throws with {@link IllegalArgumentException} <em>at the first unit that crosses the
 * ceiling</em>, so the work done on a rejected value is bounded by the budget itself, not by the
 * input length. The same single pass also verifies surrogate pairing, which is what makes the
 * accumulated count the number of UTF-8 bytes the value would really occupy.
 * </p>
 *
 * <p>
 * <strong>Why there is exactly one pass.</strong> Pairing is verified <em>inside</em> the counting
 * loop, at the unit being counted, and never as a separate scan over the whole string first. A
 * separate pre-scan would reintroduce the very unbounded work the incremental budget exists to
 * prevent, and it would also misreport the cause: for a value whose prefix already exceeds the
 * budget and whose tail carries an unpaired surrogate, the budget is the defect that occurs first,
 * and a pre-scan would blame the surrogate instead. Processing each unit (or pair) and then
 * re-checking the ceiling keeps the accumulated count within the budget plus one encoded unit, so it
 * can neither overflow an {@code int} nor read past the input.
 * </p>
 *
 * <p>
 * <strong>Why an unpaired surrogate is illegal.</strong> An unpaired surrogate is not a Unicode
 * scalar value and has no UTF-8 encoding of its own; it is only ever <em>represented</em> in UTF-8 by
 * a substituting encoder, and the replacement is lossy. Accepting such a value would mean the model
 * holds text whose encoding is not the text itself, and two model values that are unequal as Java
 * strings could encode to the same bytes. Unpaired surrogates are therefore an illegal scalar,
 * rejected exactly like an over-budget value, and the failure detail names the offending index and
 * unit. This type makes no claim about which substitute byte sequence any particular encoder emits;
 * it rejects the input rather than depending on that behaviour.
 * </p>
 *
 * <p>
 * <strong>Scope.</strong> This is a pure stateless helper with no caching, no configuration and
 * no registry access; it answers syntax questions only. It never consults a Minecraft registry, a
 * world, a save or a datapack, so a syntactically legal but unknown namespace or resource kind
 * passes here and is judged by a business validator later. The dimension and namespaced rules are
 * deliberately the same grammar, so a future region, mode, kind or resource-kind scalar cannot
 * drift away from the frozen {@code WorldRef} vector semantics.
 * </p>
 */
final class MapScalars {

    /** Maximum accepted UTF-8 size of a single line map name, in bytes. */
    private static final int MAX_NAME_UTF8_BYTES = 512;

    /** Maximum accepted UTF-8 size of a map description, in bytes. */
    private static final int MAX_DESCRIPTION_UTF8_BYTES = 8192;

    /**
     * Maximum accepted length of a namespaced identifier such as {@code namespace:path}, in ASCII
     * characters. This is the same budget the frozen {@code WorldRef} applies to its dimension id.
     */
    private static final int MAX_NAMESPACED_CHARS = 191;

    /**
     * Non instantiable: this type is a namespace for static scalar checks and holds no state.
     */
    private MapScalars() {}

    /**
     * Validates a single line display name.
     *
     * <p>
     * The value must be non-null, must be legal paired Unicode, must stay within 512 UTF-8 bytes
     * and must not contain a C0 control character other than a legal surrogate pair member. The
     * accepted length is measured in UTF-8 bytes, not in Java characters: 512 bytes are accepted and
     * 513 are rejected, so 512 ASCII characters pass, 513 do not, and 170 CJK characters plus two
     * ASCII characters is exactly 512 bytes and passes. {@code DEL}, {@code U+007F}, is a legal
     * Unicode scalar and is kept, as are emoji, which occupy four UTF-8 bytes each.
     * </p>
     *
     * <p>
     * Pairing and the byte budget are judged in a single forward pass, and the defect that occurs
     * first is the one reported. A value whose prefix already exceeds 512 bytes therefore fails on
     * the budget even when its tail also carries an unpaired surrogate; the trailing unit is never
     * read once the ceiling has been crossed.
     * </p>
     *
     * <p>
     * {@code nonEmpty} selects between the two card sanctioned name policies and nothing else:
     * a team name passes {@code true} and is rejected only when it is a genuinely empty string,
     * whereas a spawn, region or map display name passes {@code false} and may be empty because the
     * UI supplies the fallback label. A single space is a non-empty string and is kept as given:
     * this method never trims, so it never decides that a name is "blank".
     * </p>
     *
     * @param value    the name exactly as the caller supplied it
     * @param nonEmpty {@code true} to reject the empty string, {@code false} to allow it
     * @return the identical, unmodified {@code value} reference
     * @throws IllegalArgumentException if {@code value} is null, empty when {@code nonEmpty} is set,
     *                                  unpaired, contains a C0 control character, or exceeds 512 UTF-8 bytes
     */
    static String requireName(String value, boolean nonEmpty) {
        if (value == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (nonEmpty && value.isEmpty()) {
            throw new IllegalArgumentException("name must not be empty");
        }
        int utf8 = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("name must be legal paired Unicode but has" + " unpaired high surrogate U+" + toHex4(c) + " at index " + i);
                }
                utf8 += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("name must be legal paired Unicode but has" + " unpaired low surrogate U+" + toHex4(c) + " at index " + i);
            } else {
                if (c <= 0x1F) {
                    throw new IllegalArgumentException(
                            "name must not contain control character U+" + toHex4(c) + " at index " + i);
                }
                utf8 += (c < 0x80) ? 1 : (c < 0x800) ? 2 : 3;
            }
            if (utf8 > MAX_NAME_UTF8_BYTES) {
                throw new IllegalArgumentException("name must be at most " + MAX_NAME_UTF8_BYTES + " UTF-8 bytes, but exceeded the budget at index " + i);
            }
        }
        return value;
    }

    /**
     * Validates a multi line description.
     *
     * <p>
     * The value must be non-null, must be legal paired Unicode and must stay within 8192 UTF-8
     * bytes. Unlike {@link #requireName(String, boolean)} this method <em>does not</em> restrict
     * control characters: a line feed, a carriage return, a tab and every other escaped JSON control
     * character are kept, because the contract restricts only names. Substituting or stripping them
     * would silently rewrite author text, so nothing is replaced.
     * </p>
     *
     * <p>
     * As in {@link #requireName(String, boolean)} the pairing and budget checks share one forward
     * pass, so a prefix that already exceeds 8192 bytes is reported as a budget failure even when the
     * tail also carries an unpaired surrogate.
     * </p>
     *
     * @param value the description exactly as the caller supplied it
     * @return the identical, unmodified {@code value} reference
     * @throws IllegalArgumentException if {@code value} is null, unpaired, or exceeds 8192 UTF-8
     *                                  bytes
     */
    static String requireDescription(String value) {
        if (value == null) {
            throw new IllegalArgumentException("description must not be null");
        }
        int utf8 = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("description must be legal paired Unicode but" + " has unpaired high surrogate U+" + toHex4(c) + " at index " + i);
                }
                utf8 += 4;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("description must be legal paired Unicode but has" + " unpaired low surrogate U+" + toHex4(c) + " at index " + i);
            } else {
                utf8 += (c < 0x80) ? 1 : (c < 0x800) ? 2 : 3;
            }
            if (utf8 > MAX_DESCRIPTION_UTF8_BYTES) {
                throw new IllegalArgumentException("description must be at most " + MAX_DESCRIPTION_UTF8_BYTES + " UTF-8 bytes, but exceeded the budget at index " + i);
            }
        }
        return value;
    }

    /**
     * Validates an explicit dimension id, e.g. {@code namespace:path}.
     *
     * <p>
     * This is the scalar rule a spawn carries. The value must be explicit: the namespace is never
     * completed with an implicit {@code minecraft:}. The whole string must be ASCII, at most 191
     * characters, exactly one colon, a non-empty namespace of at most 189 characters matching
     * {@code [a-z0-9_.-]+} and a non-empty path of at most 190 characters matching
     * {@code [a-z0-9_./-]+}. Upper case, whitespace, a leading or trailing colon, a doubled colon and
     * every non-ASCII character are rejected, so a value accepted here stays inside a one byte per
     * character budget.
     * </p>
     *
     * <p>
     * The accepted set is intentionally the same as the frozen {@code WorldRef} dimension rule, so
     * the two cannot disagree on the same vector.
     * </p>
     *
     * @param value the dimension id exactly as the caller supplied it
     * @return the identical, unmodified {@code value} reference
     * @throws IllegalArgumentException if {@code value} is null or violates the namespaced syntax
     */
    static String requireDimension(String value) {
        return requireNamespacedScalar(value, "dimensionId");
    }

    /**
     * Validates any namespaced scalar that uses the {@code namespace:path} grammar with the 191
     * ASCII character budget.
     *
     * <p>
     * The card reserves this method for later scalars such as a mode, a kind or a resource kind,
     * so the syntax decision lives in one place instead of being restated per record. The accepted
     * grammar and budget are exactly those of {@link #requireDimension(String)}; only the name used
     * in the failure detail differs.
     * </p>
     *
     * <p>
     * Passing here means the text is well formed, <em>not</em> that the namespace or the kind is
     * registered. This method never consults a provider or a registry: an unknown but syntactically
     * legal namespace passes, and whether it means anything at all is decided by a business
     * validator, which reports that as a business problem rather than a construction failure.
     * </p>
     *
     * @param value the namespaced value exactly as the caller supplied it
     * @return the identical, unmodified {@code value} reference
     * @throws IllegalArgumentException if {@code value} is null or violates the namespaced syntax
     */
    static String requireNamespaced(String value) {
        return requireNamespacedScalar(value, "namespaced value");
    }

    /**
     * Shared implementation of the {@code namespace:path} grammar with the 191 ASCII character
     * budget.
     *
     * @param value the value to check
     * @param what  the scalar name used in the failure detail
     * @return the identical, unmodified {@code value} reference
     */
    private static String requireNamespacedScalar(String value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " must not be null");
        }
        int length = value.length();
        if (length == 0) {
            throw new IllegalArgumentException(what + " must not be empty");
        }
        if (length > MAX_NAMESPACED_CHARS) {
            throw new IllegalArgumentException(what + " must be at most " + MAX_NAMESPACED_CHARS + " ASCII characters, got " + length);
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    what + " must be explicit 'namespace:path'; the namespace is never defaulted");
        }
        if (value.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException(
                    what + " must contain exactly one ':' separator");
        }
        if (colon == 0) {
            throw new IllegalArgumentException(what + " namespace must not be empty");
        }
        if (colon == length - 1) {
            throw new IllegalArgumentException(what + " path must not be empty");
        }
        for (int i = 0; i < colon; i++) {
            if (!isNamespaceCharacter(value.charAt(i))) {
                throw new IllegalArgumentException(
                        what + " namespace must match [a-z0-9_.-]+ at index " + i);
            }
        }
        for (int i = colon + 1; i < length; i++) {
            if (!isPathCharacter(value.charAt(i))) {
                throw new IllegalArgumentException(
                        what + " path must match [a-z0-9_./-]+ at index " + i);
            }
        }
        return value;
    }

    private static boolean isNamespaceCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
    }

    private static boolean isPathCharacter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '/' || c == '-';
    }

    private static String toHex4(char c) {
        String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
        return "0000".substring(hex.length()) + hex;
    }
}
