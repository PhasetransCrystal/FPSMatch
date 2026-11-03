package net.ptcrys.fpsmatch.editor.common.model.geometry;

/**
 * Immutable integral block coordinate of the world editor rewrite (contract R1, section 2 / fixed
 * geometry task card R04.1).
 *
 * <p>
 * All three components are plain {@code int} values and are stored verbatim; no location is
 * normalised, sorted or clamped. The only arithmetic offered is {@link #add(long, long, long)},
 * which evaluates each axis as {@code Math.addExact((long) component, delta)} and then requires the
 * exact sum to fit into an {@code int}. A {@code long} overflow and an {@code int} range violation
 * are both reported as {@link IllegalArgumentException}; the result is never silently wrapped by a
 * narrowing cast.
 */
public record IntPosition(int x, int y, int z) {

    /**
     * Returns the component wise sum of this position and the supplied deltas.
     *
     * <p>
     * Each axis is computed independently, so a failure on one axis never produces a partially
     * shifted position. Deltas outside the {@code int} domain are legal inputs as long as the exact
     * sum still fits into an {@code int}.
     *
     * @param dx delta added to {@link #x()}
     * @param dy delta added to {@link #y()}
     * @param dz delta added to {@link #z()}
     * @return the shifted position
     * @throws IllegalArgumentException if any axis sum overflows {@code long} or falls outside the
     *                                  {@code int} range
     */
    public IntPosition add(long dx, long dy, long dz) {
        return new IntPosition(checkedAdd(x, dx, "x"), checkedAdd(y, dy, "y"), checkedAdd(z, dz, "z"));
    }

    private static int checkedAdd(int component, long delta, String axis) {
        long sum;
        try {
            sum = Math.addExact((long) component, delta);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "IntPosition." + axis + " overflow: " + component + " + " + delta, e);
        }
        if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "IntPosition." + axis + " out of int range: " + component + " + " + delta + " = " + sum);
        }
        return (int) sum;
    }
}
