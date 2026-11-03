package net.ptcrys.fpsmatch.editor.common.ui.layout;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Integer, host-independent measure/arrange for Studio composition. A published result is the
 * sole geometry for painting and interaction. Children default to the top/left; containers do not
 * clip, scroll, elide, or invoke actions. The host applies those policies to the resulting bounds.
 *
 * <p>
 * Fixed lengths and minima may exceed the offered space. Content uses its measured size; it
 * is never proportionally compressed to make siblings fit. Stretch shares the remaining main
 * axis space equally, respecting each minimum and maximum. With an unbounded axis, stretch
 * measures as content. Grid uses equal-width tracks and content-height rows. Wrap packs stretch
 * children using their minima, then distributes spare width within each line; declare a useful
 * minimum to obtain responsive fields. Excess is retained and reported, not hidden or clipped.
 * Main-axis alignment uses only positive space left after allocation, independently per wrap
 * line; it never changes intrinsic measurement or shifts overflowing content before the origin.
 *
 * <p>
 * Measurements must be deterministic and free of business effects. They receive the actual
 * available content width, after padding/track allocation, and may be called at several widths.
 * A text leaf must use the same font and line breaking as its renderer. No text metrics are
 * guessed here. Arithmetic that cannot be represented by integer bounds is rejected.
 */
public final class FlowLayout {

    private FlowLayout() {}

    public enum Kind {
        LEAF,
        COLUMN,
        ROW,
        WRAP,
        GRID
    }

    public enum Mode {
        FIXED,
        CONTENT,
        STRETCH
    }

    public enum MainAlign {
        START,
        CENTER,
        END
    }

    public record Size(int width, int height) {

        public Size {
            nonNegative(width, "width");
            nonNegative(height, "height");
        }
    }

    /** UNBOUNDED is a measurement sentinel, not a request for a giant stretch allocation. */
    public record Constraints(int maxWidth, int maxHeight) {

        public static final int UNBOUNDED = Integer.MAX_VALUE;

        public Constraints {
            nonNegative(maxWidth, "maxWidth");
            nonNegative(maxHeight, "maxHeight");
        }
    }

    @FunctionalInterface
    public interface Measurer {

        Size measure(Constraints constraints);
    }

    public record Length(Mode mode, int value, int min, int max) {

        public Length {
            Objects.requireNonNull(mode);
            nonNegative(value, "value");
            nonNegative(min, "min");
            if (max < min) throw new IllegalArgumentException("max is below min");
        }

        public static Length fixed(int value) {
            return new Length(Mode.FIXED, value, 0, Integer.MAX_VALUE);
        }

        public static Length content() {
            return new Length(Mode.CONTENT, 0, 0, Integer.MAX_VALUE);
        }

        public static Length stretch() {
            return new Length(Mode.STRETCH, 0, 0, Integer.MAX_VALUE);
        }

        public Length limits(int minimum, int maximum) {
            return new Length(mode, value, minimum, maximum);
        }

        private int clamp(int desired) {
            return Math.max(min, Math.min(max, desired));
        }
    }

    public record Insets(int top, int right, int bottom, int left) {

        public static final Insets ZERO = new Insets(0, 0, 0, 0);

        public Insets {
            nonNegative(top, "top");
            nonNegative(right, "right");
            nonNegative(bottom, "bottom");
            nonNegative(left, "left");
            sum(left, right);
            sum(top, bottom);
        }

        public static Insets all(int value) {
            return new Insets(value, value, value, value);
        }

        public static Insets symmetric(int horizontal, int vertical) {
            return new Insets(vertical, horizontal, vertical, horizontal);
        }

        private int horizontal() {
            return sum(left, right);
        }

        private int vertical() {
            return sum(top, bottom);
        }
    }

    public record Style(Length width, Length height, Insets padding, int gap, int columns, MainAlign mainAlign) {

        public Style {
            Objects.requireNonNull(width);
            Objects.requireNonNull(height);
            Objects.requireNonNull(padding);
            Objects.requireNonNull(mainAlign);
            nonNegative(gap, "gap");
            if (columns < 1) throw new IllegalArgumentException("columns must be positive");
        }

        public Style(Length width, Length height, Insets padding, int gap, int columns) {
            this(width, height, padding, gap, columns, MainAlign.START);
        }

        public static Style defaults() {
            return new Style(Length.stretch(), Length.content(), Insets.ZERO, 0, 1);
        }

        public static Style content() {
            return defaults().width(Length.content());
        }

        public static Style fill() {
            return defaults().height(Length.stretch());
        }

        public Style width(Length value) {
            return new Style(value, height, padding, gap, columns, mainAlign);
        }

        public Style height(Length value) {
            return new Style(width, value, padding, gap, columns, mainAlign);
        }

        public Style padding(Insets value) {
            return new Style(width, height, value, gap, columns, mainAlign);
        }

        public Style gap(int value) {
            return new Style(width, height, padding, value, columns, mainAlign);
        }

        public Style columns(int value) {
            return new Style(width, height, padding, gap, value, mainAlign);
        }

        /** Aligns rows, individual wrap lines, or a column after stretch has consumed its share. */
        public Style mainAlign(MainAlign value) {
            return new Style(width, height, padding, gap, columns, value);
        }
    }

    public record Node(String id, Kind kind, Style style, List<Node> children, Measurer measurer) {

        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(style);
            if (id.isBlank()) throw new IllegalArgumentException("layout id is blank");
            children = List.copyOf(children);
            if (kind == Kind.LEAF) {
                Objects.requireNonNull(measurer);
                if (!children.isEmpty()) throw new IllegalArgumentException("leaf has children");
            } else if (measurer != null) throw new IllegalArgumentException("container has a leaf measurer");
        }
    }

    public static Node leaf(String id, Style style, Measurer measurer) {
        return new Node(id, Kind.LEAF, style, List.of(), measurer);
    }

    public static Node column(String id, Style style, List<Node> children) {
        return new Node(id, Kind.COLUMN, style, children, null);
    }

    public static Node row(String id, Style style, List<Node> children) {
        return new Node(id, Kind.ROW, style, children, null);
    }

    public static Node wrap(String id, Style style, List<Node> children) {
        return new Node(id, Kind.WRAP, style, children, null);
    }

    public static Node grid(String id, Style style, List<Node> children) {
        return new Node(id, Kind.GRID, style, children, null);
    }

    /** Overflow is the number of pixels extending beyond the right/bottom, including descendants. */
    public record Placed(String id, Bounds bounds, Bounds contentBounds, int overflowWidth, int overflowHeight, List<Placed> children) {

        public Placed {
            Objects.requireNonNull(id);
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(contentBounds);
            nonNegative(overflowWidth, "overflowWidth");
            nonNegative(overflowHeight, "overflowHeight");
            children = List.copyOf(children);
        }
    }

    public record Result(Placed root, Map<String, Placed> byId) {

        public Result {
            Objects.requireNonNull(root);
            byId = Collections.unmodifiableMap(new LinkedHashMap<>(byId));
        }

        public Placed require(String id) {
            var placed = byId.get(id);
            if (placed == null) throw new IllegalArgumentException("unknown layout id: " + id);
            return placed;
        }
    }

    public static Size measure(Node node, Constraints constraints) {
        validate(node, new HashSet<>());
        return new Engine().measure(node, Objects.requireNonNull(constraints), false);
    }

    /** The host owns the root slot exactly; root lengths affect measure(), not this assigned slot. */
    public static Result layout(Node root, Bounds bounds) {
        validate(root, new HashSet<>());
        Objects.requireNonNull(bounds);
        return new Engine().layout(root, bounds);
    }

    /** Fixed host width, natural root height; suitable for a scroll body's single frozen result. */
    public static Result layoutContent(Node root, int x, int y, int width) {
        validate(root, new HashSet<>());
        nonNegative(width, "width");
        var engine = new Engine();
        int height = engine.atWidth(root, width).height();
        return engine.layout(root, new Bounds(x, y, width, height));
    }

    enum Operation {
        MEASURE,
        WIDTH,
        CONTENTS,
        ARRANGE,
        LAYOUT
    }

    record Query(Operation operation, Object argument) {}

    private record Key(Constraints constraints, boolean intrinsicWidth) {}

    private record ContentKey(int width, int height, boolean intrinsicWidth) {}

    record Cell(Node node, int x, int y, int width, int height) {}

    record Plan(List<Cell> cells, Size extent) {}

    record Arrangement(Plan plan, Bounds contentBounds, int overflowWidth, int overflowHeight) {}

    interface Memo {

        Object get(Node node, Query query);

        void put(Node node, Query query, Object value);

        default void leafMeasured() {}

        default void arranged() {}
    }

    private static final class LocalMemo implements Memo {

        private final Map<Node, Map<Query, Object>> values = new IdentityHashMap<>();

        @Override
        public Object get(Node node, Query query) {
            var entries = values.get(node);
            return entries == null ? null : entries.get(query);
        }

        @Override
        public void put(Node node, Query query, Object value) {
            values.computeIfAbsent(node, ignored -> new HashMap<>()).put(query, value);
        }
    }

    static final class Engine {

        private final Memo memo;

        Engine() {
            this(new LocalMemo());
        }

        Engine(Memo memo) {
            this.memo = memo;
        }

        Size measure(Node node, Constraints limits, boolean intrinsicWidth) {
            var key = new Query(Operation.MEASURE, new Key(limits, intrinsicWidth));
            var prior = (Size) memo.get(node, key);
            if (prior != null) return prior;
            var length = node.style.width;
            boolean known = length.mode == Mode.FIXED || length.mode == Mode.STRETCH && !intrinsicWidth && limits.maxWidth != Constraints.UNBOUNDED;
            int offered = length.clamp(length.mode == Mode.FIXED ? length.value : limits.maxWidth);
            Plan plan = contents(node, offered, Constraints.UNBOUNDED, !known);
            int width = known ? offered : length.clamp(sum(plan.extent.width, node.style.padding.horizontal()));
            if (width != offered) plan = contents(node, width, Constraints.UNBOUNDED);
            int height = resolve(node.style.height, sum(plan.extent.height, node.style.padding.vertical()), limits.maxHeight);
            var value = new Size(width, height);
            memo.put(node, key, value);
            return value;
        }

        Size atWidth(Node node, int width) {
            var key = new Query(Operation.WIDTH, width);
            var prior = (Size) memo.get(node, key);
            if (prior != null) return prior;
            var plan = contents(node, width, Constraints.UNBOUNDED);
            var value = new Size(width, resolve(node.style.height, sum(plan.extent.height, node.style.padding.vertical()), Constraints.UNBOUNDED));
            memo.put(node, key, value);
            return value;
        }

        Result layout(Node node, Bounds bounds) {
            var key = new Query(Operation.LAYOUT, new Size(bounds.width(), bounds.height()));
            var result = (Result) memo.get(node, key);
            if (result == null) {
                var index = new LinkedHashMap<String, Placed>();
                var root = place(node, new Bounds(0, 0, bounds.width(), bounds.height()), index);
                result = new Result(root, index);
                memo.put(node, key, result);
            }
            if (bounds.x() == 0 && bounds.y() == 0) return result;
            var index = new LinkedHashMap<String, Placed>();
            return new Result(translate(result.root(), bounds.x(), bounds.y(), index), index);
        }

        private Placed translate(Placed node, int x, int y, Map<String, Placed> index) {
            var children = node.children().stream().map(child -> translate(child, x, y, index)).toList();
            var bounds = node.bounds();
            var content = node.contentBounds();
            var moved = new Placed(node.id(), new Bounds(coordinate(bounds.x(), x), coordinate(bounds.y(), y), bounds.width(), bounds.height()),
                    new Bounds(coordinate(content.x(), x), coordinate(content.y(), y), content.width(), content.height()), node.overflowWidth(), node.overflowHeight(), children);
            coordinate(moved.bounds().x(), moved.bounds().width());
            coordinate(moved.bounds().y(), moved.bounds().height());
            index.put(moved.id(), moved);
            return moved;
        }

        private Arrangement arrange(Node node, int width, int height) {
            var key = new Query(Operation.ARRANGE, new Size(width, height));
            var prior = (Arrangement) memo.get(node, key);
            if (prior != null) return prior;
            memo.arranged();
            var padding = node.style.padding;
            var inner = new Bounds(padding.left, padding.top,
                    Math.max(0, width - padding.horizontal()), Math.max(0, height - padding.vertical()));
            var plan = contents(node, width, height);
            int extentWidth = plan.extent.width, extentHeight = plan.extent.height;
            for (var cell : plan.cells) {
                var child = arrange(cell.node, cell.width, cell.height);
                extentWidth = Math.max(extentWidth, sum(cell.x, cell.width, child.overflowWidth));
                extentHeight = Math.max(extentHeight, sum(cell.y, cell.height, child.overflowHeight));
            }
            var value = new Arrangement(plan, inner,
                    Math.max(0, sum(extentWidth, padding.horizontal()) - width),
                    Math.max(0, sum(extentHeight, padding.vertical()) - height));
            memo.put(node, key, value);
            return value;
        }

        /**
         * Materialization changes coordinate space only; retained arrangement never stores a
         * parent position or repeats unchanged descendants' allocation/overflow work.
         */
        private Placed place(Node node, Bounds bounds, Map<String, Placed> index) {
            coordinate(bounds.x(), bounds.width());
            coordinate(bounds.y(), bounds.height());
            var arranged = arrange(node, bounds.width(), bounds.height());
            var content = arranged.contentBounds;
            var inner = new Bounds(coordinate(bounds.x(), content.x()), coordinate(bounds.y(), content.y()), content.width(), content.height());
            var children = new ArrayList<Placed>();
            for (var cell : arranged.plan.cells) children.add(place(cell.node,
                    new Bounds(coordinate(inner.x(), cell.x), coordinate(inner.y(), cell.y), cell.width, cell.height), index));
            var value = new Placed(node.id, bounds, inner, arranged.overflowWidth, arranged.overflowHeight, children);
            index.put(node.id, value);
            return value;
        }

        private Plan contents(Node node, int outerWidth, int outerHeight) {
            return contents(node, outerWidth, outerHeight, false);
        }

        private Plan contents(Node node, int outerWidth, int outerHeight, boolean intrinsicWidth) {
            var key = new Query(Operation.CONTENTS, new ContentKey(outerWidth, outerHeight, intrinsicWidth));
            var prior = (Plan) memo.get(node, key);
            if (prior != null) return prior;
            var value = contentsUncached(node, outerWidth, outerHeight, intrinsicWidth);
            memo.put(node, key, value);
            return value;
        }

        private Plan contentsUncached(Node node, int outerWidth, int outerHeight, boolean intrinsicWidth) {
            int width = inner(outerWidth, node.style.padding.horizontal());
            int height = inner(outerHeight, node.style.padding.vertical());
            if (node.kind == Kind.LEAF) {
                memo.leafMeasured();
                return new Plan(List.of(), Objects.requireNonNull(node.measurer.measure(new Constraints(width, height)), "leaf measurement"));
            }
            if (node.children.isEmpty()) return new Plan(List.of(), new Size(0, 0));
            return switch (node.kind) {
                case COLUMN -> columnPlan(node, width, height, intrinsicWidth);
                case ROW -> rowPlan(node.children, node.style.gap, width, height, intrinsicWidth, node.style.mainAlign);
                case WRAP -> wrapPlan(node, width, intrinsicWidth);
                case GRID -> gridPlan(node, width, intrinsicWidth);
                case LEAF -> throw new AssertionError();
            };
        }

        private Plan columnPlan(Node node, int width, int height, boolean intrinsicWidth) {
            int count = node.children.size();
            int[] childWidths = new int[count], childHeights = new int[count];
            var lengths = new ArrayList<Length>();
            for (int i = 0; i < count; i++) {
                var child = node.children.get(i);
                childWidths[i] = measure(child, new Constraints(width, Constraints.UNBOUNDED), intrinsicWidth).width;
                childHeights[i] = atWidth(child, childWidths[i]).height;
                lengths.add(child.style.height);
            }
            allocate(childHeights, lengths, height, node.style.gap);
            var cells = new ArrayList<Cell>();
            int y = 0;
            for (int i = 0; i < count; i++) {
                cells.add(new Cell(node.children.get(i), 0, y, childWidths[i], childHeights[i]));
                y = sum(y, childHeights[i], i + 1 < count ? node.style.gap : 0);
            }
            return aligned(cells, offset(node.style.mainAlign, height, y), false);
        }

        private Plan rowPlan(List<Node> children, int gap, int width, int height, boolean intrinsicWidth, MainAlign align) {
            int count = children.size();
            int[] childWidths = new int[count];
            var lengths = new ArrayList<Length>();
            for (int i = 0; i < count; i++) {
                var child = children.get(i);
                lengths.add(child.style.width);
                childWidths[i] = measure(child, new Constraints(width, Constraints.UNBOUNDED), true).width;
            }
            allocate(childWidths, lengths, intrinsicWidth ? Constraints.UNBOUNDED : width, gap);
            int[] childHeights = new int[count];
            int naturalHeight = 0;
            for (int i = 0; i < count; i++) {
                childHeights[i] = atWidth(children.get(i), childWidths[i]).height;
                naturalHeight = Math.max(naturalHeight, childHeights[i]);
            }
            int cross = height == Constraints.UNBOUNDED ? naturalHeight : height;
            var cells = new ArrayList<Cell>();
            int x = 0;
            for (int i = 0; i < count; i++) {
                var child = children.get(i);
                int childHeight = resolve(child.style.height, childHeights[i], cross);
                cells.add(new Cell(child, x, 0, childWidths[i], childHeight));
                x = sum(x, childWidths[i], i + 1 < count ? gap : 0);
            }
            return aligned(cells, intrinsicWidth ? 0 : offset(align, width, x), true);
        }

        private Plan wrapPlan(Node node, int width, boolean intrinsicWidth) {
            var lines = new ArrayList<List<Node>>();
            var line = new ArrayList<Node>();
            int used = 0;
            for (var child : node.children) {
                int desired = !intrinsicWidth && width != Constraints.UNBOUNDED && child.style.width.mode == Mode.STRETCH ? child.style.width.min : measure(child, new Constraints(width, Constraints.UNBOUNDED), true).width;
                long next = (long) used + (line.isEmpty() ? 0 : node.style.gap) + desired;
                if (!line.isEmpty() && next > width) {
                    lines.add(List.copyOf(line));
                    line.clear();
                    used = 0;
                }
                used = sum(used, line.isEmpty() ? 0 : node.style.gap, desired);
                line.add(child);
            }
            if (!line.isEmpty()) lines.add(List.copyOf(line));
            var cells = new ArrayList<Cell>();
            int y = 0;
            for (int i = 0; i < lines.size(); i++) {
                var row = rowPlan(lines.get(i), node.style.gap, width, Constraints.UNBOUNDED, intrinsicWidth, node.style.mainAlign);
                for (var cell : row.cells) cells.add(new Cell(cell.node, cell.x, y, cell.width, cell.height));
                y = sum(y, row.extent.height, i + 1 < lines.size() ? node.style.gap : 0);
            }
            return plan(cells);
        }

        private Plan gridPlan(Node node, int width, boolean intrinsicWidth) {
            int columns = node.style.columns, gap = node.style.gap;
            int gaps = product(columns - 1, gap), widest = 0;
            if (width == Constraints.UNBOUNDED || intrinsicWidth) {
                for (var child : node.children) widest = Math.max(widest, measure(child, new Constraints(width, Constraints.UNBOUNDED), true).width);
                width = sum(product(columns, widest), gaps);
            }
            int usable = Math.max(0, width - gaps), y = 0;
            var cells = new ArrayList<Cell>();
            for (int start = 0; start < node.children.size(); start += columns) {
                var row = new ArrayList<Cell>();
                int rowHeight = 0;
                for (int column = 0; column < Math.min(columns, node.children.size() - start); column++) {
                    int left = (int) ((long) usable * column / columns), right = (int) ((long) usable * (column + 1) / columns);
                    var child = node.children.get(start + column);
                    int childWidth = measure(child, new Constraints(right - left, Constraints.UNBOUNDED), false).width;
                    int childHeight = atWidth(child, childWidth).height;
                    rowHeight = Math.max(rowHeight, childHeight);
                    row.add(new Cell(child, sum(left, product(column, gap)), y, childWidth, childHeight));
                }
                for (var cell : row) cells.add(new Cell(cell.node, cell.x, cell.y, cell.width, resolve(cell.node.style.height, cell.height, rowHeight)));
                y = sum(y, rowHeight, start + columns < node.children.size() ? gap : 0);
            }
            return plan(cells);
        }
    }

    /** Water filling avoids losing pixels at max caps, and has deterministic source-order remainders. */
    private static void allocate(int[] sizes, List<Length> lengths, int available, int gap) {
        if (available == Constraints.UNBOUNDED) return;
        long used = (long) Math.max(0, sizes.length - 1) * gap;
        for (int i = 0; i < sizes.length; i++) {
            var length = lengths.get(i);
            if (length.mode == Mode.STRETCH) sizes[i] = length.min;
            used += sizes[i];
        }
        int remaining = (int) Math.max(0, available - used);
        while (remaining > 0) {
            int count = 0;
            for (int i = 0; i < sizes.length; i++) if (lengths.get(i).mode == Mode.STRETCH && sizes[i] < lengths.get(i).max) count++;
            if (count == 0) break;
            int share = remaining / count, remainder = remaining % count, consumed = 0;
            for (int i = 0; i < sizes.length; i++) if (lengths.get(i).mode == Mode.STRETCH && sizes[i] < lengths.get(i).max) {
                int wanted = share + (remainder-- > 0 ? 1 : 0);
                int added = Math.min(wanted, lengths.get(i).max - sizes[i]);
                sizes[i] += added;
                consumed += added;
            }
            if (consumed == 0) break;
            remaining -= consumed;
        }
    }

    private static Plan plan(List<Cell> cells) {
        int width = 0, height = 0;
        for (var cell : cells) {
            width = Math.max(width, sum(cell.x, cell.width));
            height = Math.max(height, sum(cell.y, cell.height));
        }
        return new Plan(List.copyOf(cells), new Size(width, height));
    }

    private static int offset(MainAlign alignment, int available, int used) {
        if (available == Constraints.UNBOUNDED || available <= used) return 0;
        return switch (alignment) {
            case START -> 0;
            case CENTER -> (available - used) / 2;
            case END -> available - used;
        };
    }

    private static Plan aligned(List<Cell> cells, int offset, boolean horizontal) {
        if (offset == 0) return plan(cells);
        return plan(cells.stream().map(cell -> new Cell(cell.node, horizontal ? sum(cell.x, offset) : cell.x,
                horizontal ? cell.y : sum(cell.y, offset), cell.width, cell.height)).toList());
    }

    private static int resolve(Length length, int content, int available) {
        return length.clamp(length.mode == Mode.FIXED ? length.value : length.mode == Mode.STRETCH && available != Constraints.UNBOUNDED ? available : content);
    }

    private static int inner(int outer, int padding) {
        return outer == Constraints.UNBOUNDED ? outer : Math.max(0, outer - padding);
    }

    private static void validate(Node node, Set<String> ids) {
        Objects.requireNonNull(node);
        if (!ids.add(node.id)) throw new IllegalArgumentException("duplicate layout id: " + node.id);
        for (var child : node.children) validate(child, ids);
    }

    private static void nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " is negative");
    }

    private static int sum(int... values) {
        long result = 0;
        for (int value : values) result += value;
        return dimension(result);
    }

    private static int product(int value, int count) {
        return dimension((long) value * count);
    }

    private static int dimension(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("layout dimension exceeds integer bounds");
        return (int) value;
    }

    private static int coordinate(int origin, int offset) {
        long value = (long) origin + offset;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new IllegalArgumentException("layout coordinate exceeds integer bounds");
        return (int) value;
    }
}
