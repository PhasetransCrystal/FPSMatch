package net.ptcrys.fpsmatch.editor.common.codec;

import net.ptcrys.fpsmatch.editor.common.model.FieldPath;

import java.util.regex.Pattern;

/**
 * Failure type of the structured schema contract (R06.0, design revision R1.1).
 *
 * <p>
 * Every schema failure is reported as this unchecked exception carrying a stable {@code code}, the
 * exact {@link FieldPath} of the offending field, a human readable technical message and an optional
 * real cause. The message is preserved verbatim and is never parsed to recover the code or the path:
 * machines locate a failure through {@code code} and {@code path} only.
 * </p>
 *
 * <p>
 * The message is internal technical text. It is not a localizable, player facing product message;
 * the display layer maps {@code code} to a namespaced business problem and to localized text, so this
 * type adds no natural language keys of its own.
 * </p>
 *
 * <p>
 * Codes are the fixed names of this contract:
 * {@link #CODE_MISSING_FIELD}, {@link #CODE_WRONG_TYPE}, {@link #CODE_INVALID_VALUE},
 * {@link #CODE_UNSUPPORTED_SCHEMA}, {@link #CODE_RESERVED_COLLISION} and the codes already frozen by
 * the strict JSON codec, {@link #CODE_INVALID_JSON}, {@link #CODE_INVALID_NUMBER},
 * {@link #CODE_INVALID_UNICODE}, {@link #CODE_DUPLICATE_KEY} and {@link #CODE_LIMIT_EXCEEDED}. This
 * class defines no enumeration and no problem DTO of its own.
 * </p>
 */
public final class SchemaException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** A required field is absent. It is never conflated with an explicitly present JSON null. */
    static final String CODE_MISSING_FIELD = "MISSING_FIELD";

    /** A field is present but holds a JSON type the schema does not accept. */
    static final String CODE_WRONG_TYPE = "WRONG_TYPE";

    /** A field has the right JSON type but a value outside the contract. */
    static final String CODE_INVALID_VALUE = "INVALID_VALUE";

    /** The schema version or schema shape is not supported by this codec. */
    static final String CODE_UNSUPPORTED_SCHEMA = "UNSUPPORTED_SCHEMA";

    /** A reserved name collides with a definition owned field or member. */
    static final String CODE_RESERVED_COLLISION = "RESERVED_COLLISION";

    /** The text is not lexically legal JSON. Frozen by the strict JSON codec. */
    static final String CODE_INVALID_JSON = "INVALID_JSON";

    /** A number literal is syntactically legal but outside the accepted numeric contract. */
    static final String CODE_INVALID_NUMBER = "INVALID_NUMBER";

    /** Unicode input is not representable under the contract, for example an unpaired surrogate. */
    static final String CODE_INVALID_UNICODE = "INVALID_UNICODE";

    /** The same key appears more than once in one object. */
    static final String CODE_DUPLICATE_KEY = "DUPLICATE_KEY";

    /** A documented limit of {@link JsonLimits} was exceeded. */
    static final String CODE_LIMIT_EXCEEDED = "LIMIT_EXCEEDED";

    /**
     * Shape of every accepted code: an upper case letter followed by at most 63 upper case, digit or underscore
     * characters.
     */
    private static final Pattern CODE_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final String code;

    private final FieldPath path;

    /**
     * Creates a schema failure without a cause.
     *
     * @param code    stable failure code matching {@code [A-Z][A-Z0-9_]{0,63}}
     * @param path    exact field path of the failure, never null
     * @param message internal technical detail, preserved verbatim; may be empty but not null
     * @throws IllegalArgumentException if {@code code}, {@code path} or {@code message} is null, or
     *                                  if {@code code} does not match the code shape. A null message is rejected like
     *                                  every
     *                                  other null argument here: a failure report must never carry a fabricated
     *                                  message.
     */
    public SchemaException(String code, FieldPath path, String message) {
        super(requireMessage(message));
        this.code = requireCode(code);
        this.path = requirePath(path);
    }

    /**
     * Creates a schema failure that keeps the underlying cause.
     *
     * <p>
     * A null cause makes this constructor exactly equivalent to the three argument form.
     * </p>
     *
     * @param code    stable failure code matching {@code [A-Z][A-Z0-9_]{0,63}}
     * @param path    exact field path of the failure, never null
     * @param message internal technical detail, preserved verbatim; may be empty but not null
     * @param cause   underlying failure, preserved as the same object; may be null
     * @throws IllegalArgumentException if {@code code}, {@code path} or {@code message} is null, or
     *                                  if {@code code} does not match the code shape. A null message is rejected like
     *                                  every
     *                                  other null argument here; a null {@code cause} is accepted.
     */
    public SchemaException(String code, FieldPath path, String message, Throwable cause) {
        // super(message, cause) keeps the cause identity: Throwable(String, Throwable) stores the
        // reference, so getCause() returns the very object the caller passed.
        super(requireMessage(message), cause);
        this.code = requireCode(code);
        this.path = requirePath(path);
    }

    /**
     * @return the stable failure code, never null
     */
    public String code() {
        return this.code;
    }

    /**
     * @return the exact field path of the failure, never null
     */
    public FieldPath path() {
        return this.path;
    }

    /**
     * Validates one code against the fixed shape and rejects null.
     *
     * @param code candidate code
     * @return the same code
     */
    private static String requireCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new IllegalArgumentException("code must match [A-Z][A-Z0-9_]{0,63}: " + code);
        }
        return code;
    }

    /**
     * Rejects a null path. The path is stored as the caller's immutable {@link FieldPath} reference
     * and is never copied, rebuilt or defaulted to the root.
     *
     * @param path candidate path
     * @return the same path
     */
    private static FieldPath requirePath(FieldPath path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        return path;
    }

    /**
     * Rejects a null message with the same {@code IllegalArgumentException} family every other null
     * argument of this class uses, so one null policy covers the whole constructor surface. An empty
     * message stays legal: it is a real, distinguishable message, not a missing one.
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
