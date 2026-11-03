package net.ptcrys.fpsmatch.editor.common.ui.layout;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Registration-free dock geometry. The solver knows only the frozen tree, the registered panel
 * minimums and the two chrome measures passed by the caller; it never hardcodes a panel role, a
 * mandatory viewport or a product panel id, and it neither reads nor writes anything outside its
 * arguments.
 */
public final class DockSolver {

    private DockSolver() {}

    /**
     * Allocates {@code content} to the whole tree.
     *
     * @param layout        validated tree
     * @param content       available area; its origin may be any finite value
     * @param sizes         registered minimums, covering exactly the layout panels
     * @param splitterWidth nominal splitter gap, finite and not negative
     * @param tabHeight     nominal tab header height, finite and not negative
     * @return immutable geometry in tree traversal order
     * @throws NullPointerException     when {@code layout}, {@code content}, {@code sizes}, one of
     *                                  their keys or one of the values is {@code null}
     * @throws IllegalArgumentException when a measure is not finite or negative, when {@code sizes}
     *                                  does not cover the layout panels exactly, when a derived
     *                                  value would not be finite, or when the caller's finite
     *                                  extremes cannot express a non-negative partition
     */
    public static DockProjection solve(DockLayout layout, Rect content, Map<UUID, PanelSize> sizes,
                                       double splitterWidth, double tabHeight) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(sizes, "sizes");
        Bounds.nonNegative(splitterWidth, "splitterWidth");
        Bounds.nonNegative(tabHeight, "tabHeight");
        Map<UUID, PanelSize> registered = copySizes(sizes);
        requireExactCoverage(layout.root(), registered);

        Min rootMinimum = minimum(layout.root(), registered, splitterWidth, tabHeight);
        List<PanelBox> panels = new ArrayList<>();
        List<TabBox> tabs = new ArrayList<>();
        List<SplitBox> splitters = new ArrayList<>();
        boolean minimumsMet = allocate(layout.root(), content, registered, splitterWidth, tabHeight,
                panels, tabs, splitters);
        for (PanelBox panel : panels) {
            requireContained(panel.bounds(), content);
        }
        for (TabBox tab : tabs) {
            requireContained(tab.header(), content);
        }
        for (SplitBox splitter : splitters) {
            requireContained(splitter.bounds(), content);
        }
        boolean fits = minimumsMet && meetsMinimum(content.width(), rootMinimum.width()) && meetsMinimum(content.height(), rootMinimum.height());
        return new DockProjection(fits, panels, tabs, splitters);
    }

    private static Map<UUID, PanelSize> copySizes(Map<UUID, PanelSize> sizes) {
        Map<UUID, PanelSize> copy = new LinkedHashMap<>(Math.max(4, sizes.size() * 2));
        for (Map.Entry<UUID, PanelSize> entry : sizes.entrySet()) {
            UUID panelId = Objects.requireNonNull(entry.getKey(), "panelId");
            copy.put(panelId, Objects.requireNonNull(entry.getValue(), "panel size"));
        }
        return copy;
    }

    private static void requireExactCoverage(DockNode root, Map<UUID, PanelSize> sizes) {
        Set<UUID> expected = new LinkedHashSet<>();
        collectPanels(root, expected);
        for (UUID panelId : expected) {
            if (!sizes.containsKey(panelId)) {
                throw new IllegalArgumentException("missing panel size: " + panelId);
            }
        }
        for (UUID panelId : sizes.keySet()) {
            if (!expected.contains(panelId)) {
                throw new IllegalArgumentException("unknown panel size: " + panelId);
            }
        }
    }

    private static void collectPanels(DockNode root, Set<UUID> out) {
        Deque<DockNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            DockNode node = stack.pop();
            if (node instanceof DockNode.Leaf leaf) {
                out.add(leaf.panelId());
            } else if (node instanceof DockNode.Tabs tabs) {
                out.addAll(tabs.panels());
            } else if (node instanceof DockNode.Split split) {
                stack.push(split.second());
                stack.push(split.first());
            }
        }
    }

    private static Min minimum(DockNode node, Map<UUID, PanelSize> sizes, double splitterWidth, double tabHeight) {
        if (node instanceof DockNode.Empty) {
            return new Min(0, 0);
        }
        if (node instanceof DockNode.Leaf leaf) {
            PanelSize size = sizeOf(leaf.panelId(), sizes);
            return new Min(size.minWidth(), size.minHeight());
        }
        if (node instanceof DockNode.Tabs tabs) {
            double width = 0;
            double height = 0;
            for (UUID panelId : tabs.panels()) {
                PanelSize size = sizeOf(panelId, sizes);
                width = Math.max(width, size.minWidth());
                height = Math.max(height, size.minHeight());
            }
            return new Min(width, sum(height, tabHeight, "tab minimum height"));
        }
        DockNode.Split split = (DockNode.Split) node;
        Min first = minimum(split.first(), sizes, splitterWidth, tabHeight);
        Min second = minimum(split.second(), sizes, splitterWidth, tabHeight);
        if (split.axis() == DockNode.Axis.HORIZONTAL) {
            double width = sum(sum(first.width(), second.width(), "split minimum width"), splitterWidth,
                    "split minimum width");
            return new Min(width, Math.max(first.height(), second.height()));
        }
        double height = sum(sum(first.height(), second.height(), "split minimum height"), splitterWidth,
                "split minimum height");
        return new Min(Math.max(first.width(), second.width()), height);
    }

    /**
     * Walks the tree and reports whether every emitted panel body actually reached its registered
     * minimum, using {@link #meetsMinimum(double, double)} for the comparison. Geometry itself applies
     * no tolerance anywhere: a partition is always kept inside its direct parent and inside
     * {@code content}, and finite extremes that cannot be partitioned are refused.
     */
    private static boolean allocate(DockNode node, Rect area, Map<UUID, PanelSize> sizes,
                                    double splitterWidth, double tabHeight, List<PanelBox> panels,
                                    List<TabBox> tabs, List<SplitBox> splitters) {
        if (node instanceof DockNode.Empty) {
            return true;
        }
        if (node instanceof DockNode.Leaf leaf) {
            boolean minimumMet = meets(area, sizeOf(leaf.panelId(), sizes));
            panels.add(new PanelBox(leaf.panelId(), area, true, minimumMet));
            return minimumMet;
        }
        if (node instanceof DockNode.Tabs tabNode) {
            double headerEnd = tabHeight >= area.height() ? area.bottom() : area.y() + tabHeight;
            Rect header = partition(area, DockNode.Axis.VERTICAL, area.y(), headerEnd);
            // The body starts where the header actually ended, not where it was asked to end.
            Rect body = partition(area, DockNode.Axis.VERTICAL, header.bottom(), area.bottom());
            tabs.add(new TabBox(tabNode.id(), tabNode.panels(), tabNode.active(), header));
            boolean minimumsMet = true;
            for (UUID panelId : tabNode.panels()) {
                boolean minimumMet = meets(body, sizeOf(panelId, sizes));
                panels.add(new PanelBox(panelId, body, panelId.equals(tabNode.active()), minimumMet));
                minimumsMet &= minimumMet;
            }
            return minimumsMet;
        }
        DockNode.Split split = (DockNode.Split) node;
        boolean horizontal = split.axis() == DockNode.Axis.HORIZONTAL;
        double parentStart = horizontal ? area.x() : area.y();
        double parentEnd = horizontal ? area.right() : area.bottom();
        double axisLength = horizontal ? area.width() : area.height();
        double gap = Math.min(splitterWidth, axisLength);
        double usable = Math.max(0, axisLength - gap);
        Min firstMinimum = minimum(split.first(), sizes, splitterWidth, tabHeight);
        Min secondMinimum = minimum(split.second(), sizes, splitterWidth, tabHeight);
        double firstNeed = horizontal ? firstMinimum.width() : firstMinimum.height();
        double secondNeed = horizontal ? secondMinimum.width() : secondMinimum.height();
        double need = sum(firstNeed, secondNeed, "split minimum");
        double firstExtent;
        if (usable >= need) {
            firstExtent = Math.min(Math.max(usable * split.ratio(), firstNeed), usable - secondNeed);
        } else if (need > 0) {
            firstExtent = usable * (firstNeed / need);
        } else {
            firstExtent = usable * split.ratio();
        }
        // Every edge is clamped to the parent and each following edge is read back from the rect that
        // was actually built, so rounding can neither push a child out of the parent nor open a gap
        // between siblings.
        double firstEnd = Math.min(Math.max(parentStart + firstExtent, parentStart), parentEnd);
        Rect firstArea = partition(area, split.axis(), parentStart, firstEnd);
        double splitterStart = endOf(firstArea, split.axis());
        double splitterEnd = gap >= parentEnd - splitterStart ? parentEnd : splitterStart + gap;
        Rect splitterArea = partition(area, split.axis(), splitterStart, splitterEnd);
        Rect secondArea = partition(area, split.axis(), endOf(splitterArea, split.axis()), parentEnd);
        splitters.add(new SplitBox(split.id(), split.axis(), splitterArea));
        boolean minimumsMet = allocate(split.first(), firstArea, sizes, splitterWidth, tabHeight,
                panels, tabs, splitters);
        minimumsMet &= allocate(split.second(), secondArea, sizes, splitterWidth, tabHeight,
                panels, tabs, splitters);
        return minimumsMet;
    }

    /**
     * Builds the sub-area a parent hands out between two edges of one axis. The stored extent is
     * derived from the span's own edges and shortened by at most one ulp whenever the rounded sum
     * would otherwise reach past {@code end}; the result therefore always stays inside the parent,
     * and the caller reads the sub-area's real end back instead of assuming the requested one.
     *
     * @throws IllegalArgumentException when the span leaves the parent, is not a non-negative
     *                                  partition, or cannot be written back as a bounded finite rect
     */
    private static Rect partition(Rect parent, DockNode.Axis axis, double start, double end) {
        boolean horizontal = axis == DockNode.Axis.HORIZONTAL;
        double parentStart = horizontal ? parent.x() : parent.y();
        double parentEnd = horizontal ? parent.right() : parent.bottom();
        if (!(start >= parentStart) || !(end <= parentEnd) || !(end >= start)) {
            throw new IllegalArgumentException("dock partition " + start + ".." + end + " leaves its parent " + parentStart + ".." + parentEnd);
        }
        double extent = end - start;
        if (start + extent > end) {
            extent = Math.nextDown(extent);
            if (start + extent > end) {
                throw new IllegalArgumentException("dock partition " + start + ".." + end + " cannot be expressed as a bounded finite rect");
            }
        }
        return horizontal ? new Rect(start, parent.y(), extent, parent.height()) : new Rect(parent.x(), start, parent.width(), extent);
    }

    private static double endOf(Rect rect, DockNode.Axis axis) {
        return axis == DockNode.Axis.HORIZONTAL ? rect.right() : rect.bottom();
    }

    private static void requireContained(Rect inner, Rect content) {
        if (!(inner.x() >= content.x()) || !(inner.y() >= content.y()) || !(inner.right() <= content.right()) || !(inner.bottom() <= content.bottom())) {
            throw new IllegalArgumentException("dock geometry " + inner + " leaves its content " + content);
        }
    }

    private static PanelSize sizeOf(UUID panelId, Map<UUID, PanelSize> sizes) {
        PanelSize size = sizes.get(panelId);
        if (size == null) {
            throw new IllegalArgumentException("missing panel size: " + panelId);
        }
        return size;
    }

    private static boolean meets(Rect body, PanelSize size) {
        return meetsMinimum(body.width(), size.minWidth()) && meetsMinimum(body.height(), size.minHeight());
    }

    /**
     * Compares one actual extent with one required minimum. This is the only place where a rounded
     * decimal is forgiven: an ordinary fraction such as {@code 0.5} of a parent can lose up to two ulp
     * while it is split, and a panel must not be reported as unplaced for that. Geometry is never
     * excused this way - rectangles still lie exactly inside their parent and inside {@code content} -
     * and a zero extent never satisfies a positive minimum, nor does a real shortfall wider than two
     * ulp of the actual value.
     */
    private static boolean meetsMinimum(double actual, double required) {
        return actual >= required || (actual > 0 && Math.nextUp(Math.nextUp(actual)) >= required);
    }

    private static double sum(double first, double second, String what) {
        double total = first + second;
        if (!Double.isFinite(total)) {
            throw new IllegalArgumentException("non-finite " + what + ": " + total);
        }
        return total;
    }

    private record Min(double width, double height) {}
}
