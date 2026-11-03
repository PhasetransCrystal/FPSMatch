package net.ptcrys.fpsmatch.editor.common.client.workbench;

import java.util.Objects;
import java.util.function.BiPredicate;

/** Exact X/Z chunk-boundary traversal for a bounded client picking segment; no MC dependency. */
final class WorkbenchLoadedChunkRay {

    private WorkbenchLoadedChunkRay() {}

    record Limit(double distance, boolean unloaded) {}

    /**
     * Returns the first unloaded chunk's exact entry parameter, or the requested length.
     * The origin's floor-divided chunk is checked even when the origin lies on a boundary;
     * negative travel then crosses that boundary at t=0. Simultaneous X/Z crossings enter
     * the diagonal chunk: a side chunk touched at only one point has no ray interval.
     * Direction components are from a unit world ray; the viewport pick budget is 256 blocks.
     */
    static Limit trace(double x, double z, double directionX, double directionZ, double distance,
                       BiPredicate<Integer, Integer> loaded) {
        Objects.requireNonNull(loaded);
        if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(directionX) || !Double.isFinite(directionZ) || Math.abs(directionX) > 1 || Math.abs(directionZ) > 1 || !Double.isFinite(distance) || distance < 0 || distance > 256)
            throw new IllegalArgumentException("invalid bounded picking segment");
        int chunkX = chunk(x), chunkZ = chunk(z);
        int stepX = directionX > 0 ? 1 : directionX < 0 ? -1 : 0;
        int stepZ = directionZ > 0 ? 1 : directionZ < 0 ? -1 : 0;
        if (!loaded.test(chunkX, chunkZ)) return new Limit(0, true);
        while (true) {
            // Recompute from the original ray, rather than accumulating delta error. This
            // preserves exact ties and sub-block intervals beside a chunk corner.
            double nextX = crossing(x, directionX, chunkX, stepX);
            double nextZ = crossing(z, directionZ, chunkZ, stepZ);
            double entry = Math.max(0.0, Math.min(nextX, nextZ));
            if (entry > distance) return new Limit(distance, false);
            if (nextX == entry) chunkX = Math.addExact(chunkX, stepX);
            if (nextZ == entry) chunkZ = Math.addExact(chunkZ, stepZ);
            if (!loaded.test(chunkX, chunkZ)) return new Limit(entry, true);
            if (entry == distance) return new Limit(distance, false);
        }
    }

    private static int chunk(double coordinate) {
        double chunk = Math.floor(coordinate / 16);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE)
            throw new IllegalArgumentException("chunk coordinate out of range");
        return (int) chunk;
    }

    private static double crossing(double origin, double direction, int chunk, int step) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        double edge = ((double) chunk + (step > 0 ? 1 : 0)) * 16;
        return (edge - origin) / direction;
    }
}
