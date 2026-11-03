package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Package-private relationship validation, cycle checking and reachability closure for the resource
 * graph.
 *
 * <p>
 * The whole catalog is validated structurally and every loaded identity is checked for a cycle,
 * whether or not the caller's roots can reach it, so a corrupt unreachable node is never accepted.
 * Only {@link #closure} narrows the graph: the reachable closure of the logical resources' current
 * versions plus the caller's locked references, so unreachable old versions are ignored rather than
 * staged. Traversal is iterative, so long chains cannot overflow the stack.
 */
final class ResourceGraph {

    private static final int GRAY = 1;
    private static final int BLACK = 2;

    private final List<UUID> resourceOrder;
    private final List<UUID> versionOrder;
    private final List<UUID> byteOrder;

    private ResourceGraph(List<UUID> resourceOrder, List<UUID> versionOrder, List<UUID> byteOrder) {
        this.resourceOrder = List.copyOf(resourceOrder);
        this.versionOrder = List.copyOf(versionOrder);
        this.byteOrder = List.copyOf(byteOrder);
    }

    List<UUID> resourceOrder() {
        return resourceOrder;
    }

    List<UUID> versionOrder() {
        return versionOrder;
    }

    List<UUID> byteOrder() {
        return byteOrder;
    }

    /** Validates a catalog that is only read back: every loaded node is checked, not just its roots. */
    static void verify(ResourceCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        validate(catalog, Set.of());
        requireAcyclic(catalog);
    }

    static ResourceGraph closure(ResourceCatalog catalog, Set<ResourceRef> requiredRefs) {
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(requiredRefs, "requiredRefs");
        validate(catalog, requiredRefs);
        requireAcyclic(catalog);

        Map<UUID, Integer> color = new HashMap<>();
        Set<UUID> visited = new TreeSet<>();
        for (UUID resource : catalog.resources().keySet()) {
            walk(resource, color, visited, catalog);
        }
        for (ResourceRef ref : requiredRefs) {
            walk(ref.versionId(), color, visited, catalog);
        }
        return new ResourceGraph(closureOf(visited, catalog.resources().keySet()),
                closureOf(visited, catalog.versions().keySet()),
                closureOf(visited, catalog.byteEntries().keySet()));
    }

    /** Structural validation plus the node and dependency-edge budgets; no traversal. */
    private static void validate(ResourceCatalog catalog, Set<ResourceRef> requiredRefs) {
        Map<UUID, LogicalResource> resources = catalog.resources();
        Map<UUID, ResourceVersion> versions = catalog.versions();
        Map<UUID, ProjectState.Entry> bytes = catalog.byteEntries();
        requireDisjointIdentities(resources.keySet(), versions.keySet(), bytes.keySet());

        for (ResourceRef ref : requiredRefs) {
            Objects.requireNonNull(ref, "requiredRef");
            LogicalResource resource = resources.get(ref.resourceId());
            if (resource == null) {
                throw ResourceSupport.invalid("locked resource is not in the catalog: " + ref.resourceId());
            }
            ResourceVersion version = versions.get(ref.versionId());
            if (version == null) {
                throw ResourceSupport.invalid("locked version is not in the catalog: " + ref.versionId());
            }
            if (!version.resourceId().equals(resource.id())) {
                throw ResourceSupport.invalid("locked version " + version.id() + " does not belong to resource " + resource.id());
            }
        }

        long edges = resources.size();
        for (LogicalResource resource : resources.values()) {
            ResourceVersion current = versions.get(resource.currentVersion());
            if (current == null) {
                throw ResourceSupport.invalid("current version of resource " + resource.id() + " is not in the catalog: " + resource.currentVersion());
            }
            if (!current.resourceId().equals(resource.id())) {
                throw ResourceSupport.invalid("current version " + current.id() + " does not belong to resource " + resource.id());
            }
        }
        for (ResourceVersion version : versions.values()) {
            if (!resources.containsKey(version.resourceId())) {
                throw ResourceSupport.invalid("version " + version.id() + " belongs to an unknown resource: " + version.resourceId());
            }
            if (version.sourceEntry() != null && !bytes.containsKey(version.sourceEntry())) {
                throw ResourceSupport.invalid("version " + version.id() + " has a missing source entry: " + version.sourceEntry());
            }
            for (UUID dependency : version.dependencyVersions()) {
                if (!versions.containsKey(dependency)) {
                    throw ResourceSupport.invalid("version " + version.id() + " has a missing version dependency: " + dependency);
                }
            }
            for (UUID output : version.outputEntries()) {
                if (!bytes.containsKey(output)) {
                    throw ResourceSupport.invalid("version " + version.id() + " has a missing output entry: " + output);
                }
            }
            // The same union the bridge stores and the manifest comparison expects: a source entry that
            // is also an output is one edge, so the budget can never disagree with the manifest.
            edges += ResourceSupport.versionDependencies(version).size();
        }
        for (ProjectState.Entry entry : bytes.values()) {
            ResourceSupport.requireByteEntry(entry.id(), entry);
            for (UUID dependency : entry.assetDependencies()) {
                if (!bytes.containsKey(dependency) && !versions.containsKey(dependency) && !resources.containsKey(dependency)) {
                    throw ResourceSupport.invalid("resource byte entry " + entry.id() + " has a missing asset dependency: " + dependency);
                }
            }
            edges += entry.assetDependencies().size();
        }
        if (edges > ResourceSupport.MAX_EDGES) {
            throw ResourceSupport.invalid("resource graph holds " + edges + " dependency edges, above the bound of " + ResourceSupport.MAX_EDGES);
        }
    }

    /** Rejects a cycle anywhere in the loaded graph, including nodes no root can reach. */
    private static void requireAcyclic(ResourceCatalog catalog) {
        Map<UUID, Integer> color = new HashMap<>();
        Set<UUID> visited = new TreeSet<>();
        for (UUID id : catalog.resources().keySet()) {
            walk(id, color, visited, catalog);
        }
        for (UUID id : catalog.versions().keySet()) {
            walk(id, color, visited, catalog);
        }
        for (UUID id : catalog.byteEntries().keySet()) {
            walk(id, color, visited, catalog);
        }
    }

    private static void requireDisjointIdentities(Set<UUID> resources, Set<UUID> versions, Set<UUID> bytes) {
        for (UUID id : resources) {
            if (versions.contains(id)) {
                throw ResourceSupport.invalid("resource and version share one identity: " + id);
            }
            if (bytes.contains(id)) {
                throw ResourceSupport.invalid("resource and byte entry share one identity: " + id);
            }
        }
        for (UUID id : versions) {
            if (bytes.contains(id)) {
                throw ResourceSupport.invalid("version and byte entry share one identity: " + id);
            }
        }
    }

    private static void walk(UUID root, Map<UUID, Integer> color, Set<UUID> visited, ResourceCatalog catalog) {
        if (color.containsKey(root)) {
            return;
        }
        Deque<Frame> stack = new ArrayDeque<>();
        color.put(root, GRAY);
        stack.push(new Frame(root, children(root, catalog).iterator()));
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (!frame.children().hasNext()) {
                color.put(frame.node(), BLACK);
                visited.add(frame.node());
                stack.pop();
                continue;
            }
            UUID child = frame.children().next();
            Integer state = color.get(child);
            if (state == null) {
                color.put(child, GRAY);
                stack.push(new Frame(child, children(child, catalog).iterator()));
            } else if (state == GRAY) {
                throw ResourceSupport.invalid("resource dependency cycle through identity: " + child);
            }
        }
    }

    private static List<UUID> children(UUID node, ResourceCatalog catalog) {
        List<UUID> children = new ArrayList<>(4);
        LogicalResource resource = catalog.resources().get(node);
        if (resource != null) {
            children.add(resource.currentVersion());
            return children;
        }
        ResourceVersion version = catalog.versions().get(node);
        if (version != null) {
            if (version.sourceEntry() != null) {
                children.add(version.sourceEntry());
            }
            children.addAll(version.dependencyVersions());
            children.addAll(version.outputEntries());
            return children;
        }
        ProjectState.Entry entry = catalog.byteEntries().get(node);
        if (entry != null) {
            children.addAll(entry.assetDependencies());
            return children;
        }
        throw ResourceSupport.invalid("resource graph contains an unknown identity: " + node);
    }

    private static List<UUID> closureOf(Set<UUID> visited, Set<UUID> candidates) {
        List<UUID> ordered = new ArrayList<>();
        for (UUID id : candidates) {
            if (visited.contains(id)) {
                ordered.add(id);
            }
        }
        ordered.sort(null);
        return ordered;
    }

    private record Frame(UUID node, Iterator<UUID> children) {}
}
