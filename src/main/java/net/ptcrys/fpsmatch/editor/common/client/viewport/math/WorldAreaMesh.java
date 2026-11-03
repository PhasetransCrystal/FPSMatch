package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh.Bounds;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh.Mesh;
import net.ptcrys.fpsmatch.editor.common.client.viewport.math.WorldPointMesh.Triangle;
import net.ptcrys.fpsmatch.editor.common.model.geometry.Position;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Fixed world-unit area reference geometry. The caller supplies already interpreted
 * drawing boundaries in one local coordinate system; this class has no containment,
 * document ordering, integer conversion, or write-authority semantics.
 */
public final class WorldAreaMesh {

    private static final double ROD_HALF_WIDTH = .02;
    private static final double HANDLE_RADIUS = .10;
    private static final Mesh HANDLE = createHandle();

    private WorldAreaMesh() {}

    /**
     * The exterior of the union of square edge rods, with no area-face filling.
     * Normalization is only for drawing: neither endpoint is changed or given a +1.
     * Coincident edges become one solid, including planar, line and point inputs.
     * Work is bounded by 27 cells, independently of the enclosed world volume.
     */
    public static Mesh outline(Position first, Position second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        Axis x = axis(first.x(), second.x()), y = axis(first.y(), second.y()), z = axis(first.z(), second.z());
        boolean[][][] solid = new boolean[x.size()][y.size()][z.size()];
        for (int i = 0; i < x.size(); i++) for (int j = 0; j < y.size(); j++) for (int k = 0; k < z.size(); k++)
            solid[i][j][k] = (x.band(i) ? 1 : 0) + (y.band(j) ? 1 : 0) + (z.band(k) ? 1 : 0) >= 2;
        var triangles = new ArrayList<Triangle>();
        for (int i = 0; i < x.size(); i++) for (int j = 0; j < y.size(); j++) for (int k = 0; k < z.size(); k++) {
            if (!solid[i][j][k]) continue;
            double x0 = x.cuts[i], x1 = x.cuts[i + 1], y0 = y.cuts[j], y1 = y.cuts[j + 1];
            double z0 = z.cuts[k], z1 = z.cuts[k + 1];
            Position a = new Position(x0, y0, z0), b = new Position(x0, y0, z1);
            Position c = new Position(x0, y1, z0), d = new Position(x0, y1, z1);
            Position e = new Position(x1, y0, z0), f = new Position(x1, y0, z1);
            Position g = new Position(x1, y1, z0), h = new Position(x1, y1, z1);
            // Emit only exterior faces. The shared cut grid also avoids overlapping
            // coplanar faces and T-junctions when endpoint bands touch or overlap.
            if (i == 0 || !solid[i - 1][j][k]) quad(triangles, a, b, d, c);
            if (i + 1 == x.size() || !solid[i + 1][j][k]) quad(triangles, e, g, h, f);
            if (j == 0 || !solid[i][j - 1][k]) quad(triangles, a, e, f, b);
            if (j + 1 == y.size() || !solid[i][j + 1][k]) quad(triangles, c, d, h, g);
            if (k == 0 || !solid[i][j][k - 1]) quad(triangles, a, c, g, e);
            if (k + 1 == z.size() || !solid[i][j][k + 1]) quad(triangles, b, f, h, d);
        }
        return new Mesh(triangles, new Bounds(x.first(), y.first(), z.first(), x.last(), y.last(), z.last()));
    }

    /**
     * A closed octahedron centered at the local origin. The caller positions one
     * at each distinct ordered endpoint and retains the owning area node identity.
     */
    public static Mesh handle() {
        return HANDLE;
    }

    private record Axis(double[] cuts, boolean[] bands) {

        int size() {
            return bands.length;
        }

        boolean band(int index) {
            return bands[index];
        }

        double first() {
            return cuts[0];
        }

        double last() {
            return cuts[cuts.length - 1];
        }
    }

    private static Axis axis(double first, double second) {
        double min = Math.min(first, second), max = Math.max(first, second);
        double a = min - ROD_HALF_WIDTH, b = min + ROD_HALF_WIDTH;
        double c = max - ROD_HALF_WIDTH, d = max + ROD_HALF_WIDTH;
        if (!Double.isFinite(a) || !Double.isFinite(d) || !(a < min && min < b && c < max && max < d))
            throw new IllegalArgumentException("Area coordinates cannot represent the fixed rod width; use a local origin");
        double[] cuts = Arrays.stream(new double[] { a, b, c, d }).map(v -> v == 0 ? 0 : v).sorted().distinct().toArray();
        boolean[] bands = new boolean[cuts.length - 1];
        for (int i = 0; i < bands.length; i++)
            bands[i] = (cuts[i] >= a && cuts[i + 1] <= b) || (cuts[i] >= c && cuts[i + 1] <= d);
        return new Axis(cuts, bands);
    }

    private static Mesh createHandle() {
        double r = HANDLE_RADIUS;
        Position top = new Position(0, r, 0), bottom = new Position(0, -r, 0);
        Position[] ring = { new Position(r, 0, 0), new Position(0, 0, r),
                new Position(-r, 0, 0), new Position(0, 0, -r) };
        var triangles = new ArrayList<Triangle>();
        for (int i = 0; i < ring.length; i++) {
            int next = (i + 1) % ring.length;
            triangles.add(new Triangle(top, ring[next], ring[i]));
            triangles.add(new Triangle(bottom, ring[i], ring[next]));
        }
        return new Mesh(triangles, new Bounds(-r, -r, -r, r, r, r));
    }

    private static void quad(List<Triangle> triangles, Position a, Position b, Position c, Position d) {
        triangles.add(new Triangle(a, b, c));
        triangles.add(new Triangle(a, c, d));
    }
}
