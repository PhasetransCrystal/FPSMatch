package net.ptcrys.fpsmatch.editor.common.text.java2d;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable ARGB pixel buffer produced by {@link TextLine#rasterize()}.
 *
 * <p>
 * The samples are 32 bit ARGB with straight (non-premultiplied) alpha, row major, top left
 * origin. The alpha channel carries glyph coverage and the RGB channels carry the ink colour, so a
 * consumer composites {@code rgb} with {@code alpha} over its own background and never has to guess
 * whether the samples are premultiplied. Dimensions are in physical pixels and are at least 1; a
 * single axis is limited to 8192 and the whole buffer to 16 Mi pixels
 * (16&nbsp;*&nbsp;1024&nbsp;*&nbsp;1024 samples). The array is copied on construction and on every
 * access, so neither the caller nor a reader can mutate shared state.
 * </p>
 *
 * <p>
 * The raster is not a window and not a {@code Graphics2D}: it is only pixels, and the caller owns
 * compositing, scaling and upload. Consequently {@link TextLine#advance()} is a layout metric and
 * deliberately differs from {@link #width()}, which also covers side bearings and padding.
 * </p>
 *
 * @param width    image width in physical pixels, 1..8192
 * @param height   image height in physical pixels, 1..8192
 * @param argb     non-premultiplied ARGB samples, exactly {@code width * height} entries
 * @param originX  x offset of the text layout origin inside this buffer, in pixels
 * @param baseline y offset of the text baseline inside this buffer, in pixels
 * @throws NullPointerException     if {@code argb} is {@code null}
 * @throws IllegalArgumentException if a dimension, the sample count or an anchor is invalid
 */
public record Raster(int width, int height, int[] argb, float originX, float baseline) {

    /** Maximum value for {@link #width()} and {@link #height()}. */
    public static final int MAX_SIDE = 8192;
    /** Maximum total sample count of one raster. */
    public static final int MAX_PIXELS = 16 * 1024 * 1024;

    public Raster {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("raster dimensions must be positive");
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new IllegalArgumentException("raster side exceeds " + MAX_SIDE);
        }
        long pixels = (long) width * height;
        if (pixels > MAX_PIXELS) {
            throw new IllegalArgumentException("raster exceeds " + MAX_PIXELS + " pixels");
        }
        Objects.requireNonNull(argb, "argb");
        if (argb.length != pixels) {
            throw new IllegalArgumentException("argb length does not match raster dimensions");
        }
        if (!Float.isFinite(originX) || !Float.isFinite(baseline)) {
            throw new IllegalArgumentException("raster anchor must be finite");
        }
        argb = argb.clone();
    }

    @Override
    public int[] argb() {
        return argb.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Raster raster)) {
            return false;
        }
        return width == raster.width && height == raster.height && Float.compare(originX, raster.originX) == 0 && Float.compare(baseline, raster.baseline) == 0 && Arrays.equals(argb, raster.argb);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * Integer.hashCode(width) + Integer.hashCode(height)) + Arrays.hashCode(argb)) + Float.hashCode(originX)) + Float.hashCode(baseline);
    }

    @Override
    public String toString() {
        return "Raster[width=" + width + ", height=" + height + ", originX=" + originX + ", baseline=" + baseline + ']';
    }
}
