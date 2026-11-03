package net.ptcrys.fpsmatch.editor.core.editing;

import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure presentation and atomic command contribution; never owns a document or native widget. */
public interface DocumentEditor<T> {

    enum Kind {
        TEXT,
        BOOLEAN,
        INTEGER,
        DECIMAL,
        CHOICE
    }

    enum Accent {
        NONE,
        X,
        Y,
        Z
    }

    /** A same-object applied field must match exactly; disabled controls retain their value and raw input. */
    record Dependency(String property, String expectedRaw, TextContent disabledReason) {

        public Dependency {
            requireAddress(property);
            Objects.requireNonNull(expectedRaw);
            Objects.requireNonNull(disabledReason);
            if (expectedRaw.length() > 4096) throw new IllegalArgumentException("dependency text exceeds budget");
        }
    }

    /** Pure layout hints; identities are presentation-local and never field command addresses. */
    record Presentation(String pageId, TextContent pageLabel, String groupId, TextContent groupLabel,
                        String rowId, TextContent compactLabel, Accent accent, Optional<Dependency> dependency) {

        public Presentation(String pageId, TextContent pageLabel, String groupId, TextContent groupLabel,
                            String rowId, TextContent compactLabel, Accent accent) {
            this(pageId, pageLabel, groupId, groupLabel, rowId, compactLabel, accent, Optional.empty());
        }

        public Presentation {
            requireAddress(pageId);
            requireAddress(groupId);
            requireAddress(rowId);
            Objects.requireNonNull(pageLabel);
            Objects.requireNonNull(groupLabel);
            Objects.requireNonNull(compactLabel);
            Objects.requireNonNull(accent);
            Objects.requireNonNull(dependency);
        }
    }

    /** Exact command token and its user-facing label; never a user-entered object identity. */
    record Choice(String raw, TextContent label) {

        public Choice {
            Objects.requireNonNull(raw);
            Objects.requireNonNull(label);
            if (raw.length() > 4096) throw new IllegalArgumentException("choice text exceeds budget");
        }
    }

    /** Domain-owned semantic hint; hosts map it to their icon set without reading labels or IDs. */
    enum Symbol {
        OBJECT,
        MAP,
        TEAM,
        POINT,
        AREA,
        SHOP,
        RULE
    }

    record Node(String id, Optional<String> parent, TextContent label, Symbol symbol) {

        public Node(String id, Optional<String> parent, TextContent label) {
            this(id, parent, label, Symbol.OBJECT);
        }

        public Node {
            requireAddress(id);
            Objects.requireNonNull(parent).ifPresent(DocumentEditor::requireAddress);
            Objects.requireNonNull(label);
            Objects.requireNonNull(symbol);
        }
    }

    record Field(String property, TextContent label, Kind kind, String raw, TextContent unit, List<Choice> choices,
                 Optional<Presentation> presentation) {

        public Field(String property, TextContent label, Kind kind, String raw, TextContent unit, List<Choice> choices) {
            this(property, label, kind, raw, unit, choices, Optional.empty());
        }

        public Field(String property, TextContent label, Kind kind, String raw, TextContent unit) {
            this(property, label, kind, raw, unit, List.of());
        }

        public Field {
            requireAddress(property);
            Objects.requireNonNull(label);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(raw);
            Objects.requireNonNull(unit);
            choices = List.copyOf(choices);
            Objects.requireNonNull(presentation);
            if (choices.size() > 8192 || choices.stream().map(Choice::raw).distinct().count() != choices.size() || (kind != Kind.CHOICE && !choices.isEmpty()))
                throw new IllegalArgumentException("invalid choices");
            if (raw.length() > 4096) throw new IllegalArgumentException("field text exceeds budget");
        }

        public Field presented(Presentation layout) {
            return new Field(property, label, kind, raw, unit, choices, Optional.of(layout));
        }
    }

    /** Ordered local-object references, separate from bounded scalar raw text. */
    record ReferenceList(String property, TextContent label, TextContent unit,
                         List<Choice> candidates, List<String> selected) {

        public ReferenceList {
            requireAddress(property);
            Objects.requireNonNull(label);
            Objects.requireNonNull(unit);
            candidates = List.copyOf(candidates);
            selected = List.copyOf(selected);
            if (candidates.size() > 8192 || selected.size() > 8192 || candidates.stream().map(Choice::raw).distinct().count() != candidates.size() || selected.stream().distinct().count() != selected.size())
                throw new IllegalArgumentException("invalid reference list");
            candidates.forEach(choice -> requireAddress(choice.raw()));
            selected.forEach(DocumentEditor::requireAddress);
            var available = candidates.stream().map(Choice::raw).collect(java.util.stream.Collectors.toSet());
            if (!available.containsAll(selected)) throw new IllegalArgumentException("unavailable selected reference");
        }
    }

    /** A declared reference from a retained object into the proposed deletion. */
    record RemovalReference(Node source, String property, Node target) {

        public RemovalReference {
            Objects.requireNonNull(source);
            net.ptcrys.fpsmatch.editor.api.EditorId.parse(property);
            Objects.requireNonNull(target);
        }
    }

    /** Complete local deletion preview. References block deletion; they are never silently cleared. */
    record RemovalPlan(List<Node> objects, List<RemovalReference> references, Optional<TextContent> unavailable) {

        public RemovalPlan {
            objects = List.copyOf(objects);
            references = List.copyOf(references);
            Objects.requireNonNull(unavailable);
            if (objects.size() > 8192 || references.size() > 8192 || objects.stream().map(Node::id).distinct().count() != objects.size() || references.stream().map(r -> List.of(r.source().id(), r.property(), r.target().id())).distinct().count() != references.size() || (objects.isEmpty() && unavailable.isEmpty())) throw new IllegalArgumentException("invalid removal plan");
            var removed = objects.stream().collect(java.util.stream.Collectors.toMap(Node::id, java.util.function.Function.identity()));
            for (var reference : references) if (removed.containsKey(reference.source().id()) || !reference.target().equals(removed.get(reference.target().id())))
                throw new IllegalArgumentException("invalid removal reference");
        }

        public boolean allowed() {
            return !objects.isEmpty() && unavailable.isEmpty() && references.isEmpty();
        }
    }

    Class<T> valueType();

    TextContent label();

    default DocumentEditor<T> bind(ExtensionRegistry registry) {
        return this;
    }

    /** Session resources are optional; existing contributions retain their registry-only binding. */
    default DocumentEditor<T> bind(ExtensionRegistry registry,
                                   net.ptcrys.fpsmatch.editor.core.session.EditorResourceContext resources) {
        return bind(registry);
    }

    /** Stable node ids, ordered for display, bounded to 8192 entries. */
    List<Node> nodes(T value);

    /** Scene-only projection; specialized document internals remain addressable through nodes(). */
    default List<Node> sceneNodes(T value) {
        return nodes(value);
    }

    /**
     * Existing point nodes only, with exact document coordinates; bounded to 8192 unique identities.
     * Owners may declare an effective direction in document axes independently of stored angles.
     * Missing direction means marker-only; hosts must not guess facing from labels or zero angles.
     */
    default List<SpatialDocument.Point> spatialPoints(T value) {
        return List.of();
    }

    /**
     * Pure position-only command. Preserve all other content, including node identity, parent, role,
     * dimension, stored orientation and optional effective direction. The host validates and
     * publishes the complete candidate atomically.
     */
    default T moveSpatialPoint(T value, String node, SpatialDocument.Position target) {
        throw new UnsupportedOperationException("spatial point movement unavailable");
    }

    /**
     * Existing area nodes, with ordered exact corners and explicit owner-declared semantics.
     * Area and point identities must be unique across both declarations and present in nodes().
     */
    default List<SpatialDocument.Area> spatialAreas(T value) {
        return List.of();
    }

    /**
     * Pure, atomic replacement of both ordered endpoints. Preserve identity, dimension, semantics
     * and all unrelated content. Owners reject coordinates outside their declared domain.
     */
    default T setSpatialAreaBounds(T value, String node, SpatialDocument.AreaBounds target) {
        throw new UnsupportedOperationException("spatial area editing unavailable");
    }

    /** Central forms explicitly declared by the owner; hosts never infer routes from IDs or labels. */
    default List<StudioDocument.Form> studioForms(T value) {
        return List.of();
    }

    /** Finds the specialized document owning this exact selection, including its internal objects. */
    default Optional<StudioDocument.Grid> studioGrid(T value, String node) {
        return Optional.empty();
    }

    default List<StudioDocument.Section> studioSections(T value, String node) {
        return List.of();
    }

    /** Opaque immutable clipboard payload retained by its issuing editor and document only. */
    default Object copyStudioCell(T value, String node) {
        throw new UnsupportedOperationException("grid copy unavailable");
    }

    default T applyStudioCommand(T value, StudioDocument.Command command, Optional<Object> clipboard) {
        throw new UnsupportedOperationException("grid editing unavailable");
    }

    default T createStudioCell(T value, String owner, String column, int row, java.util.UUID identity) {
        throw new UnsupportedOperationException("grid creation unavailable");
    }

    /** Stable property addresses within the selected node, bounded to 256 entries. */
    List<Field> fields(T value, String node);

    /** Strictly parses raw and returns a complete candidate. No mutation, I/O or publication. */
    T apply(T value, String node, String property, String raw);

    default List<ReferenceList> referenceLists(T value, String node) {
        return List.of();
    }

    /** Complete ordered replacement; implementations must validate targets and the complete graph. */
    default T replaceReferences(T value, String node, String property, List<String> selected) {
        throw new UnsupportedOperationException("reference editing unavailable");
    }

    /**
     * Missing capability protects the object. Plans must include the complete owned subtree and incoming references.
     */
    default Optional<RemovalPlan> planRemoval(T value, String node) {
        return Optional.empty();
    }

    /** Recheck the complete plan and return one validated candidate; no mutation, I/O or cross-document edits. */
    default T remove(T value, String node) {
        throw new UnsupportedOperationException("object removal unavailable");
    }

    static void requireAddress(String address) {
        Objects.requireNonNull(address);
        if (address.isBlank() || address.length() > 256 || address.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid field or node address");
    }
}
