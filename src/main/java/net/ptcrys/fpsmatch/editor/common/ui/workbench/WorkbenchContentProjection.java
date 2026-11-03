package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owner-thread cache of validated, applied content metadata. Every lookup checks the current
 * handle identity and full branch token. Raw input, permissions, errors, translation, callbacks
 * and action-time validation remain the host/model's responsibility. Returned values are
 * immutable snapshots, not authority to execute an action.
 *
 * Document entries independently retain a full node index and a complete grid-owner list.
 * Query LRU eviction therefore cannot cause repeated full grid discovery. Cold grid discovery
 * deliberately preserves sceneNodes/studioGrid validation and can still cost O(N squared).
 * Limits bound cache entries, not bytes or the number of nodes/cells inside an entry.
 */
public final class WorkbenchContentProjection {

    public record Limits(int maxDocuments, int maxQueries) {

        public Limits {
            if (maxDocuments < 1 || maxQueries < 1)
                throw new IllegalArgumentException("content cache limits must be positive");
        }

        public static Limits defaults() {
            return new Limits(64, 256);
        }
    }

    /** Counts successful installed cold loads; clear/retain do not reset counters. */
    public record Stats(long nodeLoads, long gridLoads, long gridsLoads, long choiceLoads,
                        long referenceChoiceLoads, long pathLoads, int cachedDocuments, int cachedQueries) {}

    private static final class Nodes {

        final Map<String, DocumentEditor.Node> byId;
        final ObjectHierarchy hierarchy;

        Nodes(List<DocumentEditor.Node> source) {
            hierarchy = new ObjectHierarchy(source);
            var index = new LinkedHashMap<String, DocumentEditor.Node>();
            for (var node : source) index.put(node.id(), node);
            byId = Collections.unmodifiableMap(index);
        }
    }

    private static final class DocumentData {

        final DocumentHandle<?> handle;
        final DocumentToken token;
        Nodes nodes;
        List<StudioDocument.Grid> grids;

        DocumentData(DocumentHandle<?> handle, DocumentToken token) {
            this.handle = handle;
            this.token = token;
        }
    }

    private enum Query {
        GRID,
        CHOICES,
        REFERENCE_CHOICES,
        PATH
    }

    private record QueryKey(DocumentData document, Query kind, String node, String property) {}

    private final ProjectWorkbench model;
    private final WorkbenchListProjection lists;
    private final Limits limits;
    private final LinkedHashMap<UUID, DocumentData> documents = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<QueryKey, Object> queries = new LinkedHashMap<>(16, .75f, true);
    private WeakReference<Map<UUID, DocumentHandle<?>>> knownDirectory = new WeakReference<>(null);
    private long nodeLoads, gridLoads, gridsLoads, choiceLoads, referenceChoiceLoads, pathLoads;

    public WorkbenchContentProjection(ProjectWorkbench model, WorkbenchListProjection lists) {
        this(model, lists, Limits.defaults());
    }

    public WorkbenchContentProjection(ProjectWorkbench model, WorkbenchListProjection lists, Limits limits) {
        this.model = Objects.requireNonNull(model);
        this.lists = Objects.requireNonNull(lists);
        this.limits = Objects.requireNonNull(limits);
    }

    public Optional<StudioDocument.Grid> grid(UUID document, String node) {
        DocumentEditor.requireAddress(node);
        var data = document(document);
        if (data == null) return Optional.empty();
        return query(new QueryKey(data, Query.GRID, node, null), () -> model.studioGrid(document, node));
    }

    /** Nullable selection is explicit; it never falls back to the model's active selection. */
    public List<ProjectWorkbench.CreationChoice> creationChoices(UUID document, String selection) {
        if (selection != null) DocumentEditor.requireAddress(selection);
        var data = document(document);
        if (data == null) return List.of();
        return query(new QueryKey(data, Query.CHOICES, selection, null),
                () -> List.copyOf(model.creationChoices(document, selection)));
    }

    public List<ProjectWorkbench.CreationChoice> creationChoicesForReference(ProjectWorkbench.Address address) {
        Objects.requireNonNull(address);
        var data = document(address.document());
        if (data == null) return List.of();
        return query(new QueryKey(data, Query.REFERENCE_CHOICES, address.node(), address.property()),
                () -> List.copyOf(model.creationChoicesForReference(address)));
    }

    /** Ordered grid owners from the validated scene projection, including owners without SHOP symbols. */
    public List<StudioDocument.Grid> grids(UUID document) {
        var data = document(document);
        if (data == null) return List.of();
        if (data.grids != null) return data.grids;
        var result = new ArrayList<StudioDocument.Grid>();
        for (var node : model.sceneNodes(document))
            model.studioGrid(document, node.id()).filter(grid -> grid.owner().equals(node.id())).ifPresent(result::add);
        var next = List.copyOf(result);
        requireCurrent(data);
        install(data);
        data.grids = next;
        gridsLoads++;
        return next;
    }

    /** Looks up full document nodes, including internals hidden by sceneNodes. */
    public Optional<DocumentEditor.Node> node(UUID document, String node) {
        DocumentEditor.requireAddress(node);
        var data = document(document);
        if (data == null) return Optional.empty();
        var nodes = prepareNodes(data);
        requireCurrent(data);
        install(data);
        installNodes(data, nodes);
        return Optional.ofNullable(nodes.byId.get(node));
    }

    /** Root first and target last; a missing document or node has an empty path. */
    public List<DocumentEditor.Node> path(UUID document, String node) {
        DocumentEditor.requireAddress(node);
        var data = document(document);
        if (data == null) return List.of();
        var key = new QueryKey(data, Query.PATH, node, null);
        var cached = queries.get(key);
        if (cached != null) return pathValue(cached);
        var nodes = prepareNodes(data);
        var result = new ArrayList<DocumentEditor.Node>();
        if (nodes.byId.containsKey(node)) {
            var ancestors = new ArrayList<>(nodes.hierarchy.ancestors(node));
            Collections.reverse(ancestors);
            for (var ancestor : ancestors) result.add(nodes.byId.get(ancestor));
            result.add(nodes.byId.get(node));
        }
        var next = List.copyOf(result);
        requireCurrent(data);
        install(data);
        installNodes(data, nodes);
        installQuery(key, next);
        pathLoads++;
        return next;
    }

    @SuppressWarnings("unchecked")
    private static List<DocumentEditor.Node> pathValue(Object cached) {
        return (List<DocumentEditor.Node>) cached;
    }

    private Nodes prepareNodes(DocumentData data) {
        return data.nodes != null ? data.nodes : new Nodes(List.copyOf(model.nodes(data.token.documentId())));
    }

    private void installNodes(DocumentData data, Nodes nodes) {
        if (data.nodes == null) {
            data.nodes = nodes;
            nodeLoads++;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T query(QueryKey key, Supplier<T> load) {
        var cached = queries.get(key);
        if (cached != null) return (T) cached;
        T next = Objects.requireNonNull(load.get());
        requireCurrent(key.document());
        install(key.document());
        installQuery(key, next);
        switch (key.kind()) {
            case GRID -> gridLoads++;
            case CHOICES -> choiceLoads++;
            case REFERENCE_CHOICES -> referenceChoiceLoads++;
            case PATH -> throw new AssertionError("paths install their node index separately");
        }
        return next;
    }

    private void installQuery(QueryKey key, Object next) {
        queries.put(key, next);
        while (queries.size() > limits.maxQueries()) queries.remove(queries.keySet().iterator().next());
    }

    private DocumentData document(UUID document) {
        Objects.requireNonNull(document);
        var handle = refreshOwners().get(document);
        if (handle == null) return null;
        var token = handle.view().token();
        var cached = documents.get(document);
        return cached != null && cached.handle == handle && cached.token.equals(token) ? cached : new DocumentData(handle, token);
    }

    private void requireCurrent(DocumentData data) {
        if (lists.documents().get(data.token.documentId()) != data.handle || !data.handle.view().token().equals(data.token))
            throw new IllegalStateException("content document changed during preparation");
    }

    private void install(DocumentData data) {
        var previous = documents.put(data.token.documentId(), data);
        if (previous != null && previous != data) removeQueries(previous);
        while (documents.size() > limits.maxDocuments()) {
            var oldest = documents.keySet().iterator().next();
            removeQueries(documents.remove(oldest));
        }
    }

    private void removeQueries(DocumentData data) {
        queries.keySet().removeIf(key -> key.document() == data);
    }

    private Map<UUID, DocumentHandle<?>> refreshOwners() {
        var directory = lists.documents();
        if (directory != knownDirectory.get()) {
            var iterator = documents.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (directory.get(entry.getKey()) != entry.getValue().handle) {
                    removeQueries(entry.getValue());
                    iterator.remove();
                }
            }
            knownDirectory = new WeakReference<>(directory);
        }
        return directory;
    }

    /** Release documents absent from the host's successfully published views/tabs. */
    public void retainDocuments(Set<UUID> liveDocuments) {
        var live = Set.copyOf(liveDocuments);
        refreshOwners();
        var iterator = documents.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                removeQueries(entry.getValue());
                iterator.remove();
            }
        }
    }

    public void clear() {
        documents.clear();
        queries.clear();
        knownDirectory.clear();
    }

    public Stats stats() {
        refreshOwners();
        return new Stats(nodeLoads, gridLoads, gridsLoads, choiceLoads, referenceChoiceLoads, pathLoads,
                documents.size(), queries.size());
    }
}
