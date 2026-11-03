package net.ptcrys.fpsmatch.editor.core.documents;

import java.util.Arrays;
import java.util.Objects;

/**
 * One immutable save capture issued by exactly one {@link DocumentSession}.
 *
 * <p>
 * A capture remembers the token, the deeply immutable content and the already computed encoding
 * of the revision that was current when it was issued, so confirming it never re-encodes and never
 * depends on the retained history window. The content is the session's own immutable snapshot and is
 * returned as is; the encoding is copied on construction and on every read, so a caller that mutates
 * the returned array cannot move the save baseline.
 * </p>
 *
 * <p>
 * A capture also carries the private identity of its issuing session. That identity is never
 * published: {@link #issuedBy(Object)} only compares it with the identity a session offers, so code
 * outside the session - including another session with the same {@code documentId} - cannot produce
 * a capture this session will accept. There is no global registry, and a capture does not reference
 * the session itself.
 * </p>
 *
 * <p>
 * Captures have no owner here: a caller's save queue holds and releases them, this class keeps no
 * list of the captures it issued. This type is not a save queue.
 * </p>
 *
 * @param <T> the domain value type of the issuing session
 */
public final class DocumentCapture<T> {

    private final Object issuer;
    private final DocumentToken token;
    private final T content;
    private final byte[] encoding;

    /**
     * Creates a capture for {@code issuer}; not public on purpose, so captures can only be obtained
     * from the session that issued them.
     */
    DocumentCapture(Object issuer, DocumentToken token, T content, byte[] encoding) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.token = Objects.requireNonNull(token, "token");
        this.content = Objects.requireNonNull(content, "content");
        Objects.requireNonNull(encoding, "encoding");
        this.encoding = Arrays.copyOf(encoding, encoding.length);
    }

    /** Returns the token the issuing session had when this capture was taken. */
    public DocumentToken token() {
        return token;
    }

    /** Returns the deeply immutable content of the captured revision. */
    public T content() {
        return content;
    }

    /** Returns a defensive copy of the encoding that was computed for the captured revision. */
    public byte[] encoding() {
        return Arrays.copyOf(encoding, encoding.length);
    }

    /**
     * Tells whether {@code candidate} is this capture's issuing identity.
     *
     * <p>
     * Package-private and one-way on purpose: it can only be asked, never read, so the identity
     * cannot be taken from a legitimate capture and reused to forge another one.
     * </p>
     */
    boolean issuedBy(Object candidate) {
        return issuer == candidate;
    }
}
