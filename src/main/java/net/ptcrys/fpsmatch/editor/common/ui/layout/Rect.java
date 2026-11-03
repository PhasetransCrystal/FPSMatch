package net.ptcrys.fpsmatch.editor.common.ui.layout;

/**
 * A finite rectangle with non-negative size. The left and top edges are inclusive, the right and
 * bottom edges are exclusive, so a zero-sized rectangle contains no point at all.
 *
 * @param x      left edge
 * @param y      top edge
 * @param width  width, at least {@code 0}
 * @param height height, at least {@code 0}
 */
public record Rect(double x, double y, double width, double height) {

    public Rect {
        Bounds.finite(x, "x");
        Bounds.finite(y, "y");
        Bounds.nonNegative(width, "width");
        Bounds.nonNegative(height, "height");
        Bounds.finite(x + width, "right edge");
        Bounds.finite(y + height, "bottom edge");
    }

    /** Exclusive right edge. */
    public double right() {
        return x + width;
    }

    /** Exclusive bottom edge. */
    public double bottom() {
        return y + height;
    }

    /**
     * Tests whether a point lies inside this rectangle. Left/top are inclusive, right/bottom are
     * exclusive.
     *
     * @throws IllegalArgumentException when either coordinate is not finite
     */
    public boolean contains(double pointX, double pointY) {
        Bounds.finite(pointX, "point x");
        Bounds.finite(pointY, "point y");
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }
}
