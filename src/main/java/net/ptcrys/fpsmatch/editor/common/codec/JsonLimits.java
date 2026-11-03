package net.ptcrys.fpsmatch.editor.common.codec;

/**
 * Sole source of the strict JSON limits.
 *
 * <p>
 * Later parts of the strict codec (number handling, freeze, reader) reference these constants
 * instead of copying the numbers. Values are not configurable at runtime by design.
 * </p>
 */
final class JsonLimits {

    /** Maximum UTF-8 byte size of the original document text. */
    static final int MAX_UTF8_BYTES = 8388608;

    /** Maximum container nesting depth. */
    static final int MAX_DEPTH = 64;

    /** Maximum total number of nodes in one document. */
    static final int MAX_NODES = 500000;

    /** Maximum UTF-8 byte size of one decoded string value. */
    static final int MAX_STRING_UTF8_BYTES = 65536;

    /** Maximum UTF-8 byte size of one decoded object key. */
    static final int MAX_KEY_UTF8_BYTES = 1024;

    /** Maximum character length of one number literal in the original text. */
    static final int MAX_NUMBER_LITERAL_CHARS = 1024;

    /** Maximum accepted absolute decimal exponent of a number. */
    static final int MAX_ABS_EXPONENT = 1000000;

    private JsonLimits() {}
}
