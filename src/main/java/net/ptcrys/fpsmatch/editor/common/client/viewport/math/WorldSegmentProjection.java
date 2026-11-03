package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

import java.util.Objects;
import java.util.Optional;

/** Pure world-segment projection. Clip in homogeneous space before either perspective divide. */
public final class WorldSegmentProjection {

    private WorldSegmentProjection() {}

    /** Continuous logical coordinates; the drawing backend clips the stroke's width. */
    public record Segment(double x0, double y0, double x1, double y1) {

        public Segment {
            if (!Double.isFinite(x0) || !Double.isFinite(y0) || !Double.isFinite(x1) || !Double.isFinite(y1))
                throw new IllegalArgumentException("Finite segment coordinates required");
        }
    }

    /** Flags describe the original endpoints, so a clipped end never acquires an arrowhead. */
    public record ProjectedSegment(Segment segment, boolean startClipped, boolean endClipped) {

        public ProjectedSegment {
            Objects.requireNonNull(segment);
        }
    }

    public static Optional<ProjectedSegment> project(DocumentPointProjection.View view, Position from, Position to) {
        Objects.requireNonNull(view);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        var a = relative(from, view.camera());
        var b = relative(to, view.camera());
        return a.isPresent() && b.isPresent() ? projectRelative(view, a.get(), b.get()) : Optional.empty();
    }

    /**
     * Also lets a short direction offset be added after camera subtraction, without losing it at large world origins.
     */
    static Optional<ProjectedSegment> projectRelative(DocumentPointProjection.View view, Position from, Position to) {
        Vector4 a, b;
        try {
            a = transform(view, from);
            b = transform(view, to);
        } catch (IllegalArgumentException numericalFailure) {
            return Optional.empty();
        }
        return clip(view.layout(), a, b);
    }

    public static Optional<ProjectedSegment> clip(ViewportLayout layout, Vector4 from, Vector4 to) {
        Objects.requireNonNull(layout);
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (layout.framebufferRect().isEmpty()) return Optional.empty();
        double enter = 0, leave = 1;
        int enterPlanes = 0, leavePlanes = 0;
        boolean startClipped = false, endClipped = false;
        for (int plane = 0; plane < 6; plane++) {
            double a = distance(from, plane), b = distance(to, plane);
            if (!Double.isFinite(a) || !Double.isFinite(b)) return Optional.empty();
            startClipped |= a < 0;
            endClipped |= b < 0;
            if (a < 0 && b < 0) return Optional.empty();
            if (a >= 0 && b >= 0) continue;
            // Scale the ratio rather than subtracting opposite, potentially huge distances.
            double scale = Math.max(Math.abs(a), Math.abs(b));
            double crossing = (a / scale) / (a / scale - b / scale);
            if (!Double.isFinite(crossing)) return Optional.empty();
            if (a < 0) {
                if (crossing > enter) {
                    enter = crossing;
                    enterPlanes = 1 << plane;
                } else if (crossing == enter) enterPlanes |= 1 << plane;
            } else {
                if (crossing < leave) {
                    leave = crossing;
                    leavePlanes = 1 << plane;
                } else if (crossing == leave) leavePlanes |= 1 << plane;
            }
            if (enter > leave) return Optional.empty();
        }
        var a = screen(layout, intersection(from, to, enter, enterPlanes));
        var b = screen(layout, intersection(from, to, leave, leavePlanes));
        if (a.isEmpty() || b.isEmpty()) return Optional.empty();
        return Optional.of(new ProjectedSegment(new Segment(a.get()[0], a.get()[1], b.get()[0], b.get()[1]),
                startClipped, endClipped));
    }

    private static Optional<Position> relative(Position point, Position camera) {
        double x = point.x() - camera.x(), y = point.y() - camera.y(), z = point.z() - camera.z();
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) ? Optional.of(new Position(x, y, z)) : Optional.empty();
    }

    private static Vector4 transform(DocumentPointProjection.View view, Position point) {
        return MatrixMath.transform(view.projection(), MatrixMath.transform(view.view(),
                new Vector4(point.x(), point.y(), point.z(), 1)));
    }

    private static double distance(Vector4 point, int plane) {
        return switch (plane) {
            case 0 -> point.w() + point.x();
            case 1 -> point.w() - point.x();
            case 2 -> point.w() + point.y();
            case 3 -> point.w() - point.y();
            case 4 -> point.w() + point.z();
            case 5 -> point.w() - point.z();
            default -> throw new IllegalArgumentException("Unknown clip plane");
        };
    }

    private static Vector4 intersection(Vector4 from, Vector4 to, double t, int planes) {
        double x = interpolate(from.x(), to.x(), t), y = interpolate(from.y(), to.y(), t);
        double z = interpolate(from.z(), to.z(), t), w = interpolate(from.w(), to.w(), t);
        // Enforce the analytically intersected plane, not an epsilon clamp of arbitrary points.
        if ((planes & 1) != 0) x = -w;
        if ((planes & 2) != 0) x = w;
        if ((planes & 4) != 0) y = -w;
        if ((planes & 8) != 0) y = w;
        if ((planes & 16) != 0) z = -w;
        if ((planes & 32) != 0) z = w;
        return new Vector4(x, y, z, w);
    }

    private static double interpolate(double a, double b, double t) {
        if (t == 0) return a;
        if (t == 1) return b;
        return (1 - t) * a + t * b;
    }

    private static Optional<double[]> screen(ViewportLayout layout, Vector4 clip) {
        if (!(clip.w() > 0)) return Optional.empty();
        double x = clip.x() / clip.w(), y = clip.y() / clip.w();
        if (!Double.isFinite(x) || !Double.isFinite(y)) return Optional.empty();
        var pixels = layout.framebufferRect();
        double px = pixels.x() + (x + 1) * pixels.width() / 2;
        double py = pixels.y() + (1 - y) * pixels.height() / 2;
        try {
            return Optional.of(new double[] { layout.scaleTransform().toLogicalX(px), layout.scaleTransform().toLogicalY(py) });
        } catch (IllegalArgumentException numericalFailure) {
            return Optional.empty();
        }
    }
}
