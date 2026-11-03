package net.ptcrys.fpsmatch.editor.common.ui.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Behaviour tests for the registration-free dock geometry. */
class DockSolverTest {
    private static final double EPSILON = 1e-9;

    private static UUID id(int seed) {
        return new UUID(0x51ed000000000000L + seed, 0x0badc0deL);
    }

    private static Map<UUID, PanelSize> sizes(Object... pairs) {
        Map<UUID, PanelSize> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 3) {
            result.put((UUID) pairs[i], new PanelSize((Double) pairs[i + 1], (Double) pairs[i + 2]));
        }
        return result;
    }

    private static PanelBox box(DockProjection projection, UUID panelId) {
        PanelBox found = null;
        for (PanelBox candidate : projection.panels()) {
            if (candidate.panelId().equals(panelId)) {
                assertNull(found, "duplicate panel box for " + panelId);
                found = candidate;
            }
        }
        assertNotNull(found, "no panel box for " + panelId);
        return found;
    }

    private static void assertInside(Rect inner, Rect outer) {
        assertTrue(inner.x() >= outer.x(), "left edge " + inner + " vs " + outer);
        assertTrue(inner.y() >= outer.y(), "top edge " + inner + " vs " + outer);
        assertTrue(inner.right() <= outer.right(), "right edge " + inner + " vs " + outer);
        assertTrue(inner.bottom() <= outer.bottom(), "bottom edge " + inner + " vs " + outer);
    }

    private static void assertAllInside(DockProjection projection, Rect content) {
        for (PanelBox panel : projection.panels()) {
            assertInside(panel.bounds(), content);
        }
        for (TabBox tab : projection.tabs()) {
            assertInside(tab.header(), content);
        }
        for (SplitBox splitter : projection.splitters()) {
            assertInside(splitter.bounds(), content);
        }
    }

    private static void assertNoOverlap(PanelBox first, PanelBox second) {
        boolean overlap = first.bounds().x() < second.bounds().right()
                && second.bounds().x() < first.bounds().right()
                && first.bounds().y() < second.bounds().bottom()
                && second.bounds().y() < first.bounds().bottom();
        assertFalse(overlap, "panels overlap: " + first.bounds() + " / " + second.bounds());
    }

    private static DockNode completeTree(int splits, int[] nodeNext) {
        if (splits == 0) {
            return new DockNode.Empty();
        }
        int first = splits / 2;
        return new DockNode.Split(id(300000 + nodeNext[0]++), DockNode.Axis.HORIZONTAL, 0.5,
                completeTree(first, nodeNext), completeTree(splits - 1 - first, nodeNext));
    }

    private static DockNode panelTree(int levels, int[] leafNext, int[] nodeNext) {
        if (levels == 0) {
            return new DockNode.Leaf(id(leafNext[0]++));
        }
        return new DockNode.Split(id(400000 + nodeNext[0]++), DockNode.Axis.VERTICAL, 0.5,
                panelTree(levels - 1, leafNext, nodeNext), panelTree(levels - 1, leafNext, nodeNext));
    }

    private static DockNode chain(int depth, DockNode innermost, int[] nodeNext) {
        DockNode node = innermost;
        for (int i = 1; i < depth; i++) {
            node = new DockNode.Split(id(500000 + nodeNext[0]++), DockNode.Axis.HORIZONTAL, 0.5, node,
                    new DockNode.Empty());
        }
        return node;
    }

    private static DockNode leafTree(List<UUID> leafIds, int[] nodeNext) {
        if (leafIds.size() == 1) {
            return new DockNode.Leaf(leafIds.get(0));
        }
        int half = leafIds.size() / 2;
        return new DockNode.Split(id(900000 + nodeNext[0]++), DockNode.Axis.HORIZONTAL, 0.5,
                leafTree(leafIds.subList(0, half), nodeNext),
                leafTree(leafIds.subList(half, leafIds.size()), nodeNext));
    }

    private static int countPanels(DockNode node) {
        if (node instanceof DockNode.Leaf) {
            return 1;
        }
        if (node instanceof DockNode.Tabs tabs) {
            return tabs.panels().size();
        }
        if (node instanceof DockNode.Split split) {
            return countPanels(split.first()) + countPanels(split.second());
        }
        return 0;
    }

    @Test
    void emptyTreeWithoutAnyPanelFitsAnyContent() {
        DockProjection empty = DockSolver.solve(new DockLayout(new DockNode.Empty()),
                new Rect(0, 0, 0, 0), Map.of(), 0, 0);
        assertTrue(empty.fits());
        assertTrue(empty.panels().isEmpty());
        assertTrue(empty.tabs().isEmpty());
        assertTrue(empty.splitters().isEmpty());

        DockNode split = new DockNode.Split(id(1), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Empty(), new DockNode.Empty());
        DockProjection projection = DockSolver.solve(new DockLayout(split), new Rect(0, 0, 100, 50),
                Map.of(), 4, 0);
        assertTrue(projection.fits());
        assertTrue(projection.panels().isEmpty());
        assertEquals(1, projection.splitters().size());
        assertEquals(new SplitBox(id(1), DockNode.Axis.HORIZONTAL, new Rect(48, 0, 4, 50)),
                projection.splitters().get(0));
    }

    @Test
    void thirdPartyIdentitiesAndViewportFreeTreesSolve() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        DockNode root = new DockNode.Tabs(id(2), List.of(first, second, third), second);
        Rect content = new Rect(10, 20, 300, 200);
        DockProjection projection = DockSolver.solve(new DockLayout(root), content,
                sizes(first, 50.0, 40.0, second, 60.0, 50.0, third, 55.0, 45.0), 6, 18);

        assertTrue(projection.fits());
        assertEquals(1, projection.tabs().size());
        assertEquals(3, projection.panels().size());
        Rect body = box(projection, first).bounds();
        assertEquals(body, box(projection, second).bounds());
        assertEquals(body, box(projection, third).bounds());
        assertEquals(new Rect(10, 38, 300, 182), body);
        assertFalse(box(projection, first).visible());
        assertTrue(box(projection, second).visible());
        assertFalse(box(projection, third).visible());
        assertTrue(box(projection, third).meetsMinimum());
        assertEquals(new TabBox(id(2), List.of(first, second, third), second, new Rect(10, 20, 300, 18)),
                projection.tabs().get(0));
        assertTrue(projection.splitters().isEmpty());
    }

    @Test
    void tabsClampHeaderAndShareBodyAcrossMembers() {
        UUID first = id(3);
        UUID second = id(4);
        DockNode root = new DockNode.Tabs(id(5), List.of(first, second), first);
        DockProjection projection = DockSolver.solve(new DockLayout(root), new Rect(0, 0, 100, 10),
                sizes(first, 20.0, 30.0, second, 20.0, 5.0), 0, 40);

        Rect expectedBody = new Rect(0, 10, 100, 0);
        assertEquals(expectedBody, box(projection, first).bounds());
        assertEquals(expectedBody, box(projection, second).bounds());
        assertEquals(new Rect(0, 0, 100, 10), projection.tabs().get(0).header());
        assertFalse(box(projection, first).meetsMinimum());
        assertFalse(projection.fits());
    }

    @Test
    void splitAllocatesRatioWhenBothMinimumsFit() {
        UUID first = id(6);
        UUID second = id(7);
        DockNode root = new DockNode.Split(id(8), DockNode.Axis.HORIZONTAL, 0.25,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockProjection projection = DockSolver.solve(new DockLayout(root), new Rect(0, 0, 400, 100),
                sizes(first, 50.0, 30.0, second, 40.0, 20.0), 0, 0);

        assertTrue(projection.fits());
        assertAllInside(projection, new Rect(0, 0, 400, 100));
        assertEquals(new Rect(0, 0, 100, 100), box(projection, first).bounds());
        assertEquals(new Rect(100, 0, 300, 100), box(projection, second).bounds());
        assertEquals(List.of(first, second),
                projection.panels().stream().map(PanelBox::panelId).toList());

        DockProjection gapped = DockSolver.solve(new DockLayout(root), new Rect(0, 0, 400, 100),
                sizes(first, 50.0, 30.0, second, 40.0, 20.0), 10, 0);
        assertEquals(new Rect(0, 0, 97.5, 100), box(gapped, first).bounds());
        assertEquals(new Rect(97.5, 0, 10, 100), gapped.splitters().get(0).bounds());
        assertEquals(new Rect(107.5, 0, 292.5, 100), box(gapped, second).bounds());
        assertNoOverlap(box(gapped, first), box(gapped, second));
        assertAllInside(gapped, new Rect(0, 0, 400, 100));
    }

    @Test
    void splitHonoursRegisteredMinimumWhenRatioWouldStarveAChild() {
        UUID first = id(9);
        UUID second = id(10);
        DockNode starvingFirst = new DockNode.Split(id(11), DockNode.Axis.HORIZONTAL, 0.05,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockProjection projection = DockSolver.solve(new DockLayout(starvingFirst), new Rect(0, 0, 400, 100),
                sizes(first, 300.0, 10.0, second, 50.0, 10.0), 0, 0);
        assertTrue(projection.fits());
        assertEquals(new Rect(0, 0, 300, 100), box(projection, first).bounds());
        assertEquals(new Rect(300, 0, 100, 100), box(projection, second).bounds());

        DockNode starvingSecond = new DockNode.Split(id(12), DockNode.Axis.HORIZONTAL, 0.95,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockProjection mirrored = DockSolver.solve(new DockLayout(starvingSecond), new Rect(0, 0, 400, 100),
                sizes(first, 0.0, 10.0, second, 300.0, 10.0), 0, 0);
        assertTrue(mirrored.fits());
        assertEquals(new Rect(0, 0, 100, 100), box(mirrored, first).bounds());
        assertEquals(new Rect(100, 0, 300, 100), box(mirrored, second).bounds());
    }

    @Test
    void compressedSplitReportsNoFitAndStaysInsideContent() {
        UUID first = id(13);
        UUID second = id(14);
        Rect content = new Rect(0, 0, 100, 100);
        DockNode horizontal = new DockNode.Split(id(15), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockProjection projection = DockSolver.solve(new DockLayout(horizontal), content,
                sizes(first, 150.0, 0.0, second, 150.0, 0.0), 0, 0);
        assertFalse(projection.fits());
        assertEquals(new Rect(0, 0, 50, 100), box(projection, first).bounds());
        assertEquals(new Rect(50, 0, 50, 100), box(projection, second).bounds());
        assertNoOverlap(box(projection, first), box(projection, second));
        assertInside(box(projection, first).bounds(), content);
        assertAllInside(projection, content);
        assertFalse(box(projection, first).meetsMinimum());

        DockNode vertical = new DockNode.Split(id(16), DockNode.Axis.VERTICAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockProjection stacked = DockSolver.solve(new DockLayout(vertical), content,
                sizes(first, 0.0, 100.0, second, 0.0, 100.0), 0, 0);
        assertFalse(stacked.fits());
        assertEquals(new Rect(0, 0, 100, 50), box(stacked, first).bounds());
        assertEquals(new Rect(0, 50, 100, 50), box(stacked, second).bounds());
        assertNoOverlap(box(stacked, first), box(stacked, second));
    }

    @Test
    void zeroSizeAndOffsetContentAndVaryingGapStayContained() {
        UUID first = id(17);
        UUID second = id(18);
        DockNode root = new DockNode.Split(id(19), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        DockLayout layout = new DockLayout(root);
        Map<UUID, PanelSize> registered = sizes(first, 10.0, 10.0, second, 10.0, 10.0);

        Rect zero = new Rect(700, -300, 0, 0);
        DockProjection collapsed = DockSolver.solve(layout, zero, registered, 5, 0);
        for (PanelBox panel : collapsed.panels()) {
            assertEquals(zero, panel.bounds());
            assertInside(panel.bounds(), zero);
        }
        assertFalse(collapsed.fits());

        Rect offset = new Rect(-500, 250, 200, 80);
        DockProjection gapped = DockSolver.solve(layout, offset, registered, 12, 0);
        assertEquals(new Rect(-500, 250, 94, 80), box(gapped, first).bounds());
        assertEquals(new Rect(-406, 250, 12, 80), gapped.splitters().get(0).bounds());
        assertEquals(new Rect(-394, 250, 94, 80), box(gapped, second).bounds());
        assertNoOverlap(box(gapped, first), box(gapped, second));
        for (PanelBox panel : gapped.panels()) {
            assertInside(panel.bounds(), offset);
        }
        for (SplitBox splitter : gapped.splitters()) {
            assertInside(splitter.bounds(), offset);
        }

        DockProjection oversizeGap = DockSolver.solve(layout, offset, registered, 1000, 0);
        assertEquals(0.0, box(oversizeGap, first).bounds().width(), EPSILON);
        assertEquals(200.0, oversizeGap.splitters().get(0).bounds().width(), EPSILON);
        assertEquals(0.0, box(oversizeGap, second).bounds().width(), EPSILON);
        assertInside(box(oversizeGap, first).bounds(), offset);
        assertInside(box(oversizeGap, second).bounds(), offset);
        assertInside(oversizeGap.splitters().get(0).bounds(), offset);
        assertFalse(oversizeGap.fits());
    }

    @Test
    void minimumsPropagateThroughNestedSplitsAndTabs() {
        UUID tabA = id(20);
        UUID tabB = id(21);
        UUID leaf = id(22);
        DockNode tabs = new DockNode.Tabs(id(23), List.of(tabA, tabB), tabA);
        DockNode inner = new DockNode.Split(id(24), DockNode.Axis.VERTICAL, 0.5,
                new DockNode.Leaf(leaf), new DockNode.Empty());
        DockNode root = new DockNode.Split(id(25), DockNode.Axis.HORIZONTAL, 0.5, tabs, inner);
        DockLayout layout = new DockLayout(root);
        Map<UUID, PanelSize> registered = sizes(tabA, 40.0, 30.0, tabB, 60.0, 50.0, leaf, 70.0, 20.0);

        DockProjection exact = DockSolver.solve(layout, new Rect(0, 0, 134, 60), registered, 4, 10);
        assertTrue(exact.fits());
        assertEquals(28.0, box(exact, leaf).bounds().height(), EPSILON);
        assertTrue(box(exact, leaf).meetsMinimum());
        assertEquals(new Rect(0, 10, 60, 50), box(exact, tabA).bounds());
        assertEquals(2, exact.splitters().size());
        assertAllInside(exact, new Rect(0, 0, 134, 60));

        assertFalse(DockSolver.solve(layout, new Rect(0, 0, 133.5, 60), registered, 4, 10).fits());
        assertFalse(DockSolver.solve(layout, new Rect(0, 0, 134, 59.5), registered, 4, 10).fits());
    }

    @Test
    void traversalOrderAndTabVisibilityFollowTheTree() {
        UUID first = id(26);
        UUID second = id(27);
        UUID third = id(28);
        DockNode tabs = new DockNode.Tabs(id(29), List.of(first, second), second);
        DockNode root = new DockNode.Split(id(30), DockNode.Axis.HORIZONTAL, 0.5, tabs,
                new DockNode.Leaf(third));
        DockProjection projection = DockSolver.solve(new DockLayout(root), new Rect(0, 0, 400, 100),
                sizes(first, 10.0, 10.0, second, 10.0, 10.0, third, 10.0, 10.0), 0, 5);
        assertEquals(List.of(first, second, third),
                projection.panels().stream().map(PanelBox::panelId).toList());
        assertEquals(List.of(id(30)), projection.splitters().stream().map(SplitBox::nodeId).toList());
        assertEquals(List.of(id(29)), projection.tabs().stream().map(TabBox::nodeId).toList());
        assertFalse(box(projection, first).visible());
        assertTrue(box(projection, second).visible());
        assertTrue(box(projection, third).visible());

        DockNode flipped = new DockNode.Tabs(id(29), List.of(first, second), first);
        DockProjection other = DockSolver.solve(new DockLayout(flipped), new Rect(0, 0, 100, 100),
                sizes(first, 10.0, 10.0, second, 10.0, 10.0), 0, 5);
        assertEquals(box(other, first).bounds(), box(other, second).bounds());
        assertTrue(box(other, first).visible());
        assertFalse(box(other, second).visible());
    }

    @Test
    void rectAndPanelSizeAndSplitValidationAreFrozen() {
        Rect rect = new Rect(1, 2, 3, 4);
        assertEquals(4.0, rect.right(), EPSILON);
        assertEquals(6.0, rect.bottom(), EPSILON);
        assertTrue(rect.contains(1, 2));
        assertTrue(rect.contains(3.999, 5.999));
        assertFalse(rect.contains(4, 3));
        assertFalse(rect.contains(1, 6));
        assertFalse(rect.contains(0.999, 2));
        assertFalse(new Rect(0, 0, 0, 10).contains(0, 5));
        assertThrows(IllegalArgumentException.class, () -> rect.contains(Double.NaN, 2));
        assertThrows(IllegalArgumentException.class, () -> rect.contains(2, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Rect(0, 0, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Rect(Double.MAX_VALUE, 0, Double.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> new PanelSize(-0.5, 0));
        assertThrows(IllegalArgumentException.class, () -> new PanelSize(0, Double.NaN));
        assertEquals(new PanelSize(0, 0), new PanelSize(0.0, 0.0));
    }

    @Test
    void invalidTreesAndRatiosAreRejected() {
        UUID duplicated = id(31);
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(new DockNode.Split(id(32),
                DockNode.Axis.HORIZONTAL, 0.5, new DockNode.Leaf(duplicated), new DockNode.Leaf(duplicated))));
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(new DockNode.Split(id(33),
                DockNode.Axis.HORIZONTAL, 0.5, new DockNode.Tabs(id(34), List.of(duplicated), duplicated),
                new DockNode.Leaf(duplicated))));
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(new DockNode.Split(id(35),
                DockNode.Axis.HORIZONTAL, 0.5, new DockNode.Tabs(id(36), List.of(id(37)), id(37)),
                new DockNode.Tabs(id(36), List.of(id(38)), id(38)))));
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(new DockNode.Split(id(39),
                DockNode.Axis.HORIZONTAL, 0.5, new DockNode.Tabs(id(39), List.of(id(40)), id(40)),
                new DockNode.Empty())));

        for (double ratio : new double[] {0.0, 0.049, 0.9500001, 1.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new DockNode.Split(id(41),
                    DockNode.Axis.HORIZONTAL, ratio, new DockNode.Empty(), new DockNode.Empty()));
        }
        assertEquals(0.05, new DockNode.Split(id(42), DockNode.Axis.HORIZONTAL, 0.05,
                new DockNode.Empty(), new DockNode.Empty()).ratio(), EPSILON);
        assertEquals(0.95, new DockNode.Split(id(43), DockNode.Axis.VERTICAL, 0.95,
                new DockNode.Empty(), new DockNode.Empty()).ratio(), EPSILON);

        assertThrows(IllegalArgumentException.class,
                () -> new DockNode.Tabs(id(44), List.of(), id(45)));
        assertThrows(IllegalArgumentException.class,
                () -> new DockNode.Tabs(id(46), List.of(id(47)), id(48)));
        assertThrows(IllegalArgumentException.class,
                () -> new DockNode.Tabs(id(49), List.of(id(50), id(50)), id(50)));

        List<UUID> oversized = new ArrayList<>();
        for (int i = 0; i < 1025; i++) {
            oversized.add(id(60000 + i));
        }
        assertThrows(IllegalArgumentException.class, () -> new DockNode.Tabs(id(51), oversized, oversized.get(0)));
        List<UUID> atLimit = new ArrayList<>(oversized.subList(0, 1024));
        assertEquals(1024, new DockNode.Tabs(id(52), atLimit, atLimit.get(1023)).panels().size());
    }

    @Test
    void structuralLimitsAreEnforced() {
        DockLayout deepest = new DockLayout(chain(64, new DockNode.Leaf(id(53)), new int[] {1}));
        int depth = 0;
        DockNode cursor = deepest.root();
        while (cursor instanceof DockNode.Split split) {
            depth++;
            cursor = split.first();
        }
        assertEquals(64, depth + 1);
        assertThrows(IllegalArgumentException.class,
                () -> new DockLayout(chain(65, new DockNode.Leaf(id(54)), new int[] {1})));

        new DockLayout(completeTree(2047, new int[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(completeTree(2048, new int[] {1})));

        // Every panel below carries its own id, so only the panel budget can reject the layout.
        List<UUID> grouped = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            grouped.add(id(70000 + i));
        }
        List<UUID> leaves = new ArrayList<>();
        for (int i = 0; i < 513; i++) {
            leaves.add(id(72000 + i));
        }
        DockNode overBudget = new DockNode.Split(id(55), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Tabs(id(56), grouped, grouped.get(0)), leafTree(leaves, new int[] {1}));
        assertEquals(1025, countPanels(overBudget));
        assertThrows(IllegalArgumentException.class, () -> new DockLayout(overBudget));

        DockNode atBudget = new DockNode.Split(id(57), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Tabs(id(58), grouped, grouped.get(0)),
                leafTree(new ArrayList<>(leaves.subList(0, 512)), new int[] {1}));
        assertEquals(1024, countPanels(atBudget));
        assertEquals(1024, countPanels(new DockLayout(atBudget).root()));

        List<UUID> full = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            full.add(id(80000 + i));
        }
        DockLayout atLimit = new DockLayout(new DockNode.Tabs(id(59), full, full.get(0)));
        assertEquals(1024, ((DockNode.Tabs) atLimit.root()).panels().size());
    }

    @Test
    void solveValidatesParametersAndPanelCoverage() {
        UUID first = id(60);
        UUID second = id(61);
        DockLayout layout = new DockLayout(new DockNode.Split(id(62), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second)));
        Rect content = new Rect(0, 0, 100, 100);
        Map<UUID, PanelSize> complete = sizes(first, 1.0, 1.0, second, 1.0, 1.0);
        Map<UUID, PanelSize> snapshot = new LinkedHashMap<>(complete);

        assertThrows(NullPointerException.class, () -> DockSolver.solve(null, content, complete, 0, 0));
        assertThrows(NullPointerException.class, () -> DockSolver.solve(layout, null, complete, 0, 0));
        assertThrows(NullPointerException.class, () -> DockSolver.solve(layout, content, null, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> DockSolver.solve(layout, content, sizes(first, 1.0, 1.0), 0, 0));
        Map<UUID, PanelSize> extra = sizes(first, 1.0, 1.0, second, 1.0, 1.0, id(63), 1.0, 1.0);
        assertThrows(IllegalArgumentException.class, () -> DockSolver.solve(layout, content, extra, 0, 0));
        Map<UUID, PanelSize> nullValue = new HashMap<>();
        nullValue.put(first, null);
        nullValue.put(second, new PanelSize(1, 1));
        assertThrows(NullPointerException.class, () -> DockSolver.solve(layout, content, nullValue, 0, 0));
        Map<UUID, PanelSize> nullKey = new HashMap<>();
        nullKey.put(null, new PanelSize(1, 1));
        assertThrows(NullPointerException.class, () -> DockSolver.solve(layout, content, nullKey, 0, 0));
        for (double bad : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> DockSolver.solve(layout, content, complete, bad, 0));
            assertThrows(IllegalArgumentException.class, () -> DockSolver.solve(layout, content, complete, 0, bad));
        }
        assertEquals(snapshot, complete);
    }

    @Test
    void inputsAreNotModifiedAndResultsAreImmutableAndDeterministic() {
        UUID first = id(64);
        UUID second = id(65);
        List<UUID> members = new ArrayList<>(List.of(first, second));
        DockNode tabs = new DockNode.Tabs(id(66), members, first);
        DockLayout layout = new DockLayout(tabs);
        Map<UUID, PanelSize> registered = sizes(first, 20.0, 20.0, second, 20.0, 20.0);
        Map<UUID, PanelSize> snapshot = new LinkedHashMap<>(registered);
        Rect content = new Rect(5, 6, 200, 100);

        DockProjection projection = DockSolver.solve(layout, content, registered, 3, 7);
        assertEquals(snapshot, registered);
        members.add(id(67));
        assertEquals(2, layout.root() instanceof DockNode.Tabs group ? group.panels().size() : -1);
        assertEquals(2, projection.tabs().get(0).panels().size());

        assertEquals(projection, DockSolver.solve(layout, content, registered, 3, 7));
        assertEquals(projection.panels(), DockSolver.solve(layout, content, registered, 3, 7).panels());
        assertThrows(UnsupportedOperationException.class, () -> projection.panels().clear());
        assertThrows(UnsupportedOperationException.class, () -> projection.tabs().clear());
        assertThrows(UnsupportedOperationException.class, () -> projection.splitters().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> projection.tabs().get(0).panels().add(id(68)));
    }

    @Test
    void derivedValuesThatWouldOverflowAreRejected() {
        UUID first = id(69);
        UUID second = id(70);
        DockLayout layout = new DockLayout(new DockNode.Split(id(71), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second)));
        assertThrows(IllegalArgumentException.class, () -> DockSolver.solve(layout,
                new Rect(0, 0, 1000, 1000), sizes(first, Double.MAX_VALUE, 1.0,
                        second, Double.MAX_VALUE, 1.0), 4, 0));
        DockLayout stacked = new DockLayout(new DockNode.Split(id(73), DockNode.Axis.VERTICAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second)));
        assertThrows(IllegalArgumentException.class, () -> DockSolver.solve(stacked,
                new Rect(0, 0, 1000, 1000),
                sizes(first, 1.0, Double.MAX_VALUE, second, 1.0, Double.MAX_VALUE), 4, 0));
    }

    @Test
    void largeTreesStayBoundedAndFitContent() {
        int[] leafNext = {1};
        int[] nodeNext = {1};
        DockNode root = panelTree(10, leafNext, nodeNext);
        DockLayout layout = new DockLayout(root);
        Map<UUID, PanelSize> registered = new LinkedHashMap<>();
        for (int i = 1; i <= 1024; i++) {
            registered.put(id(i), new PanelSize(1, 1));
        }
        Rect content = new Rect(0, 0, 100000, 100000);
        DockProjection projection = DockSolver.solve(layout, content, registered, 2, 0);

        assertTrue(projection.fits());
        assertEquals(1024, projection.panels().size());
        assertEquals(1023, projection.splitters().size());
        for (PanelBox panel : projection.panels()) {
            assertInside(panel.bounds(), content);
            assertTrue(panel.meetsMinimum());
        }
        DockProjection deep = DockSolver.solve(
                new DockLayout(chain(64, new DockNode.Leaf(id(72)), new int[] {1})),
                content, sizes(id(72), 1.0, 1.0), 0, 0);
        assertTrue(deep.fits());
        assertInside(box(deep, id(72)).bounds(), content);
        assertAllInside(deep, content);
    }

    @Test
    void fitsRequiresEveryPanelBodyToReachItsMinimum() {
        UUID first = id(90);
        UUID second = id(91);
        DockNode root = new DockNode.Tabs(id(92), List.of(first, second), first);

        // The header swallows the whole content and the rounded minimum sum (1e16 + 1) is still 1e16,
        // so only the body itself can reveal that the one-unit row is missing.
        Rect collapsed = new Rect(0, 0, 1, 1e16);
        Map<UUID, PanelSize> registered = sizes(first, 1.0, 1.0, second, 1.0, 1.0);
        DockProjection starving = DockSolver.solve(new DockLayout(root), collapsed, registered, 0, 1e16);
        assertFalse(starving.fits());
        assertEquals(new Rect(0, 1e16, 1, 0), box(starving, first).bounds());
        assertFalse(box(starving, first).meetsMinimum());
        assertAllInside(starving, collapsed);
        // The content does cover the header and the tree, so fits is false only because of the row.
        assertTrue(DockSolver.solve(new DockLayout(root), collapsed,
                sizes(first, 0.0, 0.0, second, 0.0, 0.0), 0, 1e16).fits());

        // An ordinary fractional boundary: a 10.5 header plus a 3.25 body needs exactly 13.75.
        Map<UUID, PanelSize> fractional = sizes(first, 1.0, 3.25, second, 1.0, 3.25);
        Rect exactBounds = new Rect(0, 0, 4, 13.75);
        DockProjection fitting = DockSolver.solve(new DockLayout(root), exactBounds, fractional, 0, 10.5);
        assertTrue(fitting.fits());
        assertEquals(new Rect(0, 10.5, 4, 3.25), box(fitting, first).bounds());
        assertTrue(box(fitting, second).meetsMinimum());
        assertAllInside(fitting, exactBounds);

        Rect tooShort = new Rect(0, 0, 4, 13.749999);
        DockProjection missing = DockSolver.solve(new DockLayout(root), tooShort, fractional, 0, 10.5);
        assertFalse(missing.fits());
        assertFalse(box(missing, first).meetsMinimum());
        assertAllInside(missing, tooShort);
    }

    @Test
    void ordinaryDecimalMinimumsAreForgivenWithinTwoUlps() {
        UUID first = id(110);
        UUID second = id(111);
        Map<UUID, PanelSize> registered = sizes(first, 0.2, 0.0, second, 0.3, 0.0);
        DockNode split = new DockNode.Split(id(112), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second));

        // 0.2 + 0.3 is exactly 0.5, but the second leaf ends one ulp below 0.3 once the parent has been
        // split, so an exact comparison alone would report fits=false and the product would drop panels.
        Rect content = new Rect(0.1, 0, 0.5, 1);
        DockProjection projection = DockSolver.solve(new DockLayout(split), content, registered, 0, 0);
        Rect firstArea = box(projection, first).bounds();
        Rect secondArea = box(projection, second).bounds();
        assertTrue(projection.fits());
        assertTrue(box(projection, first).meetsMinimum());
        assertTrue(box(projection, second).meetsMinimum());
        assertTrue(firstArea.width() >= 0.2);
        assertTrue(secondArea.width() < 0.3 && secondArea.width() > 0,
                "expected an ordinary decimal shortfall: " + secondArea.width());
        assertTrue(Math.nextUp(Math.nextUp(secondArea.width())) >= 0.3);
        assertEquals(content.x(), firstArea.x());
        assertEquals(firstArea.right(), secondArea.x());
        assertEquals(content.right(), secondArea.right());
        assertAllInside(projection, content);
        assertNoOverlap(box(projection, first), box(projection, second));

        // A visibly insufficient body is still rejected: the gap is far wider than two ulp.
        Rect squeezedContent = new Rect(0.1, 0, 0.25, 1);
        DockProjection squeezed = DockSolver.solve(new DockLayout(split), squeezedContent, registered, 0, 0);
        assertFalse(squeezed.fits());
        assertFalse(box(squeezed, first).meetsMinimum());
        assertFalse(box(squeezed, second).meetsMinimum());
        assertAllInside(squeezed, squeezedContent);

        // A zero extent never satisfies a positive minimum, on either axis.
        DockProjection zeroed = DockSolver.solve(new DockLayout(split), content, registered, 0.5, 0);
        assertEquals(0.0, box(zeroed, first).bounds().width());
        assertFalse(box(zeroed, first).meetsMinimum());
        assertFalse(zeroed.fits());

        DockProjection swallowed = DockSolver.solve(
                new DockLayout(new DockNode.Tabs(id(113), List.of(first, second), first)),
                content, sizes(first, 0.2, 0.5, second, 0.3, 0.5), 0, 1);
        assertEquals(0.0, box(swallowed, first).bounds().height());
        assertFalse(box(swallowed, first).meetsMinimum());
        assertFalse(swallowed.fits());
    }

    @Test
    void extremeOffsetsNeverLeaveTheParentOrContent() {
        UUID first = id(93);
        UUID second = id(94);
        Map<UUID, PanelSize> free = sizes(first, 0.0, 0.0, second, 0.0, 0.0);

        DockNode split = new DockNode.Split(id(95), DockNode.Axis.HORIZONTAL, 0.5,
                new DockNode.Leaf(first), new DockNode.Leaf(second));
        Rect wide = new Rect(1e16, 0, 4, 1);
        DockProjection projection = DockSolver.solve(new DockLayout(split), wide, free, 1, 0);
        Rect firstArea = box(projection, first).bounds();
        Rect splitterArea = projection.splitters().get(0).bounds();
        Rect secondArea = box(projection, second).bounds();
        assertTrue(projection.fits());
        assertAllInside(projection, wide);
        assertEquals(new Rect(1e16, 0, 2, 1), firstArea);
        assertEquals(new Rect(1e16 + 2, 0, 2, 1), splitterArea);
        assertEquals(new Rect(1e16 + 4, 0, 0, 1), secondArea);
        assertEquals(wide.x(), firstArea.x());
        assertEquals(firstArea.right(), splitterArea.x());
        assertEquals(splitterArea.right(), secondArea.x());
        assertEquals(wide.right(), secondArea.right());
        assertNoOverlap(box(projection, first), box(projection, second));

        DockNode tabs = new DockNode.Tabs(id(96), List.of(first, second), first);
        Rect tall = new Rect(0, 1e16, 2, 4);
        DockProjection stacked = DockSolver.solve(new DockLayout(tabs), tall, free, 0, 1);
        Rect header = stacked.tabs().get(0).header();
        Rect body = box(stacked, first).bounds();
        assertTrue(stacked.fits());
        assertAllInside(stacked, tall);
        assertEquals(new Rect(0, 1e16, 2, 0), header);
        assertEquals(new Rect(0, 1e16, 2, 4), body);
        assertEquals(header.bottom(), body.y());
        assertEquals(tall.bottom(), body.bottom());
        assertEquals(body, box(stacked, second).bounds());

        // Nested children of an offset parent also stay inside their own parent area.
        UUID third = id(100);
        UUID fourth = id(101);
        UUID fifth = id(102);
        UUID sixth = id(103);
        Map<UUID, PanelSize> nestedSizes = sizes(third, 0.0, 0.0, fourth, 0.0, 0.0,
                fifth, 0.0, 0.0, sixth, 0.0, 0.0);
        Rect nestedContent = new Rect(1e16, 1e16, 4, 4);
        DockNode nested = new DockNode.Split(id(97), DockNode.Axis.VERTICAL, 0.5,
                new DockNode.Split(id(98), DockNode.Axis.HORIZONTAL, 0.5,
                        new DockNode.Leaf(third), new DockNode.Leaf(fourth)),
                new DockNode.Tabs(id(99), List.of(fifth, sixth), fifth));
        DockProjection deep = DockSolver.solve(new DockLayout(nested), nestedContent, nestedSizes, 1, 1);
        Rect outerSplitter = deep.splitters().get(0).bounds();
        Rect innerSplitter = deep.splitters().get(1).bounds();
        Rect innerFirst = box(deep, third).bounds();
        Rect innerSecond = box(deep, fourth).bounds();
        Rect tabHeader = deep.tabs().get(0).header();
        assertTrue(deep.fits());
        assertAllInside(deep, nestedContent);
        assertEquals(DockNode.Axis.VERTICAL, deep.splitters().get(0).axis());
        assertEquals(DockNode.Axis.HORIZONTAL, deep.splitters().get(1).axis());
        assertEquals(innerFirst.right(), innerSplitter.x());
        assertEquals(innerSplitter.right(), innerSecond.x());
        assertTrue(innerSplitter.bottom() <= outerSplitter.y(), "inner split left its parent: "
                + innerSplitter + " vs " + outerSplitter);
        assertEquals(innerFirst.bottom(), outerSplitter.y());
        assertEquals(outerSplitter.bottom(), tabHeader.y());
        assertEquals(outerSplitter.x(), innerFirst.x());
        assertEquals(nestedContent.right(), innerSecond.right());
    }
}
