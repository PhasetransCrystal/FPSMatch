package net.ptcrys.fpsmatch.editor.common.text.java2d;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One laid out line of text, as produced by {@link Java2dTextBackend#layout}.
 *
 * <p>
 * All positions and sizes are physical pixels. {@link #advance()} is the shaped text advance from
 * {@link TextLayout} and is deliberately <em>not</em> the width of {@link #rasterize()}: the raster
 * is the real inked pixel bounds plus a small bounded padding, because italic and other scripts
 * overhang the advance box.
 * </p>
 *
 * <p>
 * Instances are immutable and safe for concurrent use. Metrics are computed once at construction;
 * the queries that walk the layout synchronize on a private monitor, so no mutable AWT state is
 * shared between threads. Empty text has no {@link TextLayout} at all, because the JDK rejects a zero
 * length string, and is reported as zero metrics with an empty {@link #usedFonts()}.
 * </p>
 *
 * <p>
 * The cluster boundaries computed while shaping the line are kept, so {@link #hitTest(float)}
 * reuses this line's own shaping result and its cached boundaries instead of shaping the text again.
 * Hit testing itself is still delegated to the JDK: {@link TextLayout#hitTestChar} supplies the
 * insertion position and the caret shapes of at most the two adjacent boundaries supply the pixel
 * positions. The cost of those delegated calls is the platform's own and is not characterized here;
 * this class promises no system frame rate and no GPU performance.
 * </p>
 */
public final class TextLine {

    /** Padding kept on each side of the inked pixel bounds, so no bearing is clipped. */
    private static final int PADDING = 2;

    /**
     * Render context shared by every layout of every backend, with the same antialiasing and
     * fractional metrics the raster uses. {@link TextLayout#getPixelBounds} is asked with this exact
     * context, so the measured pixel bounds belong to the same rendering as the drawing.
     */
    private static final FontRenderContext SHARED_FRC = createRenderContext();

    private final Object lock = new Object();
    private final String text;
    private final TextLayout layout;
    private final List<FontFaceId> usedFonts;
    /** Ascending UTF-16 cluster boundaries, {@code [0]} is {@code 0} and the last is the text length. */
    private final int[] clusterBoundaries;
    private final float advance;
    private final float ascent;
    private final float descent;
    private final float inkLeft, inkRight;

    TextLine(String text, TextLayout layout, List<FontFaceId> usedFonts, int[] clusterBoundaries) {
        this.text = text;
        if (text.isEmpty()) {
            if (layout != null) {
                throw new IllegalArgumentException("empty line must not carry a layout");
            }
        } else if (layout == null) {
            throw new NullPointerException("layout");
        }
        this.layout = layout;
        this.usedFonts = List.copyOf(usedFonts);
        this.clusterBoundaries = requireBoundaries(clusterBoundaries, text.length());
        this.advance = layout == null ? 0f : layout.getAdvance();
        this.ascent = layout == null ? 0f : layout.getAscent();
        this.descent = layout == null ? 0f : layout.getDescent();
        var ink = layout == null ? new Rectangle2D.Float() : layout.getBounds();
        this.inkLeft = (float) ink.getMinX();
        this.inkRight = (float) ink.getMaxX();
    }

    /** The exact logical text this line was built from, never a normalized or reordered version. */
    public String text() {
        return text;
    }

    /** Shaped text advance in physical pixels; {@code 0} for empty text. */
    public float advance() {
        return advance;
    }

    /** Distance above the baseline in physical pixels; {@code 0} for empty text. */
    public float ascent() {
        return ascent;
    }

    /** Distance below the baseline in physical pixels; {@code 0} for empty text. */
    public float descent() {
        return descent;
    }

    public float inkLeft() {
        return inkLeft;
    }

    public float inkRight() {
        return inkRight;
    }

    /**
     * The distinct faces actually used, in first-use order.
     *
     * <p>
     * This is the diagnostic evidence for fallback: when no fallback face covers a cluster, the
     * first face is used and appears here, so a caller can detect a missing glyph on the host
     * without inspecting pixels. The list is empty for empty text and immutable.
     * </p>
     */
    public List<FontFaceId> usedFonts() {
        return usedFonts;
    }

    /** Clamps to the preceding cluster boundary found by this line's own shaping pass. */
    public int boundary(int utf16Index) {
        int bounded = Math.max(0, Math.min(text.length(), utf16Index));
        return clusterBoundaries[boundarySlot(bounded)];
    }

    /** Disjoint visual spans from the same logical highlight shape as the shaped line. */
    public List<TextSpan> selectionSpans(int anchor, int caret) {
        int start = boundary(Math.min(anchor, caret)), end = boundary(Math.max(anchor, caret));
        if (start == end || layout == null) return List.of();
        synchronized (lock) {
            var spans = new ArrayList<TextSpan>();
            var path = layout.getLogicalHighlightShape(start, end).getPathIterator(null, 0.01);
            double[] coordinates = new double[6];
            double left = 0, right = 0;
            boolean open = false;
            while (!path.isDone()) {
                int type = path.currentSegment(coordinates);
                if (type == PathIterator.SEG_MOVETO) {
                    if (open && right > left) spans.add(new TextSpan((float) left, (float) (right - left)));
                    left = right = coordinates[0];
                    open = true;
                } else if (type == PathIterator.SEG_LINETO) {
                    left = Math.min(left, coordinates[0]);
                    right = Math.max(right, coordinates[0]);
                } else if (type == PathIterator.SEG_CLOSE) {
                    if (open && right > left) spans.add(new TextSpan((float) left, (float) (right - left)));
                    open = false;
                }
                path.next();
            }
            if (open && right > left) spans.add(new TextSpan((float) left, (float) (right - left)));
            spans.sort(Comparator.comparingDouble(TextSpan::x));
            var merged = new ArrayList<TextSpan>();
            for (var span : spans) {
                if (!merged.isEmpty()) {
                    var previous = merged.get(merged.size() - 1);
                    if (span.x() <= previous.x() + previous.width() + 0.001f) {
                        float edge = Math.max(previous.x() + previous.width(), span.x() + span.width());
                        merged.set(merged.size() - 1, new TextSpan(previous.x(), edge - previous.x()));
                        continue;
                    }
                }
                merged.add(span);
            }
            return List.copyOf(merged);
        }
    }

    /**
     * Caret position for a logical UTF-16 index, in physical pixels.
     *
     * <p>
     * The position comes from the same {@link TextLayout} that {@link #hitTest(float)} uses, so
     * the two are always consistent. Bidi text is not assumed to be monotonic in logical index: an
     * Arabic segment placed after Latin text returns decreasing positions as the index advances.
     * </p>
     *
     * @param utf16Index index in {@code [0, text().length()]}
     * @return x of the caret in the line's pixel coordinate system
     * @throws IndexOutOfBoundsException if the index is outside the text
     * @throws IllegalArgumentException  if the index would split a surrogate pair
     */
    public float caretX(int utf16Index) {
        if (utf16Index < 0 || utf16Index > text.length()) {
            throw new IndexOutOfBoundsException("caret index out of range: " + utf16Index);
        }
        synchronized (lock) {
            if (text.isEmpty()) {
                return 0f;
            }
            if (utf16Index > 0 && utf16Index < text.length() && Character.isLowSurrogate(text.charAt(utf16Index)) && Character.isHighSurrogate(text.charAt(utf16Index - 1))) {
                throw new IllegalArgumentException(
                        "utf16 index splits a surrogate pair: " + utf16Index);
            }
            return caretAt(utf16Index);
        }
    }

    /**
     * Nearest legal character boundary to a pixel position.
     *
     * <p>
     * The insertion position comes from the same {@link TextLayout} as {@link #caretX(int)}
     * ({@link TextLayout#hitTestChar}), is located in the cluster boundaries cached at layout time by
     * binary search, and is then normalized to the nearer of the two adjacent boundaries using their
     * caret positions. Only those two carets are probed, so this class adds no shaping work and no
     * scan over every boundary; the binary search over the cached boundaries grows only with the
     * logarithm of the boundary count, while the cost of the delegated JDK calls is the platform's own
     * and is not characterized here. A surrogate pair or a combining sequence can therefore never be
     * split. Ties resolve to the smaller logical index. Bidi text is handled with pixel positions,
     * never by assuming that logical indices grow monotonically in x.
     * </p>
     *
     * @param x pixel position, read in the same coordinate system as {@link #caretX(int)}
     * @return a legal UTF-16 index in {@code [0, text().length()]}; {@code 0} for empty text
     * @throws IllegalArgumentException if {@code x} is not finite
     */
    public int hitTest(float x) {
        if (!Float.isFinite(x)) {
            throw new IllegalArgumentException("x must be finite");
        }
        synchronized (lock) {
            if (text.isEmpty()) {
                return 0;
            }
            int insertion = layout.hitTestChar(x, 0f).getInsertionIndex();
            if (insertion <= 0) {
                return 0;
            }
            if (insertion >= text.length()) {
                return text.length();
            }
            int slot = boundarySlot(insertion);
            int lower = clusterBoundaries[slot];
            if (lower == insertion) {
                return lower;
            }
            int upper = clusterBoundaries[slot + 1];
            float lowerDistance = Math.abs(caretAt(lower) - x);
            float upperDistance = Math.abs(caretAt(upper) - x);
            return lowerDistance <= upperDistance ? lower : upper;
        }
    }

    /**
     * Slot of the greatest cached cluster boundary that is not greater than {@code index}.
     *
     * @param index UTF-16 index strictly inside the text
     * @return an index into {@link #clusterBoundaries}; the caller uses the following slot as well
     */
    private int boundarySlot(int index) {
        int low = 0;
        int high = clusterBoundaries.length - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (clusterBoundaries[middle] <= index) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /**
     * Validates cached cluster boundaries and copies them defensively.
     *
     * @param boundaries ascending cluster boundaries ending at the text length
     * @param length     text length in UTF-16 units
     * @return a private copy of the boundaries
     */
    private static int[] requireBoundaries(int[] boundaries, int length) {
        if (boundaries == null || boundaries.length == 0) {
            throw new NullPointerException("clusterBoundaries");
        }
        int[] copy = boundaries.clone();
        if (copy[0] != 0 || copy[copy.length - 1] != length) {
            throw new IllegalArgumentException("cluster boundaries must span the text");
        }
        for (int index = 1; index < copy.length; index++) {
            if (copy[index] <= copy[index - 1]) {
                throw new IllegalArgumentException("cluster boundaries must ascend");
            }
        }
        return copy;
    }

    /**
     * Renders the line into a fresh ARGB buffer using the real pixel bounds plus bounded padding.
     *
     * <p>
     * {@link Raster#originX()} and {@link Raster#baseline()} are the layout origin and baseline
     * inside the buffer, so the caller can place a caret or an overlay without re-measuring. Every
     * sample is straight (non-premultiplied) ARGB: the alpha channel carries glyph coverage and the
     * RGB channels carry the ink colour, which is opaque black, so a consumer can composite the
     * raster over any background without fringing. The ink colour is set explicitly with
     * {@link Color#BLACK} because the default paint of {@code BufferedImage.createGraphics()} is
     * white: without it every inked sample, partially covered ones included, would carry white RGB
     * and compositing straight ARGB would produce white fringes. No per-pixel fixup is needed. The
     * buffer is a private copy on every call.
     * </p>
     *
     * @return a new raster; {@code 1x1} and fully transparent for empty text
     * @throws IllegalStateException if the inked bounds exceed the {@link Raster} limits
     */
    public Raster rasterize() {
        synchronized (lock) {
            if (text.isEmpty()) {
                return new Raster(1, 1, new int[1], 0f, 0f);
            }
            Rectangle2D pixels = layout.getPixelBounds(SHARED_FRC, 0f, 0f);
            double left = Math.floor(pixels.getMinX()) - PADDING;
            double top = Math.floor(pixels.getMinY()) - PADDING;
            double right = Math.ceil(pixels.getMaxX()) + PADDING;
            double bottom = Math.ceil(pixels.getMaxY()) + PADDING;
            long width = Math.max(1L, Math.round(right - left));
            long height = Math.max(1L, Math.round(bottom - top));
            if (width > Raster.MAX_SIDE || height > Raster.MAX_SIDE || width * height > Raster.MAX_PIXELS) {
                throw new IllegalStateException(
                        "line raster exceeds raster limits: " + width + "x" + height);
            }
            int bufferWidth = (int) width;
            int bufferHeight = (int) height;
            float originX = (float) -left;
            float baseline = (float) -top;
            BufferedImage image = new BufferedImage(bufferWidth, bufferHeight,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                // The default paint of BufferedImage.createGraphics() is white, which would put white
                // in the RGB channels of every sample; the ink colour has to be set explicitly.
                graphics.setColor(Color.BLACK);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                layout.draw(graphics, originX, baseline);
            } finally {
                graphics.dispose();
            }
            int[] samples = image.getRGB(0, 0, bufferWidth, bufferHeight, null, 0, bufferWidth);
            return new Raster(bufferWidth, bufferHeight, samples, originX, baseline);
        }
    }

    /**
     * Rasterizes a horizontal window of this already shaped line at the requested density.
     * Window coordinates use this line's units (logical pixels when laid out at scale one).
     * No substring is shaped and no full-width image is allocated. Returned anchors and dimensions
     * are physical pixels; padding is always two physical pixels. The JDK may still visit glyphs
     * outside the image while drawing, so this is not a claim of glyph-range CPU submission.
     */
    public Raster rasterize(float density, float windowX, float windowWidth) {
        if (!Float.isFinite(density) || density <= 0f || !Float.isFinite(windowX) || !Float.isFinite(windowWidth) || windowWidth < 0f)
            throw new IllegalArgumentException("invalid raster density or window");
        double physicalLeft = (double) windowX * density, physicalRight = ((double) windowX + windowWidth) * density;
        if (!Double.isFinite(physicalLeft) || !Double.isFinite(physicalRight)) throw new IllegalArgumentException("nonfinite raster window");
        synchronized (lock) {
            if (text.isEmpty() || windowWidth == 0f) return new Raster(1, 1, new int[1], 0f, 0f);
            var transform = AffineTransform.getScaleInstance(density, density);
            var context = new FontRenderContext(transform, true, true);
            Rectangle2D pixels = layout.getPixelBounds(context, 0f, 0f);
            double left = Math.floor(physicalLeft) - PADDING, right = Math.ceil(physicalRight) + PADDING;
            double top = Math.floor(pixels.getMinY()) - PADDING, bottom = Math.ceil(pixels.getMaxY()) + PADDING;
            double width = right - left, height = Math.max(1d, bottom - top);
            if (width < 1d || width > Raster.MAX_SIDE || height > Raster.MAX_SIDE || width * height > Raster.MAX_PIXELS || !Float.isFinite((float) -left) || !Float.isFinite((float) -top))
                throw new IllegalStateException("window raster exceeds raster limits: " + width + "x" + height);
            int bufferWidth = (int) width, bufferHeight = (int) height;
            var image = new BufferedImage(bufferWidth, bufferHeight, BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.BLACK);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                graphics.translate(-left, -top);
                graphics.scale(density, density);
                layout.draw(graphics, 0f, 0f);
            } finally {
                graphics.dispose();
            }
            return new Raster(bufferWidth, bufferHeight, image.getRGB(0, 0, bufferWidth, bufferHeight, null, 0, bufferWidth), (float) -left, (float) -top);
        }
    }

    /** Caret x for one index; caller holds {@link #lock} and has already validated the index. */
    private float caretAt(int utf16Index) {
        Shape[] carets = layout.getCaretShapes(utf16Index);
        if (carets.length == 0) {
            return 0f;
        }
        Rectangle2D box = carets[0].getBounds2D();
        return layout.isLeftToRight() ? (float) box.getMinX() : (float) box.getMaxX();
    }

    /**
     * The render context every line of every backend is shaped with. Antialiasing and fractional
     * metrics are on, matching what {@link #rasterize()} sets on the drawing graphics, so the pixel
     * bounds measured for a raster belong to the same rendering as the drawing itself.
     *
     * @return the one shared, immutable render context
     */
    static FontRenderContext renderContext() {
        return SHARED_FRC;
    }

    /**
     * Builds the shared render context from a throwaway 1x1 image. The probe graphics is always
     * disposed.
     *
     * @return a render context with antialiasing and fractional metrics enabled
     */
    private static FontRenderContext createRenderContext() {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = probe.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            return graphics.getFontRenderContext();
        } finally {
            graphics.dispose();
        }
    }
}
