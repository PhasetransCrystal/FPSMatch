package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Host-neutral continuous icon geometry. Paths retain subpixel precision until device rasterization. */
public final class StudioIcon {

    public static final int SIZE = 12;
    public static final int ADVANCE = 16;
    public static final int GEOMETRY_VERSION = 1;

    public sealed interface Shape permits Stroke, Fill {}

    public record Point(double x, double y) {

        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("nonfinite icon point");
        }
    }

    /** Round caps and joins are part of the icon contract, not a host-specific default. */
    public record Stroke(List<Point> points, double width, boolean closed) implements Shape {

        public Stroke {
            points = List.copyOf(points);
            if (points.size() < 2 || !Double.isFinite(width) || width <= 0) throw new IllegalArgumentException("invalid icon stroke");
            for (var point : points) checkBounds(point.x - width / 2, point.y - width / 2, width, width);
        }
    }

    public record Fill(double x, double y, double width, double height) implements Shape {

        public Fill {
            checkBounds(x, y, width, height);
        }
    }

    public record Glyph(List<Shape> shapes) {

        public Glyph {
            shapes = List.copyOf(shapes);
        }
    }

    private static void checkBounds(double x, double y, double width, double height) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height) || x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > SIZE || y + height > SIZE)
            throw new IllegalArgumentException("icon paint must fit the logical square");
    }

    private static final Map<Icon, Glyph> GLYPHS = createGlyphs();

    private StudioIcon() {}

    /** Geometry is immutable; colors and device resolution are deliberately not stored here. */
    public static Glyph glyph(Icon icon) {
        return GLYPHS.get(Objects.requireNonNull(icon));
    }

    private static Map<Icon, Glyph> createGlyphs() {
        var result = new EnumMap<Icon, Glyph>(Icon.class);
        for (var icon : Icon.values()) result.put(icon, geometry(icon));
        return Map.copyOf(result);
    }

    private static Glyph geometry(Icon icon) {
        var r = new Paths();
        switch (icon) {
            case NONE -> {}
            case PLUS -> r.line(2, 6, 10, 6).line(6, 2, 6, 10);
            case MINUS -> r.line(2, 6, 10, 6);
            // Preserve legacy CLEAR; callers choose DELETE, MINUS or ERASER explicitly.
            case CLOSE, CLEAR -> r.line(3, 3, 9, 9).line(3, 9, 9, 3);
            case MORE -> {
                for (int x = 2; x <= 10; x += 4) r.fill(x, 5, 1, 2);
            }
            case CHEVRON_DOWN -> r.path(3, 4, 6, 7, 9, 4);
            case CHEVRON_RIGHT, NEXT -> r.path(4, 3, 7, 6, 4, 9);
            case PREVIOUS -> r.path(7, 3, 4, 6, 7, 9);
            case UP -> r.line(6, 10, 6, 2).path(3, 5, 6, 2, 9, 5);
            case DOWN -> r.line(6, 2, 6, 10).path(3, 7, 6, 10, 9, 7);
            case PLAY -> r.path(3, 2, 10, 6, 3, 10, 3, 2);
            case SEARCH -> r.path(3, 1, 6, 1, 8, 3, 8, 6, 6, 8, 3, 8, 1, 6, 1, 3, 3, 1)
                    .line(8, 8, 11, 11);
            case FOLDER -> r.path(1, 3, 5, 3, 6, 4, 11, 4, 11, 10, 1, 10, 1, 3);
            case BOX -> r.path(6, 1, 11, 4, 11, 8, 6, 11, 1, 8, 1, 4, 6, 1)
                    .path(1, 4, 6, 7, 11, 4).line(6, 7, 6, 11);
            case TREE -> {
                r.line(2, 1, 2, 10);
                for (int y = 3; y <= 9; y += 3) r.line(2, y, 4, y).line(6, y, 11, y);
            }
            case SLIDERS -> {
                for (int y = 2; y <= 10; y += 4)
                    r.line(1, y, 11, y).fill(y == 6 ? 8 : 4, y - 1, 2, 3);
            }
            case SETTINGS -> r.path(4, 0, 7, 0, 7, 2, 9, 2, 9, 4, 11, 4, 11, 7, 9, 7,
                    9, 9, 7, 9, 7, 11, 4, 11, 4, 9, 2, 9, 2, 7, 0, 7, 0, 4, 2, 4, 2, 2, 4, 2, 4, 0)
                    .rect(4, 4, 4, 4);
            case UNDO -> r.path(2, 4, 10, 4, 10, 9).path(5, 1, 2, 4, 5, 7);
            case REDO -> r.path(10, 4, 2, 4, 2, 9).path(7, 1, 10, 4, 7, 7);
            case MAXIMIZE -> r.path(1, 4, 1, 1, 4, 1).path(7, 1, 10, 1, 10, 4)
                    .path(1, 7, 1, 10, 4, 10).path(7, 10, 10, 10, 10, 7);
            case RESTORE -> r.path(1, 4, 4, 4, 4, 1).path(7, 1, 7, 4, 10, 4)
                    .path(1, 7, 4, 7, 4, 10).path(7, 10, 7, 7, 10, 7);
            case COPY -> r.rect(4, 4, 7, 7).path(1, 8, 1, 1, 8, 1);
            case PASTE -> r.path(3, 2, 1, 2, 1, 10, 10, 10, 10, 2, 8, 2)
                    .rect(3, 1, 5, 3).line(4, 6, 7, 6).line(4, 8, 7, 8);
            case SWAP -> r.line(1, 3, 10, 3).path(8, 1, 10, 3, 8, 5)
                    .line(2, 9, 11, 9).path(4, 7, 2, 9, 4, 11);
            case SUN -> r.path(4, 3, 7, 3, 8, 4, 8, 7, 7, 8, 4, 8, 3, 7, 3, 4, 4, 3)
                    .line(5, 0, 5, 1).line(5, 10, 5, 11).line(0, 5, 1, 5).line(10, 5, 11, 5)
                    .line(1, 1, 2, 2).line(9, 9, 10, 10).line(1, 10, 2, 9).line(9, 2, 10, 1);
            case MOON -> r.path(7, 1, 4, 1, 2, 3, 1, 5, 1, 7, 3, 10, 6, 11, 8, 10, 10, 8,
                    7, 8, 5, 6, 5, 3, 7, 1);
            case CLOCK -> r.path(4, 1, 7, 1, 10, 4, 10, 7, 7, 10, 4, 10, 1, 7, 1, 4, 4, 1)
                    .path(5, 3, 5, 6, 8, 7);
            case WARNING -> r.path(6, 1, 11, 11, 1, 11, 6, 1).line(6, 4, 6, 7).fill(6, 9, 1, 1);
            case SAVE -> r.path(1, 1, 8, 1, 10, 3, 10, 10, 1, 10, 1, 1)
                    .rect(3, 1, 4, 3).rect(3, 6, 6, 5);
            case MAP -> r.path(1, 2, 4, 1, 7, 3, 10, 1, 10, 9, 7, 11, 4, 9, 1, 10, 1, 2)
                    .line(4, 1, 4, 9).line(7, 3, 7, 11);
            case TEAM -> r.rect(4, 1, 4, 4).path(2, 10, 2, 8, 4, 6, 7, 6, 9, 8, 9, 10)
                    .path(1, 2, 0, 3, 1, 4).path(1, 6, 0, 7, 0, 9)
                    .path(10, 2, 11, 3, 10, 4).path(10, 6, 11, 7, 11, 9);
            case POINT -> r.path(4, 1, 7, 1, 9, 3, 9, 6, 6, 11, 5, 11, 2, 6, 2, 3, 4, 1)
                    .fill(5, 3, 2, 2);
            case AREA -> {
                for (int n = 1; n <= 9; n += 4)
                    r.line(n, 1, n + 2, 1).line(n, 11, n + 2, 11)
                            .line(1, n, 1, n + 2).line(11, n, 11, n + 2);
            }
            case SHOP -> r.rect(2, 5, 8, 6).line(1, 3, 10, 3).line(3, 1, 8, 1).line(4, 7, 7, 7);
            case RULE -> r.line(1, 3, 11, 3).line(1, 9, 11, 9).rect(3, 1, 3, 5).rect(7, 7, 3, 5);
            case LAYOUT -> r.rect(1, 1, 10, 10).line(1, 3, 10, 3).line(4, 3, 4, 10);
            case GRID -> r.rect(1, 1, 4, 4).rect(7, 1, 4, 4).rect(1, 7, 4, 4).rect(7, 7, 4, 4);
            case RESOURCES -> r.path(1, 3, 5, 1, 10, 3, 5, 5, 1, 3)
                    .path(1, 6, 5, 8, 10, 6).path(1, 9, 5, 11, 10, 9);
            case DELETE -> r.line(1, 3, 10, 3).path(4, 3, 4, 1, 7, 1, 7, 3)
                    .path(2, 4, 3, 10, 8, 10, 9, 4).line(4, 5, 4, 8).line(7, 5, 7, 8);
            case ERASER -> r.path(4, 3, 7, 1, 10, 4, 5, 9, 3, 9, 1, 7, 4, 3)
                    .line(4, 3, 7, 6).line(4, 11, 10, 11);
        }
        return r.finish();
    }

    /**
     * Existing recipes describe stroke-center samples. The half-unit translation preserves
     * their original painted extent while keeping every segment continuous.
     */
    private static final class Paths {

        private final List<Shape> shapes = new ArrayList<>();

        Paths line(double x, double y, double endX, double endY) {
            return path(x, y, endX, endY);
        }

        Paths path(double... coordinates) {
            if (coordinates.length < 4 || coordinates.length % 2 != 0) throw new IllegalArgumentException("invalid icon path");
            var points = new ArrayList<Point>();
            for (int i = 0; i < coordinates.length; i += 2) points.add(new Point(coordinates[i] + .5, coordinates[i + 1] + .5));
            boolean closed = points.size() > 2 && points.get(0).equals(points.get(points.size() - 1));
            if (closed) points.remove(points.size() - 1);
            shapes.add(new Stroke(points, 1, closed));
            return this;
        }

        Paths rect(double x, double y, double width, double height) {
            return path(x, y, x + width - 1, y, x + width - 1, y + height - 1,
                    x, y + height - 1, x, y);
        }

        Paths fill(double x, double y, double width, double height) {
            shapes.add(new Fill(x, y, width, height));
            return this;
        }

        Glyph finish() {
            return new Glyph(shapes);
        }
    }
}
