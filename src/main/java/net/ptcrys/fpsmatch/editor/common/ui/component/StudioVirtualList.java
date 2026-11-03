package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.Objects;

/** Fixed-height pixel windows. No domain, focus ownership, node lifetime or scroll state is held. */
public final class StudioVirtualList {

    /** Per-side prefetch ceiling, independent of the caller's collection size. */
    public static final int MAX_OVERSCAN = 64;

    private StudioVirtualList() {}

    @FunctionalInterface
    public interface RowRenderer<R> {

        /** Full row bounds; painting and hit registration must use the window's effective clip. */
        void render(int index, String key, R row, Bounds bounds);
    }

    /** No trailing gap. The current integer geometry cannot represent heights above MAX_VALUE. */
    public static int contentHeight(int total, int rowHeight, int gap) {
        if (total < 0 || rowHeight < 1 || gap < 0 || (long) rowHeight + gap > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid fixed row measures");
        long height = (long) total * rowHeight + (long) Math.max(0, total - 1) * gap;
        if (height > Integer.MAX_VALUE) throw new IllegalArgumentException("list exceeds integer geometry");
        return (int) height;
    }

    /**
     * Reveals a row within its owning scroll body. listContentY is the unscrolled list origin
     * in that body, not a screen coordinate. Keeps the current offset when the entire row is
     * visible, otherwise moves only as far as needed. A row taller than the viewport (including
     * a zero-height viewport) aligns its top. The owner applies the final upper scroll limit.
     */
    public static int revealOffset(int rowIndex, int total, int listContentY, int rowHeight,
                                   int gap, int currentOffset, int viewportHeight) {
        contentHeight(total, rowHeight, gap);
        Objects.checkIndex(rowIndex, total);
        if (currentOffset < 0 || viewportHeight < 0)
            throw new IllegalArgumentException("negative scroll offset or viewport height");
        long top = (long) listContentY + (long) rowIndex * (rowHeight + gap);
        long bottom = top + rowHeight;
        if (top > Integer.MAX_VALUE || bottom > Integer.MAX_VALUE)
            throw new IllegalArgumentException("row content position overflow");
        long offset = currentOffset;
        if (rowHeight > viewportHeight || top < currentOffset) offset = top;
        else if (bottom > (long) currentOffset + viewportHeight) offset = bottom - viewportHeight;
        return (int) Math.max(0, offset);
    }

    /**
     * listBounds is already translated by the owning scroll container. Its height must equal
     * contentHeight(total,rowHeight,gap). viewport is that container's effective clipped aperture.
     */
    public static Window window(Bounds listBounds, Bounds viewport, int total, int rowHeight,
                                int gap, int overscan) {
        return window(listBounds, viewport, 0, total, rowHeight, gap, overscan);
    }

    /**
     * Alternative for unscrolled list bounds. offset is the owner's already-clamped pixel offset,
     * and is subtracted exactly once; it is never clamped to this list's height because headers
     * and other content can share the same scroll body. An entirely offscreen list emits no rows.
     */
    public static Window window(Bounds listBounds, Bounds viewport, int offset, int total,
                                int rowHeight, int gap, int overscan) {
        Objects.requireNonNull(listBounds, "list bounds");
        Objects.requireNonNull(viewport, "viewport");
        int height = contentHeight(total, rowHeight, gap);
        if (listBounds.height() != height) throw new IllegalArgumentException("list height differs from row measures");
        if (offset < 0) throw new IllegalArgumentException("negative scroll offset");
        if (overscan < 0 || overscan > MAX_OVERSCAN) throw new IllegalArgumentException("invalid overscan");
        checkedBounds(listBounds);
        checkedBounds(viewport);
        long translatedY = (long) listBounds.y() - offset;
        if (translatedY < Integer.MIN_VALUE) throw new IllegalArgumentException("translated list origin overflow");
        var translated = new Bounds(listBounds.x(), (int) translatedY, listBounds.width(), height);
        checkedBounds(translated);
        var clip = intersection(translated, viewport);
        int first = 0, end = 0, mountedFirst = 0, mountedEnd = 0;
        if (clip.width() > 0 && clip.height() > 0) {
            long start = (long) clip.y() - translated.y();
            long limit = start + clip.height();
            long pitch = (long) rowHeight + gap;
            first = (int) Math.max(0, Math.min(total, Math.floorDiv(start - rowHeight, pitch) + 1));
            end = (int) Math.max(first, Math.min(total, (limit + pitch - 1) / pitch));
            mountedFirst = first;
            mountedEnd = end;
            if (first < end) {
                mountedFirst = Math.max(0, first - overscan);
                mountedEnd = (int) Math.min(total, (long) end + overscan);
            }
        }
        return new Window(translated, viewport, clip, offset, total, rowHeight, gap, overscan,
                first, end, mountedFirst, mountedEnd);
    }

    /** Only mounted rows are read or rendered. Source size mismatches fail before any callback. */
    public static <R> void render(StudioRowSource<R> source, Window window, RowRenderer<? super R> renderer) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(renderer, "renderer");
        if (source.size() != window.total()) throw new IllegalArgumentException("source size differs from window");
        for (int index = window.firstMounted(); index < window.endMounted(); index++)
            renderer.render(index, source.keyAt(index), source.rowAt(index), window.rowBounds(index));
    }

    private static void checkedBounds(Bounds bounds) {
        if ((long) bounds.x() + bounds.width() > Integer.MAX_VALUE || (long) bounds.y() + bounds.height() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("bounds endpoint overflow");
    }

    private static Bounds intersection(Bounds first, Bounds second) {
        int left = Math.max(first.x(), second.x()), top = Math.max(first.y(), second.y());
        long right = Math.min((long) first.x() + first.width(), (long) second.x() + second.width());
        long bottom = Math.min((long) first.y() + first.height(), (long) second.y() + second.height());
        return new Bounds(left, top, (int) Math.max(0, right - left), (int) Math.max(0, bottom - top));
    }

    /** Factory-owned immutable geometry. All index ranges are half-open. */
    public static final class Window {

        private final Bounds listBounds, viewport, clip;
        private final int offset, total, rowHeight, gap, overscan;
        private final int firstVisible, endVisible, firstMounted, endMounted;

        private Window(Bounds listBounds, Bounds viewport, Bounds clip, int offset, int total,
                       int rowHeight, int gap, int overscan, int firstVisible, int endVisible,
                       int firstMounted, int endMounted) {
            this.listBounds = listBounds;
            this.viewport = viewport;
            this.clip = clip;
            this.offset = offset;
            this.total = total;
            this.rowHeight = rowHeight;
            this.gap = gap;
            this.overscan = overscan;
            this.firstVisible = firstVisible;
            this.endVisible = endVisible;
            this.firstMounted = firstMounted;
            this.endMounted = endMounted;
        }

        /** Final translated list box, not an additional transform to apply. */
        public Bounds listBounds() {
            return listBounds;
        }

        public Bounds viewport() {
            return viewport;
        }

        public Bounds clip() {
            return clip;
        }

        public int offset() {
            return offset;
        }

        public int total() {
            return total;
        }

        public int rowHeight() {
            return rowHeight;
        }

        public int gap() {
            return gap;
        }

        public int overscan() {
            return overscan;
        }

        public int contentHeight() {
            return listBounds.height();
        }

        public int firstVisible() {
            return firstVisible;
        }

        public int endVisible() {
            return endVisible;
        }

        public int firstMounted() {
            return firstMounted;
        }

        public int endMounted() {
            return endMounted;
        }

        public int visibleCount() {
            return endVisible - firstVisible;
        }

        public int mountedCount() {
            return endMounted - firstMounted;
        }

        /** Full geometry is also available for an unmounted row, without reading its payload. */
        public Bounds rowBounds(int index) {
            Objects.checkIndex(index, total);
            long y = (long) listBounds.y() + (long) index * (rowHeight + gap);
            return new Bounds(listBounds.x(), (int) y, listBounds.width(), rowHeight);
        }

        /** Empty for an overscan row or an empty aperture; never enlarges a hit target. */
        public Bounds visibleBounds(int index) {
            return intersection(rowBounds(index), clip);
        }
    }
}
