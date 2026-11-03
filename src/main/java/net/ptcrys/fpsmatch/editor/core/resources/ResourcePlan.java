package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One fixed resource selection, issued by {@link ResourceStorageBridge#plan}: exactly the logical
 * resources, resource versions and asset byte entries a publish will write, narrowed to the
 * reachable closure the bridge resolved.
 *
 * <p>
 * The constructor is package-private, so only the resource bridge issues a plan: a caller may
 * enumerate and stage a plan but cannot fabricate one. Every map is defensively copied, ordered by
 * UUID and immutable, and every value is an existing immutable description, so a plan holds no
 * store, thread, plugin, UI or caller collection. A plan is not a durable handle and is never
 * serialized; the descriptors it lists are valid only for the catalog they were resolved from.
 */
public final class ResourcePlan {

    private final Map<UUID, LogicalResource> resources;
    private final Map<UUID, ResourceVersion> versions;
    private final Map<UUID, ProjectState.Entry> byteEntries;

    ResourcePlan(Map<UUID, LogicalResource> resources, Map<UUID, ResourceVersion> versions,
                 Map<UUID, ProjectState.Entry> byteEntries) {
        this.resources = copy(resources, "resources");
        this.versions = copy(versions, "versions");
        this.byteEntries = copy(byteEntries, "byteEntries");
    }

    /** The logical resources whose descriptors this plan writes, ordered by identity. */
    public Map<UUID, LogicalResource> resources() {
        return resources;
    }

    /** Every resource version this plan writes, including locked old versions, ordered by identity. */
    public Map<UUID, ResourceVersion> versions() {
        return versions;
    }

    /** The reachable asset byte entries this plan carries verbatim, ordered by identity. */
    public Map<UUID, ProjectState.Entry> byteEntries() {
        return byteEntries;
    }

    private static <T> Map<UUID, T> copy(Map<UUID, T> source, String label) {
        Objects.requireNonNull(source, label);
        TreeMap<UUID, T> copy = new TreeMap<>();
        for (Map.Entry<UUID, T> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), label + " key"),
                    Objects.requireNonNull(entry.getValue(), label + " value"));
        }
        return Collections.unmodifiableMap(copy);
    }
}
