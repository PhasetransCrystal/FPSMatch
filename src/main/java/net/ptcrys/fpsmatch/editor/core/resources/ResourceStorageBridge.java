package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.core.storage.BlobRef;
import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The only bridge between the resource graph and the project store. It never commits, never touches
 * a world or a runtime and never rewrites the caller's project state.
 *
 * <p>
 * Storage semantics are fixed: descriptors are asset entries owned by {@value
 * ResourceSupport#OWNER}, typed {@value ResourceSupport#TYPE_RESOURCE} or {@value
 * ResourceSupport#TYPE_VERSION}, written with codec {@value ResourceSupport#CODEC} at schema
 * {@value ResourceSupport#SCHEMA}, and encoded with a bounded JDK binary format rather than JSON.
 *
 * <p>
 * Caller obligations: the returned entries must be merged into a project state whose
 * {@code modules} map names {@code editor:resources} and every module that actually imported a
 * version, because each descriptor entry is owned by {@code editor:resources} while the importing
 * module stays recorded inside the version payload.
 *
 * <p>
 * The selection a publish writes is issued once, by {@link #plan}, and is a fixed snapshot of the
 * reachable closure, so a caller that enumerates producers and modules beforehand describes exactly
 * the descriptors {@link #stage} writes rather than a second traversal that could drift.
 *
 * <p>
 * Failure contract: an invalid catalog, an unknown or misattributed reference, a missing
 * reference, overlapping identities, a dependency cycle, an illegal byte entry or a corrupt
 * descriptor is rejected with {@link IllegalArgumentException} before any content is staged, so a
 * rejected call writes no descriptor. {@link #stage} then verifies every selected blob exists, and
 * only afterwards publishes the descriptors. A real store I/O failure after publication began
 * surfaces as {@link IOException} and may leave an unreferenced blob behind: this bridge does not
 * claim a disk transaction it cannot provide.
 */
public final class ResourceStorageBridge {

    private ResourceStorageBridge() {}

    /**
     * Validates the catalog, resolves the reachable closure of the logical resources' current
     * versions plus {@code requiredRefs}, and stages every descriptor's content as an asset blob.
     *
     * <p>
     * This overload is exactly {@code stage(store, plan(catalog, requiredRefs))}: the closure,
     * the descriptor encodability check and the cumulative descriptor budget happen with no store
     * access, then every unique reachable {@link BlobRef} is verified against the store, and only
     * then is each descriptor encoded and staged one at a time so no more than one payload is held
     * in memory.
     *
     * @return unmodifiable asset entries (descriptors and the reachable byte entries) to merge into
     *         a project state; the caller still owns the commit and the modules map
     */
    public static Map<UUID, ProjectState.Entry> stage(LocalProjectStore store, ResourceCatalog catalog,
                                                      Set<ResourceRef> requiredRefs) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(requiredRefs, "requiredRefs");
        return stage(store, plan(catalog, requiredRefs));
    }

    /**
     * Resolves the one fixed selection a publish will use, with no store access at all: the reachable
     * closure of the logical resources' current versions plus {@code requiredRefs}, the structural,
     * cycle and identity validation of the whole catalog, the descriptor encodability of the selected
     * node and the cumulative descriptor budget, in that order.
     *
     * <p>
     * Locked old versions keep their version dependencies and outputs, unreachable old versions
     * stay out, and a missing reference is refused instead of silently filled in. The result never
     * follows the caller afterwards: a later change to {@code requiredRefs}, to a caller map or to
     * whatever is "current" can only be seen by a new plan.
     */
    public static ResourcePlan plan(ResourceCatalog catalog, Set<ResourceRef> requiredRefs) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(requiredRefs, "requiredRefs");
        ResourceGraph graph = ResourceGraph.closure(catalog, requiredRefs);
        requireDescriptorBudget(catalog, graph);
        return new ResourcePlan(select(catalog.resources(), graph.resourceOrder()),
                select(catalog.versions(), graph.versionOrder()),
                select(catalog.byteEntries(), graph.byteOrder()));
    }

    /**
     * Validates and retains the complete admitted catalog, including inactive versions and byte
     * entries. This explicit selection is for resource-directory saves, which do not perform GC.
     * The ordinary {@link #plan} continues to select only its reachable closure.
     */
    public static ResourcePlan planAll(ResourceCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        ResourceGraph.verify(catalog);
        long total = 0;
        for (ResourceVersion version : catalog.versions().values()) {
            total += DescriptorCodec.encodedSize(version);
            ResourceSupport.requireTotalDescriptorBytes(total);
        }
        for (LogicalResource resource : catalog.resources().values()) {
            ResourceSupport.requireDisplayName(resource.name());
            total += DescriptorCodec.encodedSize(resource);
            ResourceSupport.requireTotalDescriptorBytes(total);
        }
        return new ResourcePlan(catalog.resources(), catalog.versions(), catalog.byteEntries());
    }

    /**
     * Stages the fixed selection of {@code plan}: relationships, encodability and budgets were
     * settled when the plan was issued, so this call starts with the store.
     *
     * <p>
     * Order is deliberate: every unique selected byte {@link BlobRef} is verified against the
     * store first, and only after all of them are readable is each descriptor encoded and staged one
     * at a time, so no more than one payload is held in memory, with the byte entries added last. A
     * plan is never re-derived here: this call does not re-read a current version, re-infer the
     * caller's locked references or keep the caller's collections.
     *
     * @return unmodifiable, identity-sorted asset entries to merge into a project state
     */
    public static Map<UUID, ProjectState.Entry> stage(LocalProjectStore store, ResourcePlan plan)
                                                                                                  throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(plan, "plan");

        Set<BlobRef> verified = new LinkedHashSet<>();
        for (ProjectState.Entry entry : plan.byteEntries().values()) {
            BlobRef content = entry.content();
            if (verified.add(content)) {
                store.copyContent(content, OutputStream.nullOutputStream());
            }
        }

        Map<UUID, ProjectState.Entry> staged = new TreeMap<>();
        for (Map.Entry<UUID, ResourceVersion> item : plan.versions().entrySet()) {
            UUID id = item.getKey();
            ResourceVersion version = item.getValue();
            staged.put(id, descriptor(id, ResourceSupport.TYPE_VERSION, id.toString(),
                    ResourceSupport.versionDependencies(version),
                    store.stage(BlobRef.Kind.ASSET, DescriptorCodec.encode(version))));
        }
        for (Map.Entry<UUID, LogicalResource> item : plan.resources().entrySet()) {
            UUID id = item.getKey();
            LogicalResource resource = item.getValue();
            staged.put(id, descriptor(id, ResourceSupport.TYPE_RESOURCE, resource.name(),
                    Set.of(resource.currentVersion()),
                    store.stage(BlobRef.Kind.ASSET, DescriptorCodec.encode(resource))));
        }
        for (Map.Entry<UUID, ProjectState.Entry> item : plan.byteEntries().entrySet()) {
            staged.put(item.getKey(), item.getValue());
        }
        return Collections.unmodifiableMap(staged);
    }

    /**
     * Descriptor encodability and the cumulative descriptor budget of a resolved selection, in the
     * order the original stage used: versions first, then the resources' display names and payloads.
     */
    private static void requireDescriptorBudget(ResourceCatalog catalog, ResourceGraph graph) {
        long total = 0;
        for (UUID id : graph.versionOrder()) {
            total += DescriptorCodec.encodedSize(catalog.versions().get(id));
            ResourceSupport.requireTotalDescriptorBytes(total);
        }
        for (UUID id : graph.resourceOrder()) {
            LogicalResource resource = catalog.resources().get(id);
            ResourceSupport.requireDisplayName(resource.name());
            total += DescriptorCodec.encodedSize(resource);
            ResourceSupport.requireTotalDescriptorBytes(total);
        }
    }

    /** Copies the nodes the closure selected, in the order the closure resolved them. */
    private static <T> Map<UUID, T> select(Map<UUID, T> source, List<UUID> order) {
        Map<UUID, T> selected = new TreeMap<>();
        for (UUID id : order) {
            selected.put(id, source.get(id));
        }
        return selected;
    }

    /**
     * Decodes every resource descriptor it understands and keeps every other asset entry verbatim
     * as byte content, then validates the full relationship graph of the recovered catalog.
     *
     * <p>
     * Budgets are settled first, from declared metadata only: more than the node bound of entries,
     * more than the edge bound of declared dependencies, an oversized descriptor or an oversized
     * cumulative descriptor total is refused before a single entry is copied and before any blob is
     * opened. Each descriptor is then fetched with a bounded sink, so an oversized payload is refused
     * before it is buffered, and its manifest must agree exactly with the payload it was rebuilt from:
     * a resource depends on its current version alone, a version depends on its source, version
     * dependencies and outputs, and neither may depend on a document. A missing or extra declared
     * edge is rejected rather than silently repaired from the payload. Every byte entry's content is
     * then read back through the store, so a missing or corrupt blob fails this call with a real
     * {@link IOException} instead of yielding a plausible catalog.
     */
    public static ResourceCatalog read(LocalProjectStore store, Map<UUID, ProjectState.Entry> entries)
                                                                                                       throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(entries, "entries");
        // Budgets come first, on declared metadata alone: an oversized catalog or an oversized edge
        // set is refused before any entry is copied and before any blob is opened.
        if (entries.size() > ResourceSupport.MAX_NODES) {
            throw ResourceSupport.invalid("resource entries hold " + entries.size() + " nodes, above the bound of " + ResourceSupport.MAX_NODES);
        }
        Map<UUID, ProjectState.Entry> ordered = new TreeMap<>();
        long total = 0;
        long edges = 0;
        for (Map.Entry<UUID, ProjectState.Entry> item : entries.entrySet()) {
            UUID key = Objects.requireNonNull(item.getKey(), "entry key");
            ProjectState.Entry entry = Objects.requireNonNull(item.getValue(), "entry");
            if (!key.equals(entry.id())) {
                throw ResourceSupport.invalid("asset entry key does not match its identity: " + key);
            }
            if (entry.content().kind() != BlobRef.Kind.ASSET) {
                throw ResourceSupport.invalid("resource catalog does not accept document content: " + key);
            }
            edges += (long) entry.assetDependencies().size() + entry.documentDependencies().size();
            if (edges > ResourceSupport.MAX_EDGES) {
                throw ResourceSupport.invalid("resource entries declare " + edges + " dependency edges, above the bound of " + ResourceSupport.MAX_EDGES);
            }
            if (ResourceSupport.claimsDescriptorIdentity(entry)) {
                requireDescriptorShape(entry);
                if (entry.content().size() > ResourceSupport.MAX_DESCRIPTOR_BYTES) {
                    throw ResourceSupport.invalid("resource descriptor exceeds " + ResourceSupport.MAX_DESCRIPTOR_BYTES + " bytes: " + key);
                }
                total += entry.content().size();
                ResourceSupport.requireTotalDescriptorBytes(total);
            } else {
                ResourceSupport.requireByteEntry(key, entry);
            }
            ordered.put(key, entry);
        }

        Map<UUID, LogicalResource> resources = new TreeMap<>();
        Map<UUID, ResourceVersion> versions = new TreeMap<>();
        Map<UUID, ProjectState.Entry> byteEntries = new TreeMap<>();
        for (Map.Entry<UUID, ProjectState.Entry> item : ordered.entrySet()) {
            UUID key = item.getKey();
            ProjectState.Entry entry = item.getValue();
            if (!ResourceSupport.claimsDescriptorIdentity(entry)) {
                byteEntries.put(key, entry);
                continue;
            }
            byte[] payload = readDescriptor(store, entry);
            if (ResourceSupport.TYPE_RESOURCE.equals(entry.typeId())) {
                LogicalResource resource = DescriptorCodec.decodeResource(payload);
                if (!resource.id().equals(entry.id())) {
                    throw ResourceSupport.invalid("resource descriptor identity mismatch: " + key);
                }
                requireManifest(entry, Set.of(resource.currentVersion()), "resource");
                resources.put(resource.id(), resource);
            } else {
                ResourceVersion version = DescriptorCodec.decodeVersion(payload);
                if (!version.id().equals(entry.id())) {
                    throw ResourceSupport.invalid("resource version descriptor identity mismatch: " + key);
                }
                requireManifest(entry, ResourceSupport.versionDependencies(version), "resource version");
                versions.put(version.id(), version);
            }
        }

        Set<BlobRef> verified = new LinkedHashSet<>();
        for (ProjectState.Entry entry : byteEntries.values()) {
            if (verified.add(entry.content())) {
                store.copyContent(entry.content(), OutputStream.nullOutputStream());
            }
        }
        ResourceCatalog catalog = new ResourceCatalog(resources, versions, byteEntries);
        ResourceGraph.verify(catalog);
        return catalog;
    }

    private static ProjectState.Entry descriptor(UUID id, String typeId, String displayName,
                                                 Set<UUID> dependencies, BlobRef content) {
        return new ProjectState.Entry(id, ResourceSupport.OWNER, typeId, ResourceSupport.CODEC,
                ResourceSupport.SCHEMA, displayName, content, Set.of(), dependencies);
    }

    private static void requireDescriptorShape(ProjectState.Entry entry) {
        if (!ResourceSupport.OWNER.equals(entry.ownerModule()) || !ResourceSupport.CODEC.equals(entry.codecId()) || entry.schemaVersion() != ResourceSupport.SCHEMA || (!ResourceSupport.TYPE_RESOURCE.equals(entry.typeId()) && !ResourceSupport.TYPE_VERSION.equals(entry.typeId())) || !entry.documentDependencies().isEmpty()) {
            throw ResourceSupport.invalid("unsupported resource descriptor shape for entry " + entry.id());
        }
    }

    /** Rejects a descriptor whose declared dependencies are not exactly what its payload implies. */
    private static void requireManifest(ProjectState.Entry entry, Set<UUID> expected, String label) {
        if (!entry.assetDependencies().equals(expected)) {
            throw ResourceSupport.invalid(label + " descriptor " + entry.id() + " declares asset dependencies " + new TreeSet<>(entry.assetDependencies()) + " instead of " + new TreeSet<>(expected));
        }
    }

    private static byte[] readDescriptor(LocalProjectStore store, ProjectState.Entry entry) throws IOException {
        ResourceSupport.BoundedByteSink sink = new ResourceSupport.BoundedByteSink(ResourceSupport.MAX_DESCRIPTOR_BYTES);
        store.copyContent(entry.content(), sink);
        return sink.toByteArray();
    }
}
