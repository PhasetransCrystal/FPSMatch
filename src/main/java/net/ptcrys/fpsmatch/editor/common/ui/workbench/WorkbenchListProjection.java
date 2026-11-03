package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioRowSource;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioRowSource.FocusSlot;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.core.documents.DocumentToken;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;
import net.ptcrys.fpsmatch.editor.core.session.DocumentHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Owner-thread list projections over the workbench's existing validated domain APIs. Scrolling
 * reuses sources; it does not copy the document directory or validate every scene and form again.
 * Project-list invalidation follows ProjectWorkbench.version(): callers editing a raw handle
 * outside that model must clear this cache before asking for another project-list projection.
 * Per-document queries additionally compare the complete token, including its branch identity.
 * No rows retain handles, callbacks, controls or viewport geometry.
 */
public final class WorkbenchListProjection {

    public record ProjectRow(UUID id, String displayName, List<StudioDocument.Form> forms) {

        public ProjectRow {
            Objects.requireNonNull(id);
            Objects.requireNonNull(displayName);
            forms = List.copyOf(forms);
        }
    }

    /** Counts actual cold work, not an estimate of time or allocated memory. */
    public record Stats(long directorySnapshots, long formLoads, long hierarchyLoads,
                        long projectBuilds, long objectBuilds, int cachedDocuments,
                        int projectViews, int objectViews) {}

    private static final List<FocusSlot> ACTIVATE = List.of(new FocusSlot("activate", Role.TREE_ITEM));
    private static final List<FocusSlot> PROJECT_FORMS = List.of(
            new FocusSlot("activate", Role.TREE_ITEM), new FocusSlot("forms", Role.BUTTON));
    private static final List<FocusSlot> OBJECT_BRANCH = List.of(
            new FocusSlot("expand", Role.BUTTON), new FocusSlot("activate", Role.TREE_ITEM));
    private static final ObjectHierarchy EMPTY_HIERARCHY = new ObjectHierarchy(List.of());

    private final ProjectWorkbench model;
    private final Function<TextContent, String> labels;
    private final Map<UUID, DocumentState> states = new HashMap<>();
    private final Map<String, ProjectView> projectViews = new HashMap<>();
    private final Map<String, ObjectView> objectViews = new HashMap<>();
    private Map<UUID, DocumentHandle<?>> directory = Map.of();
    private long directoryVersion = -1, directoryEpoch, stateEpoch, sequence;
    private long rowsVersion = -1, rowsStateEpoch = -1, rowsDirectoryEpoch = -1, rowsRevision;
    private List<ProjectRow> projectRows = List.of();
    private long directorySnapshots, formLoads, hierarchyLoads, projectBuilds, objectBuilds;
    private long collapseComparisons;

    private static final class DocumentState {

        final DocumentHandle<?> handle;
        final DocumentToken token;
        final long revision;
        List<StudioDocument.Form> forms;
        ObjectHierarchy hierarchy;
        Set<String> initialCollapsed;

        DocumentState(DocumentHandle<?> handle, DocumentToken token, long revision) {
            this.handle = handle;
            this.token = token;
            this.revision = revision;
        }
    }

    private record ProjectView(long rowsRevision, String query, long languageGeneration,
                               StudioRowSource<ProjectRow> source) {}

    private record ObjectView(UUID document, long documentRevision, Set<String> collapsed,
                              String query, long languageGeneration,
                              StudioRowSource<ObjectHierarchy.Row> source) {}

    public WorkbenchListProjection(ProjectWorkbench model, Function<TextContent, String> labels) {
        this.model = Objects.requireNonNull(model);
        this.labels = Objects.requireNonNull(labels);
    }

    /** One immutable directory snapshot per observed model version; iteration order is retained. */
    public Map<UUID, DocumentHandle<?>> documents() {
        long version = model.version();
        if (directoryVersion == version) return directory;
        var next = Collections.unmodifiableMap(new LinkedHashMap<>(model.documents()));
        boolean changed = directory.size() != next.size();
        if (!changed) for (var entry : directory.entrySet()) {
            if (next.get(entry.getKey()) != entry.getValue()) {
                changed = true;
                break;
            }
        }
        states.entrySet().removeIf(entry -> next.get(entry.getKey()) != entry.getValue().handle);
        // A removed/replaced document must not keep its old projection alive in a hidden panel.
        objectViews.entrySet().removeIf(entry -> {
            UUID id = entry.getValue().document();
            return directory.get(id) != next.get(id);
        });
        if (changed) {
            directoryEpoch++;
            projectViews.clear();
        }
        directory = next;
        directoryVersion = version;
        directorySnapshots++;
        return directory;
    }

    public List<StudioDocument.Form> forms(UUID document) {
        var state = state(document);
        return state == null ? List.of() : forms(document, state);
    }

    public ObjectHierarchy hierarchy(UUID document) {
        var state = state(document);
        return state == null ? EMPTY_HIERARCHY : hierarchy(document, state);
    }

    /** Immutable default collapse value; hosts copy it when creating their mutable view state. */
    public Set<String> initialCollapsed(UUID document) {
        var state = state(document);
        if (state == null) return Set.of();
        if (state.initialCollapsed == null) {
            var result = new HashSet<String>();
            for (var row : hierarchy(document, state).rows(Set.of(), "", node -> ""))
                if (row.branch() && row.depth() >= 2) result.add(row.node().id());
            state.initialCollapsed = Set.copyOf(result);
        }
        return state.initialCollapsed;
    }

    public StudioRowSource<ProjectRow> projects(String viewId, String query, long languageGeneration) {
        requireView(viewId);
        String search = search(query);
        refreshProjectRows();
        var cached = projectViews.get(viewId);
        if (cached != null && cached.rowsRevision() == rowsRevision && cached.query().equals(search) && cached.languageGeneration() == languageGeneration) return cached.source();
        var visible = search.isEmpty() ? projectRows : projectRows.stream()
                .filter(row -> row.displayName().toLowerCase(Locale.ROOT).contains(search)).toList();
        var source = StudioRowSource.of(nextRevision(), visible, row -> row.id().toString(),
                row -> row.forms().isEmpty() ? ACTIVATE : PROJECT_FORMS);
        projectViews.put(viewId, new ProjectView(rowsRevision, search, languageGeneration, source));
        projectBuilds++;
        return source;
    }

    public StudioRowSource<ObjectHierarchy.Row> objects(String viewId, UUID document,
                                                        Set<String> collapsed, String query, long languageGeneration) {
        requireView(viewId);
        Objects.requireNonNull(document);
        Objects.requireNonNull(collapsed);
        String search = search(query);
        var state = state(document);
        long revision = state == null ? 0 : state.revision;
        var cached = objectViews.get(viewId);
        if (cached != null && cached.document().equals(document) && cached.documentRevision() == revision && cached.query().equals(search) && cached.languageGeneration() == languageGeneration) {
            if (cached.collapsed() == collapsed) return cached.source();
            collapseComparisons++;
            if (cached.collapsed().equals(collapsed)) {
                // Adopt an equal replacement so immutable host state takes the identity path on
                // later scrolls. Mutable callers still receive a defensive snapshot.
                objectViews.put(viewId, new ObjectView(document, revision, Set.copyOf(collapsed),
                        search, languageGeneration, cached.source()));
                return cached.source();
            }
        }
        var collapseSnapshot = Set.copyOf(collapsed);
        var tree = state == null ? EMPTY_HIERARCHY : hierarchy(document, state);
        var rows = tree.rows(collapseSnapshot, search, node -> labels.apply(node.label()));
        var source = StudioRowSource.of(nextRevision(), rows, row -> row.node().id(),
                row -> row.branch() ? OBJECT_BRANCH : ACTIVATE);
        objectViews.put(viewId, new ObjectView(document, revision, collapseSnapshot, search, languageGeneration, source));
        objectBuilds++;
        return source;
    }

    /** Each live view owns one projection of each kind, not an unbounded query history. */
    public void retainViews(Set<String> liveViews) {
        Objects.requireNonNull(liveViews);
        projectViews.keySet().retainAll(liveViews);
        objectViews.keySet().retainAll(liveViews);
    }

    /** Releases all cached handles and projections; source revisions are never recycled. */
    public void clear() {
        states.clear();
        projectViews.clear();
        objectViews.clear();
        directory = Map.of();
        projectRows = List.of();
        directoryVersion = -1;
        rowsVersion = -1;
        rowsStateEpoch = -1;
        rowsDirectoryEpoch = -1;
        directoryEpoch++;
        stateEpoch++;
    }

    public Stats stats() {
        return new Stats(directorySnapshots, formLoads, hierarchyLoads, projectBuilds, objectBuilds,
                states.size(), projectViews.size(), objectViews.size());
    }

    /** Actual value comparisons, excluding constant-time immutable-state identity hits. */
    long collapseComparisons() {
        return collapseComparisons;
    }

    private DocumentState state(UUID document) {
        Objects.requireNonNull(document);
        var handle = documents().get(document);
        if (handle == null) return null;
        var token = handle.view().token();
        var cached = states.get(document);
        if (cached != null && cached.handle == handle && cached.token.equals(token)) return cached;
        var result = new DocumentState(handle, token, nextRevision());
        states.put(document, result);
        stateEpoch++;
        return result;
    }

    private List<StudioDocument.Form> forms(UUID document, DocumentState state) {
        if (state.forms == null) {
            var result = List.copyOf(model.studioForms(document));
            state.forms = result;
            formLoads++;
        }
        return state.forms;
    }

    private ObjectHierarchy hierarchy(UUID document, DocumentState state) {
        if (state.hierarchy == null) {
            var result = new ObjectHierarchy(model.sceneNodes(document));
            state.hierarchy = result;
            hierarchyLoads++;
        }
        return state.hierarchy;
    }

    private void refreshProjectRows() {
        documents();
        if (rowsVersion == directoryVersion && rowsStateEpoch == stateEpoch) return;
        var rows = new ArrayList<ProjectRow>(directory.size());
        for (var entry : directory.entrySet()) {
            var state = state(entry.getKey());
            rows.add(new ProjectRow(entry.getKey(), entry.getValue().displayName(), forms(entry.getKey(), state)));
        }
        if (rowsDirectoryEpoch != directoryEpoch || !projectRows.equals(rows)) {
            projectRows = List.copyOf(rows);
            rowsRevision = nextRevision();
        }
        rowsVersion = directoryVersion;
        rowsStateEpoch = stateEpoch;
        rowsDirectoryEpoch = directoryEpoch;
    }

    private long nextRevision() {
        sequence = Math.incrementExact(sequence);
        return sequence;
    }

    private static String search(String query) {
        return Objects.requireNonNull(query).strip().toLowerCase(Locale.ROOT);
    }

    private static void requireView(String view) {
        if (Objects.requireNonNull(view).isBlank()) throw new IllegalArgumentException("empty projection view");
    }
}
