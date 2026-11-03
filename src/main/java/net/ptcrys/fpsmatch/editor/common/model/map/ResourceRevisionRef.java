package net.ptcrys.fpsmatch.editor.common.model.map;

import java.util.UUID;

/**
 * One reference to a specific revision of an external resource (fixed task card R05.2, revision
 * R1.1).
 *
 * <p>
 * A reference binds a resource kind, the resource identity, a positive revision number, a
 * content digest and a frozen carrier for members this revision does not interpret. It never reads
 * a resource library, a provider, a file or a network location, and it never computes a digest.
 * </p>
 *
 * <p>
 * <strong>Resource identity is the pair, not either half.</strong> The identity of the referenced
 * resource is {@code (kindId, resourceId)} together. Neither half is resolved here: this type does
 * not look the resource up, does not check that it exists, and does not call
 * {@link UUID#fromString(String)}. Whether the same resource identity occurs twice in one map is
 * an aggregate question owned by R05.3, and two occurrences of one resource identity are two
 * distinct values that each keep their own members rather than being collapsed by a shared
 * identity keyed on the UUID.
 * </p>
 *
 * <p>
 * <strong>The revision is carried exactly, never shifted or converted.</strong> A revision must
 * be strictly greater than zero. {@link Long#MAX_VALUE} is legal and is stored unchanged: this type
 * never adds one to it, never narrows it to an {@code int}, and never derives it from a digest or a
 * hash. This is a reference to one specific revision, not a "latest" marker, so no revision is
 * inferred from anything else and no newest-wins rule lives here.
 * </p>
 *
 * <p>
 * <strong>The digest is checked as text, cheaply and without normalising it.</strong> A digest
 * must be exactly 64 characters, each of which is a lower case ASCII hexadecimal digit
 * ({@code [0-9a-f]}). The length is verified first and then every character is inspected in one
 * pass, so an over-long input is rejected by its length without any regular expression being
 * compiled against it and without its UTF-8 bytes being materialised. The value is never trimmed,
 * never case folded, never re-encoded and never recomputed from the resource content; upper case
 * hexadecimal is rejected rather than accepted as an equivalent spelling. An all zero digest is
 * <em>format</em> legal: this type makes no claim that any resource exists, that any content
 * matches this digest, or that the digest was produced by any particular algorithm. Verifying the
 * actual content against the digest belongs to a real resource library or validator, not here.
 * </p>
 *
 * <p>
 * <strong>Null versus JSON null.</strong> Every member must be a non-null Java reference and a
 * null argument is rejected with {@link IllegalArgumentException}. There is no "null means absent"
 * reference and no defaulting path: a resource reference with no resource is not expressible, and
 * JSON {@code null} is only meaningful <em>inside</em> the frozen {@code wireExtras} members, where
 * it is a legal frozen JSON value and is carried verbatim.
 * </p>
 *
 * <p>
 * <strong>Wire members.</strong> {@code wireExtras} is a required frozen carrier whose own slots
 * are object-only and is reused as supplied, and it is reused as supplied rather than copied.
 * Unknown members of one resource reference occurrence stay in that occurrence's own slot, so a
 * second occurrence of the same resource identity cannot overwrite the first one's unknown members
 * through a shared global table and the two occurrences do not compare equal merely because their
 * identity halves agree.
 * </p>
 *
 * @param kindId     explicit {@code namespace:path} resource kind, never defaulted and never resolved
 * @param resourceId the resource identity half, never null and never looked up here
 * @param revision   the referenced revision, strictly positive and carried unchanged
 * @param digest     exactly 64 lower case ASCII hexadecimal characters, never normalised or recomputed
 * @param wireExtras unknown members of this resource reference occurrence
 */
public record ResourceRevisionRef(String kindId, UUID resourceId, long revision, String digest,
                                  WireExtras.ResourceExtras wireExtras) {

    /** Exact number of characters of a well formed digest. */
    private static final int DIGEST_LENGTH = 64;

    /**
     * Rejects every null or illegal member. No member is normalised or defaulted.
     */
    public ResourceRevisionRef {
        kindId = MapScalars.requireNamespaced(kindId);
        if (resourceId == null) {
            throw new IllegalArgumentException("ResourceRevisionRef.resourceId must not be null");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException(
                    "ResourceRevisionRef.revision must be > 0 but was " + revision);
        }
        digest = requireDigest(digest);
        if (wireExtras == null) {
            throw new IllegalArgumentException("ResourceRevisionRef.wireExtras must not be null");
        }
    }

    /**
     * Checks that a digest is exactly 64 lower case ASCII hexadecimal characters.
     *
     * <p>
     * The length is tested before any character is examined, so a very large input is rejected
     * without a regular expression being compiled or its UTF-8 bytes being produced. The characters
     * are then checked one at a time in a single pass, and the supplied reference is returned
     * unchanged: no trimming, no case conversion and no re-encoding takes place.
     * </p>
     *
     * @param digest the digest text exactly as the caller supplied it
     * @return the identical, unmodified {@code digest} reference
     * @throws IllegalArgumentException if {@code digest} is null, is not exactly 64 characters, or
     *                                  contains a character outside {@code [0-9a-f]}
     */
    private static String requireDigest(String digest) {
        if (digest == null) {
            throw new IllegalArgumentException("ResourceRevisionRef.digest must not be null");
        }
        if (digest.length() != DIGEST_LENGTH) {
            throw new IllegalArgumentException("ResourceRevisionRef.digest must be exactly " + DIGEST_LENGTH + " lower case hex characters, got " + digest.length());
        }
        for (int i = 0; i < DIGEST_LENGTH; i++) {
            char c = digest.charAt(i);
            if (!isLowerCaseHexDigit(c)) {
                throw new IllegalArgumentException("ResourceRevisionRef.digest must match [0-9a-f]" + " but has '" + c + "' (U+" + toHex4(c) + ") at index " + i);
            }
        }
        return digest;
    }

    private static boolean isLowerCaseHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    private static String toHex4(char c) {
        String hex = Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT);
        return "0000".substring(hex.length()) + hex;
    }
}
