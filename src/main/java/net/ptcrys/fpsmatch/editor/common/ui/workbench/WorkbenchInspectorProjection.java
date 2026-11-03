package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-thread cache of validated inspector data. Full document nodes, rather than scene nodes,
 * supply identity and ancestry. Applied data is keyed by handle identity and the entire branch
 * token; raw input, permissions, errors, translated labels, callbacks and geometry remain live.
 * Each view owns only its current selection. Both views and lazily loaded field targets have
 * finite LRU budgets; hosts release hidden views after successful publication with retainViews.
 */
public final class WorkbenchInspectorProjection {

    public record Limits(int maxViews, int maxFieldTargets) {

        public Limits {
            if (maxViews < 1 || maxFieldTargets < 1) throw new IllegalArgumentException("inspector cache limits must be positive");
        }

        public static Limits defaults() {
            return new Limits(128, 256);
        }
    }

    /** Successful cold loads, including prepared data subsequently rejected by another loader. */
    public record Stats(long nodeLoads, long selectionLoads, long fieldLoads, int cachedDocuments,
                        int cachedViews, int cachedFieldTargets) {}

    private static final class DocumentData {

        final DocumentHandle<?> handle;
        final DocumentToken token;
        final Map<String, DocumentEditor.Node> nodes;
        final ObjectHierarchy hierarchy;

        DocumentData(DocumentHandle<?> handle, DocumentToken token, List<DocumentEditor.Node> source) {
            this.handle = handle;
            this.token = token;
            hierarchy = new ObjectHierarchy(source);
            var indexed = new LinkedHashMap<String, DocumentEditor.Node>();
            for (var node : source) indexed.put(node.id(), node);
            nodes = Collections.unmodifiableMap(indexed);
        }
    }

    private record FieldKey(Selection selection, String target) {}

    private record FieldData(List<DocumentEditor.Field> fields, List<InspectorPresentation.Page> pages) {}

    private record ViewData(DocumentData document, Selection selection) {}

    /**
     * Immutable metadata for one view selection, with checked lazy field access. Metadata remains
     * readable after retirement; fields/pages reject retired selections, changed tokens and closed
     * handles even when the requested target used to be cached. Obtain a fresh selection instead.
     */
    public static final class Selection {

        private final WorkbenchInspectorProjection owner;
        private final String viewKey;
        private final DocumentToken token;
        private final Map<String, DocumentEditor.Node> nodes;
        private final DocumentEditor.Node node;
        private final List<DocumentEditor.Node> path;
        private final List<StudioDocument.Section> sections;
        private final List<DocumentEditor.ReferenceList> references;

        private Selection(WorkbenchInspectorProjection owner, String viewKey, DocumentData data,
                          DocumentEditor.Node node, List<DocumentEditor.Node> path,
                          List<StudioDocument.Section> sections, List<DocumentEditor.ReferenceList> references) {
            this.owner = owner;
            this.viewKey = viewKey;
            this.token = data.token;
            this.nodes = data.nodes;
            this.node = node;
            this.path = List.copyOf(path);
            this.sections = List.copyOf(sections);
            this.references = List.copyOf(references);
        }

        public UUID document() {
            return token.documentId();
        }

        public DocumentToken token() {
            return token;
        }

        public DocumentEditor.Node node() {
            return node;
        }

        public Optional<DocumentEditor.Node> node(String target) {
            return Optional.ofNullable(nodes.get(Objects.requireNonNull(target)));
        }

        /** Root first, selected node last; retain TextContent until the host translates it. */
        public List<DocumentEditor.Node> path() {
            return path;
        }

        public List<StudioDocument.Section> sections() {
            return sections;
        }

        public List<DocumentEditor.ReferenceList> references() {
            return references;
        }

        public List<DocumentEditor.Field> fields(String target) {
            return owner.fields(this, target).fields();
        }

        public List<InspectorPresentation.Page> pages(String target) {
            return owner.fields(this, target).pages();
        }
    }

    private final ProjectWorkbench model;
    private final WorkbenchListProjection lists;
    private final Limits limits;
    private final Map<UUID, DocumentData> documents = new HashMap<>();
    private final LinkedHashMap<String, ViewData> views = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<FieldKey, FieldData> targets = new LinkedHashMap<>(16, .75f, true);
    private WeakReference<Map<UUID, DocumentHandle<?>>> knownDirectory = new WeakReference<>(null);
    private long nodeLoads, selectionLoads, fieldLoads;

    public WorkbenchInspectorProjection(ProjectWorkbench model, WorkbenchListProjection lists) {
        this(model, lists, Limits.defaults());
    }

    public WorkbenchInspectorProjection(ProjectWorkbench model, WorkbenchListProjection lists, Limits limits) {
        this.model = Objects.requireNonNull(model);
        this.lists = Objects.requireNonNull(lists);
        this.limits = Objects.requireNonNull(limits);
    }

    public Selection selection(String viewKey, UUID document, String nodeKey) {
        if (Objects.requireNonNull(viewKey).isBlank()) throw new IllegalArgumentException("empty inspector view");
        Objects.requireNonNull(document);
        DocumentEditor.requireAddress(nodeKey);
        var directory = refreshOwners();
        var handle = directory.get(document);
        if (handle == null) throw new IllegalArgumentException("missing inspector document");
        var token = handle.view().token();
        var cached = views.get(viewKey);
        if (cached != null && cached.document().handle == handle && cached.selection().token().equals(token) && cached.selection().node().id().equals(nodeKey)) return cached.selection();
        var data = documents.get(document);
        if (data == null || data.handle != handle || !data.token.equals(token)) {
            data = new DocumentData(handle, token, List.copyOf(model.nodes(document)));
            nodeLoads++;
        }
        var node = data.nodes.get(nodeKey);
        if (node == null) throw new IllegalArgumentException("missing inspector node: " + nodeKey);
        var ancestors = new ArrayList<>(data.hierarchy.ancestors(nodeKey));
        Collections.reverse(ancestors);
        var path = new ArrayList<DocumentEditor.Node>();
        for (String ancestor : ancestors) path.add(data.nodes.get(ancestor));
        path.add(node);
        var sections = List.copyOf(model.studioSections(document, nodeKey));
        var references = List.copyOf(model.referenceLists(document, nodeKey));
        var next = new Selection(this, viewKey, data, node, path, sections, references);
        if (!current(data)) throw new IllegalStateException("inspector document changed during preparation");
        // All fallible preparation precedes replacement of a successfully cached selection.
        var prepared = data;
        views.entrySet().removeIf(entry -> entry.getValue().selection().document().equals(document) && entry.getValue().document() != prepared);
        documents.put(document, data);
        views.put(viewKey, new ViewData(data, next));
        selectionLoads++;
        while (views.size() > limits.maxViews()) views.remove(views.keySet().iterator().next());
        prune();
        return next;
    }

    private FieldData fields(Selection selection, String target) {
        DocumentEditor.requireAddress(target);
        requireCurrent(selection);
        if (!selection.nodes.containsKey(target)) throw new IllegalArgumentException("missing inspector target: " + target);
        var key = new FieldKey(selection, target);
        var cached = targets.get(key);
        if (cached != null) return cached;
        var fields = List.copyOf(model.fields(selection.document(), target));
        var pages = InspectorPresentation.pages(fields);
        requireCurrent(selection);
        var next = new FieldData(fields, pages);
        targets.put(key, next);
        fieldLoads++;
        while (targets.size() > limits.maxFieldTargets()) targets.remove(targets.keySet().iterator().next());
        return next;
    }

    private boolean current(DocumentData data) {
        return lists.documents().get(data.token.documentId()) == data.handle && data.handle.view().token().equals(data.token);
    }

    private void requireCurrent(Selection selection) {
        refreshOwners();
        var view = views.get(selection.viewKey);
        if (view == null || view.selection() != selection || !current(view.document()))
            throw new IllegalStateException("inspector selection is no longer current");
    }

    private Map<UUID, DocumentHandle<?>> refreshOwners() {
        var directory = lists.documents();
        if (directory != knownDirectory.get()) {
            if (views.entrySet().removeIf(entry -> directory.get(entry.getValue().selection().document()) != entry.getValue().document().handle)) prune();
            knownDirectory = new WeakReference<>(directory);
        }
        return directory;
    }

    private void prune() {
        var live = new HashSet<Selection>();
        for (var view : views.values()) live.add(view.selection());
        targets.keySet().removeIf(key -> !live.contains(key.selection()));
        var used = new HashSet<DocumentData>();
        for (var view : views.values()) used.add(view.document());
        documents.values().removeIf(data -> !used.contains(data));
    }

    public void retainViews(Set<String> liveViews) {
        Objects.requireNonNull(liveViews);
        refreshOwners();
        views.keySet().retainAll(liveViews);
        prune();
    }

    public void clear() {
        views.clear();
        targets.clear();
        documents.clear();
        knownDirectory.clear();
    }

    public Stats stats() {
        return new Stats(nodeLoads, selectionLoads, fieldLoads, documents.size(), views.size(), targets.size());
    }
}
