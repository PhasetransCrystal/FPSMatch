package net.ptcrys.fpsmatch.editor.common.codec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Content digest of a frozen payload (R06.0, design revision R1.1).
 *
 * <p>
 * The digest is {@code SHA-256} over the complete canonical UTF-8 bytes of an
 * {@link ExactJson.FrozenJson} value, spelled as 64 lower case ASCII hexadecimal characters. The
 * input is already frozen, so the canonical text it hashes is the unique canonical form of the whole
 * payload: unknown members are included, known members are not filtered, nothing is re-parsed and no
 * target, actor or operation tuple is concatenated into the hashed text. A caller that supplies a
 * digest of its own is never trusted.
 * </p>
 *
 * <p>
 * Only the payload value enters the digest. Request fingerprints and envelope verification are
 * separate contracts with their own tasks and are deliberately not part of this API.
 * </p>
 */
public final class ContentDigest {

    /** Algorithm name of the frozen canonical digest, recorded alongside the digest in envelopes. */
    public static final String ALGORITHM = "exact-json-r1";

    /** Name of the JDK digest implementation used by {@link #sha256(ExactJson.FrozenJson)}. */
    private static final String JDK_ALGORITHM = "SHA-256";

    /** Hexadecimal characters, lower case, indexed by nibble. */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ContentDigest() {}

    /**
     * Computes the digest of one frozen payload.
     *
     * <p>
     * The digest is computed exactly once over a complete copy of the canonical bytes, using a
     * fresh JDK {@link MessageDigest} instance for this call. No mutable digest object is held or
     * cached in a field, so two calls can never observe each other's partial state, and the input
     * value is never mutated.
     * </p>
     *
     * @param payload frozen payload whose canonical bytes are hashed, never null
     * @return 64 lower case hexadecimal characters, never null
     * @throws IllegalArgumentException if {@code payload} is null
     * @throws IllegalStateException    if this JVM cannot provide {@link #JDK_ALGORITHM}. Every
     *                                  conforming Java 17 runtime provides it, so this is a real configuration failure
     *                                  and it
     *                                  is reported as such, with the original {@link NoSuchAlgorithmException} as the
     *                                  cause; no
     *                                  empty string, placeholder or weaker digest is ever returned instead.
     */
    public static String sha256(ExactJson.FrozenJson payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        final byte[] canonicalUtf8 = payload.canonicalUtf8();
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(JDK_ALGORITHM);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(
                    JDK_ALGORITHM + " is unavailable in this JVM, the canonical content digest cannot be computed",
                    failure);
        }
        final byte[] hashed = digest.digest(canonicalUtf8);
        final StringBuilder hex = new StringBuilder(hashed.length * 2);
        for (byte b : hashed) {
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return hex.toString();
    }

    static {
        // The published algorithm name and the JDK algorithm must stay in step with the frozen contract.
        if (!"exact-json-r1".equals(ALGORITHM)) {
            throw new IllegalStateException("unexpected ALGORITHM: " + ALGORITHM);
        }
        if (StandardCharsets.UTF_8 == null) {
            throw new IllegalStateException("UTF-8 charset is unavailable");
        }
    }
}
