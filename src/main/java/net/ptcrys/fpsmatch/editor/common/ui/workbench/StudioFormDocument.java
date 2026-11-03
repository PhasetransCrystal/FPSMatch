package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.StudioDocument;

import java.util.*;
import java.util.function.Function;

/** Session-only navigation for an explicitly opened form. Field values remain in the document. */
public final class StudioFormDocument {

    public record Key(UUID document, String form) {

        public Key {
            Objects.requireNonNull(document);
            DocumentEditor.requireAddress(form);
        }

        public String controlId() {
            return "form/" + document + "/" + form;
        }
    }

    private String page = "", query = "";
    private final Map<String, Integer> offsets = new HashMap<>();
    private final Set<List<String>> collapsed = new HashSet<>();
    private final Set<List<String>> searchCollapsed = new HashSet<>();

    /** Contributors choose the included pages and their order; fields retain their exact identities. */
    public static List<InspectorPresentation.Page> pages(StudioDocument.Form form, List<DocumentEditor.Field> fields) {
        var available = InspectorPresentation.pages(fields);
        return form.pageIds().stream().map(id -> available.stream().filter(value -> value.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("missing form page"))).toList();
    }

    public String page(List<InspectorPresentation.Page> pages) {
        if (pages.stream().noneMatch(value -> value.id().equals(page))) page = pages.isEmpty() ? "" : pages.get(0).id();
        return page;
    }

    public void selectPage(String id) {
        page = Objects.requireNonNull(id);
    }

    public String query() {
        return query;
    }

    public void query(String value) {
        value = Objects.requireNonNull(value);
        if (query.equals(value)) return;
        query = value;
        offsets.clear();
        searchCollapsed.clear();
    }

    public int offset() {
        return offsets.getOrDefault(page, 0);
    }

    public void offset(int value) {
        offsets.put(page, Math.max(0, value));
    }

    public boolean collapsed(String group) {
        return (query.isBlank() ? collapsed : searchCollapsed).contains(List.of(page, group));
    }

    public void toggle(String group) {
        var key = List.of(page, group);
        var state = query.isBlank() ? collapsed : searchCollapsed;
        if (!state.add(key)) state.remove(key);
    }

    public List<InspectorPresentation.Group> groups(InspectorPresentation.Page source, Function<TextContent, String> text) {
        String search = query.strip().toLowerCase(Locale.ROOT);
        var result = new ArrayList<InspectorPresentation.Group>();
        for (var group : source.groups()) {
            boolean groupMatches = search.isEmpty() || text.apply(source.label()).toLowerCase(Locale.ROOT).contains(search) || text.apply(group.label()).toLowerCase(Locale.ROOT).contains(search);
            var rows = group.rows().stream().filter(row -> groupMatches || row.fields().stream().anyMatch(field -> text.apply(field.label()).toLowerCase(Locale.ROOT).contains(search))).toList();
            if (!rows.isEmpty()) result.add(new InspectorPresentation.Group(group.id(), group.label(), rows));
        }
        return List.copyOf(result);
    }

    /** Problem navigation clears filtering and opens the exact page/group containing the address. */
    public boolean reveal(List<InspectorPresentation.Page> pages, String property, boolean clearSearch) {
        for (var candidate : pages) for (var group : candidate.groups()) for (var row : group.rows())
            if (row.fields().stream().anyMatch(field -> field.property().equals(property))) {
                page = candidate.id();
                collapsed.remove(List.of(page, group.id()));
                searchCollapsed.remove(List.of(page, group.id()));
                if (clearSearch) query("");
                return true;
            }
        return false;
    }

    public static int revealOffset(int offset, int start, int rowHeight, int viewportHeight, int contentHeight) {
        int next = offset;
        if (start < offset || rowHeight > viewportHeight) next = start;
        else if (start + rowHeight > offset + viewportHeight) next = start + rowHeight - viewportHeight;
        return Math.max(0, Math.min(next, Math.max(0, contentHeight - viewportHeight)));
    }
}
