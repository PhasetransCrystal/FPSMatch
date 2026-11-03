package net.ptcrys.fpsmatch.editor.common.codec;

/**
 * Failure type of the strict JSON codec contract.
 *
 * <p>
 * Every failure is reported as this unchecked exception carrying a stable {@code code} and a
 * JSON-pointer-like {@code path}. The path is the empty string for the document root and otherwise
 * starts with {@code '/'}; escaping of individual path segments is the caller's responsibility.
 * </p>
 *
 * <p>
 * The message and the optional cause are preserved as supplied; this type never swallows or
 * rewrites them.
 * </p>
 */
public class JsonContractException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** The text is not lexically legal JSON. */
    static final String CODE_INVALID_JSON = "INVALID_JSON";

    /** A number literal is syntactically legal but outside the accepted numeric contract. */
    static final String CODE_INVALID_NUMBER = "INVALID_NUMBER";

    /** Unicode input is not representable under the contract (for example an unpaired surrogate). */
    static final String CODE_INVALID_UNICODE = "INVALID_UNICODE";

    /** The same key appears more than once in one object. */
    static final String CODE_DUPLICATE_KEY = "DUPLICATE_KEY";

    /** A documented limit of {@link JsonLimits} was exceeded. */
    static final String CODE_LIMIT_EXCEEDED = "LIMIT_EXCEEDED";

    private final String code;

    private final String path;

    /**
     * Creates a contract failure without a cause.
     *
     * @param code    one of the supported codes
     * @param path    empty for the document root, otherwise a string starting with {@code '/'}
     * @param message human readable detail, preserved verbatim
     * @throws IllegalArgumentException if {@code code} is null or unsupported, or if {@code path} is
     *                                  null or not a root/absolute JSON pointer
     */
    public JsonContractException(String code, String path, String message) {
        super(message);
        this.code = requireCode(code);
        this.path = requirePath(path);
    }

    /**
     * Creates a contract failure that keeps the underlying cause.
     *
     * @param code    one of the supported codes
     * @param path    empty for the document root, otherwise a string starting with {@code '/'}
     * @param message human readable detail, preserved verbatim
     * @param cause   underlying failure, preserved verbatim; may be null
     * @throws IllegalArgumentException if {@code code} is null or unsupported, or if {@code path} is
     *                                  null or not a root/absolute JSON pointer
     */
    public JsonContractException(String code, String path, String message, Throwable cause) {
        super(message, cause);
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
     * @return the JSON pointer path, empty for the document root, never null
     */
    public String path() {
        return this.path;
    }

    private static String requireCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("code must not be null");
        }
        switch (code) {
            case CODE_INVALID_JSON:
            case CODE_INVALID_NUMBER:
            case CODE_INVALID_UNICODE:
            case CODE_DUPLICATE_KEY:
            case CODE_LIMIT_EXCEEDED:
                return code;
            default:
                throw new IllegalArgumentException("unsupported code: " + code);
        }
    }

    private static String requirePath(String path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        if (!path.isEmpty() && path.charAt(0) != '/') {
            throw new IllegalArgumentException("path must be empty or start with '/': " + path);
        }
        return path;
    }
}
