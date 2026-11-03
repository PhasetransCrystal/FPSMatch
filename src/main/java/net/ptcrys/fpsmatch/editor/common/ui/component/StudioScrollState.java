package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.List;
import java.util.function.IntConsumer;

/** Shared wheel/track/thumb behavior for lists, grids and inspector regions. */
public final class StudioScrollState {

    public record Region(Bounds viewport, Bounds track, Bounds thumb, int offset, int maximum, int layer, IntConsumer change, Bounds clip, int step, String id) {

        public Region(Bounds viewport, Bounds track, Bounds thumb, int offset, int maximum, int layer, IntConsumer change) {
            this(viewport, track, thumb, offset, maximum, layer, change, viewport, 1, "");
        }

        public Region(Bounds viewport, Bounds track, Bounds thumb, int offset, int maximum, int layer, IntConsumer change, Bounds clip, int step) {
            this(viewport, track, thumb, offset, maximum, layer, change, clip, step, "");
        }

        public Region {
            if (maximum < 0 || step < 1) throw new IllegalArgumentException("invalid scroll range or step");
            java.util.Objects.requireNonNull(id);
        }

        public Region clipped(Bounds bounds) {
            return new Region(viewport, track, thumb, offset, maximum, layer, change, clip.intersect(bounds), step, id);
        }
    }

    private Region dragging;
    private double grab, pressX, pressY;

    public boolean press(List<Region> regions, double x, double y, int layer) {
        return press(regions, x, y, layer, null);
    }

    public boolean press(List<Region> regions, double x, double y, int layer, ControlTree tree) {
        cancel();
        for (int i = regions.size() - 1; i >= 0; i--) {
            var region = regions.get(i);
            if (region.layer() == layer && region.clip().contains(x, y) && region.track().contains(x, y)) {
                if (tree != null && !tree.pointerExposed(region.id(), x, y)) continue;
                dragging = region;
                pressX = x;
                pressY = y;
                grab = region.thumb().contains(x, y) ? y - region.thumb().y() : region.thumb().height() / 2d;
                drag(y);
                return true;
            }
        }
        return false;
    }

    public boolean drag(double y) {
        if (dragging == null) return false;
        var region = dragging;
        int travel = region.track().height() - region.thumb().height();
        int value = travel <= 0 ? 0 : (int) Math.round((y - grab - region.track().y()) / travel * region.maximum());
        region.change().accept(Math.max(0, Math.min(region.maximum(), value)));
        return true;
    }

    public boolean release(double y) {
        if (dragging == null) return false;
        drag(y);
        dragging = null;
        return true;
    }

    public void cancel() {
        dragging = null;
    }

    /**
     * Revalidate capture against each successfully published frame. A modal, removed region,
     * changed mapping or new occluding surface releases the old callback. Ordinary thumb movement
     * keeps the original grab geometry and adopts the current callback.
     */
    public void reconcile(List<Region> regions, ControlTree tree) {
        if (dragging == null) return;
        Region current = regions.stream().filter(r -> !r.id().isEmpty() && r.id().equals(dragging.id())).findFirst().orElse(null);
        if (current == null || current.layer() != tree.topLayer() || !current.track().equals(dragging.track()) || !current.viewport().equals(dragging.viewport()) || !current.clip().equals(dragging.clip()) || current.maximum() != dragging.maximum() || !tree.pointerExposed(current.id(), pressX, pressY)) {
            cancel();
            return;
        }
        dragging = new Region(dragging.viewport(), dragging.track(), dragging.thumb(), dragging.offset(), current.maximum(), current.layer(), current.change(), current.clip(), current.step(), current.id());
    }

    public boolean wheel(List<Region> regions, double x, double y, double delta, int layer) {
        return wheel(regions, x, y, delta, layer, null);
    }

    public boolean wheel(List<Region> regions, double x, double y, double delta, int layer, ControlTree tree) {
        if (delta == 0) return false;
        for (int i = regions.size() - 1; i >= 0; i--) {
            var region = regions.get(i);
            if (region.layer() == layer && region.clip().contains(x, y) && region.viewport().contains(x, y)) {
                if (tree != null && !tree.pointerExposed(region.id(), x, y)) continue;
                region.change().accept(Math.max(0, Math.min(region.maximum(), region.offset() + (delta > 0 ? -region.step() : region.step()))));
                return true;
            }
        }
        return false;
    }
}
