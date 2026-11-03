package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioLayoutTest {
    @Test void collapsedAndNarrowContainersDoNotPlaceColumnsOutsideTheirAssignedWidth() {
        for (int width : new int[]{0, 1, 3, 11}) {
            var bounds = new Bounds(17, 9, width, 20);
            for (var column : StudioLayout.columns(bounds, 5, 7)) {
                assertTrue(column.x() >= bounds.x());
                assertTrue(column.x() + column.width() <= bounds.x() + bounds.width());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> StudioLayout.columns(new Bounds(0, 0, 10, 10), -1, 2));
        assertThrows(IllegalArgumentException.class, () -> StudioLayout.window(new Bounds(0, 0, 10, 10), 1, 0, 0));
    }

    @Test void unevenWidthPreservesEveryPixelAndLeavesOnlyRequestedGaps() {
        var bounds = new Bounds(17, 9, 503, 50);
        var columns = StudioLayout.columns(bounds, 5, 7);
        assertEquals(5, columns.size());
        assertEquals(bounds.x(), columns.get(0).x());
        int accounted = 0;
        for (int i = 0; i < columns.size(); i++) {
            var column = columns.get(i);
            assertTrue(column.width() >= 0);
            assertEquals(bounds.y(), column.y());
            accounted += column.width();
            if (i > 0) {
                var previous = columns.get(i - 1);
                assertEquals(7, column.x() - previous.x() - previous.width());
            }
        }
        var last = columns.get(columns.size() - 1);
        assertEquals(bounds.x() + bounds.width(), last.x() + last.width());
        assertEquals(bounds.width(), accounted + 4 * 7);
    }

    @Test void scrollingAfterDeletionOrResizeKeepsLastRowReachable() {
        var viewport = new Bounds(10, 20, 100, 60);
        var afterDeletion = StudioLayout.window(viewport, 7, 99, 20);
        assertEquals(4, afterDeletion.offset());
        assertEquals(7, afterDeletion.end());
        assertEquals(new Bounds(10, 60, 100, 20), afterDeletion.row(6));
        var enlarged = StudioLayout.window(new Bounds(10, 20, 100, 200), 7, afterDeletion.offset(), 20);
        assertEquals(0, enlarged.offset());
        assertEquals(7, enlarged.end());
        var empty = StudioLayout.window(viewport, 0, 99, 20);
        assertEquals(0, empty.offset());
        assertEquals(0, empty.end());
    }
}
