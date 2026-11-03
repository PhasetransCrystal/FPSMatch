package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.util.*;

/** Pure document-owned projections. Display coordinates never become persistent object identities. */
public final class StudioDocument {

    private StudioDocument() {}

    /** A named central form over existing fields of one exact object. No copied draft or history. */
    public record Form(String id, TextContent label, String target, List<String> pageIds) {

        public Form {
            DocumentEditor.requireAddress(id);
            Objects.requireNonNull(label);
            DocumentEditor.requireAddress(target);
            pageIds = List.copyOf(pageIds);
            if (pageIds.isEmpty() || pageIds.size() > 16 || pageIds.stream().distinct().count() != pageIds.size())
                throw new IllegalArgumentException("form page budget or duplicate page");
            pageIds.forEach(DocumentEditor::requireAddress);
        }
    }

    public enum Action {
        CLEAR,
        PASTE,
        SWAP,
        GROUP
    }

    /** One atomic grid edit. A clipboard is captured by the host, never supplied by native UI. */
    public record Command(Action action, List<String> targets, Optional<String> argument) {

        public Command {
            Objects.requireNonNull(action);
            targets = List.copyOf(targets);
            Objects.requireNonNull(argument);
            if (targets.isEmpty() || targets.size() > 1024 || targets.stream().distinct().count() != targets.size() || argument.filter(s -> s.length() > 4096).isPresent()) throw new IllegalArgumentException("grid command budget");
            targets.forEach(DocumentEditor::requireAddress);
        }
    }

    public record Column(String id, TextContent label, int rows) {

        public Column {
            DocumentEditor.requireAddress(id);
            Objects.requireNonNull(label);
            if (rows < 1 || rows > 64) throw new IllegalArgumentException("grid row budget");
        }
    }

    /**
     * Missing node means a declared empty position, not an invented persistent object.
     * Empty is a domain-provided display state: a real slot may contain a persisted empty-item
     * object. Renderers must not infer it from object IDs, translated labels or missing identity.
     */
    public record Cell(String column, int row, Optional<String> node, Optional<String> item,
                       TextContent label, List<TextContent> details, Optional<String> group, boolean empty,
                       Optional<TextContent> badge) {

        public Cell(String column, int row, Optional<String> node, Optional<String> item,
                    TextContent label, List<TextContent> details, Optional<String> group) {
            this(column, row, node, item, label, details, group, item.isEmpty(), Optional.empty());
        }

        public Cell(String column, int row, Optional<String> node, Optional<String> item,
                    TextContent label, List<TextContent> details, Optional<String> group, boolean empty) {
            this(column, row, node, item, label, details, group, empty, Optional.empty());
        }

        public Cell {
            DocumentEditor.requireAddress(column);
            Objects.requireNonNull(node).ifPresent(DocumentEditor::requireAddress);
            Objects.requireNonNull(item).ifPresent(DocumentEditor::requireAddress);
            Objects.requireNonNull(label);
            details = List.copyOf(details);
            Objects.requireNonNull(group);
            Objects.requireNonNull(badge);
            if (row < 0 || row >= 64 || details.size() > 8 || item.isPresent() && node.isEmpty())
                throw new IllegalArgumentException("invalid grid cell");
        }
    }

    public record Grid(String owner, TextContent label, List<Column> columns, List<Cell> cells) {

        public Grid {
            DocumentEditor.requireAddress(owner);
            Objects.requireNonNull(label);
            columns = List.copyOf(columns);
            cells = List.copyOf(cells);
            if (columns.isEmpty() || columns.size() > 16 || cells.size() > 1024 || columns.stream().map(Column::id).distinct().count() != columns.size())
                throw new IllegalArgumentException("invalid grid columns");
            Map<String, Column> byId = new HashMap<>();
            columns.forEach(column -> byId.put(column.id(), column));
            Set<String> positions = new HashSet<>(), nodes = new HashSet<>();
            for (Cell cell : cells) {
                Column column = byId.get(cell.column());
                if (column == null || cell.row() >= column.rows() || !positions.add(cell.column() + "/" + cell.row()) || cell.node().isPresent() && !nodes.add(cell.node().orElseThrow()))
                    throw new IllegalArgumentException("invalid grid position");
            }
            if (cells.size() != columns.stream().mapToInt(Column::rows).sum())
                throw new IllegalArgumentException("incomplete grid");
        }
    }

    /** Exact target and subordinate objects for inline field editing; shared references stay explicit. */
    public record Section(String id, DocumentEditor.Node target, TextContent context,
                          List<DocumentEditor.Node> children, List<DocumentEditor.Node> referencedBy,
                          boolean library) {

        public Section {
            DocumentEditor.requireAddress(id);
            Objects.requireNonNull(target);
            Objects.requireNonNull(context);
            children = List.copyOf(children);
            referencedBy = List.copyOf(referencedBy);
            if (children.size() > 64 || referencedBy.size() > 8192 || children.stream().map(DocumentEditor.Node::id).distinct().count() != children.size() || referencedBy.stream().map(DocumentEditor.Node::id).distinct().count() != referencedBy.size())
                throw new IllegalArgumentException("inline section budget");
        }
    }
}
