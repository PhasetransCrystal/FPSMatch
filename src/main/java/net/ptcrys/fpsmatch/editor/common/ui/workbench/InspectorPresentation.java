package net.ptcrys.fpsmatch.editor.common.ui.workbench;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor.Field;

import java.util.*;

/** Ordered, bounded projection of module-declared pages, groups and compact rows. */
public final class InspectorPresentation {

    private InspectorPresentation() {}

    public record Row(String id, List<Field> fields) {

        public Row {
            fields = List.copyOf(fields);
        }
    }

    public record Group(String id, TextContent label, List<Row> rows) {

        public Group {
            rows = List.copyOf(rows);
        }
    }

    public record Page(String id, TextContent label, List<Group> groups) {

        public Page {
            groups = List.copyOf(groups);
        }
    }

    private static final TextContent EMPTY = new TextContent.Literal("");

    public static List<Page> pages(List<Field> fields) {
        if (fields.size() > 256) throw new IllegalArgumentException("field budget exceeded");
        var pages = new LinkedHashMap<String, TextContent>();
        var groups = new LinkedHashMap<List<String>, TextContent>();
        var rows = new LinkedHashMap<List<String>, List<Field>>();
        var properties = new HashSet<String>();
        for (Field field : fields) {
            if (!properties.add(field.property())) throw new IllegalArgumentException("duplicate field");
            var p = field.presentation().orElseGet(() -> new DocumentEditor.Presentation("overview", ProjectWorkbench.text("inspector_overview"),
                    "general", EMPTY, field.property(), field.label(), DocumentEditor.Accent.NONE));
            TextContent previous = pages.putIfAbsent(p.pageId(), p.pageLabel());
            if (previous != null && !previous.equals(p.pageLabel())) throw new IllegalArgumentException("page label differs");
            var group = List.of(p.pageId(), p.groupId());
            previous = groups.putIfAbsent(group, p.groupLabel());
            if (previous != null && !previous.equals(p.groupLabel())) throw new IllegalArgumentException("group label differs");
            var row = rows.computeIfAbsent(List.of(p.pageId(), p.groupId(), p.rowId()), ignored -> new ArrayList<>());
            if (row.size() == 3) throw new IllegalArgumentException("compact row has more than three fields");
            row.add(field);
        }
        for (Field field : fields) field.presentation().flatMap(DocumentEditor.Presentation::dependency).ifPresent(dependency -> {
            if (dependency.property().equals(field.property()) || !properties.contains(dependency.property())) throw new IllegalArgumentException("invalid field dependency");
        });
        List<Page> result = new ArrayList<>();
        pages.forEach((page, label) -> {
            List<Group> projected = new ArrayList<>();
            groups.forEach((key, groupLabel) -> {
                if (!key.get(0).equals(page)) return;
                List<Row> content = new ArrayList<>();
                rows.forEach((row, values) -> { if (row.get(0).equals(page) && row.get(1).equals(key.get(1))) content.add(new Row(row.get(2), values)); });
                projected.add(new Group(key.get(1), groupLabel, content));
            });
            result.add(new Page(page, label, projected));
        });
        return List.copyOf(result);
    }
}
