package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.client.viewport.FramebufferRect;
import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.core.editing.DocumentEditor;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Pure screen geometry for X-ray document markers. It never tests terrain or grants authority. */
public final class DocumentPointProjection {

    public static final double HIT_RADIUS = 8.0;
    private static final int MAX_POINTS = 8192;
    private static final double DIRECTION_LENGTH = 2.0, HEAD_LENGTH = 6.0, HEAD_HALF_WIDTH = 3.0;
    // An angular arithmetic tolerance, independent of camera distance and screen-pixel length.
    private static final double RADIAL_TOLERANCE = 1e-9;

    private DocumentPointProjection() {}

    /** Actual captured camera matrices; no reconstruction from player pose or GUI scale. */
    public record View(Position camera, FrameMatrix view, FrameMatrix projection, ViewportLayout layout) {

        public View {
            Objects.requireNonNull(camera);
            Objects.requireNonNull(view);
            Objects.requireNonNull(projection);
            Objects.requireNonNull(layout);
        }
    }

    /** One rounded logical center shared by drawing and hit testing. Depth is positive camera depth. */
    public record Marker(String node, int x, int y, double depth, boolean selected, Optional<Glyph> direction) {

        public Marker(String node, int x, int y, double depth, boolean selected) {
            this(node, x, y, depth, selected, Optional.empty());
        }

        public Marker {
            DocumentEditor.requireAddress(node);
            if (!Double.isFinite(depth) || depth <= 0) throw new IllegalArgumentException("Positive finite depth required");
            Objects.requireNonNull(direction);
        }
    }

    public enum Facing {
        NONE,
        TOWARD_CAMERA,
        AWAY_FROM_CAMERA
    }

    /** Ready-to-draw direction geometry. It never participates in marker hit testing. */
    public record Glyph(List<WorldSegmentProjection.Segment> segments, Facing facing) {

        public Glyph {
            segments = List.copyOf(segments);
            Objects.requireNonNull(facing);
        }
    }

    public static List<Marker> project(View captured, List<SpatialDocument.Point> points,
                                       Map<EditorId, EditorId> mappings, String targetDimension, String selectedNode) {
        Objects.requireNonNull(captured);
        Objects.requireNonNull(points);
        Objects.requireNonNull(mappings);
        Objects.requireNonNull(targetDimension);
        if (points.size() > MAX_POINTS) throw new IllegalArgumentException("Too many spatial points");
        var result = new ArrayList<Marker>();
        var identities = new HashSet<String>();
        FramebufferRect pixels = captured.layout().framebufferRect();
        for (var point : points) {
            if (!identities.add(point.node())) throw new IllegalArgumentException("Duplicate spatial node");
            EditorId target = mappings.get(point.dimension());
            if (target == null || !target.toString().equals(targetDimension)) continue;
            project(captured, pixels, point, point.node().equals(selectedNode)).ifPresent(result::add);
        }
        // Paint farther points first, with the current selection last. Selection never changes hit priority.
        result.sort(Comparator.comparing(Marker::selected)
                .thenComparing(Comparator.comparingDouble(Marker::depth).reversed()).thenComparing(Marker::node));
        return List.copyOf(result);
    }

    private static Optional<Marker> project(View captured, FramebufferRect pixels, SpatialDocument.Point point, boolean selected) {
        if (pixels.isEmpty()) return Optional.empty();
        double x = point.x().doubleValue() - captured.camera().x();
        double y = point.y().doubleValue() - captured.camera().y();
        double z = point.z().doubleValue() - captured.camera().z();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return Optional.empty();
        Vector4 viewed, clip;
        try {
            viewed = MatrixMath.transform(captured.view(), new Vector4(x, y, z, 1));
            clip = MatrixMath.transform(captured.projection(), viewed);
        } catch (IllegalArgumentException numericalFailure) {
            return Optional.empty();
        }
        if (!(clip.w() > 0) || viewed.w() == 0) return Optional.empty();
        double depth = -viewed.z() / viewed.w();
        double nx = clip.x() / clip.w(), ny = clip.y() / clip.w(), nz = clip.z() / clip.w();
        if (!Double.isFinite(depth) || depth <= 0 || !ndc(nx) || !ndc(ny) || !ndc(nz)) return Optional.empty();
        double px = pixels.x() + (nx + 1) * pixels.width() / 2;
        double py = pixels.y() + (1 - ny) * pixels.height() / 2;
        if (!Double.isFinite(px) || !Double.isFinite(py) || !pixels.contains(px, py)) return Optional.empty();
        double logicalX, logicalY;
        try {
            logicalX = captured.layout().scaleTransform().toLogicalX(px);
            logicalY = captured.layout().scaleTransform().toLogicalY(py);
        } catch (IllegalArgumentException numericalFailure) {
            return Optional.empty();
        }
        // Leave room for the fixed marker and hit box without integer overflow. No edge clamping.
        if (logicalX < Integer.MIN_VALUE + 16.0 || logicalX > Integer.MAX_VALUE - 16.0 || logicalY < Integer.MIN_VALUE + 16.0 || logicalY > Integer.MAX_VALUE - 16.0) return Optional.empty();
        int cx = (int) Math.round(logicalX), cy = (int) Math.round(logicalY);
        if (!captured.layout().logicalRect().contains(cx, cy)) return Optional.empty();
        var direction = point.direction().flatMap(value -> direction(captured, new Position(x, y, z), clip, value));
        return Optional.of(new Marker(point.node(), cx, cy, depth, selected, direction));
    }

    private static Optional<Glyph> direction(View captured, Position relative, Vector4 origin, SpatialDocument.Direction direction) {
        double scale = Math.max(Math.abs(relative.x()), Math.max(Math.abs(relative.y()), Math.abs(relative.z())));
        if (!(scale > 0)) return Optional.empty();
        double rx = relative.x() / scale, ry = relative.y() / scale, rz = relative.z() / scale;
        double length = Math.hypot(Math.hypot(rx, ry), rz);
        rx /= length;
        ry /= length;
        rz /= length;
        double dx = direction.x(), dy = direction.y(), dz = direction.z();
        double cross = Math.hypot(Math.hypot(dy * rz - dz * ry, dz * rx - dx * rz), dx * ry - dy * rx);
        if (cross <= RADIAL_TOLERANCE) {
            double dot = dx * rx + dy * ry + dz * rz;
            return Optional.of(new Glyph(List.of(), dot < 0 ? Facing.TOWARD_CAMERA : Facing.AWAY_FROM_CAMERA));
        }
        double ex = relative.x() + DIRECTION_LENGTH * dx, ey = relative.y() + DIRECTION_LENGTH * dy;
        double ez = relative.z() + DIRECTION_LENGTH * dz;
        if (!Double.isFinite(ex) || !Double.isFinite(ey) || !Double.isFinite(ez)) return Optional.empty();
        var projected = WorldSegmentProjection.projectRelative(captured, relative, new Position(ex, ey, ez));
        if (projected.isEmpty()) return Optional.empty();
        var value = projected.get();
        var shaft = value.segment();
        var segments = new ArrayList<WorldSegmentProjection.Segment>();
        if (shaft.x0() != shaft.x1() || shaft.y0() != shaft.y1()) segments.add(shaft);
        if (!value.endClipped()) {
            // The homogeneous derivative keeps subpixel, distant bearings stable without extending the world shaft.
            Vector4 delta;
            try {
                delta = MatrixMath.transform(captured.projection(), MatrixMath.transform(captured.view(), new Vector4(dx, dy, dz, 0)));
            } catch (IllegalArgumentException numericalFailure) {
                return Optional.empty();
            }
            var pixels = captured.layout().framebufferRect();
            var transform = captured.layout().scaleTransform();
            double ux = (delta.x() - origin.x() / origin.w() * delta.w()) * pixels.width() / transform.xScale();
            double uy = -(delta.y() - origin.y() / origin.w() * delta.w()) * pixels.height() / transform.yScale();
            double norm = Math.hypot(ux, uy);
            if (Double.isFinite(norm) && norm > 0) {
                ux /= norm;
                uy /= norm;
                double bx = shaft.x1() - HEAD_LENGTH * ux, by = shaft.y1() - HEAD_LENGTH * uy;
                segments.add(new WorldSegmentProjection.Segment(shaft.x1(), shaft.y1(), bx - HEAD_HALF_WIDTH * uy, by + HEAD_HALF_WIDTH * ux));
                segments.add(new WorldSegmentProjection.Segment(shaft.x1(), shaft.y1(), bx + HEAD_HALF_WIDTH * uy, by - HEAD_HALF_WIDTH * ux));
            }
        }
        return Optional.of(new Glyph(segments, Facing.NONE));
    }

    /** Deterministic first hit; coincident nodes remain distinct and are never merged by position. */
    public static Optional<Marker> hit(List<Marker> displayed, double x, double y) {
        Objects.requireNonNull(displayed);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return Optional.empty();
        Marker best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var marker : displayed) {
            double dx = x - marker.x(), dy = y - marker.y(), distance = dx * dx + dy * dy;
            if (distance > HIT_RADIUS * HIT_RADIUS || !Double.isFinite(distance)) continue;
            if (best == null || distance < bestDistance || distance == bestDistance && (marker.depth() < best.depth() || marker.depth() == best.depth() && marker.node().compareTo(best.node()) < 0)) {
                best = marker;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean ndc(double value) {
        return Double.isFinite(value) && value >= -1 && value <= 1;
    }
}
