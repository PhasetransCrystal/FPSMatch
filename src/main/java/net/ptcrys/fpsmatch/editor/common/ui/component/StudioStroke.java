package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.List;

/** Constant-size coverage mesh for a butt-capped logical stroke. No pixel stepping. */
public final class StudioStroke {

    private StudioStroke() {}

    public record Vertex(double x, double y, double coverage) {

        public Vertex {
            if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > Float.MAX_VALUE || Math.abs(y) > Float.MAX_VALUE || !Double.isFinite(coverage) || coverage < 0 || coverage > 1)
                throw new IllegalArgumentException("Unrepresentable stroke vertex");
        }
    }

    public record Face(Vertex a, Vertex b, Vertex c, Vertex d) {}

    public static List<Face> mesh(StudioCanvas.Segment line, double deviceScale) {
        if (!Double.isFinite(deviceScale) || deviceScale <= 0) throw new IllegalArgumentException("Positive device scale required");
        double dx = line.x1() - line.x0(), dy = line.y1() - line.y0(), length = Math.hypot(dx, dy);
        if (length == 0) return List.of();
        double ux = dx / length, uy = dy / length, px = -uy, py = ux;
        double feather = 1 / deviceScale;
        double inner = Math.max(0, (line.width() - feather) / 2), outer = (line.width() + feather) / 2;
        double inset = Math.min(length / 2, feather / 2);
        double alpha = Math.min(1, line.width() / feather) * Math.min(1, length / feather);
        Vertex a = point(line.x0() + ux * inset, line.y0() + uy * inset, px, py, inner, alpha);
        Vertex b = point(line.x1() - ux * inset, line.y1() - uy * inset, px, py, inner, alpha);
        Vertex c = point(line.x1() - ux * inset, line.y1() - uy * inset, px, py, -inner, alpha);
        Vertex d = point(line.x0() + ux * inset, line.y0() + uy * inset, px, py, -inner, alpha);
        Vertex oa = point(line.x0() - ux * feather / 2, line.y0() - uy * feather / 2, px, py, outer, 0);
        Vertex ob = point(line.x1() + ux * feather / 2, line.y1() + uy * feather / 2, px, py, outer, 0);
        Vertex oc = point(line.x1() + ux * feather / 2, line.y1() + uy * feather / 2, px, py, -outer, 0);
        Vertex od = point(line.x0() - ux * feather / 2, line.y0() - uy * feather / 2, px, py, -outer, 0);
        return List.of(new Face(a, b, c, d), new Face(oa, ob, b, a), new Face(ob, oc, c, b),
                new Face(oc, od, d, c), new Face(od, oa, a, d));
    }

    private static Vertex point(double x, double y, double px, double py, double half, double alpha) {
        return new Vertex(x + px * half, y + py * half, alpha);
    }
}
