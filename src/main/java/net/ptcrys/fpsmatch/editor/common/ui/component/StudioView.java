package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Constraints;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Size;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;
import net.ptcrys.fpsmatch.editor.common.ui.layout.RetainedLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/**
 * Declarative Studio composition. Content determines geometry before a frame is emitted.
 * Stable control IDs, raw input, blur groups and callbacks pass unchanged to StudioFrame.
 * Containers have no paint or hit target unless explicitly declared as a surface.
 */
public final class StudioView {

    public record Input(String id, String label, String raw, boolean enabled, boolean readOnly,
                        boolean invalid, String reason, String group, Consumer<String> change, Runnable commit) {

        public Input {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(raw);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(change);
        }
    }

    public record Field(String id, String label, Element control, String hint, String error,
                        List<StudioComponents.Action> actions, boolean inlineLabel, Integer labelColor) {

        public Field {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(control);
            Objects.requireNonNull(hint);
            Objects.requireNonNull(error);
            actions = List.copyOf(actions);
        }

        public Field(String id, String label, Element control, String hint, String error, List<StudioComponents.Action> actions) {
            this(id, label, control, hint, error, actions, false, null);
        }
    }

    public static final class Element {

        private final FlowLayout.Node node;
        private final List<Element> children;
        private final Function<FlowLayout.Placed, Emission> presentation;
        private final RetainedLayout.Dependency dependency;

        private Element(FlowLayout.Node node, List<Element> children, RetainedLayout.Dependency dependency, Function<FlowLayout.Placed, Emission> presentation) {
            this.node = node;
            this.children = List.copyOf(children);
            this.dependency = dependency;
            this.presentation = presentation;
        }

        public FlowLayout.Node node() {
            return node;
        }

        public Element style(Style style) {
            return new Element(new FlowLayout.Node(node.id(), node.kind(), style, node.children(), node.measurer()), children, dependency, presentation);
        }

        private Element detail(String detail) {
            return new Element(node, children, dependency, placed -> {
                var value = presentation.apply(placed);
                return new Emission(value.id(), value.role(), value.name(), value.value(), value.bounds(),
                        value.enabled(), value.selected(), value.readOnly(), value.reason(), value.visual(),
                        value.action(), value.change(), value.group(), value.commit(), detail);
            });
        }
    }

    private record Emission(String id, Role role, String name, String value, Bounds bounds, boolean enabled,
                            boolean selected, boolean readOnly, String reason, Visual visual, Runnable action,
                            Consumer<String> change, String group, Runnable commit, String detail) {

        void emit(StudioFrame frame, int layer) {
            frame.emit(id, role, name, value, bounds, enabled, selected, readOnly, reason, layer, visual);
            if (action != null) frame.action(id, action);
            if (change != null) frame.input(id, change);
            if (commit != null) frame.blur(id, group, commit);
            if (detail != null && !detail.isEmpty()) frame.detail(id, detail);
        }
    }

    /** Frozen geometry and presentation; emission never measures text or invokes domain callbacks. */
    public static final class Layout {

        private final FlowLayout.Result geometry;
        private final List<Emission> emissions;

        private Layout(FlowLayout.Result geometry, List<Emission> emissions) {
            this.geometry = geometry;
            this.emissions = List.copyOf(emissions);
        }

        public FlowLayout.Result geometry() {
            return geometry;
        }

        public Bounds bounds(String id) {
            return geometry.require(id).bounds();
        }

        public Bounds contentBounds(String id) {
            return geometry.require(id).contentBounds();
        }

        public int contentHeight() {
            return Math.addExact(geometry.root().bounds().height(), geometry.root().overflowHeight());
        }

        public Layout translated(int dx, int dy) {
            if (dx == 0 && dy == 0) return this;
            var index = new LinkedHashMap<String, FlowLayout.Placed>();
            var root = translate(geometry.root(), dx, dy, index);
            var moved = emissions.stream().map(value -> new Emission(value.id(), value.role(), value.name(), value.value(),
                    translate(value.bounds(), dx, dy), value.enabled(), value.selected(), value.readOnly(), value.reason(),
                    value.visual(), value.action(), value.change(), value.group(), value.commit(), value.detail())).toList();
            return new Layout(new FlowLayout.Result(root, index), moved);
        }

        private static FlowLayout.Placed translate(FlowLayout.Placed node, int dx, int dy, java.util.Map<String, FlowLayout.Placed> index) {
            var children = node.children().stream().map(child -> translate(child, dx, dy, index)).toList();
            var moved = new FlowLayout.Placed(node.id(), translate(node.bounds(), dx, dy), translate(node.contentBounds(), dx, dy),
                    node.overflowWidth(), node.overflowHeight(), children);
            index.put(node.id(), moved);
            return moved;
        }

        private static Bounds translate(Bounds bounds, int dx, int dy) {
            return new Bounds(Math.addExact(bounds.x(), dx), Math.addExact(bounds.y(), dy), bounds.width(), bounds.height());
        }

        public void emit(StudioFrame frame, int layer) {
            Objects.requireNonNull(frame);
            for (var emission : emissions) emission.emit(frame, layer);
        }
    }

    private final StudioTextLayout text;
    private final StudioMetrics metrics;
    private final RetainedLayout retained;

    public StudioView(StudioTextLayout text, StudioMetrics metrics) {
        this(text, metrics, RetainedLayout.Budget.defaults());
    }

    public StudioView(StudioTextLayout text, StudioMetrics metrics, RetainedLayout.Budget budget) {
        this.text = Objects.requireNonNull(text);
        this.metrics = Objects.requireNonNull(metrics);
        this.retained = new RetainedLayout(budget);
    }

    public StudioTextLayout textLayout() {
        return text;
    }

    public RetainedLayout.Publication beginPublication(Object scopeKey) {
        return retained.beginPublication(scopeKey, text.generation());
    }

    public RetainedLayout.Stats layoutStats() {
        return retained.stats();
    }

    public long layoutMountGeneration(String rootId, String elementId) {
        return retained.mountGeneration(rootId, elementId);
    }

    /** Releases this view's layout and mount state when its screen closes. */
    public void clearRetainedLayout() {
        retained.clear();
    }

    public Size measure(Element element, Constraints constraints) {
        return retained.evaluate(element.node, dependencies(element), text.generation(), session -> session.measure(constraints));
    }

    public Layout layout(Element element, Bounds bounds) {
        return retained.evaluate(element.node, dependencies(element), text.generation(), session -> freeze(element, session.layout(bounds)));
    }

    public Layout layoutContent(Element element, int x, int y, int width) {
        return retained.evaluate(element.node, dependencies(element), text.generation(), session -> freeze(element, session.layoutContent(x, y, width)));
    }

    private static java.util.Map<String, RetainedLayout.Dependency> dependencies(Element root) {
        var result = new LinkedHashMap<String, RetainedLayout.Dependency>();
        collectDependencies(root, result);
        return result;
    }

    private static void collectDependencies(Element element, java.util.Map<String, RetainedLayout.Dependency> result) {
        result.put(element.node.id(), element.dependency);
        for (var child : element.children) collectDependencies(child, result);
    }

    private Layout freeze(Element root, FlowLayout.Result geometry) {
        var emissions = new ArrayList<Emission>();
        collect(root, geometry, emissions);
        return new Layout(geometry, emissions);
    }

    private static void collect(Element element, FlowLayout.Result geometry, List<Emission> emissions) {
        if (element.presentation != null) emissions.add(element.presentation.apply(geometry.require(element.node.id())));
        for (var child : element.children) collect(child, geometry, emissions);
    }

    public Element text(String id, String value, TextSize size, Variant variant) {
        return label(id, value, size, variant, null, false);
    }

    public Element text(String id, String value) {
        return text(id, value, TextSize.BODY, Variant.DEFAULT);
    }

    public Element paragraph(String id, String value, TextSize size, Variant variant) {
        return label(id, value, size, variant, null, true);
    }

    public Element paragraph(String id, String value) {
        return paragraph(id, value, TextSize.BODY, Variant.DEFAULT);
    }

    private Element label(String id, String value, TextSize size, Variant variant, Integer color, boolean wrap) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(size);
        Objects.requireNonNull(variant);
        var node = FlowLayout.leaf(id, Style.defaults(), limits -> {
            if (!wrap) return new Size(text.width(value, size), text.lineHeight(size));
            var lines = text.wrap(value, limits.maxWidth(), size);
            return new Size(lines.width(), lines.height());
        });
        return new Element(node, List.of(), new RetainedLayout.Dependency(wrap ? "paragraph" : "label", List.of(value, size), true), placed -> {
            var visual = Visual.of(Kind.LABEL).size(size).variant(variant);
            if (color != null) visual = visual.color(color);
            if (wrap) visual = visual.lines(text.wrap(value, placed.contentBounds().width(), size).lines());
            return new Emission(id, Role.LABEL, value, "", placed.contentBounds(), true, false, false, "",
                    visual, null, null, null, null, value);
        });
    }

    /** Fixed-size, non-interactive local image. Resource failure never changes its geometry. */
    public Element image(String id, String resource, Icon fallback, int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("positive image dimensions required");
        var image = new Image(resource, fallback);
        var style = Style.defaults().width(Length.fixed(width)).height(Length.fixed(height));
        var node = FlowLayout.leaf(id, style, limits -> new Size(width, height));
        return new Element(node, List.of(), new RetainedLayout.Dependency("image", List.of(width, height)), placed -> new Emission(id, Role.LABEL, "", "", placed.contentBounds(), true, false, false, "",
                Visual.of(Kind.IMAGE).image(image), null, null, null, null, null));
    }

    public Element button(StudioComponents.Action action) {
        return button(action, Kind.BUTTON);
    }

    /** One action target containing passive labels/images and non-painting layout containers. */
    public Element actionRow(StudioComponents.Action action, Style style, List<Element> children) {
        var copy = children.stream().map(StudioView::actionRowLabel).toList();
        var node = FlowLayout.row(action.id(), style, copy.stream().map(Element::node).toList());
        return new Element(node, copy, new RetainedLayout.Dependency("action-row", ""), placed -> new Emission(
                action.id(), Role.BUTTON, action.label(), "", placed.bounds(), action.enabled(), action.selected(), false,
                action.reason(), Visual.of(Kind.ACTION_ROW).variant(action.variant()), action.invoke(), null, null, null, action.label()));
    }

    private static Element actionRowLabel(Element element) {
        var children = element.children.stream().map(StudioView::actionRowLabel).toList();
        Function<FlowLayout.Placed, Emission> content = element.presentation == null ? null : placed -> {
            var emission = element.presentation.apply(placed);
            if (emission.role() != Role.LABEL)
                throw new IllegalArgumentException("actionRow descendants must be labels or layout containers: " + emission.id());
            return emission;
        };
        return new Element(element.node, children, element.dependency, content);
    }

    public Element button(StudioComponents.Action action, Kind kind) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(kind);
        int advance = action.icon() == Icon.NONE ? 0 : StudioIcon.ADVANCE;
        boolean compact = kind == Kind.ICON || kind == Kind.CHECKBOX;
        String measuredLabel = compact ? "" : action.label();
        var node = FlowLayout.leaf(action.id(), Style.content(), limits -> new Size(
                compact ? metrics.row() : Math.max(metrics.row(), text.width(measuredLabel, TextSize.BODY) + 12 + advance), Math.max(metrics.row(), text.lineHeight(TextSize.BODY))));
        Role role = kind == Kind.TAB || kind == Kind.DOCUMENT_TAB ? Role.TAB : kind == Kind.TREE_ROW ? Role.TREE_ITEM : Role.BUTTON;
        return new Element(node, List.of(), new RetainedLayout.Dependency("button/" + kind, List.of(measuredLabel, compact ? 0 : advance, metrics.row()), true), placed -> new Emission(action.id(), role, action.label(), "", placed.contentBounds(),
                action.enabled(), action.selected(), false, action.reason(), Visual.of(kind).variant(action.variant()).icon(action.icon()),
                action.invoke(), null, null, null, action.label()));
    }

    public Element input(Input input) {
        Objects.requireNonNull(input);
        var node = FlowLayout.leaf(input.id(), Style.defaults(), limits -> new Size(0, Math.max(metrics.row(), text.lineHeight(TextSize.BODY))));
        return new Element(node, List.of(), new RetainedLayout.Dependency("input", metrics.row(), true), placed -> new Emission(input.id(), Role.TEXT_FIELD, input.label(), input.raw(), placed.contentBounds(),
                input.enabled(), false, input.readOnly() || !input.enabled(), input.reason(), Visual.of(Kind.INPUT).flags(false, false, false, input.invalid()),
                null, input.change(), input.group(), input.commit(), input.label()));
    }

    /**
     * A measured tree row with the legacy activation, disclosure and selection identities.
     * Indentation yields to the label at narrow widths; leaf rows reserve the same arrow slot.
     */
    public Element treeRow(StudioComponents.TreeRow row) {
        Objects.requireNonNull(row);
        int arrow = Math.max(0, metrics.row() - 5);
        long indentation = (long) Math.max(0, row.depth()) * Math.max(0, metrics.treeIndent());
        var indent = placeholder(row.id() + "/indent", Style.content(), indentation, limits -> new Size((int) Math.min(indentation, Math.max(0, limits.maxWidth() - 65)), 0));
        Element disclosure = row.branch() ? button(new StudioComponents.Action(row.id() + "/expand", "",
                row.expanded() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, Variant.MUTED,
                true, false, "", row.toggle()), Kind.ICON).detail(row.label()) : placeholder(row.id() + "/arrow-slot", Style.defaults());
        disclosure = disclosure.style(Style.fill().width(Length.fixed(arrow)));
        var activate = button(new StudioComponents.Action(row.id(), row.label(), row.icon(),
                Variant.DEFAULT, true, row.selected(), "", row.activate()), Kind.TREE_ROW).style(Style.fill());
        var children = List.of(indent, disclosure, activate);
        var node = FlowLayout.row(row.id() + "/selection", Style.defaults(), children.stream().map(Element::node).toList());
        return new Element(node, children, new RetainedLayout.Dependency("tree-row", ""), !row.selected() ? null : placed -> new Emission(row.id() + "/selection", Role.PANEL, "", "", placed.bounds(),
                true, true, false, "", Visual.of(Kind.TREE_ROW), null, null, null, null, null));
    }

    public Element placeholder(String id, Style style) {
        return new Element(FlowLayout.leaf(id, style, limits -> new Size(0, 0)), List.of(), new RetainedLayout.Dependency("placeholder", "zero"), null);
    }

    /**
     * Opaque custom measurements are only reused while their measurer identity remains stable.
     * Use the dependency overload for short-lived lambdas with explicit immutable inputs.
     */
    public Element placeholder(String id, Style style, FlowLayout.Measurer measurer) {
        return placeholder(id, style, measurer, measurer);
    }

    public Element placeholder(String id, Style style, Object measurementDependency, FlowLayout.Measurer measurer) {
        return new Element(FlowLayout.leaf(id, style, measurer), List.of(), new RetainedLayout.Dependency("custom-placeholder", measurementDependency), null);
    }

    public Element column(String id, Style style, List<Element> children) {
        return container(FlowLayout.Kind.COLUMN, id, style, children, null);
    }

    public Element row(String id, Style style, List<Element> children) {
        return container(FlowLayout.Kind.ROW, id, style, children, null);
    }

    public Element wrap(String id, Style style, List<Element> children) {
        return container(FlowLayout.Kind.WRAP, id, style, children, null);
    }

    public Element grid(String id, Style style, List<Element> children) {
        return container(FlowLayout.Kind.GRID, id, style, children, null);
    }

    public Element surface(String id, Kind kind, Style style, List<Element> children) {
        return container(FlowLayout.Kind.COLUMN, id, style, children, Visual.of(kind));
    }

    public Element surface(String id, Kind kind, FlowLayout.Kind arrangement, Style style, List<Element> children) {
        return container(arrangement, id, style, children, Visual.of(kind));
    }

    private Element container(FlowLayout.Kind arrangement, String id, Style style, List<Element> children, Visual visual) {
        var copy = List.copyOf(children);
        var node = new FlowLayout.Node(id, arrangement, style, copy.stream().map(Element::node).toList(), null);
        return new Element(node, copy, new RetainedLayout.Dependency(visual == null ? "container/" + arrangement : "surface/" + arrangement + "/" + visual.kind(), ""), visual == null ? null : placed -> new Emission(id, Role.PANEL, "", "", placed.bounds(),
                true, false, false, "", visual, null, null, null, null, null));
    }

    /** Label/control/hint/error/actions follow the approved field structure, with no caller y arithmetic. */
    public Element field(Field field) {
        var parts = new ArrayList<Element>();
        TextSize labelSize = field.inlineLabel() ? TextSize.BODY : TextSize.SMALL;
        var label = label(field.id() + "/label", field.label(), labelSize, field.inlineLabel() ? Variant.DEFAULT : Variant.MUTED, field.labelColor(), true);
        if (field.inlineLabel()) {
            parts.add(wrap(field.id() + "/control-row", Style.defaults().gap(metrics.gap()), List.of(
                    label.style(Style.defaults().width(Length.stretch().limits(65, Integer.MAX_VALUE))),
                    field.control().style(field.control().node.style().width(Length.content())))));
        } else {
            parts.add(label);
            parts.add(field.control());
        }
        if (!field.hint().isBlank()) parts.add(paragraph(field.id() + "/hint", field.hint(), TextSize.SMALL, Variant.MUTED));
        if (!field.error().isBlank()) parts.add(paragraph(field.id() + "/error", field.error(), TextSize.SMALL, Variant.DANGER));
        if (!field.actions().isEmpty()) parts.add(wrap(field.id() + "/actions", Style.defaults().gap(metrics.gap()), field.actions().stream().map(this::button).toList()));
        return column(field.id() + "/field", Style.defaults().gap(metrics.gap()), parts);
    }

    /** Compatibility data adapter. The old components may keep their public field records and IDs. */
    public Element fields(String id, List<StudioComponents.Field> fields, StudioComponents.FieldActions actions) {
        if (fields.isEmpty()) throw new IllegalArgumentException("empty field row");
        var cells = new ArrayList<Element>();
        for (var field : fields) {
            Element control;
            if (field.kind() == StudioComponents.FieldKind.TEXT) {
                control = input(new Input(field.id(), field.label(), field.raw(), true, !field.enabled(), field.invalid(), "", id + "/", field.change(), field.commit()));
            } else {
                boolean toggle = field.kind() == StudioComponents.FieldKind.TOGGLE;
                control = button(new StudioComponents.Action(field.id(), toggle ? field.label() : field.display(), field.kind() == StudioComponents.FieldKind.CHOICE ? Icon.CHEVRON_DOWN : Icon.NONE,
                        Variant.OUTLINED, field.enabled(), toggle && Boolean.parseBoolean(field.raw()), field.hint(), field.choose()),
                        toggle ? Kind.CHECKBOX : Kind.BUTTON).style(Style.defaults());
            }
            control = control.detail(field.label() + (field.hint().isBlank() ? "" : " · " + field.hint()));
            cells.add(field(new Field(field.id(), field.label(), control, field.hint(), "", List.of(),
                    field.kind() == StudioComponents.FieldKind.TOGGLE, field.kind() == StudioComponents.FieldKind.TOGGLE ? null : field.labelColor()))
                    .style(Style.defaults().width(Length.stretch().limits(65, Integer.MAX_VALUE)).gap(metrics.gap())));
        }
        var rows = new ArrayList<Element>();
        rows.add(wrap(id + "/fields", Style.defaults().gap(metrics.gap()), cells));
        if (actions != null && actions.apply() != null) {
            rows.add(wrap(id + "/actions", Style.defaults().gap(metrics.gap()), List.of(
                    button(new StudioComponents.Action(id + "/apply", actions.applyLabel(), Icon.NONE, Variant.OUTLINED, actions.enabled(), false, "", actions.apply())),
                    button(StudioComponents.Action.plain(id + "/discard", actions.discardLabel(), actions.discard())))));
        }
        if (actions != null && !actions.error().isBlank()) rows.add(paragraph(id + "/error", actions.error(), TextSize.SMALL, Variant.DANGER));
        String layoutId = fields.stream().anyMatch(field -> field.id().equals(id)) ? id + "/layout" : id;
        return column(layoutId, Style.defaults().gap(metrics.gap()).padding(new Insets(0, metrics.padding(), metrics.fieldGap(), metrics.padding())), rows);
    }
}
