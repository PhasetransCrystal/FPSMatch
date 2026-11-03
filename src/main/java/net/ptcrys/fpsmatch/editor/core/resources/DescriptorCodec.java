package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Bounded, deterministic binary encoding for stored resource descriptors, built on JDK
 * {@link DataOutputStream}/{@link DataInputStream} only. This is not JSON and must not be
 * presented as such.
 *
 * <p>
 * Format: {@code int magic}, every UUID as two longs, every string as {@code int utf8Length}
 * followed by strict UTF-8 bytes, every collection as {@code int count} followed by its sorted
 * elements. A resource holds {@code id, type, name, currentVersion}; a version holds
 * {@code id, resourceId, importer, importerVersion, settings sorted by key, sourceEntry flag+value,
 * dependencyVersions sorted, outputEntries sorted}. Decoding rejects truncation, trailing bytes,
 * duplicate entries and out-of-bound values.
 */
final class DescriptorCodec {

    private DescriptorCodec() {}

    @FunctionalInterface
    private interface Body {

        void write(DataOutputStream out) throws IOException;
    }

    static byte[] encode(LogicalResource resource) {
        return encode(resourceBody(resource));
    }

    static byte[] encode(ResourceVersion version) {
        return encode(versionBody(version));
    }

    /** Measures the exact encoded size without allocating the payload, rejecting an oversize one. */
    static int encodedSize(LogicalResource resource) {
        return measure(resourceBody(resource));
    }

    /** Measures the exact encoded size without allocating the payload, rejecting an oversize one. */
    static int encodedSize(ResourceVersion version) {
        return measure(versionBody(version));
    }

    private static Body resourceBody(LogicalResource resource) {
        return out -> {
            out.writeInt(ResourceSupport.MAGIC_RESOURCE);
            writeUuid(out, resource.id());
            writeString(out, resource.type().toString());
            writeString(out, resource.name());
            writeUuid(out, resource.currentVersion());
        };
    }

    private static Body versionBody(ResourceVersion version) {
        return out -> {
            out.writeInt(ResourceSupport.MAGIC_VERSION);
            writeUuid(out, version.id());
            writeUuid(out, version.resourceId());
            writeString(out, version.importer().toString());
            writeString(out, version.importerVersion());
            writeSettings(out, version.settings());
            if (version.sourceEntry() == null) {
                out.writeByte(0);
            } else {
                out.writeByte(1);
                writeUuid(out, version.sourceEntry());
            }
            writeUuids(out, version.dependencyVersions());
            writeUuids(out, version.outputEntries());
        };
    }

    static LogicalResource decodeResource(byte[] payload) {
        DataInputStream in = stream(payload);
        try {
            requireMagic(in, ResourceSupport.MAGIC_RESOURCE, "resource");
            UUID id = readUuid(in);
            EditorId type = parseId(readString(in));
            String name = readString(in);
            UUID currentVersion = readUuid(in);
            requireExhausted(in);
            return new LogicalResource(id, type, name, currentVersion);
        } catch (IOException failure) {
            throw ResourceSupport.invalid("truncated resource descriptor: " + failure);
        }
    }

    static ResourceVersion decodeVersion(byte[] payload) {
        DataInputStream in = stream(payload);
        try {
            requireMagic(in, ResourceSupport.MAGIC_VERSION, "resource version");
            UUID id = readUuid(in);
            UUID resourceId = readUuid(in);
            EditorId importer = parseId(readString(in));
            String importerVersion = readString(in);
            Map<String, String> settings = readSettings(in);
            UUID sourceEntry = readSourceEntry(in);
            Set<UUID> dependencyVersions = readUuids(in);
            Set<UUID> outputEntries = readUuids(in);
            requireExhausted(in);
            return new ResourceVersion(id, resourceId, importer, importerVersion, settings,
                    sourceEntry, dependencyVersions, outputEntries);
        } catch (IOException failure) {
            throw ResourceSupport.invalid("truncated resource version descriptor: " + failure);
        }
    }

    /**
     * Encodes through a bounded sink that refuses a byte past the descriptor budget before it is
     * appended, so an oversized descriptor is never materialized. The size is measured first and the
     * payload is then written into an exactly sized array; no full-size candidate array is built.
     */
    private static byte[] encode(Body body) {
        int size = measure(body);
        byte[] payload = new byte[size];
        write(body, new Sink(payload, ResourceSupport.MAX_DESCRIPTOR_BYTES));
        return payload;
    }

    private static int measure(Body body) {
        Sink sink = new Sink(null, ResourceSupport.MAX_DESCRIPTOR_BYTES);
        write(body, sink);
        return sink.count();
    }

    private static void write(Body body, Sink sink) {
        try {
            DataOutputStream out = new DataOutputStream(sink);
            body.write(out);
            out.flush();
        } catch (IOException failure) {
            throw new IllegalStateException("in-memory descriptor encoding failed", failure);
        }
    }

    /** Counts and optionally stores bytes, failing as soon as the frozen per-descriptor budget is passed. */
    private static final class Sink extends OutputStream {

        private final byte[] target;
        private final int limit;
        private int count;

        Sink(byte[] target, int limit) {
            this.target = target;
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            if (target != null) {
                target[count - 1] = (byte) value;
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length < 0 || offset < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException("invalid byte range");
            }
            reserve(length);
            if (target != null) {
                System.arraycopy(bytes, offset, target, count - length, length);
            }
        }

        private void reserve(int length) throws IOException {
            if (length > limit - count) {
                throw ResourceSupport.invalid("resource descriptor exceeds " + limit + " bytes");
            }
            count += length;
        }

        int count() {
            return count;
        }
    }

    private static DataInputStream stream(byte[] payload) {
        if (payload == null || payload.length > ResourceSupport.MAX_DESCRIPTOR_BYTES) {
            throw ResourceSupport.invalid("resource descriptor payload is missing or oversized");
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void requireMagic(DataInputStream in, int expected, String label) throws IOException {
        int magic = in.readInt();
        if (magic != expected) {
            throw ResourceSupport.invalid("unsupported " + label + " descriptor magic: " + magic);
        }
    }

    private static void requireExhausted(DataInputStream in) throws IOException {
        if (in.read() != -1) {
            throw ResourceSupport.invalid("resource descriptor has trailing bytes");
        }
    }

    private static EditorId parseId(String value) {
        try {
            return EditorId.parse(value);
        } catch (IllegalArgumentException failure) {
            throw ResourceSupport.invalid("resource descriptor carries an invalid editor identity: " + value);
        }
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static UUID readSourceEntry(DataInputStream in) throws IOException {
        int present = in.readUnsignedByte();
        if (present == 0) {
            return null;
        }
        if (present != 1) {
            throw ResourceSupport.invalid("resource version source flag is neither present nor absent");
        }
        return readUuid(in);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = encodeUtf8(value);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > ResourceSupport.MAX_STRING_BYTES) {
            throw ResourceSupport.invalid("resource descriptor string length out of range: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return decodeUtf8(bytes);
    }

    private static void writeUuids(DataOutputStream out, Set<UUID> ids) throws IOException {
        List<UUID> sorted = new ArrayList<>(ids);
        Collections.sort(sorted);
        if (sorted.size() > ResourceSupport.MAX_COLLECTION) {
            throw ResourceSupport.invalid("too many resource dependency identities: " + sorted.size());
        }
        out.writeInt(sorted.size());
        for (UUID id : sorted) {
            writeUuid(out, id);
        }
    }

    private static Set<UUID> readUuids(DataInputStream in) throws IOException {
        int count = readCount(in, ResourceSupport.MAX_COLLECTION, "identity");
        Set<UUID> ids = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            UUID id = readUuid(in);
            if (!ids.add(id)) {
                throw ResourceSupport.invalid("resource descriptor repeats an identity: " + id);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private static void writeSettings(DataOutputStream out, Map<String, String> settings) throws IOException {
        List<Map.Entry<String, String>> sorted = new ArrayList<>(settings.entrySet());
        sorted.sort(Map.Entry.comparingByKey());
        out.writeInt(sorted.size());
        for (Map.Entry<String, String> entry : sorted) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
    }

    private static Map<String, String> readSettings(DataInputStream in) throws IOException {
        int count = readCount(in, ResourceSupport.MAX_SETTINGS, "setting");
        TreeMap<String, String> settings = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String key = readString(in);
            String value = readString(in);
            if (settings.put(key, value) != null) {
                throw ResourceSupport.invalid("resource descriptor repeats a setting key: " + key);
            }
        }
        return Collections.unmodifiableMap(settings);
    }

    private static int readCount(DataInputStream in, int maximum, String label) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > maximum) {
            throw ResourceSupport.invalid("resource descriptor " + label + " count out of range: " + count);
        }
        return count;
    }

    private static byte[] encodeUtf8(String value) {
        // Every UTF-16 code unit costs at least one UTF-8 byte, so an over-long character count is
        // already fatal and must be rejected before any encoding buffer is allocated.
        if (value.length() > ResourceSupport.MAX_STRING_BYTES) {
            throw ResourceSupport.invalid("resource descriptor string exceeds " + ResourceSupport.MAX_STRING_BYTES + " characters");
        }
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            ByteBuffer encoded = encoder.encode(CharBuffer.wrap(value));
            if (encoded.remaining() > ResourceSupport.MAX_STRING_BYTES) {
                throw ResourceSupport.invalid("resource descriptor string exceeds " + ResourceSupport.MAX_STRING_BYTES + " bytes");
            }
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException failure) {
            throw ResourceSupport.invalid("resource descriptor string is not valid Unicode");
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw ResourceSupport.invalid("resource descriptor string is not strict UTF-8");
        }
    }
}
