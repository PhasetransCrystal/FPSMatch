package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * An immutable view of the resource graph plus the asset byte entries it may reference.
 * All maps are defensively copied, keyed by the immutable identity of their value, and bounded to
 * {@value ResourceSupport#MAX_NODES} nodes in total; the bound is checked on the declared sizes
 * before any map is copied.
 *
 * <p>
 * {@code byteEntries} holds asset byte entries only: staged descriptors are never part of it. The
 * relationship rules that decide whether a byte entry may be staged at all are enforced by {@link
 * ResourceStorageBridge#stage} and {@link ResourceStorageBridge#read}, so a catalog can be built and
 * then rejected with a diagnostic instead of failing while the caller assembles it.
 */
public record ResourceCatalog(Map<UUID, LogicalResource> resources, Map<UUID, ResourceVersion> versions,
                              Map<UUID, ProjectState.Entry> byteEntries) {

    public ResourceCatalog {
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(byteEntries, "byteEntries");
        // The node budget is settled on the declared sizes before anything is copied, so an oversized
        // catalog is refused without walking or duplicating a single entry.
        long nodes = (long) resources.size() + versions.size() + byteEntries.size();
        if (nodes > ResourceSupport.MAX_NODES) {
            throw ResourceSupport.invalid("catalog holds " + nodes + " nodes, above the bound of " + ResourceSupport.MAX_NODES);
        }
        resources = copyMap(resources, "resources", LogicalResource::id);
        versions = copyMap(versions, "versions", ResourceVersion::id);
        byteEntries = copyByteEntries(byteEntries);
    }

    private static <T> Map<UUID, T> copyMap(Map<UUID, T> source, String label,
                                            java.util.function.Function<T, UUID> identity) {
        Objects.requireNonNull(source, label);
        TreeMap<UUID, T> copy = new TreeMap<>();
        for (Map.Entry<UUID, T> entry : source.entrySet()) {
            UUID key = Objects.requireNonNull(entry.getKey(), label + " key");
            T value = Objects.requireNonNull(entry.getValue(), label + " value");
            if (!key.equals(identity.apply(value))) {
                throw ResourceSupport.invalid(label + " key does not match its value identity");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Map<UUID, ProjectState.Entry> copyByteEntries(Map<UUID, ProjectState.Entry> source) {
        Objects.requireNonNull(source, "byteEntries");
        TreeMap<UUID, ProjectState.Entry> copy = new TreeMap<>();
        for (Map.Entry<UUID, ProjectState.Entry> entry : source.entrySet()) {
            UUID key = Objects.requireNonNull(entry.getKey(), "byte entry key");
            ProjectState.Entry value = Objects.requireNonNull(entry.getValue(), "byte entry");
            if (!key.equals(value.id())) {
                throw ResourceSupport.invalid("byte entry key does not match its identity");
            }
            if (value.content().kind() != BlobRef.Kind.ASSET) {
                throw ResourceSupport.invalid("resource catalog holds a non-asset byte entry");
            }
            copy.put(key, value);
        }
        return Collections.unmodifiableMap(copy);
    }
}
