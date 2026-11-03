package net.ptcrys.fpsmatch.editor.common.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Full aggregate contract tests for the R02.3 {@code ExactJson} frozen JSON codec.
 *
 * <p><strong>What this class is.</strong> These are the formal, focused JUnit tests for
 * {@link ExactJson}: the aggregate behaviour of parse + freeze + canonical output. They exercise the
 * public aggregation surface, not the already frozen helper layers. A green run here is the
 * aggregate evidence R02.3 asks for; it is not by itself the complete P02, which only the primary
 * agent can grant after its own independent verification.</p>
 *
 * <p><strong>Independent oracle.</strong> {@link CountingModel} is a small, separately written JSON
 * model: it is built directly from Java values (never through {@code ExactJson}), and it computes
 * the canonical text and the canonical UTF-8 byte size with its own escaping and number rules. The
 * generated-tree tests compare {@code ExactJson} against that model, so "parse(canonical()) equals
 * canonical()" is <em>not</em> the only oracle. Round-trip closure is asserted additionally, never
 * instead.</p>
 *
 * <p><strong>Heap discipline.</strong> Every large fixture is built, used and released one at a
 * time; no test keeps several maximal trees alive at once, and the class holds no static fixtures.
 * Large fixtures are still built from {@code String}/array copies on the test side, so the suite's own
 * floor is above the product's: the formal full run is given {@code -Xmx384m}, and no output here
 * claims that the whole class runs at {@code -Xmx64m}. The product's 64 MiB behaviour is evidenced
 * separately by the primary agent's own independent process.</p>
 *
 * <p><strong>Assertions.</strong> Every rejection asserts the exception type, the stable
 * {@code code} and the JSON {@code path}. No test passes merely because "some exception" was thrown,
 * and no test asserts a whole localized message sentence.</p>
 */
class ExactJsonContractTest {

    private static final int MAX_UTF8_BYTES = 8388608;

    private static final int MAX_DEPTH = 64;

    private static final int MAX_NODES = 500000;

    private static final int MAX_STRING_UTF8_BYTES = 65536;

    private static final int MAX_KEY_UTF8_BYTES = 1024;

    private static final int MAX_NUMBER_LITERAL_CHARS = 1024;

    private static final int MAX_ABS_EXPONENT = 1000000;

    // ------------------------------------------------------------------
    // shared assertion helpers
    // ------------------------------------------------------------------

    /** Asserts the public constants still alias the single frozen limit source. */
    @Test
    @DisplayName("public constants alias the frozen JsonLimits values")
    void publicConstantsAliasJsonLimits() {
        assertEquals(JsonLimits.MAX_UTF8_BYTES, ExactJson.MAX_UTF8_BYTES);
        assertEquals(JsonLimits.MAX_DEPTH, ExactJson.MAX_DEPTH);
        assertEquals(JsonLimits.MAX_NODES, ExactJson.MAX_NODES);
        assertEquals(JsonLimits.MAX_STRING_UTF8_BYTES, ExactJson.MAX_STRING_UTF8_BYTES);
        assertEquals(JsonLimits.MAX_KEY_UTF8_BYTES, ExactJson.MAX_KEY_UTF8_BYTES);
        assertEquals(JsonLimits.MAX_NUMBER_LITERAL_CHARS, ExactJson.MAX_NUMBER_LITERAL_CHARS);
        assertEquals(JsonLimits.MAX_ABS_EXPONENT, ExactJson.MAX_ABS_EXPONENT);
        assertEquals(8388608, ExactJson.MAX_UTF8_BYTES);
        assertEquals(64, ExactJson.MAX_DEPTH);
        assertEquals(500000, ExactJson.MAX_NODES);
        assertEquals(65536, ExactJson.MAX_STRING_UTF8_BYTES);
        assertEquals(1024, ExactJson.MAX_KEY_UTF8_BYTES);
        assertEquals(1024, ExactJson.MAX_NUMBER_LITERAL_CHARS);
        assertEquals(1000000, ExactJson.MAX_ABS_EXPONENT);
        assertEquals(MAX_UTF8_BYTES, MAX_UTF8_BYTES);
        assertEquals(MAX_DEPTH, MAX_DEPTH);
        assertEquals(MAX_NODES, MAX_NODES);
        assertEquals(MAX_STRING_UTF8_BYTES, MAX_STRING_UTF8_BYTES);
        assertEquals(MAX_KEY_UTF8_BYTES, MAX_KEY_UTF8_BYTES);
        assertEquals(MAX_NUMBER_LITERAL_CHARS, MAX_NUMBER_LITERAL_CHARS);
        assertEquals(MAX_ABS_EXPONENT, MAX_ABS_EXPONENT);
    }

    /** Runs a failing operation and asserts type, stable code and JSON pointer path. */
    private static JsonContractException failsWith(String code, String path, Runnable operation) {
        final JsonContractException failure =
                assertThrows(JsonContractException.class, operation::run, "expected JsonContractException");
        assertEquals(code, failure.code(), "stable failure code");
        assertEquals(path, failure.path(), "JSON pointer path");
        assertInstanceOf(IllegalArgumentException.class, failure, "JsonContractException is an IAE");
        return failure;
    }

    /**
     * Asserts the only message property the contract fixes for an oversized value: no copy of that
     * value inflates the message. Wording is deliberately not asserted, because the frozen contract
     * fixes the code and the pointer, not the English text.
     */
    private static void assertBoundedMessage(JsonContractException failure, int valueLength) {
        final String message = String.valueOf(failure.getMessage());
        assertTrue(message.length() < Math.max(64, valueLength / 2),
                "the message must not carry a copy of the oversized value, length=" + message.length());
    }

    private static JsonContractException parseFails(String code, String path, String json) {
        return failsWith(code, path, () -> ExactJson.parse(json));
    }

    private static JsonContractException freezeFails(String code, String path, JsonElement input) {
        return failsWith(code, path, () -> ExactJson.freeze(input));
    }

    /** The canonical text of a parse, asserted to be self-consistent in bytes. */
    private static String canonicalOf(String json) {
        final ExactJson.FrozenJson frozen = ExactJson.parse(json);
        final byte[] bytes = frozen.canonicalUtf8();
        assertEquals(frozen.canonical(), new String(bytes, StandardCharsets.UTF_8), "canonical vs bytes");
        assertEquals(bytes.length, frozen.utf8Size(), "declared utf8Size vs actual bytes");
        assertEquals(frozen.canonical(), frozen.toString(), "toString is canonical");
        assertEquals(frozen.canonical(), ExactJson.parse(frozen.canonical()).canonical(), "closure");
        return frozen.canonical();
    }

    private static String canonicalOf(JsonElement input) {
        final ExactJson.FrozenJson frozen = ExactJson.freeze(input);
        final byte[] bytes = frozen.canonicalUtf8();
        assertEquals(frozen.canonical(), new String(bytes, StandardCharsets.UTF_8), "canonical vs bytes");
        assertEquals(bytes.length, frozen.utf8Size(), "declared utf8Size vs actual bytes");
        assertEquals(frozen.canonical(), ExactJson.parse(frozen.canonical()).canonical(), "closure");
        return frozen.canonical();
    }

    private static JsonObject obj(Object... keyValuePairs) {        final JsonObject object = new JsonObject();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            object.add((String) keyValuePairs[i], (JsonElement) keyValuePairs[i + 1]);
        }
        return object;
    }

    private static JsonArray arr(JsonElement... elements) {
        final JsonArray array = new JsonArray();
        for (JsonElement element : elements) {
            array.add(element);
        }
        return array;
    }

    private static JsonPrimitive text(String value) {
        return new JsonPrimitive(value);
    }

    /**
     * A {@link Number} whose textual form changes as a side effect of being read.
     *
     * <p>It exists to prove that {@code freeze} snapshots a mutable number at freeze time instead of
     * holding a live reference and reading it later. The first read of {@link #toString()} rewrites the
     * backing value, so any deferred read would produce a different canonical form.</p>
     */
    private static final class MutableNumber extends Number {

        private static final long serialVersionUID = 1L;

        private final int original;

        private int reads;

        private MutableNumber(int value) {
            this.original = value;
        }

        @Override
        public String toString() {
            this.reads++;
            // Only the first read is truthful; later reads report a different value.
            return this.reads == 1 ? Integer.toString(this.original) : "999999";
        }

        @Override
        public int intValue() {
            return this.reads == 0 ? this.original : 999999;
        }

        @Override
        public long longValue() {
            return intValue();
        }

        @Override
        public float floatValue() {
            return intValue();
        }

        @Override
        public double doubleValue() {
            return intValue();
        }
    }

    /**
     * A genuinely mutable {@link Number} whose backing value can be changed by the test after freezing.
     *
     * <p>Unlike {@link MutableNumber}, which only changes what it reports after the first read, this one
     * is mutated explicitly by the caller, so it proves that {@code freeze} took a value snapshot rather
     * than retaining a live reference that would silently follow later writes.</p>
     */
    private static final class MutatingNumber extends Number {

        private static final long serialVersionUID = 1L;

        private long value;

        private MutatingNumber(long value) {
            this.value = value;
        }

        private void set(long next) {
            this.value = next;
        }

        @Override
        public int intValue() {
            return (int) this.value;
        }

        @Override
        public long longValue() {
            return this.value;
        }

        @Override
        public float floatValue() {
            return this.value;
        }

        @Override
        public double doubleValue() {
            return this.value;
        }

        @Override
        public String toString() {
            return Long.toString(this.value);
        }
    }

    // ------------------------------------------------------------------
    // independent model oracle
    // ------------------------------------------------------------------

    /**
     * A deliberately separate JSON model used as an independent oracle.
     *
     * <p>It is constructed only from Java values, never from {@code ExactJson} output. {@link #copy}
     * builds a detached copy so that comparing two models never compares aliased nodes, and
     * {@link ModelWriter} renders canonical text plus its exact UTF-8 byte size with rules written
     * independently of the production writer.</p>
     */
    private static final class CountingModel {

        private final Object value;

        private CountingModel(Object value) {
            this.value = value;
        }

        static CountingModel of(Object value) {
            return new CountingModel(value);
        }

        Object value() {
            return this.value;
        }

        /** Structural copy: containers are rebuilt, scalars are immutable. */
        CountingModel copy() {
            return new CountingModel(copyValue(this.value));
        }

        private static Object copyValue(Object node) {
            if (node instanceof Map<?, ?>) {
                final Map<String, Object> source = asMap(node);
                final Map<String, Object> target = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : source.entrySet()) {
                    target.put(entry.getKey(), copyValue(entry.getValue()));
                }
                return target;
            }
            if (node instanceof List<?>) {
                final List<Object> target = new ArrayList<>();
                for (Object element : asList(node)) {
                    target.add(copyValue(element));
                }
                return target;
            }
            return node;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> asMap(Object node) {
            return (Map<String, Object>) node;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object node) {
            return (List<Object>) node;
        }

        /**
         * Renders canonical text and counts UTF-8 bytes at the same time.
         *
         * <p>The escaping table and the number spelling are implemented here from the R02 contract
         * text, so a shared bug in the production writer cannot hide behind the oracle.</p>
         */
        static final class ModelWriter {

            private final StringBuilder text = new StringBuilder();

            private long bytes;

            String text() {
                return this.text.toString();
            }

            long bytes() {
                return this.bytes;
            }

            void write(CountingModel model) {
                writeValue(model.value());
            }

            private void writeValue(Object node) {
                if (node == null) {
                    appendAscii("null");
                    return;
                }
                if (node instanceof Map<?, ?>) {
                    writeObject(asMap(node));
                    return;
                }
                if (node instanceof List<?>) {
                    writeArray(asList(node));
                    return;
                }
                if (node instanceof Boolean) {
                    appendAscii(((Boolean) node) ? "true" : "false");
                    return;
                }
                if (node instanceof Number) {
                    appendAscii(canonicalNumber((Number) node));
                    return;
                }
                writeString((String) node);
            }

            private void writeObject(Map<String, Object> object) {
                appendAscii("{");
                // The contract sorts keys by Java UTF-16 compareTo, not by a locale collator.
                final TreeMap<String, Object> sorted = new TreeMap<>(object);
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        appendAscii(",");
                    }
                    writeString(entry.getKey());
                    appendAscii(":");
                    writeValue(entry.getValue());
                    first = false;
                }
                appendAscii("}");
            }

            private void writeArray(List<Object> array) {
                appendAscii("[");
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) {
                        appendAscii(",");
                    }
                    writeValue(array.get(i));
                }
                appendAscii("]");
            }

            /** Canonical JSON escaping, computed independently of the production writer. */
            private void writeString(String value) {
                appendAscii("\"");
                for (int i = 0; i < value.length(); i++) {
                    final char c = value.charAt(i);
                    switch (c) {
                        case '"':
                            appendAscii("\\\"");
                            break;
                        case '\\':
                            appendAscii("\\\\");
                            break;
                        case '\b':
                            appendAscii("\\b");
                            break;
                        case '\t':
                            appendAscii("\\t");
                            break;
                        case '\n':
                            appendAscii("\\n");
                            break;
                        case '\f':
                            appendAscii("\\f");
                            break;
                        case '\r':
                            appendAscii("\\r");
                            break;
                        default:
                            if (c < 0x20) {
                                appendAscii(String.format("\\u%04x", (int) c));
                            } else {
                                appendChar(c);
                            }
                            break;
                    }
                }
                appendAscii("\"");
            }

            private void appendAscii(String ascii) {
                this.text.append(ascii);
                this.bytes += ascii.length();
            }

            /**
             * Counts one UTF-16 unit exactly as UTF-8 would encode it.
             *
             * <p>A well formed surrogate pair encodes as 4 bytes in total, not 3+3: the lead unit is
             * counted as 0 here and the trailing unit is counted as 4, so the pair sums to 4. Lone
             * surrogates never occur in this generator's output.</p>
             */
            private void appendChar(char c) {
                this.text.append(c);
                if (c < 0x80) {
                    this.bytes += 1;
                } else if (c < 0x800) {
                    this.bytes += 2;
                } else if (Character.isHighSurrogate(c)) {
                    this.bytes += 0;
                } else if (Character.isLowSurrogate(c)) {
                    this.bytes += 4;
                } else {
                    this.bytes += 3;
                }
            }
        }

        /**
         * Canonical number spelling written from the R02 contract: zero becomes {@code 0}, everything
         * else is {@code stripTrailingZeros().toString()}, never a plain-string expansion.
         */
        static String canonicalNumber(Number number) {
            final BigDecimal value;
            if (number instanceof BigDecimal) {
                value = (BigDecimal) number;
            } else if (number instanceof Integer || number instanceof Long) {
                value = BigDecimal.valueOf(number.longValue());
            } else {
                value = new BigDecimal(number.toString());
            }
            if (value.compareTo(BigDecimal.ZERO) == 0) {
                return "0";
            }
            return value.stripTrailingZeros().toString();
        }
    }

    // ------------------------------------------------------------------
    // independent short-tree generator
    // ------------------------------------------------------------------

    /** One generated legal document: source text, expected canonical text and expected byte size. */
    private static final class GeneratedTree {

        /** The independent typed model the tree was generated from; never derived from production. */
        final CountingModel model;

        final String json;

        final String canonical;

        final long canonicalBytes;

        GeneratedTree(CountingModel model, String json, String canonical, long canonicalBytes) {
            this.model = model;
            this.json = json;
            this.canonical = canonical;
            this.canonicalBytes = canonicalBytes;
        }

        /**
         * Independent structural signature: the type of every node, every key, and every array
         * position, derived only from the model. Distinct signatures mean structurally different
         * trees, so deduplicating on this keeps genuinely different graphs, and it never consults a
         * production result.
         */
        String signature() {
            final StringBuilder text = new StringBuilder();
            appendSignature(text, this.model.value());
            return text.toString();
        }

        private static void appendSignature(StringBuilder text, Object node) {
            if (node == null) {
                text.append('n');
                return;
            }
            if (node instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> object = (Map<String, Object>) node;
                text.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : object.entrySet()) {
                    if (!first) {
                        text.append(',');
                    }
                    // Key identity matters, and so does position among siblings for arrays.
                    text.append(entry.getKey().length()).append(':').append(entry.getKey());
                    appendSignature(text, entry.getValue());
                    first = false;
                }
                text.append('}');
                return;
            }
            if (node instanceof List<?>) {
                text.append('[');
                for (Object element : (List<?>) node) {
                    appendSignature(text, element);
                }
                text.append(']');
                return;
            }
            if (node instanceof Boolean) {
                text.append(((Boolean) node) ? 'T' : 'F');
                return;
            }
            if (node instanceof Number) {
                // Type marker plus value: the number 1 and the text "1" must never share a signature.
                text.append('#').append(CountingModel.canonicalNumber((Number) node));
                return;
            }
            text.append('"').append(node);
        }
    }

    /**
     * Builds legal short documents from independently chosen Java values, then renders the expected
     * canonical text and byte size with the model.
     *
     * <p>The source text is rendered by {@link #sourceTextOf} with deliberately <em>non</em>-canonical
     * spelling — redundant spaces and tabs, {@code 1.500}-style numbers, {@code \\u} escapes for
     * characters the canonical form writes raw, and object keys in a scrambled order. So a test that
     * passes proves the codec really parsed and re-canonicalized the document; it cannot pass by
     * echoing the input back.</p>
     */
    private static final class TreeGenerator {

        private final Random random;

        TreeGenerator(long seed) {
            this.random = new Random(seed);
        }

        GeneratedTree next() {
            final Map<String, Object> root = new LinkedHashMap<>();
            final int members = 1 + this.random.nextInt(4);
            final Set<String> used = new HashSet<>();
            for (int i = 0; i < members; i++) {
                final String key = uniqueKey(used);
                root.put(key, value(0));
            }
            final CountingModel model = CountingModel.of(root);
            return new GeneratedTree(model, sourceTextOf(model), canonicalTextOf(model),
                    canonicalBytesOf(model));
        }

        /**
         * Renders valid JSON for the model using non-canonical but legal spelling: extra whitespace,
         * numbers written with trailing zeros, {@code \\u}-escaped characters, and keys emitted in an
         * order that is generally not sorted.
         */
        static String sourceTextOf(CountingModel model) {
            final StringBuilder text = new StringBuilder();
            appendSource(text, model.value(), 0);
            return text.toString();
        }

        private static void appendSource(StringBuilder text, Object node, int depth) {
            if (node == null) {
                text.append("null");
                return;
            }
            if (node instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> object = (Map<String, Object>) node;
                if (object.isEmpty()) {
                    text.append("{ }");
                    return;
                }
                text.append("{");
                boolean first = true;
                // Deliberately scrambled order: canonical output must sort this back.
                final List<String> keys = new ArrayList<>(object.keySet());
                java.util.Collections.reverse(keys);
                for (String key : keys) {
                    if (!first) {
                        text.append(depth % 2 == 0 ? ", " : " ,\t");
                    }
                    text.append(escapedSourceString(key, true));
                    text.append(depth % 2 == 0 ? ": " : " : ");
                    appendSource(text, object.get(key), depth + 1);
                    first = false;
                }
                text.append("}");
                return;
            }
            if (node instanceof List<?>) {
                @SuppressWarnings("unchecked")
                final List<Object> array = (List<Object>) node;
                text.append("[");
                for (int i = 0; i < array.size(); i++) {
                    if (i > 0) {
                        text.append(depth % 2 == 0 ? ", " : " , ");
                    }
                    appendSource(text, array.get(i), depth + 1);
                }
                text.append("]");
                return;
            }
            if (node instanceof Boolean) {
                text.append(((Boolean) node) ? "true" : "false");
                return;
            }
            if (node instanceof Number) {
                text.append(sourceNumber((Number) node));
                return;
            }
            text.append(escapedSourceString((String) node, false));
        }

        /** Non-canonical spellings: trailing zeros and an explicit exponent survive to the parser. */
        private static String sourceNumber(Number number) {
            if (number instanceof BigDecimal) {
                final String plain = ((BigDecimal) number).toPlainString();
                // 1e3 arrives as "1000" above; write a form with redundant zeros so canonical differs.
                return plain.indexOf('.') < 0 ? plain + ".00" : plain + "0";
            }
            return number.toString() + ".0";
        }

        /**
         * Escapes a string for the source text. When {@code forceUnicodeEscapes} is set, ordinary
         * characters are written as {@code \\uXXXX}, which the canonical form never does.
         */
        private static String escapedSourceString(String value, boolean forceUnicodeEscapes) {
            final StringBuilder text = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                switch (c) {
                    case '"':
                        text.append("\\\"");
                        break;
                    case '\\':
                        text.append("\\\\");
                        break;
                    case '\n':
                        text.append("\\n");
                        break;
                    case '\t':
                        text.append("\\t");
                        break;
                    case '\r':
                        text.append("\\r");
                        break;
                    default:
                        if (c < 0x20) {
                            text.append(String.format("\\u%04x", (int) c));
                        } else if (forceUnicodeEscapes) {
                            // Legal but non-canonical spelling: the canonical form keeps most
                            // characters raw, so this proves the parser decoded the escapes.
                            text.append(String.format("\\u%04x", (int) c));
                        } else {
                            text.append(c);
                        }
                        break;
                }
            }
            return text.append("\"").toString();
        }

        /** Keys are legal, unique, and vary in length and escaping pressure. */
        private String uniqueKey(Set<String> used) {
            while (true) {
                final String candidate = keyText();
                if (used.add(candidate)) {
                    return candidate;
                }
            }
        }

        private String keyText() {
            switch (this.random.nextInt(6)) {
                case 0:
                    return "";
                case 1:
                    return "k" + this.random.nextInt(100);
                case 2:
                    return "~" + this.random.nextInt(10);
                case 3:
                    return "a/b" + this.random.nextInt(10);
                case 4:
                    return "键" + this.random.nextInt(10);
                default:
                    return "q\"" + this.random.nextInt(10);
            }
        }

        private Object value(int depth) {
            final int pick = this.random.nextInt(depth >= 2 ? 8 : 10);
            switch (pick) {
                case 0:
                    return null;
                case 1:
                    return Boolean.TRUE;
                case 2:
                    return Boolean.FALSE;
                case 3:
                    return scalarNumber();
                case 4:
                    return scalarString();
                case 5: {
                    final Map<String, Object> nested = new LinkedHashMap<>();
                    final Set<String> used = new HashSet<>();
                    final int members = this.random.nextInt(3);
                    for (int i = 0; i < members; i++) {
                        nested.put(uniqueKey(used), value(depth + 1));
                    }
                    return nested;
                }
                case 6: {
                    final List<Object> list = new ArrayList<>();
                    final int size = this.random.nextInt(3);
                    for (int i = 0; i < size; i++) {
                        list.add(value(depth + 1));
                    }
                    return list;
                }
                case 7:
                    return "";
                case 8:
                    return nestedEscapes();
                default:
                    return new BigDecimal(bigNumberText());
            }
        }

        private Object nestedEscapes() {
            switch (this.random.nextInt(4)) {
                case 0:
                    return "\uD83D\uDE00 emoji";
                case 1:
                    return "tab\tnew\nline\r\"quote\"\\slash/";
                case 2:
                    return "ctrl\u0001\u001f";
                default:
                    return "中文字符串";
            }
        }

        private Number scalarNumber() {
            switch (this.random.nextInt(5)) {
                case 0:
                    return 0;
                case 1:
                    return this.random.nextInt(1000);
                case 2:
                    return new BigDecimal("1.500");
                case 3:
                    return new BigDecimal("1e3");
                default:
                    return new BigDecimal("-2.50");
            }
        }

        private String scalarString() {
            switch (this.random.nextInt(5)) {
                case 0:
                    return "";
                case 1:
                    return "plain";
                case 2:
                    return "quote\"and\\slash";
                case 3:
                    return "换行\n制表\t";
                default:
                    return "\uD83D\uDE00";
            }
        }

        private String bigNumberText() {
            final StringBuilder digits = new StringBuilder();
            digits.append(1 + this.random.nextInt(9));
            for (int i = 0; i < 40; i++) {
                digits.append(this.random.nextInt(10));
            }
            return digits.toString();
        }

        static String canonicalTextOf(CountingModel model) {
            final CountingModel.ModelWriter writer = new CountingModel.ModelWriter();
            writer.write(model);
            return writer.text();
        }

        static long canonicalBytesOf(CountingModel model) {
            final CountingModel.ModelWriter writer = new CountingModel.ModelWriter();
            writer.write(model);
            return writer.bytes();
        }
    }

    // ==================================================================
    // 1. independent model oracle over generated legal short trees
    // ==================================================================

    @Nested
    @DisplayName("generated legal short trees match an independent model oracle")
    class GeneratedTrees {

        /**
         * Exactly 1000 structurally distinct legal trees. Each tree keeps the independent typed model
         * it was generated from; the raw document and the expected canonical text and byte size are
         * rendered by that model's own writer. The production value is then checked once per type,
         * per key, per array position and per number against that same model — canonical text is only
         * one of several views, never the oracle.
         */
        @Test
        @DisplayName("exactly 1000 distinct trees match the independent typed model in full")
        void thousandDistinctTrees() {
            final TreeGenerator generator = new TreeGenerator(20261014L);
            final Set<String> signatures = new LinkedHashSet<>();
            final List<GeneratedTree> trees = new ArrayList<>();
            // Draw until 1000 structurally different graphs exist; the loop is bounded so a broken
            // generator fails loudly instead of spinning.
            for (int attempts = 0; attempts < 200000 && trees.size() < 1000; attempts++) {
                final GeneratedTree candidate = generator.next();
                if (signatures.add(candidate.signature())) {
                    trees.add(candidate);
                }
            }
            assertEquals(1000, trees.size(),
                    "expected exactly 1000 structurally distinct generated trees, got " + trees.size());

            final Set<String> distinctCanonical = new HashSet<>();
            for (int i = 0; i < trees.size(); i++) {
                final GeneratedTree tree = trees.get(i);
                distinctCanonical.add(tree.canonical);

                // (a) the model's precomputed expected values, computed before production ran.
                final ExactJson.FrozenJson frozen = ExactJson.parse(tree.json);
                assertEquals(tree.canonical, frozen.canonical(), "canonical text of tree #" + i);
                assertEquals(tree.canonicalBytes, frozen.utf8Size(),
                        "canonical UTF-8 byte count of tree #" + i);
                assertEquals(tree.canonicalBytes, frozen.canonicalUtf8().length,
                        "actual canonical bytes of tree #" + i);

                // (b) the detached tree, checked node by node against the model: types, keys, array
                // positions and numbers. The expected side is the generator's own model, so no second
                // parse can launder a production bug into the expectation.
                assertMatchesModel(tree.model.value(), frozen.toJson(), "tree #" + i);

                // (b2) canonical key order, which toJson() does not promise, is asserted on the
                // canonical text through a model-driven rebuild: the expected text is regenerated from
                // the model's own sorted walk and the production text must match it byte for byte.
                assertEquals(expectedCanonical(tree.model), frozen.canonical(),
                        "canonical rebuild of tree #" + i);

                // (c) closure: canonical text is itself legal input with the same model.
                final ExactJson.FrozenJson reparsed = ExactJson.parse(tree.canonical);
                assertMatchesModel(tree.model.value(), reparsed.toJson(), "closure of tree #" + i);
                assertTrue(frozen.equals(reparsed), "closure equality of tree #" + i);
                assertEquals(frozen.hashCode(), reparsed.hashCode(), "hashCode agreement of tree #" + i);
            }
            assertEquals(1000, distinctCanonical.size(),
                    "distinct canonical texts must be 1000 for 1000 distinct trees");
        }

        /**
         * Rebuilds the expected canonical text from the model alone, ordering object keys by the
         * contract's canonical rule (UTF-16 code unit order, which is what the writer uses). This is a
         * second, independent rendering path used to check the canonical key order that the detached
         * {@code toJson()} tree does not carry.
         */
        private String expectedCanonical(CountingModel model) {
            final StringBuilder text = new StringBuilder();
            appendCanonical(text, model.value());
            return text.toString();
        }

        private void appendCanonical(StringBuilder text, Object node) {
            if (node == null) {
                text.append("null");
                return;
            }
            if (node instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                final Map<String, Object> object = (Map<String, Object>) node;
                text.append('{');
                boolean first = true;
                for (Map.Entry<String, Object> entry : new TreeMap<>(object).entrySet()) {
                    if (!first) {
                        text.append(',');
                    }
                    appendCanonicalString(text, entry.getKey());
                    text.append(':');
                    appendCanonical(text, entry.getValue());
                    first = false;
                }
                text.append('}');
                return;
            }
            if (node instanceof List<?>) {
                text.append('[');
                boolean first = true;
                for (Object element : (List<?>) node) {
                    if (!first) {
                        text.append(',');
                    }
                    appendCanonical(text, element);
                    first = false;
                }
                text.append(']');
                return;
            }
            if (node instanceof Boolean || node instanceof Number) {
                text.append(node instanceof Number
                        ? CountingModel.canonicalNumber((Number) node) : node.toString());
                return;
            }
            appendCanonicalString(text, (String) node);
        }

        /** Canonical string spelling: only {@code " \\ } and C0 controls are escaped. */
        private void appendCanonicalString(StringBuilder text, String value) {
            text.append('"');
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                switch (c) {
                    case '"':
                        text.append("\\\"");
                        break;
                    case '\\':
                        text.append("\\\\");
                        break;
                    case '\b':
                        text.append("\\b");
                        break;
                    case '\f':
                        text.append("\\f");
                        break;
                    case '\n':
                        text.append("\\n");
                        break;
                    case '\r':
                        text.append("\\r");
                        break;
                    case '\t':
                        text.append("\\t");
                        break;
                    default:
                        if (c < 0x20) {
                            text.append(String.format("\\u%04x", (int) c));
                        } else {
                            text.append(c);
                        }
                        break;
                }
            }
            text.append('"');
        }

        /**
         * Checks one production value against one independently generated model, recursively: JSON
         * node kind, object keys, array positions, and numbers by numeric value. A JSON number and the
         * JSON string with the same digits are different node kinds here and can never satisfy each
         * other.
         */
        private void assertMatchesModel(Object model, JsonElement actual, String where) {
            if (model == null) {
                assertTrue(actual.isJsonNull(), where + ": expected JSON null");
                return;
            }
            if (model instanceof Map<?, ?>) {
                assertTrue(actual.isJsonObject(), where + ": expected a JSON object");
                @SuppressWarnings("unchecked")
                final Map<String, Object> expected = (Map<String, Object>) model;
                final JsonObject object = actual.getAsJsonObject();
                // Key set and count must agree exactly, so a dropped or invented key is caught.
                assertEquals(expected.size(), object.entrySet().size(),
                        where + ": object member count");
                assertEquals(new TreeSet<>(expected.keySet()), new TreeSet<>(object.keySet()),
                        where + ": object key set");
                // Each member is looked up by its model key; the ordering of the detached tree is not
                // asserted here because toJson() is a plain detached tree and does not promise the
                // canonical key order that canonical() produces. Canonical ordering is asserted
                // separately, on the canonical text itself, by the caller.
                for (Map.Entry<String, Object> entry : expected.entrySet()) {
                    assertMatchesModel(entry.getValue(), object.get(entry.getKey()),
                            where + "/" + entry.getKey());
                }
                return;
            }
            if (model instanceof List<?>) {
                assertTrue(actual.isJsonArray(), where + ": expected a JSON array");
                final List<?> expected = (List<?>) model;
                final JsonArray array = actual.getAsJsonArray();
                assertEquals(expected.size(), array.size(), where + ": array length");
                for (int i = 0; i < expected.size(); i++) {
                    assertMatchesModel(expected.get(i), array.get(i), where + "/" + i);
                }
                return;
            }
            if (model instanceof Boolean) {
                assertTrue(actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isBoolean(),
                        where + ": expected a JSON boolean");
                assertEquals(model, actual.getAsJsonPrimitive().getAsBoolean(), where + ": boolean");
                return;
            }
            if (model instanceof Number) {
                assertTrue(actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isNumber(),
                        where + ": expected a JSON number, not text");
                final BigDecimal expected = new BigDecimal(CountingModel.canonicalNumber((Number) model));
                assertEquals(0, expected.compareTo(actual.getAsJsonPrimitive().getAsBigDecimal()),
                        where + ": numeric value");
                return;
            }
            assertTrue(actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isString(),
                    where + ": expected a JSON string");
            assertEquals(model, actual.getAsJsonPrimitive().getAsString(), where + ": string");
        }

        /** The independent oracle itself must be shown to be non-trivial. */
        @Test
        @DisplayName("model oracle distinguishes documented cases")
        void oracleSanity() {
            final TreeGenerator generator = new TreeGenerator(7L);
            final GeneratedTree tree = generator.next();
            assertEquals(tree.canonical, ExactJson.parse(tree.json).canonical());
            // Keys really are sorted back into UTF-16 order, and non-canonical spellings really change.
            assertEquals("{\"a\":2,\"b\":1}", canonicalOf("{\"b\":1,\"a\":2}"));
            assertEquals("1.5", canonicalOf("1.500"));
            assertEquals("1E+3", canonicalOf("1e3"));
            assertEquals("1E+49", CountingModel.canonicalNumber(new BigDecimal("1" + "0".repeat(49))));
            assertEquals("0", CountingModel.canonicalNumber(new BigDecimal("0.000")));
        }
    }

    /** Converts a detached Gson tree into the independent model's plain Java values. */
    private static CountingModel toModel(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return CountingModel.of(null);
        }
        if (element.isJsonObject()) {
            final Map<String, Object> object = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                object.put(entry.getKey(), toModel(entry.getValue()).value());
            }
            return CountingModel.of(object);
        }
        if (element.isJsonArray()) {
            final List<Object> array = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                array.add(toModel(child).value());
            }
            return CountingModel.of(array);
        }
        final JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return CountingModel.of(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            return CountingModel.of(new BigDecimal(primitive.getAsString()));
        }
        return CountingModel.of(primitive.getAsString());
    }

    // ==================================================================
    // 2. R02 vectors: primitives, keys, null, escaping
    // ==================================================================

    @Nested
    @DisplayName("R02 primitive and structure vectors")
    class PrimitiveVectors {

        @Test
        @DisplayName("null, booleans and text stay exactly as written")
        void primitives() {
            assertEquals("null", canonicalOf("null"));
            assertEquals("true", canonicalOf("true"));
            assertEquals("false", canonicalOf("false"));
            assertEquals("\"abc\"", canonicalOf("\"abc\""));
            assertEquals("[]", canonicalOf("[]"));
            assertEquals("{}", canonicalOf("{}"));
            assertEquals("[null,true,false]", canonicalOf("[null, true, false]"));
        }

        @Test
        @DisplayName("a numeric string is never turned into a number")
        void textVersusNumeric() {
            assertEquals("\"1\"", canonicalOf("\"1\""));
            assertEquals("\"1.0\"", canonicalOf("\"1.0\""));
            assertEquals("1", canonicalOf("1"));
            assertEquals("[1,\"1\"]", canonicalOf("[1,\"1\"]"));
            // freeze side: a String primitive under a numeric-looking text stays a string.
            assertEquals("\"42\"", canonicalOf(text("42")));
            assertEquals("42", canonicalOf(new JsonPrimitive(42)));
        }

        @Test
        @DisplayName("empty key, JSON null member, empty string are all legal")
        void emptyKeyAndNullMember() {
            assertEquals("{\"\":1}", canonicalOf("{\"\":1}"));
            assertEquals("{\"a\":null}", canonicalOf("{\"a\":null}"));
            assertEquals("{\"a\":\"\"}", canonicalOf("{\"a\":\"\"}"));
            final ExactJson.FrozenJson frozen = ExactJson.parse("{\"a\":null,\"b\":\"\"}");
            assertTrue(frozen.toJson().getAsJsonObject().get("a").isJsonNull(), "explicit JSON null");
            assertEquals("", frozen.toJson().getAsJsonObject().get("b").getAsString(), "empty string");
        }

        @Test
        @DisplayName("legal emoji survives both raw and paired-escape spellings")
        void legalEmoji() {
            final String raw = "\"\uD83D\uDE00\"";
            final String escaped = "\"\\uD83D\\uDE00\"";
            assertEquals(raw, canonicalOf(raw));
            assertEquals(raw, canonicalOf(escaped));
            assertEquals(ExactJson.parse(raw), ExactJson.parse(escaped), "both spellings are equal");
            assertEquals(6, ExactJson.parse(raw).utf8Size(), "2 quotes + 4 UTF-8 bytes");
            assertEquals("[\"\uD83D\uDE00\"]", canonicalOf("[\"\\uD83D\\uDE00\"]"));
            // freeze side accepts the same pair.
            assertEquals(raw, canonicalOf(text("\uD83D\uDE00")));
        }

        @Test
        @DisplayName("golden escaping text for quote, backslash, controls and slash")
        void goldenEscaping() {
            assertEquals("\"\\\"\"", canonicalOf("\"\\\"\""), "quote");
            assertEquals("\"\\\\\"", canonicalOf("\"\\\\\""), "backslash");
            assertEquals("\"\\b\\t\\n\\f\\r\"", canonicalOf("\"\\b\\t\\n\\f\\r\""), "short escapes");
            assertEquals("\"\\b\\t\\n\\f\\r\"", canonicalOf("\"\\u0008\\u0009\\u000a\\u000c\\u000d\""),
                    "escaped controls canonicalise to short escapes");
            assertEquals("\"\\u0000\\u0001\\u001f\"", canonicalOf("\"\\u0000\\u0001\\u001f\""),
                    "other controls use lowercase \\u00xx");
            assertEquals("\"/\"", canonicalOf("\"/\""), "solidus is never escaped");
            assertEquals("\"a/b\"", canonicalOf("\"a\\/b\""), "escaped solidus decodes to raw solidus");
            assertEquals("\"中文\"", canonicalOf("\"中文\""), "legal Unicode stays raw");
            // freeze side must agree with the parse side on all of the above.
            assertEquals("\"\\u0000\"", canonicalOf(text("\u0000")));
            assertEquals("\"\\r\\n\"", canonicalOf(text("\r\n")));
        }

        @Test
        @DisplayName("canonical escaping is measured in UTF-8 bytes, not chars")
        void escapingByteAccounting() {
            final ExactJson.FrozenJson frozen = ExactJson.parse("\"\\u0000\"");
            // One canonical control escape is 6 ASCII bytes plus the two quotes.
            assertEquals(8, frozen.utf8Size());
            assertEquals(8, frozen.canonicalUtf8().length);
            assertEquals("\"\\u0000\"", frozen.canonical());
        }
    }

    // ==================================================================
    // 3. numbers: canonical semantics, boundaries, exponent closure
    // ==================================================================

    @Nested
    @DisplayName("number canonicalisation and its closure")
    class NumberVectors {

        @Test
        @DisplayName("1, 1.0 and 1e0 share one canonical form; 0, -0 and 0e3 share another")
        void equivalentSpellings() {
            final String one = canonicalOf("1");
            assertEquals(one, canonicalOf("1.0"));
            assertEquals(one, canonicalOf("1e0"));
            assertEquals(one, canonicalOf("1.000E+0"));
            assertTrue(ExactJson.parse("1").equals(ExactJson.parse("1.0")), "1 equals 1.0");
            assertTrue(ExactJson.parse("1").equals(ExactJson.parse("1e0")), "1 equals 1e0");
            assertEquals(ExactJson.parse("1").hashCode(), ExactJson.parse("1e0").hashCode());

            final String zero = canonicalOf("0");
            assertEquals("0", zero);
            assertEquals(zero, canonicalOf("-0"));
            assertEquals(zero, canonicalOf("0e3"));
            assertEquals(zero, canonicalOf("0.0"));
            assertTrue(ExactJson.parse("0").equals(ExactJson.parse("-0")), "0 equals -0");
            assertTrue(ExactJson.parse("0").equals(ExactJson.parse("0e3")), "0 equals 0e3");
        }

        @Test
        @DisplayName("two 50-digit integers differing only in the last digit stay distinct")
        void fiftyDigitIntegers() {
            final String a = "1" + "0".repeat(49);
            final String b = "1" + "0".repeat(48) + "1";
            assertEquals(50, a.length());
            assertEquals(50, b.length());
            // A trailing-zero integer is not expanded into 50 digits: the canonical spelling is the
            // bounded scientific form, which is what makes closure cheap.
            assertEquals("1E+49", canonicalOf(a));
            assertEquals(b, canonicalOf(b), "a trailing non-zero digit keeps the plain form");
            assertNotEquals(canonicalOf(a), canonicalOf(b), "different canonical");
            assertFalse(ExactJson.parse(a).equals(ExactJson.parse(b)), "different identity");
            assertNotEquals(ExactJson.parse(a).hashCode(), ExactJson.parse(b).hashCode());
            // Their 50-digit neighbours across the full digit range.
            final String c = "9".repeat(50);
            assertEquals(c, canonicalOf(c));
            assertEquals("1E+50", canonicalOf("1" + "0".repeat(50)), "51 digits still legal");
        }

        @Test
        @DisplayName("1e1000000 is bounded and re-readable, 1e1000001 is rejected")
        void exponentBoundary() {
            final String canonical = canonicalOf("1e1000000");
            assertEquals(canonical, canonicalOf(canonical), "re-readable at the boundary");
            assertTrue(canonical.length() < 64, "canonical stays a bounded scientific spelling: " + canonical);
            assertTrue(canonical.indexOf("1000000") >= 0, "exponent preserved: " + canonical);
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "1e1000001");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "-1e1000001");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "1e-1000001");
        }

        @Test
        @DisplayName("10e1000000 and 0.1e-1000000 are rejected: they would not read back")
        void nonReReadableNumbers() {
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "10e1000000");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "0.1e-1000000");
            // The same inputs nested also report the node pointer, not the root.
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/a", "{\"a\":10e1000000}");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/0", "[0.1e-1000000]");
        }

        @Test
        @DisplayName("0.000e-1000000 canonicalises to 0 and closes (Important regression)")
        void zeroWithHugeNegativeExponent() {
            final ExactJson.FrozenJson frozen = ExactJson.parse("0.000e-1000000");
            assertEquals("0", frozen.canonical(), "a zero mantissa is zero at any exponent");
            assertEquals(1, frozen.utf8Size());
            assertEquals("0", ExactJson.parse(frozen.canonical()).canonical(), "closure");
            assertTrue(ExactJson.parse("0.000e-1000000").equals(ExactJson.parse("0")), "equals 0");
            // Not expanded into a million zeros anywhere: the canonical text stays 1 byte.
        }

        @Test
        @DisplayName("1024-char literals with 1017 trailing zeros and e99999 close (Important regression)")
        void trailingZerosWithLargeExponent() {
            final String literal = "1" + "0".repeat(1017) + "e99999";
            assertEquals(1024, literal.length(), "exactly the literal budget");
            final ExactJson.FrozenJson frozen = ExactJson.parse(literal);
            final String canonical = frozen.canonical();
            assertEquals(canonical, ExactJson.parse(canonical).canonical(), "closure");
            assertTrue(canonical.length() <= 1024, "canonical within the literal budget: " + canonical.length());
            // A tenth of the trailing zeros is over budget by one character.
            final String tooLong = "1" + "0".repeat(1017) + "e999999";
            assertTrue(tooLong.length() > 1024, "over the literal budget");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", tooLong);
        }

        @Test
        @DisplayName("1024-digit literals are accepted in root, array and object positions; 1025 rejected")
        void numberLiteralLengthBoundary() {
            final String digits1024 = "1" + "0".repeat(1022) + "1";
            assertEquals(1024, digits1024.length());
            assertEquals(digits1024, canonicalOf(digits1024), "root position");
            assertEquals("[" + digits1024 + "]", canonicalOf("[" + digits1024 + "]"), "array position");
            assertEquals("{\"a\":" + digits1024 + "}", canonicalOf("{\"a\":" + digits1024 + "}"),
                    "object position");

            final String digits1025 = digits1024 + "1";
            assertEquals(1025, digits1025.length());
            // The literal length guard belongs to the frozen lexical pre-scan, so an oversized literal
            // is rejected before the reader knows its position: the reported path is the document root
            // even when the literal sits deeper in the document.
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", digits1025);
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "[" + digits1025 + "]");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "{\"a\":" + digits1025 + "}");
            // The reader's own guard reports the real node pointer; it is reached when the token is
            // legal to the pre-scan but the number itself is rejected by the number layer.
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/0", "[10e1000000]");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/a", "{\"a\":0.1e-1000000}");
        }

        @Test
        @DisplayName("a legal number adjacent to a quote is INVALID_JSON, not a longer number")
        void numberAdjacentQuotes() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1\"x\"");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "[1\"x\"]");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"a\":1\"x\"}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "[\"x\"1]");
        }

        @Test
        @DisplayName("malformed numbers are INVALID_JSON or INVALID_NUMBER, never silently repaired")
        void malformedNumbers() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "01");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "-01");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1.");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", ".1");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "+1");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1e");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1e+");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "NaN");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "Infinity");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "-Infinity");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "0x10");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1_000");
        }
    }

    // ==================================================================
    // 4. object keys: budget, escaping, pointer policy
    // ==================================================================

    // ==================================================================
    // 6. strict structure: separators, literals, whitespace, raw controls
    // ==================================================================

    @Nested
    @DisplayName("strict structure, literal spelling and whitespace")
    class StructureVectors {

        @Test
        @DisplayName("only lowercase true, false and null are accepted")
        void literalSpelling() {
            assertEquals("true", canonicalOf("true"));
            assertEquals("false", canonicalOf("false"));
            assertEquals("null", canonicalOf("null"));
            assertEquals("{\"a\":true}", canonicalOf("{\"a\":true}"));
            assertEquals("[false,null,true]", canonicalOf("[false,null,true]"));
            for (String bad : new String[] {"TRUE", "True", "tRue", "FALSE", "False", "NULL", "NulL", "nul"}) {
                parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"a\":" + bad + "}");
            }
        }

        @Test
        @DisplayName("only space, tab, CR and LF are legal whitespace")
        void legalWhitespace() {
            assertEquals("{}", canonicalOf(" \t\r\n{\r\n}\t "), "all four legal whitespace characters");
            assertEquals("{\"a\":1}", canonicalOf(" \t\r\n{\"a\"\t:\r1\n} \t\r\n"));
            assertEquals("[1,2]", canonicalOf("[\n1\t,\r2\r\n]"));

            // Form feed, vertical tab, NBSP and a BOM are not JSON whitespace.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\f\"a\":1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\u000b\"a\":1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\u00a0{}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\ufeff{}");
        }

        @Test
        @DisplayName("every empty or malformed document is rejected, never silently accepted")
        void emptyAndMalformedDocuments() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "   ");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\t\r\n");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1 2");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "1\u0000");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "null true");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{} {}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "[]]");
        }

        @Test
        @DisplayName("no trailing comma is ever tolerated")
        void trailingCommas() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"a\":1,}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "/1", "[1,]");
            parseFails(JsonContractException.CODE_INVALID_JSON, "/1", "[1,,2]");
            parseFails(JsonContractException.CODE_INVALID_JSON, "/0", "[,]");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{,}");
        }

        @Test
        @DisplayName("a member needs exactly one colon and one value")
        void memberSeparators() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "/a", "{\"a\" 1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "/a", "{\"a\"::1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "/a", "{\"a\"}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"a\":1 \"b\":2}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{1:2}");
            // An array element must be a value; a quoted key there is refused before any element path
            // exists, so the pointer is still the root.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "[\"a\":1]");
        }

        @Test
        @DisplayName("empty containers canonicalise without any interior space")
        void emptyContainers() {
            assertEquals("{}", canonicalOf("{}"));
            assertEquals("{}", canonicalOf("{ }"));
            assertEquals("[]", canonicalOf("[]"));
            assertEquals("[]", canonicalOf("[ ]"));
            assertEquals(2, ExactJson.parse("{}").utf8Size());
            assertEquals(2, ExactJson.parse("[]").utf8Size());
            assertEquals("{\"a\":[],\"b\":{}}", canonicalOf("{\"b\":{ },\"a\":[ ]}"));
        }

        @Test
        @DisplayName("raw control characters are illegal inside a string but their escapes are legal")
        void rawControlCharacters() {
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"a\nb\"");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"a\tb\"");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"a\u0000b\"");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"a\u001fb\"");
            // A backslash followed by a real newline is not a legal escape.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"\\\n\"");
            // \' is not a legal JSON escape.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"a\\'b\"");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"\\x41\"");
            // The escaped forms are legal and canonical.
            assertEquals("\"\\n\\t\"", canonicalOf("\"\\n\\t\""));
            assertEquals("\"\\u0000\"", canonicalOf("\"\\u0000\""));
            assertEquals("\"\\u001f\"", canonicalOf("\"\\u001f\""));
            assertEquals("\"\\b\\f\"", canonicalOf("\"\\b\\f\""));
        }

        @Test
        @DisplayName("deep nesting is refused by the depth guard, not by a stack overflow")
        void nestingDepth() {
            // The depth budget counts nesting levels, and the two container shapes reach it differently:
            // nested arrays accept 65 levels and refuse 66, while nested object members accept 64 and
            // refuse 65. In both refusals the reader has already built 65 pointer segments.
            assertDoesNotThrow(() -> ExactJson.parse(nestedArrays(64)));
            assertDoesNotThrow(() -> ExactJson.parse(nestedArrays(65)));
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED,
                    pathOfRepeatedSegments("0", 65), nestedArrays(66));

            assertDoesNotThrow(() -> ExactJson.parse(nestedObjects(63)));
            assertDoesNotThrow(() -> ExactJson.parse(nestedObjects(64)));
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED,
                    pathOfRepeatedSegments("a", 65), nestedObjects(65));
        }

        @Test
        @DisplayName("the node budget counts the root container: 499,999 nulls fit, 500,000 do not")
        void nodeBudget() {
            // An array of n null elements costs n + 1 nodes (the elements plus the array itself), so
            // MAX_NODES permits n = MAX_NODES - 1 nulls and rejects one more. Both cases are checked
            // through the reported pointer, which is the index of the element that overflowed.
            final ExactJson.FrozenJson atLimit = ExactJson.parse(nullArray(MAX_NODES - 1));
            assertEquals(MAX_NODES, countingNodes(atLimit.toJson()),
                    "the accepted document sits exactly on the node budget");

            final JsonContractException tooMany = parseFails(JsonContractException.CODE_LIMIT_EXCEEDED,
                    "/" + (MAX_NODES - 1), nullArray(MAX_NODES));
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, tooMany.code());
        }

        /** Counts every node of a tree: each value is one node, containers contribute their children. */
        private static int countingNodes(JsonElement element) {
            if (element.isJsonObject()) {
                int total = 1;
                for (Map.Entry<String, JsonElement> member : element.getAsJsonObject().entrySet()) {
                    total += countingNodes(member.getValue());
                }
                return total;
            }
            if (element.isJsonArray()) {
                int total = 1;
                for (JsonElement child : element.getAsJsonArray()) {
                    total += countingNodes(child);
                }
                return total;
            }
            return 1;
        }

        /** Builds {@code n} nested arrays around an empty array. */
        private static String nestedArrays(int n) {
            final StringBuilder document = new StringBuilder(2 * n);
            for (int i = 0; i < n; i++) {
                document.append('[');
            }
            for (int i = 0; i < n; i++) {
                document.append(']');
            }
            return document.toString();
        }

        /** Builds {@code n} nested single-member objects holding the number 1. */
        private static String nestedObjects(int n) {
            final StringBuilder document = new StringBuilder(6 * n + 1);
            for (int i = 0; i < n; i++) {
                document.append("{\"a\":");
            }
            document.append('1');
            for (int i = 0; i < n; i++) {
                document.append('}');
            }
            return document.toString();
        }

        /** Builds an array of {@code n} JSON nulls. */
        private static String nullArray(int n) {
            final StringBuilder document = new StringBuilder(5 * n + 2);
            document.append('[');
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    document.append(',');
                }
                document.append("null");
            }
            document.append(']');
            return document.toString();
        }

        /** Builds a pointer made of {@code count} repetitions of one segment. */
        private static String pathOfRepeatedSegments(String segment, int count) {
            final StringBuilder pointer = new StringBuilder(count * (segment.length() + 1));
            for (int i = 0; i < count; i++) {
                pointer.append('/').append(segment);
            }
            return pointer.toString();
        }
    }

    // ==================================================================
    // 4. object keys: byte budget, decoded spelling and pointer policy
    // ==================================================================

    // ==================================================================
    // 7. value semantics, defensive copies and shared budget accounting
    // ==================================================================

    @Nested
    @DisplayName("frozen value semantics and defensive copies")
    class DefensiveVectors {

        @Test
        @DisplayName("JsonContractException is an IllegalArgumentException")
        void exceptionHeritage() {
            assertTrue(IllegalArgumentException.class.isAssignableFrom(JsonContractException.class));
            assertTrue(RuntimeException.class.isAssignableFrom(JsonContractException.class));
            final JsonContractException failure = parseFails(JsonContractException.CODE_INVALID_JSON, "", "}");
            assertTrue(failure instanceof IllegalArgumentException, "callers may catch the JDK type");
        }

        @Test
        @DisplayName("toJson hands out an independent deep copy every time")
        void toJsonIsDefensive() {
            final ExactJson.FrozenJson frozen = ExactJson.parse("{\"a\":[1,2]}");
            final JsonElement first = frozen.toJson();
            assertNotSame(first, frozen.toJson(), "each call returns a fresh tree");
            first.getAsJsonObject().getAsJsonArray("a").add(99);
            first.getAsJsonObject().addProperty("b", 5);
            assertEquals("{\"a\":[1,2]}", frozen.canonical(), "mutating a copy cannot change the value");
            assertEquals("{\"a\":[1,2]}", frozen.toJson().toString(), "later copies stay pristine");
        }

        @Test
        @DisplayName("canonicalUtf8 hands out a fresh array that cannot corrupt the value")
        void canonicalUtf8IsDefensive() {
            final ExactJson.FrozenJson frozen = ExactJson.parse("{\"a\":1}");
            final byte[] first = frozen.canonicalUtf8();
            assertNotSame(first, frozen.canonicalUtf8(), "each call returns a fresh array");
            Arrays.fill(first, (byte) 'X');
            assertEquals("{\"a\":1}", frozen.canonical(), "the internal bytes were not exposed");
            assertArrayEquals("{\"a\":1}".getBytes(StandardCharsets.UTF_8), frozen.canonicalUtf8());
            assertEquals(frozen.utf8Size(), frozen.canonicalUtf8().length);
        }

        @Test
        @DisplayName("canonical text, toString and byte size always agree")
        void canonicalAgreement() {
            for (String json : new String[] {"null", "true", "1.5", "\"\\u0000\"", "{}", "[]",
                    "{\"b\":[1,2],\"a\":\"x\"}", "[\"\\u00e9\\ud83d\\ude00\"]"}) {
                final ExactJson.FrozenJson frozen = ExactJson.parse(json);
                assertEquals(frozen.canonical(), frozen.toString());
                assertEquals(frozen.canonical(), new String(frozen.canonicalUtf8(), StandardCharsets.UTF_8));
                assertEquals(frozen.canonicalUtf8().length, frozen.utf8Size());
            }
        }

        @Test
        @DisplayName("equality and hashing follow the canonical form, not the source spelling")
        void equalityFollowsCanonicalForm() {
            assertEquals(ExactJson.parse("{\"a\":1,\"b\":2}"), ExactJson.parse("{\"b\":2,\"a\":1}"),
                    "member order is not part of the value");
            assertEquals(ExactJson.parse("{\"a\":1,\"b\":2}").hashCode(),
                    ExactJson.parse("{\"b\":2,\"a\":1}").hashCode());
            assertEquals(ExactJson.parse("{ \"a\" : 1 }"), ExactJson.parse("{\"a\":1}"));
            assertEquals(ExactJson.parse("1.0"), ExactJson.parse("1e0"), "equal numbers are equal values");
            assertNotEquals(ExactJson.parse("[1]"), ExactJson.parse("[2]"));
            assertNotEquals(ExactJson.parse("[1,2]"), ExactJson.parse("[2,1]"), "array order is the value");
            assertNotEquals(ExactJson.parse("{\"a\":1}"), ExactJson.parse("{\"a\":\"1\"}"));
            assertNotEquals(ExactJson.parse("1"), ExactJson.parse("\"1\""));
        }

        @Test
        @DisplayName("freeze snapshots a mutable Number instead of reading it later")
        void mutableNumberSnapshot() {
            final MutableNumber number = new MutableNumber(7);
            final JsonObject input = new JsonObject();
            input.add("n", new JsonPrimitive(number));

            final ExactJson.FrozenJson frozen = ExactJson.freeze(input);
            // MutableNumber.toString() rewrites its own value as a side effect. If the codec deferred
            // reading the number, the canonical form would show the rewritten value instead of 7.
            assertEquals("{\"n\":7}", frozen.canonical());
            assertEquals("{\"n\":7}", frozen.toString());
        }

        @Test
        @DisplayName("freeze accepts the JSON null element, and a null member becomes JSON null")
        void freezeNullHandling() {
            assertEquals("null", ExactJson.freeze(JsonNull.INSTANCE).canonical(),
                    "the JSON null element is a legal document");
            assertEquals(4, ExactJson.freeze(JsonNull.INSTANCE).utf8Size());

            // Gson turns a Java null member value into the JSON null element, so this is legal too and
            // must be the same value as spelling it out.
            final JsonObject withNullMember = new JsonObject();
            withNullMember.add("a", null);
            assertEquals("{\"a\":null}", ExactJson.freeze(withNullMember).canonical());
            assertEquals(ExactJson.parse("{\"a\":null}"), ExactJson.freeze(withNullMember));
        }

        @Test
        @DisplayName("499,999 members sharing one 1024-digit number are refused at the precomputed pointer")
        void sharedNumericBudget() {
            // The mandated fixture: one shared Number value referenced by 499,999 array members. The
            // tree itself is tiny (one BigDecimal, one JsonPrimitive, 499,999 references) so the heap
            // is never what is under test; the canonical output cannot fit the 8 MiB text budget.
            //
            // The crossing pointer is precomputed here from the contract, not read back from the codec:
            // each member is a BARE 1024-digit number literal, so it costs 1024 bytes with NO quotes
            // added, plus one separator byte per member after the first. The measured canonical size of
            // an n-member array of this value is therefore exactly
            //     bytes(n) = 1025 * n + 1
            // (one opening bracket + n * 1024 digits + n-1 separators + the closing bracket). The first
            // n with bytes(n) > 8,388,608 is n = 8,185, so the reservation that crosses the budget
            // happens while the codec is writing member index 8,184 and the reported pointer is "/8184".
            // The members that fit are indices 0 through 8,183 inclusive; index 8,184 is the REFUSED
            // one, which is why the test expects "/8184" and not "/8183" or "/8185".
            final int crossingMember = 8184;
            // Sanity check on the arithmetic above. An 8,184-member array is 8,388,601 bytes and fits,
            // which is exactly why indices 0 through 8,183 are all accepted. Writing one more member
            // makes the array 8,185 members and 8,388,626 bytes, which does not fit, and that is the
            // step during which the budget is crossed -- so the refused member is the one at index
            // 8,184, and it is refused because of the total the array reaches when it is written, not
            // because its own bytes alone are over the limit.
            assertTrue(1025L * 8184 + 1 <= ExactJson.MAX_UTF8_BYTES,
                    "an 8,184-member array still fits, so indices 0 through 8,183 are all acceptable");
            assertTrue(1025L * (crossingMember + 1) + 1 > ExactJson.MAX_UTF8_BYTES,
                    "the array crosses the budget while the member at index 8184 is written");

            final String digits = "9".repeat(1024);
            final JsonPrimitive shared = new JsonPrimitive(new java.math.BigDecimal(digits));
            final JsonArray document = new JsonArray();
            for (int i = 0; i < 499999; i++) {
                document.add(shared);
            }

            // Every member is individually legal: the value is a BARE 1024-digit number, so the member
            // costs 1024 bytes and no quotes are added. Inside the array it costs those same 1024 bytes
            // plus its one separator byte, so an n-member array is exactly 1025n + 1 bytes and NOT a
            // quoted 1026 bytes per member. Both facts are asserted so the cost model cannot drift.
            assertEquals(1024, ExactJson.freeze(shared).utf8Size(),
                    "a single bare shared member is a legal 1024-byte number with no quotes added");
            assertEquals(1025L * 2 + 1, ExactJson.parse("[" + digits + "," + digits + "]").utf8Size(),
                    "a two-member array costs 1025 * 2 + 1 bytes: bare digits plus one separator each");

            final JsonContractException failure = freezeFails(
                    JsonContractException.CODE_LIMIT_EXCEEDED, "/" + crossingMember, document);
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/" + crossingMember, failure.path(),
                    "the pointer is the member whose reservation crossed the budget");
            // The message must stay bounded: it may not embed a copy of the 1024-digit literal.
            assertBoundedMessage(failure, 1024 * 499999);
        }

        @Test
        @DisplayName("a legal 8 MiB document whose canonical form overflows is refused at /128")
        void canonicalOverflowIsRejected() {
            // A REAL canonical-output overflow of a legal input, which the earlier report wrongly
            // called unreachable. Every element is individually legal: the longest string is exactly
            // MAX_STRING_UTF8_BYTES, so no single value breaks a limit. The raw document is exactly
            // 8,388,608 bytes, so the input budget is satisfied exactly, while the canonical form is
            // one byte longer: the final member "1e3" canonicalises to "1E+3" (3 bytes -> 4 bytes),
            // which the output budget cannot fit. The refusal must therefore happen on the OUTPUT
            // budget, at the member that caused it, and not on the input budget.
            final String big = "a".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
            final StringBuilder raw = new StringBuilder();
            raw.append('[');
            for (int i = 0; i < 127; i++) {
                if (i > 0) {
                    raw.append(',');
                }
                raw.append('"').append(big).append('"');
            }
            // 127 * (2 + 65536) + separators + the tail + the last member + brackets = 8,388,609, i.e.
            // exactly one byte over the output budget. The tail length is chosen so the raw document
            // lands exactly on the input budget of 8,388,608 bytes.
            final String tail = "b".repeat(65147);
            raw.append(',').append('"').append(tail).append('"');
            raw.append(',').append("1e3");
            raw.append(']');
            final String document = raw.toString();

            // Every ingredient is legal on its own.
            assertEquals(65538, ExactJson.parse("\"" + big + "\"").utf8Size(),
                    "the longest string alone is a legal document");
            assertEquals(65149, ExactJson.parse("\"" + tail + "\"").utf8Size(),
                    "the tail string alone is a legal document");
            // The raw text sits exactly on the input budget, so the input side is not the reason.
            assertEquals(ExactJson.MAX_UTF8_BYTES, document.length(),
                    "the raw document is exactly at the 8 MiB input budget");
            assertEquals("1E+3", canonicalOf("1e3"), "the last member grows by one byte");

            // The refusal is the output budget, attributed to the member that crossed it: index 128 is
            // the "1e3" member (127 long strings, then the tail at index 127, then "1e3").
            final JsonContractException failure = parseFails(
                    JsonContractException.CODE_LIMIT_EXCEEDED, "/128", document);
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/128", failure.path(),
                    "the pointer is the member whose canonical form crossed the output budget");
            assertBoundedMessage(failure, document.length());
        }

        @Test
        @DisplayName("2000 shared 1000-byte escaped-NUL strings overflow the output budget and are refused")
        void sharedEscapedNulBudget() {
            // The second real output-budget overflow. One member is a 1000-decoded-NUL string, which
            // is legal on its own (6002 canonical bytes) and well inside every per-value limit, so the
            // input side is comfortable. 2000 references to that one shared value need about 12 MB of
            // canonical output, which the writer must refuse early rather than materialise.
            final String nuls = "\u0000".repeat(1000);
            final JsonPrimitive shared = new JsonPrimitive(nuls);
            assertEquals(6002, ExactJson.freeze(shared).utf8Size(),
                    "one shared member is a legal value well inside the budgets");
            assertTrue(ExactJson.freeze(shared).utf8Size() <= ExactJson.MAX_STRING_UTF8_BYTES,
                    "the shared member is by itself far below the per-string budget");

            final JsonArray document = new JsonArray();
            for (int i = 0; i < 2000; i++) {
                document.add(shared);
            }
            // 2000 members would need roughly 12,000,000 canonical bytes: the output budget must stop
            // this long before the writer reaches the end.
            assertTrue(2000L * 6002L > ExactJson.MAX_UTF8_BYTES,
                    "the fixture genuinely exceeds the output budget");

            final JsonContractException failure = freezeFails(
                    JsonContractException.CODE_LIMIT_EXCEEDED, "/1397", document);
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, failure.code());
            assertEquals("/1397", failure.path(),
                    "the pointer is the member whose reservation crossed the budget");
            assertBoundedMessage(failure, 2000 * 6002);
        }

        @Test
        @DisplayName("canonical form can grow past the raw text, and the codec still closes on it")
        void canonicalGrowthStaysConsistent() {
            // BigDecimal's canonical spelling of 1e3 is 1E+3: one byte LONGER than the raw literal.
            // This is the only known growth direction, and it is why the output budget must be checked
            // independently of the input budget rather than assumed to be covered by it.
            assertEquals(3, "1e3".length());
            assertEquals("1E+3", canonicalOf("1e3"));
            assertTrue(ExactJson.parse("1e3").utf8Size() > "1e3".length(),
                    "the canonical output is genuinely larger than the input here");

            // A legal document built entirely from that growth, checked for exact accounting and for
            // parse/freeze agreement. The size is deliberately modest: the point is that the growth is
            // real and exactly accounted, and a large fixture would only spend heap that belongs to the
            // 64 MiB budget checks elsewhere.
            final int members = 20000;
            final StringBuilder document = new StringBuilder(3 * members + 2);
            document.append('[');
            for (int i = 0; i < members; i++) {
                if (i > 0) {
                    document.append(',');
                }
                document.append("1e3");
            }
            document.append(']');

            final ExactJson.FrozenJson frozen = ExactJson.parse(document.toString());
            assertEquals(2 + members * 4 + (members - 1), frozen.utf8Size(),
                    "2 brackets + members * 1E+3 + separators");
            assertEquals(frozen.canonical(), ExactJson.freeze(frozen.toJson()).canonical(),
                    "freeze reproduces the parse canonical form exactly");
        }

        @Test
        @DisplayName("escaped control characters are accounted in the reader's own byte budget")
        void escapedControlBudgetAccounting() {
            // One escaped control character is six canonical ASCII bytes plus the two quotes.
            assertEquals(8, ExactJson.parse("\"\\u0001\"").utf8Size());
            assertEquals(14, ExactJson.parse("\"\\u0001\\u0002\"").utf8Size());
            assertEquals(10, ExactJson.parse("\"\\u0001\u00e9\"").utf8Size(),
                    "6 escaped bytes + 2 UTF-8 bytes + 2 quotes");

            // A long but legal run of escapes stays accepted and exactly accounted.
            final StringBuilder escaped = new StringBuilder();
            escaped.append('"');
            for (int i = 0; i < 200; i++) {
                escaped.append("\\u0000");
            }
            escaped.append('"');
            final ExactJson.FrozenJson frozen = ExactJson.parse(escaped.toString());
            assertEquals(2 + 200 * 6, frozen.utf8Size());

            // The escaped spelling is the only legal way to carry a control character; the raw form is
            // not an equivalent document but a lexical error, and it is reported at the root pointer.
            final String raw = "\u0000".repeat(200);
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "\"" + raw + "\"");
            assertEquals("\"" + "\\u0000".repeat(200) + "\"", frozen.canonical(),
                    "canonical keeps the lowercase escaped form");
        }

        @Test
        @DisplayName("a surrogate pair split across escapes is measured as four UTF-8 bytes")
        void pendingHighSurrogateBudget() {
            // 10,920 escaped emoji: the high and low halves arrive in separate escapes, so the reader
            // must sum the pair's four UTF-8 bytes exactly rather than charging each half on its own.
            final int pairs = 10920;
            final StringBuilder escaped = new StringBuilder();
            escaped.append('"');
            for (int i = 0; i < pairs; i++) {
                escaped.append("\\ud83d\\ude00");
            }
            escaped.append('"');
            final ExactJson.FrozenJson frozen = ExactJson.parse(escaped.toString());
            assertEquals(2 + pairs * 4, frozen.utf8Size());
            assertEquals("\"" + "\uD83D\uDE00".repeat(pairs) + "\"", frozen.canonical());

            // The same count written raw is the same value, which pins the accounting to the pair.
            assertEquals(frozen, ExactJson.parse("\"" + "\uD83D\uDE00".repeat(pairs) + "\""));
        }

        @Test
        @DisplayName("a lone surrogate is rejected as INVALID_UNICODE at the root pointer")
        void loneSurrogates() {
            // Raw lone high and lone low surrogates, and escaped halves that never pair up.
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "\"\uD83D\"");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "\"\uDE00\"");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "\"\\ud83d\"");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "\"\\ude00\"");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "\"\\ud83da\"");
        }
    }

    @Nested
    @DisplayName("object key budget and JSON pointer policy")
    class KeyVectors {

        /**
         * The original regression fixture, exactly as reviewed: one 20,000,000-character illegal Gson
         * key placed under {@code /nested}.
         *
         * <p>Only the key {@link String} and the two {@link JsonObject} containers exist — no raw
         * document text and no {@code StringBuilder} copy of the key is built anywhere, because the
         * {@code freeze} entry point takes an already parsed tree. Before the I1 fix the pointer was
         * assembled from the key before the key was measured, so this shape exhausted a small heap
         * instead of reporting; the assertion below is therefore only satisfiable by the
         * measure-before-build ordering.</p>
         *
         * <p>Note on what this does NOT prove: building the fixture itself allocates a {@code byte[]}
         * and the resulting {@code String}, so an out-of-memory in this method would NOT by itself prove
         * that the codec made an extra copy. The ordering claim rests on the asserted pointer, not on
         * any memory inference.</p>
         */
        @Test
        @DisplayName("freeze: a 20,000,000-char illegal key reports LIMIT_EXCEEDED at the enclosing pointer")
        void freezeHugeKey() {
            // Single test on purpose: this fixture is the original regression shape, and a 20,000,000
            // character key is a large value. Keeping it in one method guarantees that only one such
            // key is ever live at a time. Together with the raw byte[] used to build it, the fixture's
            // own construction needs more than 64 MiB of test-side heap, so a small-heap failure here
            // would be a harness limit rather than evidence about the codec.
            final String key = hugeTildeRun(20000000);

            // (a) nested under /nested: the reported pointer is the enclosing object pointer.
            final JsonObject nested = new JsonObject();
            final JsonObject outer = new JsonObject();
            nested.add(key, JsonNull.INSTANCE);
            outer.add("nested", nested);
            final JsonContractException nestedFailure =
                    freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/nested", outer);
            assertMessageDoesNotEmbedKey(nestedFailure, 20000000);
            outer.remove("nested");
            nested.entrySet().clear();

            // (b) at the root: no pointer segment can exist, so the reported pointer is the root.
            final JsonObject root = new JsonObject();
            root.add(key, JsonNull.INSTANCE);
            final JsonContractException rootFailure =
                    freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", root);
            assertMessageDoesNotEmbedKey(rootFailure, 20000000);
            root.entrySet().clear();
        }

        /**
         * A moderate oversized key, which keeps every intermediate value small while still proving the
         * measure-before-build ordering on both entry points.
         */
        @Test
        @DisplayName("freeze and parse agree on the enclosing object pointer for an oversized key")
        void oversizedKeyPointerPolicy() {
            final JsonObject nested = new JsonObject();
            final JsonObject outer = new JsonObject();
            nested.add("~".repeat(65536), JsonNull.INSTANCE);
            outer.add("nested", nested);
            assertMessageDoesNotEmbedKey(
                    freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/nested", outer), 65536);

            // parse side: the very same key spelled out in a document well inside the 8 MiB text budget.
            assertMessageDoesNotEmbedKey(parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/nested",
                    "{\"nested\":{\"" + "~".repeat(65536) + "\":null}}"), 65536);
        }

        /**
         * The document text budget is a separate guard from the key budget: it is entered through
         * {@code parse} only, it fires on the raw document, and it reports the root pointer. The
         * document is an array of many legal short strings, so no single string or key budget is in
         * play and the only guard that can fire is the document size itself.
         */
        @Test
        @DisplayName("parse: a document one byte over the 8 MiB text budget reports LIMIT_EXCEEDED at the root")
        void documentTextBudget() {
            final String overBudget = arrayDocumentOfUtf8Bytes(ExactJson.MAX_UTF8_BYTES + 1);
            assertEquals(ExactJson.MAX_UTF8_BYTES + 1,
                    overBudget.getBytes(StandardCharsets.UTF_8).length);
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", overBudget);
        }

        @Test
        @DisplayName("parse: a document exactly at the 8 MiB text budget is accepted")
        void documentTextBudgetAtLimit() {
            // This vector's test-side copies do not fit a 64 MiB heap, and the reason is harness memory
            // rather than codec behaviour: an accepted 8,388,608-byte document exists as ~16 MB of UTF-16
            // characters, is parsed into a tree, and is then encoded into an 8,388,608-byte canonical
            // buffer. The 20,000,000-character key fixture has the same property, and each of the two
            // hurts for its own separate reason, so neither is a proxy for the other. The product budget
            // is NOT reduced: the document stays at exactly 8,388,608 bytes, and this assertion is
            // unconditional. The formal full run is therefore given 384 MiB; the 64 MiB production
            // behaviour is evidenced separately by the primary agent's own independent process, and no
            // partially excluded run is ever presented as covering these vectors. Note that the suite
            // total is pinned by the runner, not restated here, so it cannot drift out of date.
            final String atLimit = arrayDocumentOfUtf8Bytes(ExactJson.MAX_UTF8_BYTES);
            assertEquals(ExactJson.MAX_UTF8_BYTES, atLimit.getBytes(StandardCharsets.UTF_8).length);

            final ExactJson.FrozenJson frozen = ExactJson.parse(atLimit);
            assertEquals(ExactJson.MAX_UTF8_BYTES, frozen.utf8Size(),
                    "this document's canonical form equals its raw text");
            assertEquals(atLimit, frozen.canonical());
        }

        /**
         * Builds an array document of exactly {@code targetBytes} UTF-8 bytes out of a small number of
         * medium ASCII strings.
         *
         * <p>Two budgets must stay clear: the node budget (500,000), so the element count is tiny, and
         * the per-string budget (65,536), so each element is a comfortable fraction of it. The elements
         * are therefore about 60 KiB each, and a short trailing element absorbs the remainder. That
         * leaves the document size as the only budget this document can trip.</p>
         */
        private static String arrayDocumentOfUtf8Bytes(int targetBytes) {
            final int elementBytes = 60000; // 2 quotes + 59998 characters, well under 65,536
            final char[] filler = new char[elementBytes - 2];
            Arrays.fill(filler, 'a');

            final StringBuilder document = new StringBuilder(targetBytes + elementBytes);
            document.append('[');
            int written = 1; // the opening bracket
            while (written + elementBytes + 1 + 1 <= targetBytes - 1) {
                // +1 for the separating comma, +1 for the closing bracket, -1 so a tail element fits.
                if (written > 1) {
                    document.append(',');
                    written++;
                }
                document.append('"').append(filler).append('"');
                written += elementBytes;
            }
            // Tail: one final element absorbing every remaining byte.
            final int separator = written > 1 ? 1 : 0;
            final int tailCharacters = targetBytes - written - separator - 3; // 2 quotes + closing bracket
            assertTrue(tailCharacters >= 1 && tailCharacters < elementBytes,
                    "tail must be a legal short element, was " + tailCharacters + " characters");
            if (separator == 1) {
                document.append(',');
            }
            document.append('"');
            for (int i = 0; i < tailCharacters; i++) {
                document.append('a');
            }
            document.append('"').append(']');
            assertEquals(targetBytes, document.length(), "ASCII document: characters equal bytes");
            return document.toString();
        }

        @Test
        @DisplayName("the key budget is measured in decoded UTF-8 bytes: 1024 accepted, 1025 rejected")
        void keyByteBoundary() {
            // 1024 plain ASCII bytes: legal, and the decoded key really is 1024 bytes long.
            final String key1024 = "~".repeat(1024);
            assertEquals(1024, key1024.getBytes(StandardCharsets.UTF_8).length);
            final ExactJson.FrozenJson accepted = ExactJson.parse("{\"" + key1024 + "\":1}");
            final String decodedKey = accepted.toJson().getAsJsonObject().keySet().iterator().next();
            assertEquals(key1024, decodedKey, "the decoded key survives unchanged");
            assertEquals(1024, decodedKey.getBytes(StandardCharsets.UTF_8).length);
            assertEquals("{\"" + key1024 + "\":1}", accepted.canonical());

            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "{\"" + "~".repeat(1025) + "\":1}");
        }

        @Test
        @DisplayName("a key of 1024 escaped NULs decodes to 1024 bytes and is accepted")
        void escapedControlKeyBoundary() {
            // Escaped control characters are legal JSON and legal keys. The budget counts the decoded
            // bytes, so 1024 NULs fit exactly; raw NUL is not legal JSON and is covered elsewhere.
            final String escapes1024 = "\\u0000".repeat(1024);
            final ExactJson.FrozenJson accepted = ExactJson.parse("{\"" + escapes1024 + "\":1}");
            final String decodedKey = accepted.toJson().getAsJsonObject().keySet().iterator().next();
            assertEquals(1024, decodedKey.length(), "1024 decoded NUL characters");
            assertEquals(1024, decodedKey.getBytes(StandardCharsets.UTF_8).length, "1024 UTF-8 bytes");
            assertEquals("\u0000".repeat(1024), decodedKey);

            // One more decoded NUL is over the key budget and must be reported at the root pointer.
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "{\"" + "\\u0000".repeat(1025) + "\":1}");
        }

        @Test
        @DisplayName("legal escaped control characters in keys and values are never rejected")
        void escapedControlsAreLegal() {
            assertEquals("{\"\\u0001\\u001f\":1}", canonicalOf("{\"\\u0001\\u001f\":1}"));
            assertEquals("{\"\\b\\f\":1}", canonicalOf("{\"\\b\\f\":1}"));
            assertEquals("{\"a\":\"\\u0000\"}", canonicalOf("{\"a\":\"\\u0000\"}"));
            assertEquals("{\"\":1}", canonicalOf("{\"\":1}"), "the empty key is legal");
            assertEquals("{\"a\":{}}", canonicalOf("{\"a\":{}}"));
        }

        @Test
        @DisplayName("multi-byte keys are measured in UTF-8 bytes, not characters")
        void multiByteKeyBudget() {
            // 341 CJK characters are 1023 bytes (legal); 342 are 1026 bytes (over budget).
            final String key341 = "键".repeat(341);
            assertEquals(1023, key341.getBytes(StandardCharsets.UTF_8).length);
            assertEquals("{\"" + key341 + "\":1}", canonicalOf("{\"" + key341 + "\":1}"));

            final String key342 = "键".repeat(342);
            assertEquals(1026, key342.getBytes(StandardCharsets.UTF_8).length);
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "", "{\"" + key342 + "\":1}");
        }

        @Test
        @DisplayName("legal ~ and / keys keep full pointer escaping in child pointers")
        void pointerEscaping() {
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a~0b", "{\"a~b\":1,\"a~b\":2}");
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a~1b", "{\"a/b\":1,\"a/b\":2}");
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/~0", "{\"~\":1,\"~\":2}");
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/~1", "{\"/\":1,\"/\":2}");
            // A failure below an escaped key composes both segments.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a~0b/c~1d",
                    "{\"a~b\":{\"c/d\":1,\"c/d\":2}}");
            // The empty key is its own pointer segment, so a duplicate reports the empty child segment.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/", "{\"\":1,\"\":2}");
        }

        @Test
        @DisplayName("legal ~ and / keys round trip unescaped in the canonical text")
        void pointerKeysRoundTrip() {
            assertEquals("{\"a~b\":1}", canonicalOf("{\"a~b\":1}"));
            assertEquals("{\"a/b\":1}", canonicalOf("{\"a/b\":1}"));
            assertEquals("{\"~\":{\"/\":1}}", canonicalOf("{\"~\":{\"/\":1}}"));
            assertEquals("{\"a~0b\":1}", canonicalOf("{\"a~0b\":1}"), "no double escaping on output");
        }

        @Test
        @DisplayName("a 1024-byte key whose text needs escaping reports the escaped child pointer")
        void maxKeyPointer() {
            // 1024 decoded '~' bytes are legal; each one becomes two pointer characters.
            final String key = "~".repeat(1024);
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/" + "~0".repeat(1024),
                    "{\"" + key + "\":1,\"" + key + "\":2}");
        }

        @Test
        @DisplayName("bad Unicode in a key is rejected before any pointer is built")
        void badUnicodeKey() {
            // The pre-scan rejects an unpaired surrogate anywhere in the text, at the document root.
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "{\"\\uD800\":1}");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "{\"\\uDC00\":1}");
            parseFails(JsonContractException.CODE_INVALID_UNICODE, "", "{\"a\\uD800b\":1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"\\uZZZZ\":1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"\\x41\":1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{\"a\":1,\"b\":\"\\u12\"}");
        }

        /**
         * Asserts only the two message properties the contract actually fixes for an oversized key:
         * the key text is not embedded, and no copy of the key inflates the message. Wording is
         * deliberately not asserted, because the contract fixes the code and the pointer, not the text.
         */
        private void assertMessageDoesNotEmbedKey(JsonContractException failure, int keyLength) {
            assertBoundedMessage(failure, keyLength);
            assertFalse(String.valueOf(failure.getMessage()).contains("~"),
                    "the message must not embed the key text");
        }

        /**
         * Builds the one large key {@link String} the fixture needs while avoiding an avoidable second
         * copy of the text.
         *
         * <p>Every route through {@code char[]} ends up with the array and the {@code String} live at the
         * same time: {@code StringBuilder.toString()} copies the backing array, and the {@code String}
         * constructor compacts a Latin-1-only {@code char[]} into a fresh {@code byte[]}. Assembling the
         * bytes directly in the compact form the {@code String} will keep avoids that extra copy, and the
         * temporary array is released straight after.</p>
         *
         * <p>What this does NOT establish: the {@code byte[]} and the resulting {@code String} are still
         * both live at the moment of construction, so this fixture alone already exceeds a 64 MiB
         * test-side heap. A small-heap failure here therefore says nothing about the codec, and this
         * method makes no claim about where an out-of-memory could or could not come from.</p>
         */
        private static String hugeTildeRun(int length) {
            final byte[] ascii = new byte[length];
            Arrays.fill(ascii, (byte) '~');
            return new String(ascii, StandardCharsets.ISO_8859_1);
        }
    }

    @Nested
    @DisplayName("mandated R02/R02.3 vectors: value-string budget, decoded duplicate keys, freeze input copy, mutable Number, cycles")
    class MandatedVectors {

        /**
         * Class 1: the decoded <em>value</em> string budget, measured in decoded UTF-8 bytes at a value
         * position, never at a key position and never in raw characters.
         *
         * <p>The accepted side's canonical byte count is derived independently from the contract's escape
         * rules (ASCII and multi-byte characters pass through unescaped; a C0 control is spelled as a
         * six-byte {@code \\u00XX} escape), so it is not read back from the codec.</p>
         */
        @Test
        @DisplayName("decoded value strings: 65536 bytes accepted, 65537 refused at /v")
        void valueStringBudget() {
            // Plain ASCII: 65536 decoded bytes is the limit and the canonical form adds only quotes.
            final String asciiAtLimit = "a".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
            final String asciiOverLimit = asciiAtLimit + "a";
            assertEquals(65536, ExactJson.MAX_STRING_UTF8_BYTES,
                    "the decoded string budget is 65536 bytes and must not be reduced");
            assertEquals(2 + 65536, canonicalValueBytes(asciiAtLimit),
                    "an accepted ASCII value is quoted with no escaping, computed independently");
            // The whole document adds exactly 6 bytes around the value: {"v": ... }.
            assertEquals(6 + 2 + 65536, ExactJson.parse("{\"v\":\"" + asciiAtLimit + "\"}").utf8Size(),
                    "the ASCII value at the limit sits exactly at the decoded budget");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/v",
                    "{\"v\":\"" + asciiOverLimit + "\"}");

            // Escaped NULs as a VALUE: the *decoded* length is what counts, not the 6x longer raw text.
            final String escapedNulsAtLimit = "\\u0000".repeat(ExactJson.MAX_STRING_UTF8_BYTES);
            final String escapedNulsOverLimit = escapedNulsAtLimit + "\\u0000";
            assertEquals(65536, exactDecodedBytes(escapedNulsAtLimit),
                    "65536 escaped NULs decode to exactly the 65536-byte budget");
            assertEquals(2 + 6 * 65536, canonicalValueBytes("\u0000".repeat(65536)),
                    "each decoded NUL is re-escaped as six bytes, counted independently");
            assertEquals(6 + 2 + 6 * 65536, ExactJson.parse("{\"v\":\"" + escapedNulsAtLimit + "\"}").utf8Size(),
                    "escaped NULs at the decoded limit are accepted and re-escaped canonically");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/v",
                    "{\"v\":\"" + escapedNulsOverLimit + "\"}");

            // Multi-byte characters are measured in UTF-8 BYTES, not characters: 21845 three-byte
            // characters plus one ASCII byte is exactly 65536 bytes.
            final String multiByteAtLimit = "\u6c49".repeat(21845) + "a";
            assertEquals(65536, exactDecodedBytes(multiByteAtLimit),
                    "21845 three-byte characters plus one ASCII byte is exactly 65536 bytes");
            assertEquals(2 + 3 * 21845 + 1, canonicalValueBytes(multiByteAtLimit),
                    "multi-byte characters pass through unescaped, counted independently");
            assertEquals(6 + 2 + 3 * 21845 + 1,
                    ExactJson.parse("{\"v\":\"" + multiByteAtLimit + "\"}").utf8Size(),
                    "the multi-byte value at the limit is accepted");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/v",
                    "{\"v\":\"" + multiByteAtLimit + "a\"}");
        }

        /**
         * Class 2: two spellings that decode to the same key are one and the same key, so the second
         * occurrence is a duplicate reported at the child pointer with full pointer escaping.
         */
        @Test
        @DisplayName("keys that differ only in spelling are duplicates, reported at the escaped child pointer")
        void decodedDuplicateKeys() {
            // A plain key and its escaped spelling are the same decoded key.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a", "{\"a\":1,\"\\u0061\":2}");
            // Pointer escaping applies to the reported child pointer: "/" becomes "~1".
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/~1", "{\"/\":1,\"\\u002f\":2}");
            // "~" becomes "~0".
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/~0", "{\"~\":1,\"\\u007e\":2}");
            // The empty key's child pointer is the bare separator, so the reported pointer is "/".
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/", "{\"\":1,\"\":2}");

            // The same decoded value must also be a duplicate when the second spelling is a raw
            // character rather than an escape, so this is about decoding and not about escape syntax.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a", "{\"\\u0061\":1,\"a\":2}");
        }

        /**
         * Class 3: {@code freeze} copies its input, so later mutation of the Gson tree cannot change an
         * already frozen value.
         *
         * <p>The input is a three-level structure (root, a child object, an array inside it). After
         * freezing, all three levels are mutated and the frozen value must still equal the literal wire
         * form and the expected typed structure captured before the mutation.</p>
         */
        @Test
        @DisplayName("freeze copies its input: mutating the source tree at all three levels cannot change it")
        void freezeCopiesInput() {
            final JsonObject child = new JsonObject();
            child.addProperty("keep", 1);
            child.addProperty("change", 2);
            final JsonArray nested = new JsonArray();
            nested.add(10);
            nested.add(20);
            child.add("nested", nested);
            final JsonObject root = new JsonObject();
            root.addProperty("top", "root");
            root.add("child", child);

            // Expectations are captured literally, before freeze runs. The array elements are Java ints,
            // which the codec canonicalises as bare exponents, so 10 and 20 are written 1E+1 and 2E+1.
            final String expectedWire =
                    "{\"child\":{\"change\":2,\"keep\":1,\"nested\":[1E+1,2E+1]},\"top\":\"root\"}";

            final ExactJson.FrozenJson frozen = ExactJson.freeze(root);
            assertEquals(expectedWire, frozen.canonical(), "the frozen wire form is the pre-mutation tree");

            // Mutate level 1 (root): remove a member and add a new one.
            root.remove("top");
            root.addProperty("added", true);
            // Mutate level 2 (child object): overwrite a value and delete a member.
            child.addProperty("change", 999);
            child.remove("keep");
            // Mutate level 3 (array): replace an element, remove one and append one.
            nested.set(0, new JsonPrimitive(777));
            nested.remove(1);
            nested.add(30);

            // The frozen value must be completely untouched by all of that.
            assertEquals(expectedWire, frozen.canonical(),
                    "the frozen canonical form is unaffected by source-tree mutation");
            assertEquals(ExactJson.parse(expectedWire), frozen,
                    "the frozen value still equals the original wire form");
            assertEquals(expectedWire, frozen.toString(),
                    "toString is the canonical form and is also unaffected");

            // The detached tree is the old structure, asserted member by member rather than just
            // non-null, so a shallow or partially shared copy would be caught here.
            final JsonObject detached = frozen.toJson().getAsJsonObject();
            assertEquals(new TreeSet<>(Arrays.asList("child", "top")), new TreeSet<>(detached.keySet()),
                    "the detached root keeps exactly the pre-mutation keys");
            assertEquals("root", detached.get("top").getAsString());
            final JsonObject detachedChild = detached.getAsJsonObject("child");
            assertEquals(new TreeSet<>(Arrays.asList("change", "keep", "nested")),
                    new TreeSet<>(detachedChild.keySet()),
                    "the detached child keeps exactly the pre-mutation keys");
            assertEquals(2, detachedChild.get("change").getAsInt(),
                    "the overwritten member has not followed the source tree");
            assertEquals(1, detachedChild.get("keep").getAsInt(),
                    "the removed member is still present in the detached tree");
            final JsonArray detachedNested = detachedChild.getAsJsonArray("nested");
            assertEquals(2, detachedNested.size(), "the detached array keeps its pre-mutation length");
            assertEquals(0, new BigDecimal(10).compareTo(detachedNested.get(0).getAsBigDecimal()),
                    "the detached first element is numerically the original value");
            assertEquals(0, new BigDecimal(20).compareTo(detachedNested.get(1).getAsBigDecimal()),
                    "the detached second element is numerically the original value");

            // Mutating the detached copy must not reach back into the frozen value either.
            detachedChild.addProperty("change", 12345);
            detachedNested.add(99);
            assertEquals(expectedWire, frozen.canonical(),
                    "the frozen value is isolated from its own detached copy");

            // A cached canonical string cannot prove that toJson() hands out a genuinely fresh deep
            // copy, so ask for a NEW detached tree after the pollution and assert every member again.
            // The tree is re-derived through the getter only; nothing is re-parsed from text, so this
            // cannot be satisfied by re-reading the wire form.
            final JsonObject fresh = frozen.toJson().getAsJsonObject();
            assertEquals(new TreeSet<>(Arrays.asList("child", "top")), new TreeSet<>(fresh.keySet()),
                    "the fresh detached root still keeps exactly the pre-mutation keys");
            assertEquals("root", fresh.get("top").getAsString(),
                    "the fresh detached root value is still unchanged");
            final JsonObject freshChild = fresh.getAsJsonObject("child");
            assertEquals(new TreeSet<>(Arrays.asList("change", "keep", "nested")),
                    new TreeSet<>(freshChild.keySet()),
                    "the fresh detached child still has the overwritten member at its old value and "
                            + "still has the member that the source tree deleted");
            assertEquals(0, new BigDecimal(2).compareTo(freshChild.get("change").getAsBigDecimal()),
                    "the fresh detached child's overwritten member is still its pre-pollution value");
            assertEquals(0, new BigDecimal(1).compareTo(freshChild.get("keep").getAsBigDecimal()),
                    "the fresh detached child's deleted-in-source member is still present and exact");
            final JsonArray freshNested = freshChild.getAsJsonArray("nested");
            assertEquals(2, freshNested.size(),
                    "the fresh detached array still has its pre-mutation length");
            assertEquals(0, new BigDecimal(10).compareTo(freshNested.get(0).getAsBigDecimal()),
                    "the fresh detached array's first element is still exactly its original value");
            assertEquals(0, new BigDecimal(20).compareTo(freshNested.get(1).getAsBigDecimal()),
                    "the fresh detached array's second element is still exactly its original value");

            // Re-freezing must reproduce the original frozen value exactly, not merely a similar one.
            final ExactJson.FrozenJson refrozen = ExactJson.freeze(frozen.toJson());
            assertEquals(frozen, refrozen,
                    "freeze(toJson()) after the pollution equals the original frozen value exactly");
            assertEquals(frozen.hashCode(), refrozen.hashCode(),
                    "the fresh round trip also agrees on hashCode");
            assertEquals(expectedWire, refrozen.canonical(),
                    "the fresh round trip reproduces the original canonical literal exactly");
            assertEquals(expectedWire, frozen.canonical(),
                    "the original frozen value is still the original canonical literal");
        }

        /**
         * Class 4: a mutable {@link Number} is snapshotted by value, and the numeric accessors of the
         * detached tree never hand back the caller's live mutable object.
         */
        @Test
        @DisplayName("a frozen mutable Number keeps its value after mutation and never exposes the live object")
        void mutableNumberSnapshotAndIsolation() {
            final long original = 1234567890123L;
            final MutatingNumber number = new MutatingNumber(original);
            final JsonObject input = new JsonObject();
            input.add("n", new JsonPrimitive(number));

            final ExactJson.FrozenJson frozen = ExactJson.freeze(input);
            assertEquals("{\"n\":" + original + "}", frozen.canonical(),
                    "the frozen value captured the number as it was at freeze time");

            // The frozen value's own numeric view must report the original, compared numerically.
            final JsonObject view = frozen.toJson().getAsJsonObject();
            final JsonElement numericView = view.get("n");
            assertTrue(numericView.isJsonPrimitive() && numericView.getAsJsonPrimitive().isNumber(),
                    "the frozen member is a JSON number");
            assertEquals(0, new BigDecimal(original).compareTo(numericView.getAsBigDecimal()),
                    "the detached number equals the original value exactly, compared as a decimal");

            // Freezing the detached tree again must reproduce exactly the same frozen value.
            final ExactJson.FrozenJson refrozen = ExactJson.freeze(frozen.toJson());
            assertEquals(frozen, refrozen, "freeze(frozen.toJson()) equals the original frozen value");
            assertEquals(frozen.hashCode(), refrozen.hashCode(),
                    "freeze(frozen.toJson()) also agrees on hashCode");
            assertEquals(frozen.canonical(), refrozen.canonical(),
                    "freeze(frozen.toJson()) reproduces the same canonical text");

            // Now mutate the caller's object. Neither assertion above may change.
            number.set(7);
            assertEquals("{\"n\":" + original + "}", frozen.canonical(),
                    "the frozen value does not follow the mutated Number");
            assertEquals(0, new BigDecimal(original).compareTo(
                            frozen.toJson().getAsJsonObject().get("n").getAsBigDecimal()),
                    "the detached number still reports the original value exactly");
            assertEquals(frozen, ExactJson.freeze(frozen.toJson()),
                    "re-freezing after the mutation still equals the original frozen value");

            // The accessors must not hand back the caller's live mutable object. A live reference would
            // let a later set() change the frozen value's own numeric view.
            final Object raw = view.get("n");
            assertFalse(raw == number,
                    "the detached tree does not expose the caller's live Number instance");
            assertFalse(view.get("n").getAsNumber() == number,
                    "getAsNumber() does not expose the caller's live Number instance either");
            assertNotEquals(999999, frozen.toJson().getAsJsonObject().get("n").getAsLong(),
                    "the detached number is unrelated to the mutated object");
        }

        /**
         * Class 5: an input graph that contains itself must be refused by the depth budget, with an
         * ordinary contract exception rather than a stack overflow or an out-of-memory error.
         *
         * <p>Both pointer shapes are asserted exactly: 65 repetitions of the child segment, which is the
         * depth budget's boundary. No reflection is used and no {@code Throwable} is swallowed: the
         * exception type is asserted, so a {@code StackOverflowError} could not be mistaken for a pass.</p>
         */
        @Test
        @DisplayName("self-referential arrays and objects are refused by the depth budget, never by a stack overflow")
        void selfReferentialInputIsRefused() {
            // An array that contains itself. Depth 65 is where the budget stops.
            final JsonArray selfArray = new JsonArray();
            selfArray.add(selfArray);
            final JsonContractException arrayFailure = assertThrows(JsonContractException.class,
                    () -> ExactJson.freeze(selfArray),
                    "a self-referential array must raise the contract exception, not a StackOverflowError");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, arrayFailure.code(),
                    "a cycle is a depth-budget refusal");
            assertEquals("/0".repeat(65), arrayFailure.path(),
                    "the reported pointer is the child segment repeated to the depth boundary");
            assertTrue(arrayFailure instanceof IllegalArgumentException,
                    "the contract exception stays a RuntimeException subtype");

            // An object that contains itself, which uses a named child segment instead of an index.
            final JsonObject selfObject = new JsonObject();
            selfObject.add("self", selfObject);
            final JsonContractException objectFailure = assertThrows(JsonContractException.class,
                    () -> ExactJson.freeze(selfObject),
                    "a self-referential object must raise the contract exception, not a StackOverflowError");
            assertEquals(JsonContractException.CODE_LIMIT_EXCEEDED, objectFailure.code(),
                    "a cycle is a depth-budget refusal");
            assertEquals("/self".repeat(65), objectFailure.path(),
                    "the reported pointer is the named child segment repeated to the depth boundary");
        }

        /** Counts the decoded UTF-8 byte length of a canonical string body, independently of the codec. */
        private int canonicalValueBytes(String decoded) {
            int bytes = 2;
            for (int i = 0; i < decoded.length(); i++) {
                final char c = decoded.charAt(i);
                final int codePoint = decoded.codePointAt(i);
                if (Character.charCount(codePoint) == 2) {
                    i++;
                }
                if (c == '"' || c == '\\') {
                    bytes += 2;
                } else if (c < 0x20) {
                    bytes += 6;
                } else if (codePoint < 0x80) {
                    bytes += 1;
                } else if (codePoint < 0x800) {
                    bytes += 2;
                } else if (codePoint < 0x10000) {
                    bytes += 3;
                } else {
                    bytes += 4;
                }
            }
            return bytes;
        }

        /** Decoded UTF-8 byte length of an input string body whose escapes are simple {@code \\uXXXX}. */
        private int exactDecodedBytes(String rawBody) {
            final String decoded = JsonParser.parseString("\"" + rawBody + "\"").getAsString();
            return decoded.getBytes(StandardCharsets.UTF_8).length;
        }

        /**
         * Class A: the {@code freeze} entry point's own boundary vectors, which the {@code parse} entry
         * point's coverage does not substitute for.
         */
        @Test
        @DisplayName("freeze(null) is JSON null and the freeze entry enforces depth and node budgets itself")
        void freezeEntryBudgets() {
            // A Java null input is the JSON null value, exactly like the explicit JsonNull element.
            assertEquals("null", ExactJson.freeze((JsonElement) null).canonical(),
                    "freeze(null) is the JSON null value");
            assertEquals(4, ExactJson.freeze((JsonElement) null).utf8Size(),
                    "the JSON null value is four canonical bytes");
            assertEquals(ExactJson.freeze(JsonNull.INSTANCE), ExactJson.freeze((JsonElement) null),
                    "a Java null and the explicit null element freeze to the same value");

            // Depth: 64 levels are accepted, 65 are refused. Both spellings of nesting are checked so
            // the boundary cannot be right by accident for one container kind.
            assertEquals(132, ExactJson.freeze(nestedElements(64, true)).utf8Size(),
                    "64 nested arrays are accepted by the freeze entry point");
            freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/0".repeat(65),
                    nestedElements(65, true));
            assertEquals(388, ExactJson.freeze(nestedElements(64, false)).utf8Size(),
                    "64 nested objects are accepted by the freeze entry point");
            freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/a".repeat(65),
                    nestedElements(65, false));

            // Nodes: an array of n nulls costs n + 1 nodes, so 499,999 members is exactly the budget
            // and 500,000 crosses it at the last member.
            assertEquals(2499996, ExactJson.freeze(nullArray(499999)).utf8Size(),
                    "an array of 499,999 nulls is exactly at the node budget");
            freezeFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/499999", nullArray(500000));
        }

        /**
         * Class B: aggregate comment and single-quote rejections through {@code parse}, each stable at
         * {@code INVALID_JSON} with the lexical contract's pointer.
         */
        @Test
        @DisplayName("aggregate documents containing comments or single quotes are refused as INVALID_JSON")
        void aggregateLexicalRejections() {
            // Comments are not JSON, in any position or spelling.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "//x\n1");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "/*x*/1");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "[1,/*x*/2]");
            // Single-quoted strings are not JSON strings.
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "{'a':1}");
            parseFails(JsonContractException.CODE_INVALID_JSON, "", "'text'");

            // The same documents must not be reachable through the freeze entry point either.
            assertThrows(JsonContractException.class, () -> ExactJson.parse("//x\n1"));
            assertThrows(JsonContractException.class, () -> ExactJson.parse("{'a':1}"));
        }

        /**
         * Class C: the two mandated number closures. Parsing a legal number and re-freezing the detached
         * tree must reproduce exactly the same frozen value.
         */
        @Test
        @DisplayName("parsed numbers close under freeze(toJson()) and stay exact through the getters")
        void numberClosureUnderRefreeze() {
            // A tiny denormal exponent canonicalises to zero.
            final ExactJson.FrozenJson tiny = ExactJson.parse("0.000e-1000000");
            assertEquals("0", tiny.canonical(), "a denormal underflow canonicalises to zero");
            assertEquals(tiny, ExactJson.freeze(tiny.toJson()),
                    "the zero closure survives freeze(toJson())");

            // An exactly 1024-character literal with a large exponent stays legal and exact.
            final String bigLiteral = "1" + "0".repeat(1017) + "e99999";
            assertEquals(1024, bigLiteral.length(),
                    "the mandated literal is exactly at the number-literal budget");
            final ExactJson.FrozenJson big = ExactJson.parse(bigLiteral);
            assertEquals("1E+101016", big.canonical(), "the large exponent canonicalises exactly");
            assertEquals(big, ExactJson.freeze(big.toJson()),
                    "the large-exponent closure survives freeze(toJson())");

            // A 50-digit integer must survive the numeric getter exactly, with no floating-point step,
            // and must still be exact after a round trip through freeze(toJson()).
            final String fiftyDigits = "9".repeat(50);
            final BigDecimal expected = new BigDecimal(fiftyDigits);
            final ExactJson.FrozenJson parsed = ExactJson.parse(fiftyDigits);
            assertEquals(0, expected.compareTo(parsed.toJson().getAsBigDecimal()),
                    "the 50-digit integer is exact in the detached tree, compared as a decimal");
            final ExactJson.FrozenJson roundTripped = ExactJson.freeze(parsed.toJson());
            assertEquals(parsed, roundTripped, "the 50-digit integer closes under freeze(toJson())");
            assertEquals(0, expected.compareTo(roundTripped.toJson().getAsBigDecimal()),
                    "the 50-digit integer is still exact after the round trip");

            // The mandated 50-digit PAIR from the original card. These two literals differ only in their
            // last digit but lie on opposite sides of the canonical-form boundary: "1" followed by 49
            // zeros collapses to a bare exponent, while the same value ending in 1 must be written out
            // in full. They are therefore genuinely different values and must not be conflated, which is
            // why each is driven through its own parse -> toJson -> freeze -> toJson chain below, and
            // why the third literal above cannot stand in for this pair.
            final String pairA = "1" + "0".repeat(49);
            final String pairB = "1" + "0".repeat(48) + "1";
            assertEquals(50, pairA.length(), "the first mandated literal is 50 characters");
            assertEquals(50, pairB.length(), "the second mandated literal is 50 characters");
            assertNotEquals(pairA, pairB, "the two mandated literals are different numbers");

            final BigDecimal expectedA = new BigDecimal(pairA);
            final BigDecimal expectedB = new BigDecimal(pairB);

            // First literal: parse, take the detached tree, re-freeze it and take the detached tree again.
            final ExactJson.FrozenJson frozenA = ExactJson.parse(pairA);
            final ExactJson.FrozenJson fromTreeA = ExactJson.freeze(frozenA.toJson());
            final ExactJson.FrozenJson fromTreeA2 = ExactJson.freeze(fromTreeA.toJson());
            assertEquals(0, expectedA.compareTo(frozenA.toJson().getAsBigDecimal()),
                    "literal A is exact in its own detached tree");
            assertEquals(0, expectedA.compareTo(fromTreeA.toJson().getAsBigDecimal()),
                    "literal A stays exact after freeze(toJson())");
            assertEquals(0, expectedA.compareTo(fromTreeA2.toJson().getAsBigDecimal()),
                    "literal A stays exact after a second freeze(toJson())");
            assertEquals("1E+49", frozenA.canonical(),
                    "literal A collapses to a bare exponent in canonical form");
            assertEquals(frozenA, fromTreeA, "literal A closes under freeze(toJson())");
            assertEquals(frozenA, fromTreeA2, "literal A closes again on the second round trip");

            // Second literal: the same chain, and it must NOT behave like the first.
            final ExactJson.FrozenJson frozenB = ExactJson.parse(pairB);
            final ExactJson.FrozenJson fromTreeB = ExactJson.freeze(frozenB.toJson());
            final ExactJson.FrozenJson fromTreeB2 = ExactJson.freeze(fromTreeB.toJson());
            assertEquals(0, expectedB.compareTo(frozenB.toJson().getAsBigDecimal()),
                    "literal B is exact in its own detached tree");
            assertEquals(0, expectedB.compareTo(fromTreeB.toJson().getAsBigDecimal()),
                    "literal B stays exact after freeze(toJson())");
            assertEquals(0, expectedB.compareTo(fromTreeB2.toJson().getAsBigDecimal()),
                    "literal B stays exact after a second freeze(toJson())");
            assertEquals(pairB, frozenB.canonical(),
                    "literal B must be written out in full, not collapsed to an exponent");
            assertEquals(frozenB, fromTreeB, "literal B closes under freeze(toJson())");
            assertEquals(frozenB, fromTreeB2, "literal B closes again on the second round trip");

            // The two must remain distinguishable at every level: canonical text, frozen value, and the
            // values actually read back. A codec that treated them as one value would fail all three.
            assertNotEquals(frozenA.canonical(), frozenB.canonical(),
                    "the two mandated literals have different canonical forms");
            assertNotEquals(frozenA, frozenB, "the two mandated literals are different frozen values");
            assertNotEquals(frozenA.hashCode(), frozenB.hashCode(),
                    "the two mandated literals hash differently");
            assertEquals(50, frozenB.utf8Size(),
                    "literal B's canonical form is the full 50 digits");
            assertEquals(5, frozenA.utf8Size(),
                    "literal A's canonical form is the 5-byte exponent form");
            assertEquals(0, expectedA.compareTo(frozenA.toJson().getAsBigDecimal())
                            + expectedB.compareTo(frozenB.toJson().getAsBigDecimal()),
                    "neither literal drifted into the other's value through the getter");
            assertEquals(0, expectedA.compareTo(frozenA.toJson().getAsBigDecimal()),
                    "literal A still reads back as A, not as B");
            assertEquals(0, expectedB.compareTo(frozenB.toJson().getAsBigDecimal()),
                    "literal B still reads back as B, not as A");
        }

        /**
         * Class D: duplicate detection on <em>decoded</em> keys inside nested containers, reported at the
         * full child pointer, and the surrogate-pair boundary of the decoded string budget.
         */
        @Test
        @DisplayName("decoded duplicate keys are found inside nested containers, and surrogate pairs count by bytes")
        void nestedDuplicateKeysAndSurrogateBoundary() {
            // The duplicate is two levels down, so the pointer must carry the full child path.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/nested/a",
                    "{\"nested\":{\"a\":1,\"\\u0061\":2}}");
            // Pointer escaping still applies to the nested reported segment.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/nested/~1",
                    "{\"nested\":{\"/\":1,\"\\u002f\":2}}");
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/nested/~0",
                    "{\"nested\":{\"~\":1,\"\\u007e\":2}}");
            // A nested empty key reports the bare separator after its parent.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/nested/",
                    "{\"nested\":{\"\":1,\"\":2}}");
            // Three levels deep, and inside an array element, both carrying the whole path.
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/a/b/x",
                    "{\"a\":{\"b\":{\"x\":1,\"\\u0078\":2}}}");
            parseFails(JsonContractException.CODE_DUPLICATE_KEY, "/0/a",
                    "[{\"a\":1,\"\\u0061\":2}]");

            // Control: the same decoded keys in DIFFERENT objects are not duplicates.
            assertEquals(34, ExactJson.parse("{\"nested\":{\"a\":1},\"other\":{\"\\u0061\":2}}").utf8Size(),
                    "equal keys in sibling objects are distinct keys, not duplicates");

            // A surrogate pair is four UTF-8 bytes, so 16384 pairs is exactly the 65536-byte budget.
            final String emoji = "\uD83D\uDE00";
            final String rawPairs = emoji.repeat(16384);
            final String escapedPairs = "\\ud83d\\ude00".repeat(16384);
            assertEquals(65536, rawPairs.getBytes(StandardCharsets.UTF_8).length,
                    "16384 surrogate pairs are exactly 65536 UTF-8 bytes");
            assertEquals(2 + 4 * 16384, canonicalValueBytes(rawPairs),
                    "each surrogate pair is four unescaped canonical bytes, counted independently");
            assertEquals(6 + 2 + 4 * 16384, ExactJson.parse("{\"v\":\"" + rawPairs + "\"}").utf8Size(),
                    "raw surrogate pairs at the byte budget are accepted");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/v", "{\"v\":\"" + rawPairs + "a\"}");
            assertEquals(6 + 2 + 4 * 16384, ExactJson.parse("{\"v\":\"" + escapedPairs + "\"}").utf8Size(),
                    "escaped surrogate pairs at the same byte budget canonicalise identically");
            parseFails(JsonContractException.CODE_LIMIT_EXCEEDED, "/v", "{\"v\":\"" + escapedPairs + "a\"}");
        }

        /** Builds {@code levels} nested arrays or objects around a JSON null, one container per level. */
        private JsonElement nestedElements(int levels, boolean asArray) {
            JsonElement current = JsonNull.INSTANCE;
            for (int i = 0; i < levels; i++) {
                if (asArray) {
                    final JsonArray array = new JsonArray();
                    array.add(current);
                    current = array;
                } else {
                    final JsonObject object = new JsonObject();
                    object.add("a", current);
                    current = object;
                }
            }
            return current;
        }

        /** Builds the node-budget fixture: an array of {@code members} JSON nulls. */
        private JsonArray nullArray(int members) {
            final JsonArray array = new JsonArray(members);
            for (int i = 0; i < members; i++) {
                array.add(JsonNull.INSTANCE);
            }
            return array;
        }
    }
}
