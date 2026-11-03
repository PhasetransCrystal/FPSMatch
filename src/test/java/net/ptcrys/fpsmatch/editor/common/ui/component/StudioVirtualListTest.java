package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Kind;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Visual;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioVirtualListTest {
    private static StudioVirtualList.Window window(int total, int rowHeight, int gap, int offset, int height, int overscan) {
        return StudioVirtualList.window(new Bounds(10, 30, 100, StudioVirtualList.contentHeight(total, rowHeight, gap)),
                new Bounds(10, 30, 100, height), offset, total, rowHeight, gap, overscan);
    }

    @Test void pixelWindowsPreservePartialRowsGapsAndTheLastRowWithoutTrailingGap() {
        var first = window(5, 20, 2, 0, 42, 0);
        assertEquals(108, first.contentHeight());
        assertEquals(0, first.firstVisible());
        assertEquals(2, first.endVisible());
        assertEquals(new Bounds(10, 30, 100, 20), first.rowBounds(0));
        assertEquals(new Bounds(10, 52, 100, 20), first.rowBounds(1));
        var partial = window(5, 20, 2, 19, 4, 0);
        assertEquals(2, partial.visibleCount());
        assertEquals(new Bounds(10, 30, 100, 1), partial.visibleBounds(0));
        assertEquals(new Bounds(10, 33, 100, 1), partial.visibleBounds(1));
        var gap = window(5, 20, 2, 20, 2, 2);
        assertEquals(1, gap.firstVisible());
        assertEquals(1, gap.endVisible());
        assertEquals(0, gap.mountedCount());
        var last = window(5, 20, 2, 88, 20, 2);
        assertEquals(4, last.firstVisible());
        assertEquals(5, last.endVisible());
        assertEquals(new Bounds(10, 30, 100, 20), last.visibleBounds(4));
        assertEquals(2, last.firstMounted());
        assertEquals(5, last.endMounted());
    }

    @Test void computedVisibilityMatchesHalfOpenRectangleIntersectionAcrossOffsetsAndMeasures() {
        for (int rows : new int[]{0, 1, 7}) for (int rowHeight : new int[]{1, 3, 20}) for (int gap : new int[]{0, 2, 5}) {
            int content = StudioVirtualList.contentHeight(rows, rowHeight, gap);
            for (int offset = 0; offset <= content + 2; offset++) for (int height : new int[]{0, 1, 2, 7, 41}) {
                var actual = window(rows, rowHeight, gap, offset, height, 2);
                var expected = new ArrayList<Integer>();
                for (int row = 0; row < rows; row++) {
                    int top = row * (rowHeight + gap) - offset;
                    if (height > 0 && top < height && top + rowHeight > 0) expected.add(row);
                }
                assertEquals(expected, IntStream.range(actual.firstVisible(), actual.endVisible()).boxed().toList());
                assertTrue(actual.mountedCount() <= actual.visibleCount() + 4);
                if (expected.isEmpty()) assertEquals(0, actual.mountedCount());
                else {
                    assertTrue(actual.firstMounted() <= actual.firstVisible());
                    assertTrue(actual.endMounted() >= actual.endVisible());
                }
            }
        }
    }

    @Test void translatedAndExplicitOffsetEntrypointsAgreeWithoutClampingTheSharedBodyOffset() {
        int content = StudioVirtualList.contentHeight(20, 20, 2);
        var viewport = new Bounds(20, 30, 140, 50);
        var withOffset = StudioVirtualList.window(new Bounds(26, 108, 120, content), viewport, 90, 20, 20, 2, 2);
        var translated = StudioVirtualList.window(new Bounds(26, 18, 120, content), viewport, 20, 20, 2, 2);
        assertEquals(90, withOffset.offset());
        assertEquals(translated.listBounds(), withOffset.listBounds());
        assertEquals(translated.clip(), withOffset.clip());
        assertEquals(translated.firstVisible(), withOffset.firstVisible());
        assertEquals(translated.endVisible(), withOffset.endVisible());
        assertEquals(translated.rowBounds(2), withOffset.rowBounds(2));
        var scrolledPast = window(1, 20, 2, 500, 30, 2);
        assertEquals(500, scrolledPast.offset());
        assertEquals(0, scrolledPast.mountedCount());
        assertEquals(-470, scrolledPast.rowBounds(0).y());
    }

    @Test void zeroAreaOffscreenAndEmptySourcesNeverCallTheRenderer() {
        var source = StudioRowSource.of(0, List.of("a"), value -> value, value -> List.of());
        var list = new Bounds(10, 30, 100, 20);
        for (var viewport : List.of(new Bounds(10, 30, 0, 20), new Bounds(10, 30, 100, 0),
                new Bounds(110, 30, 100, 20), new Bounds(10, 50, 100, 20),
                new Bounds(10, 0, 100, 30))) {
            var actual = StudioVirtualList.window(list, viewport, 1, 20, 2, 64);
            assertEquals(0, actual.visibleCount());
            StudioVirtualList.render(source, actual, (index, key, row, bounds) -> fail("No row may be materialized"));
        }
        var empty = StudioRowSource.<String>of(0, List.of(), value -> value, value -> List.of());
        StudioVirtualList.render(empty, window(0, 20, 2, 0, 30, 2),
                (index, key, row, bounds) -> fail("Empty source"));
        StudioVirtualList.render(source, StudioVirtualList.window(new Bounds(10, 30, 0, 20), list, 1, 20, 2, 2),
                (index, key, row, bounds) -> fail("Zero-width list"));
    }

    @Test void tenThousandRowsOnlyReadAndRenderTheVisibleWindowAndBoundedPrefetch() {
        var reads = new AtomicInteger();
        var keyReads = new AtomicInteger();
        var source = counted(10_000, reads, keyReads);
        for (int offset : new int[]{0, 21, 110_000, 219_800}) {
            reads.set(0); keyReads.set(0);
            var actual = window(source.size(), 20, 2, offset, 100, 2);
            var rendered = new ArrayList<Integer>();
            StudioVirtualList.render(source, actual, (index, key, row, bounds) -> {
                assertEquals(index, row.intValue());
                assertEquals("row-" + index, key);
                assertEquals(actual.rowBounds(index), bounds);
                rendered.add(index);
            });
            assertEquals(IntStream.range(actual.firstMounted(), actual.endMounted()).boxed().toList(), rendered);
            assertEquals(actual.mountedCount(), reads.get());
            assertEquals(actual.mountedCount(), keyReads.get());
            assertTrue(rendered.size() <= 10, "Work follows viewport size, not logical row count");
        }
    }

    @Test void materializedControlsUseExactlyTheSameClipForPaintAndHitTesting() {
        var source = StudioRowSource.of(0, List.of("a", "b", "c", "d", "e"), value -> value, value -> List.of());
        var actual = StudioVirtualList.window(new Bounds(10, 30, 100, 108), new Bounds(30, 35, 50, 40), 5, 20, 2, 2);
        var frame = new StudioFrame();
        try (var ignored = frame.clipped(actual.clip())) {
            StudioVirtualList.render(source, actual, (index, key, row, bounds) ->
                    frame.emit(key, Role.BUTTON, row, "", bounds, true, false, false, "", 0, Visual.of(Kind.BUTTON)));
        }
        var tree = new ControlTree(); tree.publish(frame.snapshot().nodes());
        assertEquals("a", tree.hit(30, 35).orElseThrow().id());
        assertEquals("b", tree.hit(79, 52).orElseThrow().id());
        assertEquals("c", tree.hit(30, 74).orElseThrow().id());
        assertTrue(tree.hit(29, 40).isEmpty());
        assertTrue(tree.hit(80, 40).isEmpty());
        assertTrue(tree.hit(40, 34).isEmpty());
        assertTrue(tree.hit(40, 50).isEmpty());
        assertTrue(tree.hit(40, 51).isEmpty());
        assertTrue(tree.hit(40, 75).isEmpty());
        assertTrue(tree.node("d").isPresent());
        assertFalse(tree.node("d").orElseThrow().focusable());
        assertTrue(tree.hit(40, 97).isEmpty());
    }

    @Test void invalidMeasuresAndIntegerOverflowFailBeforeAnyRendering() {
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.contentHeight(-1, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.contentHeight(1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.contentHeight(1, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.contentHeight(1, Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.contentHeight(Integer.MAX_VALUE, 2, 0));
        assertEquals(Integer.MAX_VALUE, StudioVirtualList.contentHeight(1, Integer.MAX_VALUE, 0));
        assertThrows(IllegalArgumentException.class, () -> window(10, 20, 2, -1, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> window(10, 20, 2, 0, 20, -1));
        assertThrows(IllegalArgumentException.class, () -> window(10, 20, 2, 0, 20, StudioVirtualList.MAX_OVERSCAN + 1));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.window(new Bounds(0, 0, 20, 21), new Bounds(0, 0, 20, 20), 1, 20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.window(new Bounds(Integer.MAX_VALUE, 0, 1, 20), new Bounds(0, 0, 20, 20), 1, 20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.window(new Bounds(0, Integer.MAX_VALUE, 20, 20), new Bounds(0, 0, 20, 20), 1, 20, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.window(new Bounds(0, Integer.MIN_VALUE, 20, 20), new Bounds(0, 0, 20, 20), 1, 1, 20, 0, 0));
        var valid = window(1, 20, 2, 0, 20, 0);
        assertThrows(IndexOutOfBoundsException.class, () -> valid.rowBounds(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> valid.rowBounds(1));
        var empty = StudioRowSource.<String>of(0, List.of(), value -> value, value -> List.of());
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.render(empty, valid,
                (index, key, row, bounds) -> fail("Mismatched source must not render")));
    }

    @Test void farApartValidRectanglesDoNotOverflowIntoAFalseIntersection() {
        var list = new Bounds(Integer.MIN_VALUE, Integer.MIN_VALUE, 10, 20);
        var viewport = new Bounds(Integer.MAX_VALUE - 10, Integer.MAX_VALUE - 20, 10, 20);
        var actual = StudioVirtualList.window(list, viewport, 1, 20, 0, 2);
        assertEquals(0, actual.clip().width());
        assertEquals(0, actual.clip().height());
        assertEquals(0, actual.mountedCount());
        assertEquals(0, actual.visibleBounds(0).width());
        assertEquals(0, actual.visibleBounds(0).height());
    }

    @Test void revealKeepsVisibleRowsAndMovesOnlyEnoughForClippedRowsIncludingHeaders() {
        // Row 1 is [100,120) in the owning content, whose header occupies the first 78 pixels.
        assertEquals(90, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 90, 40));
        assertEquals(100, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 105, 40));
        assertEquals(90, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 80, 30));
        assertEquals(100, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 100, 20));
        assertEquals(78, StudioVirtualList.revealOffset(0, 5, 78, 20, 2, 200, 20));
        assertEquals(166, StudioVirtualList.revealOffset(4, 5, 78, 20, 2, 0, 20));
    }

    @Test void oversizedRevealAlignsTopAndNonnegativeClampDoesNotApplyAListOnlyMaximum() {
        assertEquals(100, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 105, 10));
        assertEquals(100, StudioVirtualList.revealOffset(1, 5, 78, 20, 2, 0, 0));
        assertEquals(0, StudioVirtualList.revealOffset(0, 1, -10, 20, 0, 5, 5));
        // A one-row list can follow a tall header; its owner's valid offset exceeds list height.
        assertEquals(500, StudioVirtualList.revealOffset(0, 1, 500, 20, 0, 0, 20));
    }

    @Test void revealRejectsInvalidIndicesDimensionsAndUnrepresentableContentPositions() {
        assertThrows(IndexOutOfBoundsException.class, () -> StudioVirtualList.revealOffset(0, 0, 0, 20, 2, 0, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> StudioVirtualList.revealOffset(-1, 1, 0, 20, 2, 0, 20));
        assertThrows(IndexOutOfBoundsException.class, () -> StudioVirtualList.revealOffset(1, 1, 0, 20, 2, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.revealOffset(0, 1, 0, 20, 2, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.revealOffset(0, 1, 0, 20, 2, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.revealOffset(0, 1, 0, 0, 2, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.revealOffset(0, 1, Integer.MAX_VALUE, 20, 2, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> StudioVirtualList.revealOffset(1, 2, Integer.MAX_VALUE - 20, 20, 2, 0, 20));
        assertEquals(Integer.MAX_VALUE - 20, StudioVirtualList.revealOffset(0, 1, Integer.MAX_VALUE - 20, 20, 0, Integer.MAX_VALUE - 20, Integer.MAX_VALUE));
    }

    private static StudioRowSource<Integer> counted(int count, AtomicInteger reads, AtomicInteger keys) {
        var delegate = StudioRowSource.of(0, IntStream.range(0, count).boxed().toList(), value -> "row-" + value, value -> List.of());
        return new StudioRowSource<>() {
            @Override public long revision() { return delegate.revision(); }
            @Override public int size() { return delegate.size(); }
            @Override public Integer rowAt(int index) { reads.incrementAndGet(); return delegate.rowAt(index); }
            @Override public String keyAt(int index) { keys.incrementAndGet(); return delegate.keyAt(index); }
            @Override public int indexOf(String key) { return delegate.indexOf(key); }
            @Override public List<FocusSlot> focusSlots(int rowIndex) { return delegate.focusSlots(rowIndex); }
            @Override public FocusIndex focusIndex() { return delegate.focusIndex(); }
        };
    }
}
