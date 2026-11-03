package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.ArrayList;
import java.util.List;

/** Composable geometry shared by controls, fields, panels and grids; no graphics or domain state. */
public final class StudioLayout {

    private StudioLayout() {}

    public static Bounds inset(Bounds b, int horizontal, int vertical) {
        if (horizontal < 0 || vertical < 0) throw new IllegalArgumentException("negative inset");
        return new Bounds(b.x() + horizontal, b.y() + vertical, Math.max(0, b.width() - horizontal * 2), Math.max(0, b.height() - vertical * 2));
    }

    public static List<Bounds> columns(Bounds bounds, int count, int gap) {
        if (count < 0 || gap < 0) throw new IllegalArgumentException("negative column count or gap");
        if (count == 0) return List.of();
        var result = new ArrayList<Bounds>();
        int actualGap = count == 1 ? 0 : Math.min(gap, bounds.width() / (count - 1)), usable = Math.max(0, bounds.width() - (count - 1) * actualGap);
        for (int i = 0; i < count; i++) {
            int start = usable * i / count, end = usable * (i + 1) / count;
            result.add(new Bounds(bounds.x() + start + i * actualGap, bounds.y(), end - start, bounds.height()));
        }
        return List.copyOf(result);
    }

    public static final class Stack {

        private final Bounds bounds;
        private final int gap;
        private int cursor;

        public Stack(Bounds bounds, int gap) {
            if (gap < 0) throw new IllegalArgumentException("negative stack gap");
            this.bounds = bounds;
            this.gap = gap;
            cursor = bounds.y();
        }

        public Bounds take(int height) {
            if (height < 0) throw new IllegalArgumentException("negative stack height");
            var result = new Bounds(bounds.x(), cursor, bounds.width(), height);
            cursor += height + gap;
            return result;
        }

        public Bounds rest() {
            return new Bounds(bounds.x(), cursor, bounds.width(), Math.max(0, bounds.y() + bounds.height() - cursor));
        }
    }

    public record Window(int offset, int visible, int total, int rowHeight, Bounds viewport) {

        public int end() {
            return Math.min(total, offset + visible);
        }

        public int maximum() {
            return Math.max(0, total - visible);
        }

        public Bounds row(int index) {
            return new Bounds(viewport.x(), viewport.y() + (index - offset) * rowHeight, viewport.width(), rowHeight);
        }
    }

    /** Negative offsets clamp to zero; negative totals and nonpositive row heights are invalid. */
    public static Window window(Bounds bounds, int total, int offset, int rowHeight) {
        if (total < 0 || rowHeight < 1) throw new IllegalArgumentException("invalid scroll content measure");
        int visible = Math.max(1, bounds.height() / rowHeight);
        return new Window(Math.max(0, Math.min(offset, Math.max(0, total - visible))), visible, total, rowHeight, bounds);
    }

    /** Gaps separate rows; the last visible row does not need a trailing gap. */
    public static Window window(Bounds bounds, int total, int offset, int rowHeight, int gap) {
        if (total < 0 || rowHeight < 1 || gap < 0 || rowHeight > Integer.MAX_VALUE - gap) throw new IllegalArgumentException("invalid scroll content measure");
        int pitch = rowHeight + gap, visible = (int) Math.max(1, ((long) bounds.height() + gap) / pitch);
        return new Window(Math.max(0, Math.min(offset, Math.max(0, total - visible))), visible, total, pitch, bounds);
    }
}
