package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;
import net.ptcrys.fpsmatch.editor.core.editing.SpatialDocument;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Fixed world-unit editor geometry, expressed locally relative to the stored foot position.
 * Neither this mesh nor its intersections grant document or world write authority.
 */
public final class WorldPointMesh {

    private static final int SIDES = 16;
    private static final double BOTTOM = .012;
    private static final double TOP = .028;

    private WorldPointMesh() {}

    /** Counterclockwise vertices as viewed from outside the closed component. */
    public record Triangle(Position a, Position b, Position c) {

        public Triangle {
            Objects.requireNonNull(a);
            Objects.requireNonNull(b);
            Objects.requireNonNull(c);
        }
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ) || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ) || minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Invalid local mesh bounds");
        }

        private boolean contains(Position p) {
            return p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY && p.z() >= minZ && p.z() <= maxZ;
        }

        private double min(int axis) {
            return axis == 0 ? minX : axis == 1 ? minY : minZ;
        }

        private double max(int axis) {
            return axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
        }
    }

    public record Mesh(List<Triangle> triangles, Bounds bounds) {

        public Mesh {
            triangles = List.copyOf(triangles);
            Objects.requireNonNull(bounds);
            if (triangles.isEmpty()) throw new IllegalArgumentException("Empty point mesh");
            for (var triangle : triangles)
                if (!bounds.contains(triangle.a()) || !bounds.contains(triangle.b()) || !bounds.contains(triangle.c()))
                    throw new IllegalArgumentException("Mesh bounds exclude a vertex");
        }
    }

    /**
     * A small dot locates the stored foot; a separated short arrow shows owner-declared
     * ground direction. Missing or exactly vertical input defensively keeps only the dot.
     */
    public static Mesh build(Optional<SpatialDocument.Direction> direction) {
        Objects.requireNonNull(direction);
        var triangles = new ArrayList<Triangle>();
        cylinder(triangles, new Position(0, BOTTOM, 0), new Position(0, TOP, 0), .05);
        var value = direction.orElse(null);
        double scale = value == null ? 0 : Math.max(Math.abs(value.x()), Math.abs(value.z()));
        if (scale > 0) {
            // Scale before normalizing so even subnormal horizontal components keep their heading.
            double x = value.x() / scale, z = value.z() / scale;
            double length = Math.hypot(x, z);
            arrow(triangles, x / length, z / length);
        }
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (var triangle : triangles) for (var p : List.of(triangle.a(), triangle.b(), triangle.c())) {
            minX = Math.min(minX, p.x());
            minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x());
            maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }
        return new Mesh(triangles, new Bounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /**
     * Nearest front-facing triangle within the ray's finite near-to-far interval.
     * The result is distance from Ray3.origin, not from the camera. Translation is subtracted
     * before intersection, preserving small geometry at large world coordinates. Back faces
     * remain unpickable, including an exit surface viewed from inside a closed component.
     */
    public static OptionalDouble intersect(Mesh mesh, Position worldOrigin, Ray3 ray) {
        Objects.requireNonNull(mesh);
        Objects.requireNonNull(worldOrigin);
        Objects.requireNonNull(ray);
        double[] origin = { ray.origin().x() - worldOrigin.x(), ray.origin().y() - worldOrigin.y(),
                ray.origin().z() - worldOrigin.z() };
        for (double value : origin) if (!Double.isFinite(value)) return OptionalDouble.empty();
        double[] direction = { ray.direction().x(), ray.direction().y(), ray.direction().z() };
        if (!intersectsBounds(mesh.bounds(), origin, direction, ray.maxDistance())) return OptionalDouble.empty();
        // Project onto the plane perpendicular to the dominant ray axis. Shared vertices use
        // identical arithmetic in adjacent triangles, avoiding cracks along their shared edge.
        int z = 0;
        if (Math.abs(direction[1]) > Math.abs(direction[z])) z = 1;
        if (Math.abs(direction[2]) > Math.abs(direction[z])) z = 2;
        int x = (z + 1) % 3, y = (x + 1) % 3;
        if (direction[z] < 0) {
            int swap = x;
            x = y;
            y = swap;
        }
        double sx = direction[x] / direction[z], sy = direction[y] / direction[z];
        double nearest = Double.POSITIVE_INFINITY;
        for (var triangle : mesh.triangles()) {
            double az = coordinate(triangle.a(), z) - origin[z];
            double bz = coordinate(triangle.b(), z) - origin[z];
            double cz = coordinate(triangle.c(), z) - origin[z];
            double ax = coordinate(triangle.a(), x) - origin[x] - sx * az;
            double ay = coordinate(triangle.a(), y) - origin[y] - sy * az;
            double bx = coordinate(triangle.b(), x) - origin[x] - sx * bz;
            double by = coordinate(triangle.b(), y) - origin[y] - sy * bz;
            double cx = coordinate(triangle.c(), x) - origin[x] - sx * cz;
            double cy = coordinate(triangle.c(), y) - origin[y] - sy * cz;
            double a = bx * cy - by * cx, b = cx * ay - cy * ax, c = ax * by - ay * bx;
            // This axis ordering makes outward front faces clockwise in the projected plane.
            if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c) || a > 0 || b > 0 || c > 0) continue;
            double determinant = a + b + c;
            if (!(determinant < 0) || !Double.isFinite(determinant)) continue;
            double distance = ((a / determinant) * az + (b / determinant) * bz + (c / determinant) * cz) / direction[z];
            if (Double.isFinite(distance) && distance >= 0 && distance <= ray.maxDistance() && distance < nearest)
                nearest = distance == 0 ? 0 : distance;
        }
        return Double.isFinite(nearest) ? OptionalDouble.of(nearest) : OptionalDouble.empty();
    }

    private static boolean intersectsBounds(Bounds bounds, double[] origin, double[] direction, double limit) {
        double near = 0, far = limit;
        for (int axis = 0; axis < 3; axis++) {
            if (direction[axis] == 0) {
                if (origin[axis] < bounds.min(axis) || origin[axis] > bounds.max(axis)) return false;
            } else {
                double a = (bounds.min(axis) - origin[axis]) / direction[axis];
                double b = (bounds.max(axis) - origin[axis]) / direction[axis];
                near = Math.max(near, Math.min(a, b));
                far = Math.min(far, Math.max(a, b));
                if (near > far) return false;
            }
        }
        return true;
    }

    private static void cylinder(List<Triangle> triangles, Position start, Position end, double radius) {
        var basis = basis(start, end);
        Position[] lower = ring(start, basis, radius), upper = ring(end, basis, radius);
        for (int i = 0; i < SIDES; i++) {
            int next = (i + 1) % SIDES;
            triangles.add(new Triangle(start, lower[next], lower[i]));
            triangles.add(new Triangle(end, upper[i], upper[next]));
            triangles.add(new Triangle(lower[i], lower[next], upper[next]));
            triangles.add(new Triangle(lower[i], upper[next], upper[i]));
        }
    }

    private static void arrow(List<Triangle> triangles, double headingX, double headingZ) {
        double[][] outline = { { -.03, .11 }, { .03, .11 }, { .03, .24 }, { .11, .24 },
                { 0, .40 }, { -.11, .24 }, { -.03, .24 } };
        Position[] lower = new Position[outline.length], upper = new Position[outline.length];
        for (int i = 0; i < outline.length; i++) {
            lower[i] = arrowPosition(outline[i][0], BOTTOM, outline[i][1], headingX, headingZ);
            upper[i] = arrowPosition(outline[i][0], TOP, outline[i][1], headingX, headingZ);
        }
        // This interior point sees every edge of the concave silhouette, with no degenerate fans.
        Position bottom = arrowPosition(0, BOTTOM, .28, headingX, headingZ);
        Position top = arrowPosition(0, TOP, .28, headingX, headingZ);
        for (int i = 0; i < outline.length; i++) {
            int next = (i + 1) % outline.length;
            triangles.add(new Triangle(bottom, lower[i], lower[next]));
            triangles.add(new Triangle(top, upper[next], upper[i]));
            triangles.add(new Triangle(lower[i], upper[i], upper[next]));
            triangles.add(new Triangle(lower[i], upper[next], lower[next]));
        }
    }

    private static Position arrowPosition(double x, double y, double z, double headingX, double headingZ) {
        return new Position(x * headingZ + z * headingX, y, -x * headingX + z * headingZ);
    }

    private record Basis(Position u, Position v) {}

    private static Basis basis(Position start, Position end) {
        Position d = unit(new Position(end.x() - start.x(), end.y() - start.y(), end.z() - start.z()));
        double x = Math.abs(d.x()), y = Math.abs(d.y()), z = Math.abs(d.z());
        Position axis = x <= y && x <= z ? new Position(1, 0, 0) : y <= z ? new Position(0, 1, 0) : new Position(0, 0, 1);
        Position u = unit(cross(d, axis));
        return new Basis(u, cross(d, u));
    }

    private static Position[] ring(Position center, Basis basis, double radius) {
        Position[] result = new Position[SIDES];
        for (int i = 0; i < SIDES; i++) {
            double angle = i * Math.PI * 2 / SIDES;
            result[i] = add(add(center, basis.u(), radius * Math.cos(angle)), basis.v(), radius * Math.sin(angle));
        }
        return result;
    }

    private static Position add(Position a, Position b, double scale) {
        return new Position(a.x() + b.x() * scale, a.y() + b.y() * scale, a.z() + b.z() * scale);
    }

    private static Position cross(Position a, Position b) {
        return new Position(a.y() * b.z() - a.z() * b.y(), a.z() * b.x() - a.x() * b.z(), a.x() * b.y() - a.y() * b.x());
    }

    private static Position unit(Position p) {
        double length = Math.hypot(Math.hypot(p.x(), p.y()), p.z());
        return new Position(p.x() / length, p.y() / length, p.z() / length);
    }

    private static double coordinate(Position p, int axis) {
        return axis == 0 ? p.x() : axis == 1 ? p.y() : p.z();
    }
}
