package net.ptcrys.fpsmatch.editor.common.ui.component;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import org.junit.jupiter.api.Test;

class StudioScrollStateTest {
    @Test void opaqueOverlayBlocksBothTrackPressAndViewportWheel() {
        var tree = new ControlTree();
        var scrolling = new StudioScrollState();
        var offset = new AtomicInteger(-1);
        var region = ownedRegion(offset);
        tree.publish(List.of(track(), new Node("cover", Role.PANEL, "", "", new Bounds(0, 0, 100, 100),
                true, false, false, "", 0)));
        assertFalse(scrolling.press(List.of(region), 95, 10, 0, tree));
        assertFalse(scrolling.wheel(List.of(region), 10, 10, -1, 0, tree));
        assertEquals(-1, offset.get());
    }

    @Test void modalOrRemovedRegionReleasesCapturedCallbacks() {
        var tree = new ControlTree();
        var scrolling = new StudioScrollState();
        var offset = new AtomicInteger(-1);
        var region = ownedRegion(offset);
        tree.publish(List.of(track()));
        assertTrue(scrolling.press(List.of(region), 95, 10, 0, tree));
        tree.publish(List.of(track(), new Node("modal", Role.PANEL, "", "", new Bounds(0, 0, 10, 10),
                true, false, false, "", 1)));
        scrolling.reconcile(List.of(region), tree);
        assertFalse(scrolling.drag(90));
        assertFalse(scrolling.release(90));
        assertEquals(0, offset.get());

        tree.publish(List.of(track()));
        assertTrue(scrolling.press(List.of(region), 95, 10, 0, tree));
        scrolling.reconcile(List.of(), tree);
        assertFalse(scrolling.drag(90));
        assertEquals(0, offset.get());
    }

    @Test void validRepublishKeepsGrabMappingAndUsesTheCurrentCallback() {
        var tree = new ControlTree();
        var scrolling = new StudioScrollState();
        var oldValue = new AtomicInteger(-1);
        var newValue = new AtomicInteger(-1);
        tree.publish(List.of(track()));
        assertTrue(scrolling.press(List.of(ownedRegion(oldValue)), 95, 10, 0, tree));
        scrolling.reconcile(List.of(ownedRegion(newValue)), tree);
        assertTrue(scrolling.drag(90));
        assertEquals(0, oldValue.get());
        assertEquals(10, newValue.get());
    }

    @Test void clippedAwayRailDoesNotReceiveInputAndVisibleDragKeepsOriginalMapping() {
        var frame = new StudioFrame();
        var scrolling = new StudioScrollState();
        var offset = new AtomicInteger(-1);
        try (var ignored = frame.clipped(new Bounds(0, 20, 100, 40))) {
            frame.scroll(region(new Bounds(0, 0, 100, 100), 0, offset));
        }
        assertFalse(scrolling.press(frame.scrolls(), 95, 10, 0));
        assertFalse(scrolling.wheel(frame.scrolls(), 10, 10, -1, 0));
        assertEquals(-1, offset.get());
        assertTrue(scrolling.press(frame.scrolls(), 95, 50, 0));
        assertEquals(5, offset.get(), "Clipping must not rescale the full track mapping");
    }

    @Test void nestedWheelUsesTopmostRegionAndNeverChangesAnotherModalLayer() {
        var scrolling = new StudioScrollState();
        var outer = new AtomicInteger(-1);
        var inner = new AtomicInteger(-1);
        var regions = List.of(region(new Bounds(0, 0, 100, 100), 3, outer),
                region(new Bounds(10, 10, 50, 50), 5, inner));
        assertTrue(scrolling.wheel(regions, 20, 20, -1, 0));
        assertEquals(6, inner.get());
        assertEquals(-1, outer.get());
        assertFalse(scrolling.wheel(regions, 20, 20, -1, 2));
        assertEquals(6, inner.get());
        assertFalse(scrolling.wheel(regions, 20, 20, 0, 0));
    }

    @Test void pointerCaptureClampsOutsideTrackAndCancelReleasesTheCallback() {
        var scrolling = new StudioScrollState();
        var offset = new AtomicInteger(-1);
        var region = region(new Bounds(0, 0, 100, 100), 0, offset);
        assertTrue(scrolling.press(List.of(region), 95, 10, 0));
        assertTrue(scrolling.drag(1000));
        assertEquals(10, offset.get());
        assertTrue(scrolling.drag(-1000));
        assertEquals(0, offset.get());
        scrolling.cancel();
        assertFalse(scrolling.drag(70));
        assertFalse(scrolling.release(70));
        assertEquals(0, offset.get());
    }

    private static StudioScrollState.Region region(Bounds viewport, int offset, AtomicInteger result) {
        return new StudioScrollState.Region(viewport, new Bounds(90, 0, 10, 100),
                new Bounds(90, offset * 8, 10, 20), offset, 10, 0, result::set);
    }
    private static StudioScrollState.Region ownedRegion(AtomicInteger result) {
        var r = region(new Bounds(0, 0, 100, 100), 0, result);
        return new StudioScrollState.Region(r.viewport(), r.track(), r.thumb(), r.offset(), r.maximum(), r.layer(), r.change(), r.clip(), 1, "track");
    }
    private static Node track() {
        return new Node("track", Role.PANEL, "", "", new Bounds(90, 0, 10, 100),
                true, false, false, "", 0, new Bounds(0, 0, 100, 100), false);
    }
}
