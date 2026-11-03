package net.ptcrys.fpsmatch.editor.common.model.geometry;

/**
 * Immutable look orientation of the world editor rewrite (contract R1, section 2 / fixed geometry
 * task card R04.1).
 *
 * <p>
 * Both angles must be finite and are stored verbatim: there is no clamping to {@code [-90, 90]}
 * for pitch and no normalisation of yaw, so {@code yaw = 720} stays {@code 720} and
 * {@code pitch = 180} stays {@code 180}. A signed zero keeps its sign bit. {@code NaN} and both
 * infinities are rejected with {@link IllegalArgumentException}.
 */
public record Orientation(float yaw, float pitch) {

    /**
     * Rejects non finite angles. The original {@code float} values are retained as given.
     */
    public Orientation {
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
    }

    private static void requireFinite(float value, String component) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Orientation." + component + " must be finite but was " + value);
        }
    }
}
