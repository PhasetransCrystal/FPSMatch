package net.ptcrys.fpsmatch.editor.common.codec;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict, exact and bounded JSON freeze/canonical aggregate (R02.3, design revision R1.4).
 *
 * <p>
 * Two entry points, one behaviour: {@link #freeze(JsonElement)} snapshots an existing Gson tree
 * into an independent frozen tree, {@link #parse(String)} reads strict JSON text with a private
 * bounded cursor reader. Both produce a {@link FrozenJson} whose canonical text is unique for a
 * given value: object keys are ordered by {@link String#compareTo}, array order is kept, and numbers
 * use the exact {@link ExactJsonNumber} canonical spelling.
 * </p>
 *
 * <p>
 * All limits are aliased from {@link JsonLimits}, the single source of budget numbers. Two
 * budgets are enforced before anything is allocated: the node/depth budget and the predicted
 * canonical UTF-8 output budget. The predicted byte count accumulated while building a tree must
 * equal the byte count the writer actually produced, so an accounting mistake is an internal defect
 * rather than a silent budget extension.
 * </p>
 *
 * <p>
 * This class never uses a Gson reader for structure: Gson 2.10 rejects a legal 1024 character
 * number literal because of its internal number buffer, so the structural reader is implemented
 * here and only the strict lexical pre-scan plus {@link ExactJsonNumber} are reused.
 * </p>
 */
public final class ExactJson {

    /** Maximum UTF-8 byte size of the canonical output, aliased from {@link JsonLimits}. */
    public static final int MAX_UTF8_BYTES = JsonLimits.MAX_UTF8_BYTES;

    /** Maximum container nesting depth, aliased from {@link JsonLimits}. */
    public static final int MAX_DEPTH = JsonLimits.MAX_DEPTH;

    /** Maximum total number of nodes, aliased from {@link JsonLimits}. */
    public static final int MAX_NODES = JsonLimits.MAX_NODES;

    /** Maximum UTF-8 byte size of one decoded string value, aliased from {@link JsonLimits}. */
    public static final int MAX_STRING_UTF8_BYTES = JsonLimits.MAX_STRING_UTF8_BYTES;

    /** Maximum UTF-8 byte size of one decoded object key, aliased from {@link JsonLimits}. */
    public static final int MAX_KEY_UTF8_BYTES = JsonLimits.MAX_KEY_UTF8_BYTES;

    /** Maximum character length of one number literal, aliased from {@link JsonLimits}. */
    public static final int MAX_NUMBER_LITERAL_CHARS = JsonLimits.MAX_NUMBER_LITERAL_CHARS;

    /** Maximum accepted absolute decimal exponent, aliased from {@link JsonLimits}. */
    public static final int MAX_ABS_EXPONENT = JsonLimits.MAX_ABS_EXPONENT;

    private ExactJson() {}

    /**
     * Freezes an existing Gson tree into an independent snapshot.
     *
     * @param input element to snapshot; {@code null} is the same as JSON null
     * @return the frozen snapshot, never null
     * @throws JsonContractException with {@code INVALID_UNICODE}, {@code INVALID_NUMBER},
     *                               {@code INVALID_JSON} for an unsupported element, or {@code LIMIT_EXCEEDED} when a
     *                               budget is exceeded before the offending allocation
     */
    public static FrozenJson freeze(JsonElement input) {
        final BuildContext context = new BuildContext();
        final JsonElement tree = freezeValue(input, "", 0, context);
        return finish(tree, context);
    }

    /**
     * Reads strict JSON text into a frozen snapshot.
     *
     * @param strictJson document text; {@code null} is rejected as {@code INVALID_JSON}
     * @return the frozen snapshot, never null
     * @throws JsonContractException with {@code INVALID_JSON} for a lexical or structural failure,
     *                               {@code INVALID_UNICODE} for an unpaired surrogate, {@code INVALID_NUMBER} for an
     *                               unacceptable number, {@code DUPLICATE_KEY} for a repeated decoded key, and
     *                               {@code LIMIT_EXCEEDED} for a documented budget
     */
    public static FrozenJson parse(String strictJson) {
        StrictJsonLexical.validate(strictJson);
        final BuildContext context = new BuildContext();
        final CursorReader reader = new CursorReader(strictJson, context);
        return finish(reader.readDocument(), context);
    }

    /**
     * Produces the canonical text of an already frozen tree and verifies the byte accounting.
     *
     * @param tree    private tree built by {@link #freezeValue} or {@link CursorReader}
     * @param context build context that holds the predicted canonical byte count
     * @return the frozen public value
     */
    private static FrozenJson finish(JsonElement tree, BuildContext context) {
        final BoundedWriter writer = new BoundedWriter();
        writeValue(writer, tree, context);
        final String canonical = writer.text();
        final byte[] utf8 = canonical.getBytes(StandardCharsets.UTF_8);
        if (writer.byteCount() != context.expectedCanonicalUtf8() || utf8.length != writer.byteCount()) {
            throw new IllegalStateException("internal canonical byte accounting mismatch: predicted=" + context.expectedCanonicalUtf8() + " written=" + writer.byteCount() + " actual=" + utf8.length);
        }
        return new FrozenJson(tree, canonical, utf8);
    }

    // ------------------------------------------------------------------
    // build context: node/depth budget, predicted canonical bytes, numbers
    // ------------------------------------------------------------------

    /**
     * Per-document build state. It is never published: it only lives for the duration of one
     * {@code freeze}/{@code parse} call and holds the predicted canonical byte budget plus the
     * verified canonical spelling of every numeric primitive.
     */
    private static final class BuildContext {

        private int nodeCount;

        private long predictedCanonicalUtf8;

        /** Canonical spelling of each numeric primitive, keyed by identity of the private tree node. */
        private final Map<JsonPrimitive, String> canonicalNumbers = new IdentityHashMap<>();

        /**
         * Counts one new node and rejects depth/node violations before the node is allocated.
         *
         * @param depth depth of the node that is about to be created; root is 0
         * @param path  JSON pointer of that node, used for the failure report
         */
        void countNode(int depth, String path) {
            if (depth > JsonLimits.MAX_DEPTH) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        "nesting depth exceeds " + JsonLimits.MAX_DEPTH);
            }
            final int next = this.nodeCount + 1;
            if (next > JsonLimits.MAX_NODES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        "node count exceeds " + JsonLimits.MAX_NODES);
            }
            this.nodeCount = next;
        }

        /**
         * Reserves canonical output bytes before the caller allocates the matching content.
         *
         * @param bytes exact number of canonical UTF-8 bytes the reserved fragment will occupy
         * @param path  JSON pointer reported when the reservation breaks the output budget
         */
        void reserve(long bytes, String path) {
            if (bytes < 0) {
                throw new IllegalStateException("reserved byte count must not be negative");
            }
            final long next = this.predictedCanonicalUtf8 + bytes;
            if (next > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        "canonical output exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
            this.predictedCanonicalUtf8 = next;
        }

        long expectedCanonicalUtf8() {
            return this.predictedCanonicalUtf8;
        }

        /** Records the verified canonical spelling of a freshly created numeric primitive. */
        void rememberNumber(JsonPrimitive primitive, String canonical) {
            this.canonicalNumbers.put(primitive, canonical);
        }

        /** Returns the verified canonical spelling recorded for a numeric primitive. */
        String numberCanonical(JsonPrimitive primitive, String path) {
            final String canonical = this.canonicalNumbers.get(primitive);
            if (canonical == null) {
                throw new IllegalStateException("missing canonical number for numeric primitive at " + path);
            }
            return canonical;
        }
    }

    // ------------------------------------------------------------------
    // shared helpers: JSON pointer, escaped canonical bytes, UTF-8 size
    // ------------------------------------------------------------------

    /**
     * Builds one JSON pointer segment for an object member.
     *
     * @param key decoded member name
     * @return {@code "/"} plus the key with {@code ~} to {@code ~0} and {@code /} to {@code ~1}
     */
    private static String childPointer(String parent, String key) {
        final StringBuilder builder = new StringBuilder(parent.length() + key.length() + 1);
        builder.append(parent).append('/');
        appendPointerSegment(builder, key);
        return builder.toString();
    }

    /**
     * Builds the pointer segment of one array element index.
     *
     * @param index zero based element index
     * @return {@code parent} plus {@code "/"} and the decimal index
     */
    private static String indexPointer(String parent, int index) {
        return parent + '/' + index;
    }

    private static void appendPointerSegment(StringBuilder builder, String segment) {
        for (int i = 0; i < segment.length(); i++) {
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

    /**
     * Validates a decoded string for the contract and reports its budgets.
     *
     * <p>
     * Surrogate pairs must be well formed, the decoded UTF-8 size is checked against the given
     * limit, and the exact canonical escaped output size is accumulated so the caller can reserve it
     * before allocating anything.
     * </p>
     *
     * @param value        decoded text, never null
     * @param path         JSON pointer of this string, used for failures
     * @param decodedLimit {@code MAX_STRING_UTF8_BYTES} or {@code MAX_KEY_UTF8_BYTES}
     * @param what         short description used in the message
     * @return canonical escaped UTF-8 size of this string, including both quotes
     */
    private static long measureDecodedString(String value, String path, int decodedLimit, String what) {
        long decoded = 0L;
        long escaped = 2L;
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (c < 0x80) {
                decoded += 1L;
                escaped += escapedAsciiBytes(c);
            } else if (c < 0x800) {
                decoded += 2L;
                escaped += 2L;
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < length && Character.isLowSurrogate(value.charAt(i + 1))) {
                    decoded += 4L;
                    escaped += 4L;
                    i++;
                } else {
                    throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, path,
                            "unpaired high surrogate in " + what + " at index " + i);
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, path,
                        "unpaired low surrogate in " + what + " at index " + i);
            } else {
                decoded += 3L;
                escaped += 3L;
            }
            if (decoded > decodedLimit) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        what + " exceeds " + decodedLimit + " UTF-8 bytes");
            }
        }
        return escaped;
    }

    /** Canonical escaped size of one ASCII character, always at least one byte. */
    private static long escapedAsciiBytes(char c) {
        return switch (c) {
            case '"', '\\', '\b', '\t', '\n', '\f', '\r' -> 2L;
            default -> c < 0x20 ? 6L : 1L;
        };
    }

    private static long utf8LengthOfLiteral(String literal) {
        return literal.getBytes(StandardCharsets.UTF_8).length;
    }

    // ------------------------------------------------------------------
    // freeze: bounded snapshot of an existing tree, budget checked as it goes
    // ------------------------------------------------------------------

    /**
     * Copies one input element into the private frozen tree, charging the budgets of the node that
     * is about to be created.
     *
     * @param input   element to copy; {@code null} means JSON null
     * @param path    JSON pointer of this element
     * @param depth   depth of this element; the document root is 0
     * @param context build state carrying the node and predicted-output budgets
     * @return the independent copy, never null
     */
    private static JsonElement freezeValue(JsonElement input, String path, int depth, BuildContext context) {
        if (input == null || input.isJsonNull()) {
            context.countNode(depth, path);
            context.reserve(4L, path);
            return JsonNull.INSTANCE;
        }
        if (input instanceof JsonObject) {
            context.countNode(depth, path);
            context.reserve(2L, path);
            return freezeObject((JsonObject) input, path, depth, context);
        }
        if (input instanceof JsonArray) {
            context.countNode(depth, path);
            context.reserve(2L, path);
            return freezeArray((JsonArray) input, path, depth, context);
        }
        if (input instanceof JsonPrimitive) {
            context.countNode(depth, path);
            return freezePrimitive((JsonPrimitive) input, path, context);
        }
        throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, path,
                "unsupported JsonElement type: " + input.getClass().getName());
    }

    /**
     * Copies one primitive. Only the three JSON primitive kinds exist: numerics go through
     * {@link ExactJsonNumber}, booleans are recreated, strings are measured and copied verbatim.
     */
    private static JsonElement freezePrimitive(JsonPrimitive primitive, String path, BuildContext context) {
        if (primitive.isNumber()) {
            return freezeNumber(primitive, path, context);
        }
        if (primitive.isBoolean()) {
            final boolean value = primitive.getAsBoolean();
            context.reserve(value ? 4L : 5L, path);
            return new JsonPrimitive(value);
        }
        if (primitive.isString()) {
            final String value = primitive.getAsString();
            if (value == null) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, path,
                        "string primitive must not have a null value");
            }
            final long escapedBytes = measureDecodedString(value, path, JsonLimits.MAX_STRING_UTF8_BYTES, "string value");
            context.reserve(escapedBytes, path);
            return new JsonPrimitive(value);
        }
        throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, path,
                "unsupported JsonPrimitive content");
    }

    /**
     * Copies one numeric primitive as an immutable exact value.
     *
     * <p>
     * The original text is read exactly once, validated and normalized by
     * {@link ExactJsonNumber}, the canonical output budget is reserved, and only then is the
     * canonical {@link BigDecimal} and its primitive created. The verified canonical spelling is
     * remembered for the writer, so the canonical text is never re-parsed out of a mutable
     * {@code toString()}.
     * </p>
     */
    private static JsonPrimitive freezeNumber(JsonPrimitive primitive, String path, BuildContext context) {
        final String raw;
        try {
            raw = primitive.getAsString();
        } catch (JsonContractException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new JsonContractException(JsonContractException.CODE_INVALID_NUMBER, path,
                    "numeric primitive cannot be read as a JSON number literal", failure);
        }
        final String canonical = ExactJsonNumber.parse(raw, path).canonical();
        context.reserve(utf8LengthOfLiteral(canonical), path);
        final JsonPrimitive frozen = new JsonPrimitive(new BigDecimal(canonical));
        context.rememberNumber(frozen, canonical);
        return frozen;
    }

    /**
     * Copies an object member by member. Insertion order of the input is irrelevant because the
     * canonical writer sorts keys; only the byte budget matters here.
     */
    private static JsonObject freezeObject(JsonObject input, String path, int depth, BuildContext context) {
        final JsonObject frozen = new JsonObject();
        final int childDepth = depth + 1;
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
            final String key = entry.getKey();
            if (key == null) {
                throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, path,
                        "object key must not be null");
            }
            // The key is measured against the enclosing object pointer before any pointer segment is
            // built: building the segment escapes every character, so an unbounded key would be
            // copied (and possibly doubled, for '~' and '/') before its budget was ever checked. A
            // key that is not yet known to be legal has no usable identity, so its failure path stays
            // the enclosing object pointer and never embeds the key text.
            final long escapedKeyBytes = measureDecodedString(key, path, JsonLimits.MAX_KEY_UTF8_BYTES, "object key");
            final String keyPath = childPointer(path, key);
            if (!first) {
                context.reserve(1L, keyPath);
            }
            context.reserve(escapedKeyBytes + 1L, keyPath);
            final JsonElement value = freezeValue(entry.getValue(), keyPath, childDepth, context);
            frozen.add(key, value);
            first = false;
        }
        return frozen;
    }

    /**
     * Copies an array element by element, keeping the original order.
     */
    private static JsonArray freezeArray(JsonArray input, String path, int depth, BuildContext context) {
        final JsonArray frozen = new JsonArray();
        final int childDepth = depth + 1;
        final int size = input.size();
        for (int index = 0; index < size; index++) {
            final String childPath = indexPointer(path, index);
            if (index > 0) {
                context.reserve(1L, childPath);
            }
            final JsonElement value = freezeValue(input.get(index), childPath, childDepth, context);
            frozen.add(value);
        }
        return frozen;
    }

    // ------------------------------------------------------------------
    // private strict cursor reader (never a Gson reader)
    // ------------------------------------------------------------------

    /**
     * Bounded recursive-descent reader over the strict JSON grammar.
     *
     * <p>
     * State is one immutable {@link String} plus an {@code int} cursor. Every depth check happens
     * before a recursive call or a container allocation, so a deeply nested document fails with
     * {@code LIMIT_EXCEEDED} instead of a {@code StackOverflowError}. Structural errors are reported
     * directly as {@link JsonContractException} without a fabricated {@code IOException} cause. The
     * cursor is always bounds checked before {@code charAt}, so a truncated document is a contract
     * failure and not a {@code StringIndexOutOfBoundsException}.
     * </p>
     */
    private static final class CursorReader {

        private static final char[] WHITESPACE = { ' ', '\t', '\r', '\n' };

        private final String input;

        private final int length;

        private final BuildContext context;

        private int cursor;

        CursorReader(String input, BuildContext context) {
            this.input = input;
            this.length = input.length();
            this.context = context;
        }

        /**
         * Reads the single root value and requires that only JSON whitespace follows it.
         *
         * @return the parsed private tree root
         */
        JsonElement readDocument() {
            final JsonElement root = readValue("", 0);
            skipWhitespace();
            if (this.cursor != this.length) {
                throw invalidJson("unexpected trailing content at index " + this.cursor, "");
            }
            return root;
        }

        /**
         * Reads one value: charges the node budget first, then dispatches on the current character.
         *
         * @param path  JSON pointer of this value
         * @param depth depth of this value; the document root is 0
         * @return the parsed node
         */
        private JsonElement readValue(String path, int depth) {
            this.context.countNode(depth, path);
            skipWhitespace();
            if (this.cursor >= this.length) {
                throw invalidJson("unexpected end of input, a value was expected", path);
            }
            final char c = this.input.charAt(this.cursor);
            return switch (c) {
                case '{' -> readObject(path, depth);
                case '[' -> readArray(path, depth);
                case '"' -> readStringValue(path);
                case 't' -> readLiteral("true", true, path);
                case 'f' -> readLiteral("false", false, path);
                case 'n' -> readLiteral("null", null, path);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield readNumber(path);
                    }
                    throw invalidJson("unexpected character '" + c + "' at index " + this.cursor, path);
                }
            };
        }

        private void skipWhitespace() {
            while (this.cursor < this.length) {
                final char skipped = this.input.charAt(this.cursor);
                boolean whitespace = false;
                for (char candidate : WHITESPACE) {
                    if (skipped == candidate) {
                        whitespace = true;
                        break;
                    }
                }
                if (!whitespace) {
                    return;
                }
                this.cursor++;
            }
        }

        private void expect(char expected, String path, String what) {
            if (this.cursor >= this.length || this.input.charAt(this.cursor) != expected) {
                throw invalidJson("expected " + what + " at index " + this.cursor, path);
            }
            this.cursor++;
        }

        /**
         * Reads a {@code true}/{@code false}/{@code null} literal exactly; any other spelling is a
         * structural failure, so adjacent values and trailing tokens are rejected by the caller.
         */
        private JsonElement readLiteral(String spelling, Boolean value, String path) {
            if (this.cursor + spelling.length() > this.length) {
                throw invalidJson("truncated literal at index " + this.cursor, path);
            }
            for (int i = 0; i < spelling.length(); i++) {
                if (this.input.charAt(this.cursor + i) != spelling.charAt(i)) {
                    throw invalidJson("invalid literal at index " + this.cursor, path);
                }
            }
            this.cursor += spelling.length();
            if (value == null) {
                this.context.reserve(4L, path);
                return JsonNull.INSTANCE;
            }
            this.context.reserve(value ? 4L : 5L, path);
            return new JsonPrimitive(value);
        }

        private JsonContractException invalidJson(String message, String path) {
            return new JsonContractException(JsonContractException.CODE_INVALID_JSON, path, message);
        }

        /**
         * Reads one JSON string value, reserving the escaped canonical output size before the decoded
         * text is published.
         */
        private JsonPrimitive readStringValue(String path) {
            final DecodedString decoded = readString(path, JsonLimits.MAX_STRING_UTF8_BYTES, "string value");
            this.context.reserve(decoded.escapedBytes(), path);
            return new JsonPrimitive(decoded.value());
        }

        /**
         * Reads one quoted JSON string.
         *
         * <p>
         * Escapes are decoded strictly: only the eight short escapes and {@code \\uXXXX} are legal,
         * an escape sequence never terminates the string, and a surrogate pair is appended only once
         * both halves have been seen. A pending high surrogate followed by anything other than a low
         * surrogate, and a dangling low surrogate, fail with {@code INVALID_UNICODE}.
         * </p>
         *
         * @param path         pointer used for failures that concern the whole string
         * @param decodedLimit {@code MAX_STRING_UTF8_BYTES} or {@code MAX_KEY_UTF8_BYTES}
         * @param what         short description used in messages
         * @return the decoded text and its canonical escaped UTF-8 size, including both quotes
         */
        private DecodedString readString(String path, int decodedLimit, String what) {
            expect('"', path, "'\"'");
            final StringBuilder text = new StringBuilder(Math.min(this.length - this.cursor, 32));
            long decoded = 0L;
            long escaped = 2L;
            boolean pendingHighSurrogate = false;
            char pendingHigh = 0;
            while (true) {
                if (this.cursor >= this.length) {
                    throw invalidJson("unterminated " + what, path);
                }
                final char c = this.input.charAt(this.cursor);
                if (c == '"') {
                    if (pendingHighSurrogate) {
                        throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, path,
                                "unpaired high surrogate at the end of " + what);
                    }
                    this.cursor++;
                    if (decoded > decodedLimit) {
                        throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                                what + " exceeds " + decodedLimit + " UTF-8 bytes");
                    }
                    return new DecodedString(text.toString(), escaped);
                }
                final char decodedUnit;
                if (c == '\\') {
                    if (this.cursor + 1 >= this.length) {
                        throw invalidJson("dangling escape at index " + this.cursor, path);
                    }
                    this.cursor++;
                    decodedUnit = readEscape(path);
                } else {
                    if (c <= 0x1F) {
                        throw invalidJson("unescaped control character at index " + this.cursor, path);
                    }
                    decodedUnit = c;
                    this.cursor++;
                }
                if (pendingHighSurrogate) {
                    if (!Character.isLowSurrogate(decodedUnit)) {
                        throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, path,
                                "high surrogate in " + what + " is not followed by a low surrogate");
                    }
                    // The pair is charged before either half reaches the text: a pair that would pass
                    // the budget must be observed first, and nothing is appended on the failing path.
                    requireWithinLimit(decoded + 4L, decodedLimit, path, what);
                    text.append(pendingHigh).append(decodedUnit);
                    pendingHighSurrogate = false;
                    decoded += 4L;
                    escaped += 4L;
                } else if (Character.isHighSurrogate(decodedUnit)) {
                    // A high half has no identity of its own, so it is only staged here, never
                    // appended: it becomes text exclusively together with a validated low half.
                    pendingHigh = decodedUnit;
                    pendingHighSurrogate = true;
                    continue;
                } else if (Character.isLowSurrogate(decodedUnit)) {
                    throw new JsonContractException(JsonContractException.CODE_INVALID_UNICODE, path,
                            "unpaired low surrogate in " + what);
                } else {
                    final long widthUnit;
                    if (decodedUnit < 0x80) {
                        widthUnit = 1L;
                    } else if (decodedUnit < 0x800) {
                        widthUnit = 2L;
                    } else {
                        widthUnit = 3L;
                    }
                    requireWithinLimit(decoded + widthUnit, decodedLimit, path, what);
                    text.append(decodedUnit);
                    decoded += widthUnit;
                    escaped += widthUnit == 1L ? escapedAsciiBytes(decodedUnit) : widthUnit;
                }
                if (decoded > decodedLimit) {
                    throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                            what + " exceeds " + decodedLimit + " UTF-8 bytes");
                }
            }
        }

        /** Rejects a decoded string whose UTF-8 size would pass the given budget. */
        private void requireWithinLimit(long decodedBytes, int decodedLimit, String path, String what) {
            if (decodedBytes > decodedLimit) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        what + " exceeds " + decodedLimit + " UTF-8 bytes");
            }
        }

        /**
         * Decodes one escape sequence whose backslash has already been consumed.
         *
         * @return the decoded UTF-16 unit; a {@code \\uD800}-style escape may yield a lone high
         *         surrogate, which {@link #readString} pairs with the following unit
         */
        private char readEscape(String path) {
            final char escape = this.input.charAt(this.cursor);
            this.cursor++;
            return switch (escape) {
                case '"' -> '"';
                case '\\' -> '\\';
                case '/' -> '/';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'u' -> readUnicodeEscape(path);
                default -> throw invalidJson("invalid escape sequence at index " + (this.cursor - 2), path);
            };
        }

        /** Decodes the four hex digits of a {@code \\u} escape that has already been consumed. */
        private char readUnicodeEscape(String path) {
            if (this.cursor + 4 > this.length) {
                throw invalidJson("incomplete \\u escape at index " + (this.cursor - 2), path);
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 4) | hexValue(this.input.charAt(this.cursor + i), path);
            }
            this.cursor += 4;
            return (char) value;
        }

        private static int hexValue(char c, String path) {
            if (c >= '0' && c <= '9') {
                return c - '0';
            }
            if (c >= 'a' && c <= 'f') {
                return c - 'a' + 10;
            }
            if (c >= 'A' && c <= 'F') {
                return c - 'A' + 10;
            }
            throw new JsonContractException(JsonContractException.CODE_INVALID_JSON, path,
                    "invalid hex digit '" + c + "' in \\u escape");
        }

        /**
         * Reads one number token.
         *
         * <p>
         * The token is delimited exactly like the frozen lexical pre-scan delimits it: whitespace,
         * a structural character, a double quote or the end of input. The length guard runs before
         * any substring is allocated, so an oversized run of digits never becomes a large temporary
         * string, and the token is handed to {@link ExactJsonNumber} unmoved: no {@code double}
         * conversion happens anywhere in this reader.
         * </p>
         */
        private JsonElement readNumber(String path) {
            final int start = this.cursor;
            while (this.cursor < this.length && !endsNumber(this.input.charAt(this.cursor))) {
                this.cursor++;
            }
            final int tokenLength = this.cursor - start;
            if (tokenLength > JsonLimits.MAX_NUMBER_LITERAL_CHARS) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, path,
                        "number literal of " + tokenLength + " characters exceeds " + JsonLimits.MAX_NUMBER_LITERAL_CHARS);
            }
            final String raw = this.input.substring(start, this.cursor);
            final String canonical = ExactJsonNumber.parse(raw, path).canonical();
            this.context.reserve(utf8LengthOfLiteral(canonical), path);
            final JsonPrimitive frozen = new JsonPrimitive(new BigDecimal(canonical));
            this.context.rememberNumber(frozen, canonical);
            return frozen;
        }

        private static boolean endsNumber(char c) {
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '"') {
                return true;
            }
            return c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',';
        }

        /** One decoded string plus its canonical escaped UTF-8 size, including both quotes. */
        private static final class DecodedString {

            private final String value;

            private final long escapedBytes;

            DecodedString(String value, long escapedBytes) {
                this.value = value;
                this.escapedBytes = escapedBytes;
            }

            String value() {
                return this.value;
            }

            long escapedBytes() {
                return this.escapedBytes;
            }
        }

        /**
         * Reads one object: no trailing comma, no missing colon, no value without a quoted key, and
         * one decoded-key set for exactly this object.
         */
        private JsonObject readObject(String path, int depth) {
            final int childDepth = depth + 1;
            // The two braces are reserved before the container is allocated, so an over budget object
            // is rejected without first materialising it.
            this.context.reserve(2L, path);
            expect('{', path, "'{'");
            final JsonObject frozen = new JsonObject();
            final Set<String> keys = new HashSet<>();
            skipWhitespace();
            if (this.cursor < this.length && this.input.charAt(this.cursor) == '}') {
                this.cursor++;
                return frozen;
            }
            boolean first = true;
            while (true) {
                skipWhitespace();
                if (this.cursor >= this.length || this.input.charAt(this.cursor) != '"') {
                    throw invalidJson("object key must be a quoted string at index " + this.cursor, path);
                }
                // A key that cannot be decoded yet has no complete identity, so its failure path is
                // the enclosing object pointer.
                final DecodedString key = readString(path, JsonLimits.MAX_KEY_UTF8_BYTES, "object key");
                final String keyPath = childPointer(path, key.value());
                if (!keys.add(key.value())) {
                    throw new JsonContractException(JsonContractException.CODE_DUPLICATE_KEY, keyPath,
                            "duplicate object key");
                }
                if (!first) {
                    this.context.reserve(1L, keyPath);
                }
                this.context.reserve(key.escapedBytes() + 1L, keyPath);
                skipWhitespace();
                expect(':', keyPath, "':'");
                final JsonElement value = readValue(keyPath, childDepth);
                frozen.add(key.value(), value);
                first = false;
                skipWhitespace();
                if (this.cursor >= this.length) {
                    throw invalidJson("unterminated object", path);
                }
                final char next = this.input.charAt(this.cursor);
                if (next == ',') {
                    this.cursor++;
                    continue;
                }
                if (next == '}') {
                    this.cursor++;
                    return frozen;
                }
                throw invalidJson("expected ',' or '}' at index " + this.cursor, path);
            }
        }

        /**
         * Reads one array: element order is kept and a trailing comma is never repaired into a null.
         */
        private JsonArray readArray(String path, int depth) {
            final int childDepth = depth + 1;
            // The two brackets are reserved before the container is allocated, for the same reason as
            // in readObject.
            this.context.reserve(2L, path);
            expect('[', path, "'['");
            final JsonArray frozen = new JsonArray();
            skipWhitespace();
            if (this.cursor < this.length && this.input.charAt(this.cursor) == ']') {
                this.cursor++;
                return frozen;
            }
            int index = 0;
            while (true) {
                final String childPath = indexPointer(path, index);
                if (index > 0) {
                    this.context.reserve(1L, childPath);
                }
                frozen.add(readValue(childPath, childDepth));
                index++;
                skipWhitespace();
                if (this.cursor >= this.length) {
                    throw invalidJson("unterminated array", path);
                }
                final char next = this.input.charAt(this.cursor);
                if (next == ',') {
                    this.cursor++;
                    continue;
                }
                if (next == ']') {
                    this.cursor++;
                    return frozen;
                }
                throw invalidJson("expected ',' or ']' at index " + this.cursor, path);
            }
        }
    }

    // ------------------------------------------------------------------
    // canonical writer: one StringBuilder, byte budget checked per fragment
    // ------------------------------------------------------------------

    /**
     * Appends the canonical text of one frozen node.
     *
     * <p>
     * Object keys are emitted in {@link String#compareTo} order, array order is preserved, numbers
     * use the canonical spelling recorded while the node was created — never a re-parse of a mutable
     * {@code BigDecimal.toString()} — and strings are escaped into canonical form. The method
     * appends; it never re-reads the tree into an intermediate structure.
     * </p>
     *
     * @param writer  bounded sink that counts exactly the UTF-8 bytes it appends
     * @param node    frozen node to emit
     * @param context build context holding the canonical number spellings
     */
    private static void writeValue(BoundedWriter writer, JsonElement node, BuildContext context) {
        if (node == null || node.isJsonNull()) {
            writer.append("null");
            return;
        }
        if (node instanceof JsonObject) {
            writeObject(writer, (JsonObject) node, context);
            return;
        }
        if (node instanceof JsonArray array) {
            writer.append("[");
            final int size = array.size();
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    writer.append(",");
                }
                writeValue(writer, array.get(i), context);
            }
            writer.append("]");
            return;
        }
        if (node instanceof JsonPrimitive primitive) {
            if (primitive.isNumber()) {
                writer.append(context.numberCanonical(primitive, ""));
            } else if (primitive.isBoolean()) {
                writer.append(primitive.getAsBoolean() ? "true" : "false");
            } else {
                writeEscapedString(writer, primitive.getAsString());
            }
            return;
        }
        throw new IllegalStateException("unsupported frozen node type: " + node.getClass().getName());
    }

    /** Emits one object with its keys sorted by {@link String#compareTo}. */
    private static void writeObject(BoundedWriter writer, JsonObject object, BuildContext context) {
        writer.append("{");
        final List<String> keys = new ArrayList<>(object.keySet());
        keys.sort(null);
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                writer.append(",");
            }
            final String key = keys.get(i);
            writeEscapedString(writer, key);
            writer.append(":");
            writeValue(writer, object.get(key), context);
        }
        writer.append("}");
    }

    /**
     * Emits one string in canonical escaped form: the two mandatory escapes with short forms, other
     * control characters as lowercase {@code \\u00xx}, {@code /} unescaped and legal non-ASCII kept
     * verbatim without any normalization. Surrogate halves are appended together so the byte count
     * stays exact.
     */
    private static void writeEscapedString(BoundedWriter writer, String value) {
        writer.append("\"");
        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char c = value.charAt(i);
            if (c < 0x80) {
                switch (c) {
                    case '"':
                        writer.append("\\\"");
                        break;
                    case '\\':
                        writer.append("\\\\");
                        break;
                    case '\b':
                        writer.append("\\b");
                        break;
                    case '\t':
                        writer.append("\\t");
                        break;
                    case '\n':
                        writer.append("\\n");
                        break;
                    case '\f':
                        writer.append("\\f");
                        break;
                    case '\r':
                        writer.append("\\r");
                        break;
                    default:
                        if (c < 0x20) {
                            writer.append("\\u00");
                            writer.append(HEX_DIGITS.charAt((c >> 4) & 0xF));
                            writer.append(HEX_DIGITS.charAt(c & 0xF));
                        } else {
                            writer.append(c);
                        }
                        break;
                }
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 >= length || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalStateException(
                            "frozen string contains an unpaired high surrogate at index " + i);
                }
                writer.appendSurrogatePair(c, value.charAt(i + 1));
                i++;
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalStateException(
                        "frozen string contains an unpaired low surrogate at index " + i);
            } else {
                writer.appendBmpChar(c);
            }
        }
        writer.append("\"");
    }

    private static final String HEX_DIGITS = "0123456789abcdef";

    /**
     * Output sink that enforces the canonical UTF-8 byte budget for every fragment before it is
     * appended, using one {@link StringBuilder} and never a temporary byte array per fragment.
     */
    private static final class BoundedWriter {

        private final StringBuilder sink = new StringBuilder(256);

        private long byteCount;

        /**
         * Appends one ASCII-only fragment, charging exactly one byte per character.
         *
         * <p>
         * Every syntax fragment, escape sequence and canonical number this writer emits is ASCII,
         * so a non-ASCII character here is an internal defect rather than a caller error. Requiring
         * ASCII makes the charge exact without encoding the fragment into a temporary byte array.
         * </p>
         *
         * @param fragment text to append, never null and never non-ASCII
         */
        void append(String fragment) {
            final int length = fragment.length();
            for (int i = 0; i < length; i++) {
                if (fragment.charAt(i) >= 0x80) {
                    throw new IllegalStateException(
                            "writer fragments must be ASCII, got U+" + Integer.toHexString(fragment.charAt(i)) + " in \"" + fragment + "\"");
                }
            }
            final long next = this.byteCount + length;
            if (next > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "canonical output exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
            this.byteCount = next;
            this.sink.append(fragment);
        }

        /** Appends one ASCII character that is known to occupy a single byte. */
        void append(char ascii) {
            if (ascii >= 0x80) {
                throw new IllegalStateException("append(char) only accepts ASCII, got U+" + Integer.toHexString(ascii));
            }
            final long next = this.byteCount + 1L;
            if (next > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "canonical output exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
            this.byteCount = next;
            this.sink.append(ascii);
        }

        /**
         * Appends one well formed surrogate pair as one four byte UTF-8 sequence.
         *
         * <p>
         * The pair is charged and appended together: appending the halves separately would encode
         * the isolated high surrogate as a single byte and break the exact byte accounting.
         * </p>
         */
        void appendSurrogatePair(char high, char low) {
            final long next = this.byteCount + 4L;
            if (next > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "canonical output exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
            this.byteCount = next;
            this.sink.append(high).append(low);
        }

        /**
         * Appends one non-surrogate character of the Basic Multilingual Plane and charges its exact
         * UTF-8 width: two bytes below {@code U+0800}, three bytes above it.
         */
        void appendBmpChar(char c) {
            if (c < 0x80 || Character.isSurrogate(c)) {
                throw new IllegalStateException(
                        "appendBmpChar only accepts non-surrogate BMP characters, got U+" + Integer.toHexString(c));
            }
            final long width = c < 0x800 ? 2L : 3L;
            final long next = this.byteCount + width;
            if (next > JsonLimits.MAX_UTF8_BYTES) {
                throw new JsonContractException(JsonContractException.CODE_LIMIT_EXCEEDED, "",
                        "canonical output exceeds " + JsonLimits.MAX_UTF8_BYTES + " UTF-8 bytes");
            }
            this.byteCount = next;
            this.sink.append(c);
        }

        long byteCount() {
            return this.byteCount;
        }

        String text() {
            return this.sink.toString();
        }
    }

    // ------------------------------------------------------------------
    // published value
    // ------------------------------------------------------------------

    /**
     * Immutable frozen JSON value with unique canonical text.
     *
     * <p>
     * The instance owns its private tree: the constructor is private, the tree is never handed
     * out by reference, and {@link #toJson()} returns a fresh copy each call. Equality and hashing
     * are defined by the canonical text, so {@code 1}, {@code 1.0} and {@code 1e0} are equal and so
     * are {@code 0}, {@code -0} and {@code 0e3}.
     * </p>
     */
    public static final class FrozenJson {

        private final JsonElement tree;

        private final String canonical;

        private final byte[] canonicalUtf8;

        private FrozenJson(JsonElement tree, String canonical, byte[] canonicalUtf8) {
            this.tree = tree;
            this.canonical = canonical;
            this.canonicalUtf8 = canonicalUtf8;
        }

        /**
         * Returns an independent deep copy of the frozen tree.
         *
         * @return a new tree the caller may mutate without affecting this value
         */
        public JsonElement toJson() {
            return this.tree.deepCopy();
        }

        /** Returns the canonical text: sorted keys, stable array order, exact canonical numbers. */
        public String canonical() {
            return this.canonical;
        }

        /**
         * Returns a fresh copy of the canonical UTF-8 bytes.
         *
         * @return canonical UTF-8 bytes, never the internal array
         */
        public byte[] canonicalUtf8() {
            final byte[] copy = new byte[this.canonicalUtf8.length];
            System.arraycopy(this.canonicalUtf8, 0, copy, 0, this.canonicalUtf8.length);
            return copy;
        }

        /** Returns the exact canonical UTF-8 byte size, matching {@link #canonicalUtf8()}. */
        public int utf8Size() {
            return this.canonicalUtf8.length;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FrozenJson)) {
                return false;
            }
            return this.canonical.equals(((FrozenJson) other).canonical);
        }

        @Override
        public int hashCode() {
            return this.canonical.hashCode();
        }

        @Override
        public String toString() {
            return this.canonical;
        }
    }
}
