package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-neutral display-list recorder. Commands preserve painter order; solid geometry is
 * clipped before submission, so adjacent solids can share a batch across control boundaries.
 */
public final class StudioCanvas {

    public sealed interface Command permits Quad, Text, IconGlyph, Image, Segment {}

    public record Quad(Bounds bounds, int color) implements Command {

        public Quad {
            Objects.requireNonNull(bounds);
        }
    }

    public record Text(String value, int x, int y, int color, TextSize size, Bounds clip) implements Command {

        public Text {
            Objects.requireNonNull(value);
            Objects.requireNonNull(size);
            Objects.requireNonNull(clip);
        }
    }

    /**
     * Retains the full logical icon box when clipped. The device rasterizes that box at its
     * final physical size, then clips submission without stretching the remaining fragment.
     */
    public record IconGlyph(Icon icon, Bounds bounds, int color, Bounds clip) implements Command {

        public IconGlyph {
            Objects.requireNonNull(icon);
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(clip);
        }
    }

    /** Full image sampling bounds survive clipping; no device or atlas handle enters a picture. */
    public record Image(String resource, Icon fallback, Bounds bounds, int fallbackColor, Bounds clip) implements Command {

        public Image {
            Objects.requireNonNull(resource);
            Objects.requireNonNull(fallback);
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(clip);
        }
    }

    /** A continuous logical stroke; the device owns its antialiasing and the final clip. */
    public record Segment(double x0, double y0, double x1, double y1, double width, int color, Bounds clip) implements Command {

        public Segment {
            Objects.requireNonNull(clip);
            if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(width) || width <= 0 || !Double.isFinite(Math.hypot(x1 - x0, y1 - y0)))
                throw new IllegalArgumentException("Finite segment coordinates and positive width required");
        }
    }

    public record Picture(List<Command> commands, boolean truncated) {

        public Picture {
            commands = List.copyOf(commands);
        }

        public long weight() {
            long result = commands.size();
            for (var command : commands) {
                if (command instanceof Text text) result += text.value().length();
                else if (command instanceof Image image) result += image.resource().length();
            }
            return result;
        }
    }

    private final List<Command> commands = new ArrayList<>();
    private final Bounds clip;

    public StudioCanvas(Bounds clip) {
        this.clip = Objects.requireNonNull(clip);
    }

    public void fill(int x1, int y1, int x2, int y2, int color) {
        if ((color >>> 24) == 0) return;
        int left = Math.min(x1, x2), top = Math.min(y1, y2);
        var area = new Bounds(left, top, Math.subtractExact(Math.max(x1, x2), left), Math.subtractExact(Math.max(y1, y2), top)).intersect(clip);
        if (area.width() > 0 && area.height() > 0) commands.add(new Quad(area, color));
    }

    public void text(String value, int x, int y, int color, TextSize size) {
        text(value, x, y, color, size, clip);
    }

    public void text(String value, int x, int y, int color, TextSize size, Bounds localClip) {
        var area = clip.intersect(localClip);
        if (!value.isEmpty() && area.width() > 0 && area.height() > 0) commands.add(new Text(value, x, y, color, size, area));
    }

    public void icon(Icon icon, Bounds bounds, int color) {
        Objects.requireNonNull(icon);
        Objects.requireNonNull(bounds);
        var visible = bounds.intersect(clip);
        if (icon != Icon.NONE && (color >>> 24) != 0 && visible.width() > 0 && visible.height() > 0) commands.add(new IconGlyph(icon, bounds, color, visible));
    }

    public void image(String resource, Icon fallback, Bounds bounds, int fallbackColor) {
        var visible = bounds.intersect(clip);
        if (visible.width() > 0 && visible.height() > 0) commands.add(new Image(resource, fallback, bounds, fallbackColor, visible));
    }

    public Picture finish(boolean truncated) {
        return new Picture(commands, truncated);
    }

    public void segment(double x0, double y0, double x1, double y1, double width, int color) {
        var stroke = new Segment(x0, y0, x1, y1, width, color, clip);
        if ((color >>> 24) == 0 || clip.width() <= 0 || clip.height() <= 0 || (x0 == x1 && y0 == y1)) return;
        // Keep subpixel geometry intact. Scissoring at the device also clips the AA fringe.
        commands.add(stroke);
    }
}
