package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import org.junit.jupiter.api.Test;

class StudioListScrollAnchorTest {
    private static StudioRowSource<String> source(long revision, String... keys) {
        return StudioRowSource.of(revision, List.of(keys), value -> value, value -> List.of());
    }

    @Test void reorderKeepsFirstVisibleKeyAndItsPartialRowOffset() {
        var old = source(1, "a", "b", "c", "d");
        var window = StudioVirtualList.window(new Bounds(10, -5, 100, 86), new Bounds(10, 20, 100, 30), 4, 20, 2, 2);
        var anchor = StudioListScrollAnchor.capture(old, window).orElseThrow();
        assertEquals("b", anchor.rowKey());
        assertEquals(1, anchor.oldIndex());
        assertEquals(3, anchor.viewportOffset());
        assertSame(old, anchor.source());
        var next = source(2, "d", "a", "c", "b");
        assertEquals(147, StudioListScrollAnchor.restore(anchor, next, 78, 20, 2));
    }

    @Test void visibleHeaderProducesNegativeOffsetAndRestoresTheListPosition() {
        var old = source(1, "a", "b", "c");
        var window = StudioVirtualList.window(new Bounds(10, 98, 100, 64), new Bounds(10, 30, 100, 100), 3, 20, 2, 2);
        var anchor = StudioListScrollAnchor.capture(old, window).orElseThrow();
        assertEquals("a", anchor.rowKey());
        assertEquals(-68, anchor.viewportOffset());
        assertEquals(10, StudioListScrollAnchor.restore(anchor, old, 78, 20, 2));
        assertEquals(0, StudioListScrollAnchor.restore(anchor, old, 0, 20, 2));
    }

    @Test void deletedKeyUsesOldSuccessorBeforePredecessorRegardlessOfNewOrder() {
        var old = source(1, "a", "b", "c", "d", "e");
        var anchor = new StudioListScrollAnchor.Anchor(old, "c", 2, 5);
        // b is adjacent before c; e is two positions after c and still takes precedence.
        var next = source(2, "e", "a", "b");
        assertEquals(83, StudioListScrollAnchor.restore(anchor, next, 78, 20, 2));
        var predecessorOnly = source(3, "b", "a");
        assertEquals(83, StudioListScrollAnchor.restore(anchor, predecessorOnly, 78, 20, 2));
        var moreDistantPredecessor = source(4, "a");
        assertEquals(83, StudioListScrollAnchor.restore(anchor, moreDistantPredecessor, 78, 20, 2));
    }

    @Test void noSurvivingKeysOrNoVisibleRowsReturnNoAnchorOrZero() {
        var old = source(1, "a", "b");
        var anchor = new StudioListScrollAnchor.Anchor(old, "a", 0, 5);
        assertEquals(0, StudioListScrollAnchor.restore(anchor, source(2, "x", "y"), 78, 20, 2));
        assertEquals(0, StudioListScrollAnchor.restore(anchor, source(3), 78, 20, 2));
        for (var viewport : List.of(new Bounds(10, 30, 100, 0), new Bounds(10, 30, 0, 20),
                new Bounds(10, 50, 100, 2), new Bounds(10, 100, 100, 20))) {
            var window = StudioVirtualList.window(new Bounds(10, 30, 100, 42), viewport, 2, 20, 2, 2);
            assertTrue(StudioListScrollAnchor.capture(old, window).isEmpty());
        }
    }

    @Test void changedRowMeasuresRestoreUsingTheNewFixedPitch() {
        var old = source(1, "a", "b", "c");
        var anchor = new StudioListScrollAnchor.Anchor(old, "b", 1, 4);
        assertEquals(68, StudioListScrollAnchor.restore(anchor, source(2, "c", "a", "b"), 10, 24, 3));
        assertEquals(0, StudioListScrollAnchor.restore(new StudioListScrollAnchor.Anchor(old, "a", 0, -100), old, 10, 24, 3));
    }

    @Test void mismatchedIdentityInvalidGeometryAndOffsetOverflowFailExplicitly() {
        var old = source(1, "a", "b");
        assertThrows(IllegalArgumentException.class, () -> new StudioListScrollAnchor.Anchor(old, "a", 1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> new StudioListScrollAnchor.Anchor(old, "a", 2, 0));
        var window = StudioVirtualList.window(new Bounds(0, 0, 100, 20), new Bounds(0, 0, 100, 20), 1, 20, 2, 0);
        assertThrows(IllegalArgumentException.class, () -> StudioListScrollAnchor.capture(old, window));
        var anchor = new StudioListScrollAnchor.Anchor(old, "b", 1, 1);
        assertThrows(IllegalArgumentException.class, () -> StudioListScrollAnchor.restore(anchor, old, Integer.MAX_VALUE, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> StudioListScrollAnchor.restore(anchor, old, 0, Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> StudioListScrollAnchor.restore(anchor, old, 0, 0, 0));
    }
}
