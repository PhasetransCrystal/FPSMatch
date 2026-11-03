package net.ptcrys.fpsmatch.editor.common.codec;

import net.ptcrys.fpsmatch.editor.common.model.FieldPath;

/**
 * Failure type of the persistent envelope contract (R06.2a1, design revision R1).
 *
 * <p>
 * A failure is reported as a stable {@code code}, the {@link FieldPath} of the <em>owner</em> of the
 * offending value, the {@link FieldPath} of the offending value <em>relative</em> to that owner, a
 * human readable technical message and an optional real cause. The owner and the relative path are two
 * independent identities and are both stored exactly as supplied: this type never concatenates them,
 * never prepends the owner to the relative path, and never reinterprets one as the other. A machine
 * locates a failure through {@code code} plus the owner/relative pair only, never by parsing the
 * message.
 * </p>
 *
 * <p>
 * The two paths carry different budgets. The owner is deliberately shallow: it is a legal
 * {@link FieldPath} with at most {@link #OWNER_MAX_SEGMENTS} segments (the deepest current owner,
 * {@code receipts/index/fingerprintInputs/target}, uses exactly four, and the root owner with no
 * segment is legal). The relative path is a legal {@link FieldPath} with at most
 * {@link FieldPath#MAX_SEGMENTS} segments, where a known inner schema failure may legitimately use
 * five. Those are path segment budgets, not physical nesting depths: the physical document depth of an
 * envelope is never used as a segment allowance here, and the owner budget is never widened to match
 * the relative budget.
 * </p>
 *
 * <p>
 * The message is internal technical text, is preserved verbatim (it may be empty, may hold any
 * character and may describe any technical detail) and is never re-validated as a JSON key, never
 * trimmed and never localized. It is not a player facing product message: the future display layer maps
 * {@code code} to a trusted translation key, so this type adds no language resource and no client
 * dependency of its own.
 * </p>
 */
public final class EnvelopeException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** Minimum accepted code length. */
    private static final int CODE_MIN_LENGTH = 1;

    /** Maximum accepted code length. */
    private static final int CODE_MAX_LENGTH = 64;

    /**
     * Maximum accepted owner depth in segments. The owner is the container that owns the offending
     * value, so it is deliberately shallow and is not allowed to grow to the full relative path budget.
     */
    private static final int OWNER_MAX_SEGMENTS = 4;

    private final String code;

    private final FieldPath owner;

    private final FieldPath relativePath;

    /**
     * Creates an envelope failure without a cause.
     *
     * <p>
     * This form delegates to the five argument constructor with a null cause, so both forms share
     * exactly one validation path and one Throwable initialisation path. The underlying
     * {@code Throwable(String, Throwable)} initialises its cause slot even when the cause is null, so
     * the value built here reports {@code getCause() == null} and later rejects {@code initCause} with
     * an {@link IllegalStateException}, which is exactly the behaviour of the five argument form with
     * a null cause. The two forms are therefore interchangeable in both directions and neither can be
     * given a cause after construction.
     * </p>
     *
     * @param code         stable failure code matching {@code [A-Z][A-Z0-9_]{0,63}}
     * @param owner        owner of the offending value, never null and at most four segments
     * @param relativePath path of the offending value relative to {@code owner}, never null
     * @param message      internal technical detail, preserved verbatim; may be empty but not null
     * @throws IllegalArgumentException if any argument is null, if {@code code} does not match the
     *                                  code shape, or if {@code owner} holds more than four segments
     */
    public EnvelopeException(String code, FieldPath owner, FieldPath relativePath, String message) {
        this(code, owner, relativePath, message, null);
    }

    /**
     * Creates an envelope failure that keeps the underlying cause.
     *
     * <p>
     * A null cause makes this constructor equivalent to the four argument form, which delegates
     * here: the cause is handed to {@code super(message, cause)} as the same object, so it is never
     * parsed, never wrapped and never fabricated. A null cause leaves {@code getCause()} reading null,
     * and because {@code Throwable(String, Throwable)} initialises the cause slot either way, both
     * forms refuse a later {@code initCause} with an {@link IllegalStateException}.
     * </p>
     *
     * @param code         stable failure code matching {@code [A-Z][A-Z0-9_]{0,63}}
     * @param owner        owner of the offending value, never null and at most four segments
     * @param relativePath path of the offending value relative to {@code owner}, never null
     * @param message      internal technical detail, preserved verbatim; may be empty but not null
     * @param cause        underlying failure, preserved as the same object; may be null
     * @throws IllegalArgumentException if {@code code}, {@code owner}, {@code relativePath} or
     *                                  {@code message} is null, if {@code code} does not match the code shape, or if
     *                                  {@code owner} holds more than four segments. A null {@code cause} is accepted.
     */
    public EnvelopeException(String code, FieldPath owner, FieldPath relativePath, String message,
                             Throwable cause) {
        // super(message, cause) keeps the cause identity: Throwable(String, Throwable) stores the
        // reference, so getCause() returns the very object the caller passed and nothing is wrapped.
        super(requireMessage(message), cause);
        this.code = requireCode(code);
        this.owner = requireOwner(owner);
        this.relativePath = requirePath(relativePath, "relativePath");
    }

    /**
     * @return the stable failure code, never null
     */
    public String code() {
        return this.code;
    }

    /**
     * @return the owner of the offending value as supplied, never null
     */
    public FieldPath owner() {
        return this.owner;
    }

    /**
     * @return the path of the offending value relative to {@link #owner()}, never null
     */
    public FieldPath relativePath() {
        return this.relativePath;
    }

    /**
     * Validates one code against the fixed shape.
     *
     * <p>
     * The length is checked first, so a code that is empty or too long is rejected before it is
     * scanned, and then the alphabet is checked explicitly character by character: the first character
     * must be an upper case ASCII letter and every following character an upper case ASCII letter, a
     * digit or an underscore. The check is deliberately not a {@code String} method with wider
     * semantics, so characters outside ASCII can never be accepted by accident, and the code is stored
     * verbatim: it is never trimmed, never normalized, and never recovered from a message or from any
     * {@code toString} of another object. A future upper case code is therefore storable without
     * extending any closed enumeration here.
     * </p>
     *
     * @param code candidate code
     * @return the same code
     * @throws IllegalArgumentException if {@code code} is null or does not match
     *                                  {@code [A-Z][A-Z0-9_]{0,63}}
     */
    private static String requireCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        final int length = code.length();
        if (length < CODE_MIN_LENGTH || length > CODE_MAX_LENGTH) {
            throw new IllegalArgumentException("code must be " + CODE_MIN_LENGTH + ".." + CODE_MAX_LENGTH + " characters, got " + length);
        }
        for (int i = 0; i < length; i++) {
            final char c = code.charAt(i);
            if (i == 0) {
                if (c < 'A' || c > 'Z') {
                    throw new IllegalArgumentException(
                            "code must start with an upper case ASCII letter: " + code);
                }
            } else if ((c < 'A' || c > 'Z') && (c < '0' || c > '9') && c != '_') {
                throw new IllegalArgumentException("code must match [A-Z][A-Z0-9_]{0,63}: " + code);
            }
        }
        return code;
    }

    /**
     * Rejects a null path and stores the caller's immutable {@link FieldPath} reference.
     *
     * <p>
     * The path is never copied, rebuilt, defaulted to the root or merged with the other path.
     * </p>
     *
     * @param path candidate path
     * @param name argument name, used only for the failure detail
     * @return the same path
     * @throws IllegalArgumentException if {@code path} is null
     */
    private static FieldPath requirePath(FieldPath path, String name) {
        if (path == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return path;
    }

    /**
     * Rejects a null owner and an owner deeper than {@link #OWNER_MAX_SEGMENTS} segments, then stores
     * the caller's immutable {@link FieldPath} reference unchanged.
     *
     * <p>
     * The owner budget is checked here because it is strictly tighter than the budget of
     * {@link FieldPath} itself: a legal five segment path is a perfectly valid relative path, but it is
     * never a valid owner. The reference is stored as supplied, so this check narrows what may be
     * recorded and never widens or rewrites the path.
     * </p>
     *
     * @param owner candidate owner path
     * @return the same owner path
     * @throws IllegalArgumentException if {@code owner} is null or holds more than
     *                                  {@link #OWNER_MAX_SEGMENTS} segments
     */
    private static FieldPath requireOwner(FieldPath owner) {
        if (owner == null) {
            throw new IllegalArgumentException("owner must not be null");
        }
        final int segments = owner.segments().size();
        if (segments > OWNER_MAX_SEGMENTS) {
            throw new IllegalArgumentException("owner must hold at most " + OWNER_MAX_SEGMENTS + " segments, got " + segments);
        }
        return owner;
    }

    /**
     * Rejects a null message with the same {@code IllegalArgumentException} family every other null
     * argument of this class uses. An empty message stays legal: it is a real, distinguishable
     * message, not a missing one.
     *
     * @param message candidate message
     * @return the same message
     * @throws IllegalArgumentException if {@code message} is null
     */
    private static String requireMessage(String message) {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        return message;
    }
}
