package net.ptcrys.fpsmatch.editor.common.model.geometry;

/**
 * Immutable world space position of the world editor rewrite (contract R1, section 2 /
 * fixed geometry task card R04.1).
 *
 * <p>
 * Every component must be finite: {@code NaN}, {@code Float}/{@code Double} positive infinity
 * and negative infinity are rejected with {@link IllegalArgumentException}. The supplied values are
 * stored verbatim; in particular a signed zero keeps its sign bit and is never normalised, rounded
 * or clamped. There is no defaulting, no repair and no conversion helper on this type.
 */
public record Position(double x, double y, double z) {

    /**
     * Rejects non finite components. The original {@code double} values are retained as given.
     */
    public Position {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
    }

    private static void requireFinite(double value, String component) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Position." + component + " must be finite but was " + value);
        }
    }
}
