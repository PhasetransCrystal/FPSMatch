package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable single-line geometry. Indices are UTF-16 offsets, matching the editing model.
 * Paint the original text at -viewport.originX() inside its clip; all coordinates then use
 * the same whole-line geometry when supplied, or the compatibility prefix advances.
 * This value neither edits raw text nor owns caret, selection or undo history.
 */
public final class StudioTextLine {

    public record Selection(int start, int end, int x, int width) {}

    public static final class Viewport {

        private final StudioTextLine line;
        private final int start, end, width, origin;

        private Viewport(StudioTextLine line, int start, int end, int width) {
            this(line, start, end, width, line.x(start));
        }

        private Viewport(StudioTextLine line, int start, int end, int width, int origin) {
            this.line = line;
            this.start = start;
            this.end = end;
            this.width = width;
            this.origin = origin;
        }

        public StudioTextLine line() {
            return line;
        }

        public int start() {
            return start;
        }

        public int end() {
            return end;
        }

        public int width() {
            return width;
        }

        public int originX() {
            return origin;
        }

        /**
         * Compatibility logical envelope, not a shaped draw fragment. Bidi visibility may be
         * disjoint; rendering must retain the full original line and clip its raster window.
         */
        public String visibleText() {
            return line.text.substring(start, end);
        }

        public int x(int index) {
            return line.x(index) - originX();
        }

        /** Coordinates outside the clip may extend a drag selection into adjacent text. */
        public int hitTest(double localX) {
            return line.hitTest(localX + originX());
        }

        public Selection selection(int anchor, int caret) {
            var selected = line.selection(anchor, caret);
            int left = clamp(selected.x() - originX(), width);
            int right = clamp(selected.x() + selected.width() - originX(), width);
            return new Selection(selected.start(), selected.end(), left, Math.max(0, right - left));
        }

        public List<Selection> selections(int anchor, int caret) {
            var result = new ArrayList<Selection>();
            for (var selected : line.selections(anchor, caret)) {
                int left = clamp(selected.x() - originX(), width), right = clamp(selected.x() + selected.width() - originX(), width);
                if (right > left) result.add(new Selection(selected.start(), selected.end(), left, right - left));
            }
            return List.copyOf(result);
        }

        private static int clamp(int value, int width) {
            return Math.max(0, Math.min(width, value));
        }
    }

    private final String text;
    private final TextSize size;
    private final int lineHeight;
    private final long generation;
    private final int[] boundaries, advances;
    private final StudioTextLayout.LineGeometry geometry;

    StudioTextLine(String text, TextSize size, int lineHeight, long generation, int[] boundaries, int[] advances) {
        this.text = Objects.requireNonNull(text);
        this.size = Objects.requireNonNull(size);
        this.lineHeight = lineHeight;
        this.generation = generation;
        this.boundaries = boundaries.clone();
        this.advances = advances.clone();
        this.geometry = null;
    }

    StudioTextLine(String text, TextSize size, int lineHeight, long generation, StudioTextLayout.LineGeometry geometry) {
        this.text = Objects.requireNonNull(text);
        this.size = Objects.requireNonNull(size);
        this.lineHeight = lineHeight;
        this.generation = generation;
        this.geometry = Objects.requireNonNull(geometry);
        this.boundaries = null;
        this.advances = null;
    }

    public String text() {
        return text;
    }

    public TextSize size() {
        return size;
    }

    public int width() {
        return geometry == null ? advances[advances.length - 1] : (int) Math.ceil(geometry.advance());
    }

    public int lineHeight() {
        return lineHeight;
    }

    public long generation() {
        return generation;
    }

    public int boundary(int index) {
        return geometry == null ? boundaries[slot(index)] : geometry.boundary(index);
    }

    public int x(int index) {
        return geometry == null ? advances[slot(index)] : Math.round(geometry.caretX(boundary(index)));
    }

    /** Nearest measured insertion point; midpoint ties advance to the later code point. */
    public int hitTest(double x) {
        if (Double.isNaN(x)) throw new IllegalArgumentException("NaN text coordinate");
        if (geometry != null) return geometry.hitTest((float) Math.max(-Float.MAX_VALUE, Math.min(Float.MAX_VALUE, x)));
        if (x < 0) return 0;
        if (x == Double.POSITIVE_INFINITY) return text.length();
        int closest = 0;
        double distance = Math.abs(x - advances[0]);
        for (int index = 1; index < advances.length; index++) {
            double next = Math.abs(x - advances[index]);
            if (next <= distance) {
                closest = index;
                distance = next;
            }
        }
        return boundaries[closest];
    }

    public Selection selection(int anchor, int caret) {
        if (geometry != null) {
            var spans = selections(anchor, caret);
            int start = boundary(Math.min(anchor, caret)), end = boundary(Math.max(anchor, caret));
            if (spans.isEmpty()) return new Selection(start, end, x(caret), 0);
            int left = spans.stream().mapToInt(Selection::x).min().orElse(0), right = spans.stream().mapToInt(s -> s.x() + s.width()).max().orElse(left);
            return new Selection(start, end, left, right - left);
        }
        int first = slot(Math.min(anchor, caret)), last = slot(Math.max(anchor, caret));
        int left = Math.min(advances[first], advances[last]);
        return new Selection(boundaries[first], boundaries[last], left, Math.abs(advances[last] - advances[first]));
    }

    /** Disjoint visual ranges for a logical selection, in full-line coordinates. */
    public List<Selection> selections(int anchor, int caret) {
        if (geometry == null) {
            var selected = selection(anchor, caret);
            return selected.width() == 0 ? List.of() : List.of(selected);
        }
        int start = boundary(Math.min(anchor, caret)), end = boundary(Math.max(anchor, caret));
        if (start == end) return List.of();
        var result = new ArrayList<Selection>();
        for (var span : geometry.selectionSpans(start, end)) {
            int left = (int) Math.floor(span.x()), right = (int) Math.ceil(span.x() + span.width());
            if (right > left) result.add(new Selection(start, end, left, right - left));
        }
        return List.copyOf(result);
    }

    /** Pixel-based viewport, independent of logical character order. */
    public Viewport viewAt(int scrollX, int width) {
        if (width < 0) throw new IllegalArgumentException("negative text viewport");
        int origin = Math.max(0, Math.min(Math.max(0, width() - width), scrollX));
        int first = hitTest(origin), last = hitTest((double) origin + width);
        return new Viewport(this, Math.min(first, last), Math.max(first, last), width, origin);
    }

    public Viewport revealAt(int scrollX, int caret, int width) {
        if (width < 0) throw new IllegalArgumentException("negative text viewport");
        int target = x(caret), origin = Math.max(0, scrollX);
        if (target < origin) origin = target;
        else if ((long) target > (long) origin + width) origin = target - width;
        return viewAt(origin, width);
    }

    public Viewport viewport(int start, int width) {
        if (geometry != null) return viewAt(x(start), width);
        if (width < 0) throw new IllegalArgumentException("negative text viewport");
        int first = slot(start), last = first;
        long right = (long) advances[first] + width;
        for (int index = first + 1; index < advances.length; index++) {
            if (advances[index] > right) break;
            last = index;
        }
        return new Viewport(this, boundaries[first], boundaries[last], width);
    }

    /** Reveal the caret, then reclaim trailing empty space after deletion or viewport growth. */
    public Viewport reveal(int start, int caret, int width) {
        if (geometry != null) return revealAt(x(start), caret, width);
        if (width < 0) throw new IllegalArgumentException("negative text viewport");
        int target = slot(caret), first = Math.min(slot(start), target);
        while (first < target && advances[target] - advances[first] > width) first++;
        while (first > 0 && width() - advances[first - 1] <= width) first--;
        return viewport(boundaries[first], width);
    }

    long cacheWeight() {
        return 48L + 2L * text.length() + (geometry == null ? 8L * boundaries.length : geometry.cacheWeight());
    }

    private int slot(int index) {
        int bounded = Math.max(0, Math.min(text.length(), index));
        int found = Arrays.binarySearch(boundaries, bounded);
        return found >= 0 ? found : -found - 2;
    }
}
