package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Package-private bounds, descriptor ownership and defensive copying for the resource module.
 * No public type is added here; the resource bridge owns exactly the fixed storage identities.
 */
final class ResourceSupport {

    /** Stored descriptor ownership: the resource module, never the importer. */
    static final String OWNER = "editor:resources";
    static final String TYPE_RESOURCE = "editor:resource";
    static final String TYPE_VERSION = "editor:resource_version";
    static final String CODEC = "editor:resource_binary";
    static final int SCHEMA = 1;

    static final int MAGIC_RESOURCE = 0x45525231;
    static final int MAGIC_VERSION = 0x45525631;

    static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    static final int MAX_COLLECTION = 8192;
    static final int MAX_SETTINGS = 256;
    static final int MAX_STRING_BYTES = 65536;

    /** Frozen first-review budgets: nodes across all three maps, dependency edges, staged descriptor bytes. */
    static final int MAX_NODES = 8192;
    static final int MAX_EDGES = 65536;
    static final long MAX_TOTAL_DESCRIPTOR_BYTES = 64L * 1024 * 1024;

    /** Mirrors the storage metadata bound so a descriptor never fails after content was staged. */
    static final int MAX_DISPLAY_NAME_CHARS = 4096;

    private ResourceSupport() {}

    static IllegalArgumentException invalid(String diagnostic) {
        return new IllegalArgumentException(diagnostic);
    }

    static Map<String, String> copySettings(Map<String, String> source) {
        Objects.requireNonNull(source, "settings");
        if (source.size() > MAX_SETTINGS) {
            throw invalid("too many resource settings: " + source.size());
        }
        TreeMap<String, String> copy = new TreeMap<>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "setting key");
            String value = Objects.requireNonNull(entry.getValue(), "setting value");
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    static Set<UUID> copyIds(Set<UUID> source, String label) {
        Objects.requireNonNull(source, label);
        if (source.size() > MAX_COLLECTION) {
            throw invalid("too many " + label + ": " + source.size());
        }
        TreeSet<UUID> copy = new TreeSet<>();
        for (UUID id : source) {
            copy.add(Objects.requireNonNull(id, label + " element"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /** Rejects a display name an immutable storage entry could not carry. */
    static void requireDisplayName(String name) {
        Objects.requireNonNull(name, "displayName");
        if (name.length() > MAX_DISPLAY_NAME_CHARS) {
            throw invalid("resource display name exceeds the metadata bound");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == name.length() || !Character.isLowSurrogate(name.charAt(i))) {
                    throw invalid("resource display name has an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw invalid("resource display name has an unpaired surrogate");
            }
        }
    }

    /**
     * Rejects a byte entry the resource bridge could not hand back as plain byte content: it must be
     * asset content, must not depend on a document, and must not claim a resource descriptor
     * identity (owner, type or codec). Applying the same rule while staging and while reading keeps
     * a successful {@code stage} readable by {@code read}.
     */
    static void requireByteEntry(UUID id, ProjectState.Entry entry) {
        Objects.requireNonNull(id, "byte entry id");
        if (entry.content().kind() != BlobRef.Kind.ASSET) {
            throw invalid("resource byte entry is not asset content: " + id);
        }
        if (!entry.documentDependencies().isEmpty()) {
            throw invalid("resource byte entry must not depend on a document: " + id);
        }
        if (OWNER.equals(entry.ownerModule()) || CODEC.equals(entry.codecId()) || TYPE_RESOURCE.equals(entry.typeId()) || TYPE_VERSION.equals(entry.typeId())) {
            throw invalid("resource byte entry claims a resource descriptor identity: " + id);
        }
    }

    /** True when the entry presents itself in the fixed descriptor shape the bridge owns. */
    static boolean claimsDescriptorIdentity(ProjectState.Entry entry) {
        return OWNER.equals(entry.ownerModule()) || CODEC.equals(entry.codecId()) || TYPE_RESOURCE.equals(entry.typeId()) || TYPE_VERSION.equals(entry.typeId());
    }

    /** Enforces the frozen cumulative descriptor budget without ever allocating the whole payload set. */
    static void requireTotalDescriptorBytes(long total) {
        if (total > MAX_TOTAL_DESCRIPTOR_BYTES) {
            throw invalid("resource descriptors exceed the total budget of " + MAX_TOTAL_DESCRIPTOR_BYTES + " bytes");
        }
    }

    /**
     * The dependency union a version declares: its source entry, its version dependencies and its
     * outputs, counted once each. A source entry that is also an output is one edge, not two, because
     * the stored manifest is exactly this union and the edge budget must agree with it.
     */
    static Set<UUID> versionDependencies(ResourceVersion version) {
        Objects.requireNonNull(version, "version");
        TreeSet<UUID> dependencies = new TreeSet<>(version.dependencyVersions());
        dependencies.addAll(version.outputEntries());
        if (version.sourceEntry() != null) {
            dependencies.add(version.sourceEntry());
        }
        return Collections.unmodifiableSet(dependencies);
    }

    /**
     * A destination that refuses to buffer more than {@code limit} bytes, so a descriptor is never
     * materialized past its budget. Closing it is a no-op: the store owns the transfer, not the sink.
     */
    static final class BoundedByteSink extends OutputStream {

        private final ByteArrayOutputStream buffer;
        private final int limit;
        private int count;

        BoundedByteSink(int limit) {
            this.limit = limit;
            this.buffer = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            buffer.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.requireNonNull(bytes, "bytes");
            if (length < 0 || offset < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException("invalid byte range");
            }
            reserve(length);
            buffer.write(bytes, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length > limit - count) {
                throw invalid("resource descriptor exceeds " + limit + " bytes");
            }
            count += length;
        }

        byte[] toByteArray() {
            return buffer.toByteArray();
        }

        @Override
        public void close() {
            // Deliberately inert: the buffered content stays readable after the transfer ends.
        }
    }
}
