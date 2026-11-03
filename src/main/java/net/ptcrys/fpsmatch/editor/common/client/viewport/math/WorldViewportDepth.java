package net.ptcrys.fpsmatch.editor.common.client.viewport.math;

import net.ptcrys.fpsmatch.editor.common.client.viewport.ViewportLayout;

import java.util.Objects;
import java.util.Optional;

/** Pure pixel/depth calculations. This class never binds an FBO, reads pixels or grants a hit. */
public final class WorldViewportDepth {

    private WorldViewportDepth() {}

    /** One actual sample: global top-left pixel center and local bottom-up FBO read address. */
    public record Pixel(double framebufferCenterX, double framebufferCenterY, int readX, int readY) {

        public Pixel {
            if (!Double.isFinite(framebufferCenterX) || !Double.isFinite(framebufferCenterY) || framebufferCenterX < .5 || framebufferCenterY < .5 || framebufferCenterX - Math.floor(framebufferCenterX) != .5 || framebufferCenterY - Math.floor(framebufferCenterY) != .5 || readX < 0 || readY < 0)
                throw new IllegalArgumentException("Invalid viewport pixel");
        }
    }

    /**
     * Quantize only after checking both the half-open logical area and physical intersection.
     * The pixel center may extend beyond a fractional logical edge: the FBO really contains
     * that entire floor/ceil pixel. Ray construction and readback must use this same sample.
     */
    public static Optional<Pixel> pixel(ViewportLayout layout, double logicalX, double logicalY) {
        Objects.requireNonNull(layout);
        if (!Double.isFinite(logicalX) || !Double.isFinite(logicalY) || !layout.logicalRect().contains(logicalX, logicalY)) return Optional.empty();
        try {
            var rect = layout.framebufferRect();
            double x = layout.scaleTransform().toFramebufferX(logicalX);
            double y = layout.scaleTransform().toFramebufferY(logicalY);
            if (!rect.contains(x, y)) return Optional.empty();
            // rect is already clipped to the framebuffer; only now is an int cast safe.
            int column = (int) Math.floor(x), row = (int) Math.floor(y);
            return Optional.of(new Pixel(column + .5, row + .5,
                    column - rect.x(), rect.height() - 1 - (row - rect.y())));
        } catch (IllegalArgumentException invalidMapping) {
            return Optional.empty();
        }
    }

    /**
     * Encoding consistency only, not object identity or a bound on GPU arithmetic.
     * Fixed depth uses half of 1/(2^bits-1), plus half a float readback ULP. A 32F attachment
     * uses half its local float spacing; float-to-float readback adds no second rounding.
     * nextUp/nextDown only round the double interval calculation outward. No arbitrary
     * depth epsilon or allowance for vertex transforms/raster interpolation is added.
     */
    public static boolean matches(double expectedWindowDepth, float sampled, int depthBits, boolean floatingDepth) {
        if (!Double.isFinite(expectedWindowDepth) || expectedWindowDepth < 0 || expectedWindowDepth >= 1 || !Float.isFinite(sampled) || sampled < 0 || sampled >= 1 || depthBits < 1 || depthBits > 32 || floatingDepth && depthBits != 32) return false;
        double halfFloatStep = (double) Math.ulp(sampled) * .5;
        double halfAttachmentStep = floatingDepth ? halfFloatStep : Math.nextUp(.5 / (Math.scalb(1.0, depthBits) - 1));
        double tolerance = Math.nextUp(halfAttachmentStep + (floatingDepth ? 0 : halfFloatStep));
        double lower = Math.nextDown((double) sampled - tolerance);
        double upper = Math.nextUp((double) sampled + tolerance);
        return expectedWindowDepth >= lower && expectedWindowDepth <= upper;
    }
}
