package net.ptcrys.fpsmatch.editor.common.ui.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DockSizingTest {
    private static final double EPSILON = 1e-9;
    private static final UUID PROJECT = id(1);
    private static final UUID OBJECTS = id(2);
    private static final UUID DOCUMENT = id(3);
    private static final UUID PROBLEMS = id(4);
    private static final UUID OUTPUT = id(5);
    private static final UUID INSPECTOR = id(6);

    @Test
    void ordinaryPixelRequestsSurviveConstructionAndSolving() {
        for (DockNode.Axis axis : DockNode.Axis.values()) {
            Rect content = axis == DockNode.Axis.HORIZONTAL
                    ? new Rect(10, 20, 602, 300) : new Rect(10, 20, 300, 602);
            for (boolean trailing : List.of(false, true)) {
                double ratio = trailing ? DockSizing.trailing(602, 150, 2)
                        : DockSizing.leading(602, 150, 2);
                DockNode root = new DockNode.Split(id(10), axis, ratio,
                        new DockNode.Leaf(PROJECT), new DockNode.Leaf(DOCUMENT));
                DockProjection projection = DockSolver.solve(new DockLayout(root), content,
                        Map.of(PROJECT, new PanelSize(40, 40), DOCUMENT, new PanelSize(40, 40)), 2, 24);
                Rect requested = box(projection, trailing ? DOCUMENT : PROJECT).bounds();
                assertEquals(150, axis == DockNode.Axis.HORIZONTAL ? requested.width() : requested.height(), EPSILON);
                assertTrue(projection.fits());
                assertGeometry(projection, content);
            }
        }
    }

    @Test
    void compactBottomAtReportedFailureHeightBuildsACompleteShell() {
        // The old screen calculation yielded 0.9737130339539978, outside Split's contract.
        double oldRatio = (915d - 24 - 2) / (915 - 2);
        assertThrows(IllegalArgumentException.class, () -> new DockNode.Split(id(10),
                DockNode.Axis.VERTICAL, oldRatio, new DockNode.Empty(), new DockNode.Empty()));

        Rect content = new Rect(24, 30, 1256, 915);
        DockProjection projection = shell(content);
        assertCompleteShell(projection, content);
        Rect bottom = box(projection, PROBLEMS).bounds();
        assertEquals(content.bottom(), bottom.bottom(), EPSILON);
        assertEquals((915 - 2) * 0.05, bottom.height() + 24, EPSILON);
        assertTrue(bottom.height() > 0);
    }

    @Test
    void shellRebuildsThroughLargeSmallAndRestoredWindowSizes() {
        double[][] sequence = {{1256, 600}, {3384, 2118}, {630, 386}, {1, 1},
                {0, 0}, {1e-300, 1e-300}, {1256, 600}, {3384, 2118}};
        DockProjection initial = null;
        for (int i = 0; i < sequence.length; i++) {
            Rect content = new Rect(0, 0, sequence[i][0], sequence[i][1]);
            DockProjection projection = shell(content);
            assertGeometry(projection, content);
            assertEquals(6, projection.panels().size());
            assertEquals(4, projection.splitters().size());
            assertEquals(1, projection.tabs().size());
            if (content.width() >= 630 && content.height() >= 386) {
                assertCompleteShell(projection, content);
            } else {
                assertFalse(projection.fits());
            }
            if (i == 0) initial = projection;
            if (i == 6) assertEquals(initial, projection, "restoring the size restores the layout");
        }
    }

    @Test
    void shortOrCollapsedSpaceStillProducesContainedGeometry() {
        for (double height : new double[] {0, Double.MIN_VALUE, 1e-300, 1, 2, 3, 24, 48, 90}) {
            Rect content = new Rect(0, 0, 800, height);
            DockProjection projection = shell(content);
            assertFalse(projection.fits());
            assertGeometry(projection, content);
        }
    }

    @Test
    void oversizedAndZeroRequestsAlwaysConstructLegalSplits() {
        double[][] requests = {{0, 24, 2}, {1, 24, 2}, {2, 24, 2}, {3, 24, 2},
                {100, 0, 2}, {100, 1000, 2}, {Double.MIN_VALUE, Double.MAX_VALUE, 0}};
        for (double[] request : requests) {
            for (boolean trailing : List.of(false, true)) {
                double ratio = trailing ? DockSizing.trailing(request[0], request[1], request[2])
                        : DockSizing.leading(request[0], request[1], request[2]);
                DockNode root = new DockNode.Split(id(10), DockNode.Axis.VERTICAL, ratio,
                        new DockNode.Leaf(PROJECT), new DockNode.Leaf(DOCUMENT));
                Rect content = new Rect(0, 0, 100, request[0]);
                DockProjection projection = DockSolver.solve(new DockLayout(root), content,
                        Map.of(PROJECT, new PanelSize(0, 0), DOCUMENT, new PanelSize(0, 0)), request[2], 0);
                assertGeometry(projection, content);
            }
        }
        assertEquals(0.5, DockSizing.leading(0, 24, 2));
        assertEquals(0.5, DockSizing.trailing(2, 24, 2));
        assertThrows(IllegalArgumentException.class, () -> new DockNode.Split(id(10),
                DockNode.Axis.HORIZONTAL, 0.96, new DockNode.Empty(), new DockNode.Empty()));
    }

    @Test
    void invalidMeasuresAreRejectedBeforeProducingARatio() {
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> DockSizing.leading(invalid, 24, 2));
            assertThrows(IllegalArgumentException.class, () -> DockSizing.leading(100, invalid, 2));
            assertThrows(IllegalArgumentException.class, () -> DockSizing.leading(100, 24, invalid));
            assertThrows(IllegalArgumentException.class, () -> DockSizing.trailing(invalid, 24, 2));
            assertThrows(IllegalArgumentException.class, () -> DockSizing.trailing(100, invalid, 2));
            assertThrows(IllegalArgumentException.class, () -> DockSizing.trailing(100, 24, invalid));
        }
    }

    /** Rebuild the screen's four splits with project/objects, document, bottom tabs and inspector. */
    private static DockProjection shell(Rect content) {
        double width = content.width(), height = content.height();
        double leftWidth = 190, rightWidth = 260;
        DockNode bottom = new DockNode.Tabs(id(20), List.of(PROBLEMS, OUTPUT), PROBLEMS);
        DockNode middle = new DockNode.Split(id(21), DockNode.Axis.VERTICAL,
                DockSizing.trailing(height, 24, 2), new DockNode.Leaf(DOCUMENT), bottom);
        middle = new DockNode.Split(id(22), DockNode.Axis.HORIZONTAL,
                DockSizing.trailing(Math.max(0, width - leftWidth - 4), rightWidth, 2),
                middle, new DockNode.Leaf(INSPECTOR));
        DockNode left = new DockNode.Split(id(23), DockNode.Axis.VERTICAL,
                DockSizing.leading(height, 150, 3), new DockNode.Leaf(PROJECT), new DockNode.Leaf(OBJECTS));
        DockNode root = new DockNode.Split(id(24), DockNode.Axis.HORIZONTAL,
                DockSizing.leading(width, leftWidth, 3), left, middle);
        return DockSolver.solve(new DockLayout(root), content, Map.of(
                PROJECT, new PanelSize(80, 45), OBJECTS, new PanelSize(145, 45),
                DOCUMENT, new PanelSize(80, 45), PROBLEMS, new PanelSize(80, 0),
                OUTPUT, new PanelSize(80, 0), INSPECTOR, new PanelSize(210, 45)), 2, 24);
    }

    private static void assertCompleteShell(DockProjection projection, Rect content) {
        assertTrue(projection.fits());
        assertGeometry(projection, content);
        assertEquals(5L, projection.panels().stream().filter(PanelBox::visible).count());
        assertFalse(box(projection, OUTPUT).visible());
        for (PanelBox panel : projection.panels()) assertTrue(panel.meetsMinimum());
        for (UUID panel : List.of(PROJECT, OBJECTS, DOCUMENT, INSPECTOR)) {
            assertTrue(box(projection, panel).visible());
            assertTrue(box(projection, panel).bounds().width() > 0);
            assertTrue(box(projection, panel).bounds().height() > 0);
        }
        assertTrue(box(projection, PROBLEMS).visible());
        assertEquals(24, projection.tabs().get(0).header().height(), EPSILON);
    }

    private static void assertGeometry(DockProjection projection, Rect content) {
        for (PanelBox panel : projection.panels()) assertInside(panel.bounds(), content);
        for (TabBox tab : projection.tabs()) assertInside(tab.header(), content);
        for (SplitBox splitter : projection.splitters()) assertInside(splitter.bounds(), content);
        List<PanelBox> visible = projection.panels().stream().filter(PanelBox::visible).toList();
        for (int i = 0; i < visible.size(); i++) {
            for (int j = i + 1; j < visible.size(); j++) {
                Rect a = visible.get(i).bounds(), b = visible.get(j).bounds();
                boolean overlap = a.x() < b.right() && b.x() < a.right()
                        && a.y() < b.bottom() && b.y() < a.bottom();
                assertFalse(overlap, "visible panels overlap: " + a + " / " + b);
            }
        }
    }

    private static void assertInside(Rect inner, Rect outer) {
        for (double measure : new double[] {inner.x(), inner.y(), inner.width(), inner.height(), inner.right(), inner.bottom()}) {
            assertTrue(Double.isFinite(measure));
        }
        assertTrue(inner.width() >= 0 && inner.height() >= 0);
        assertTrue(inner.x() >= outer.x() && inner.y() >= outer.y());
        assertTrue(inner.right() <= outer.right() && inner.bottom() <= outer.bottom());
    }

    private static PanelBox box(DockProjection projection, UUID id) {
        return projection.panels().stream().filter(panel -> panel.panelId().equals(id)).findFirst().orElseThrow();
    }

    private static UUID id(int seed) {
        return new UUID(0x51ed100000000000L + seed, 0x0badc0deL);
    }
}
