package net.ptcrys.fpsmatch.editor.common.text.java2d;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Icon;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioIcon;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Owner-thread, offscreen icon coverage rasterizer. No window, GPU object or texture lease is
 * created here. Rasterize at the final physical dimensions; the caller submits texels 1:1, tints
 * white RGB by the requested color, and uses straight-alpha blending.
 */
public final class StudioIconRasterizer {

    public static final int MAX_SIDE = 512;
    public static final int RASTER_VERSION = 1;

    public record Key(Icon icon, int width, int height, int geometryVersion, int rasterVersion) {

        public Key {
            Objects.requireNonNull(icon);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) throw new IllegalArgumentException("icon raster dimensions outside budget");
            if (geometryVersion != StudioIcon.GEOMETRY_VERSION || rasterVersion != RASTER_VERSION) throw new IllegalArgumentException("unsupported icon geometry or raster version");
        }
    }

    /**
     * Row-major, top-left-origin RGBA8. Every RGB byte is 255, including transparent texels;
     * alpha alone holds antialiased coverage. Arrays are copied on construction and access.
     * No tint, position, atlas coordinate or device handle belongs in this reusable CPU value.
     */
    public record Mask(Key key, byte[] rgba) {

        public Mask {
            Objects.requireNonNull(key);
            Objects.requireNonNull(rgba);
            if (rgba.length != Math.multiplyExact(Math.multiplyExact(key.width(), key.height()), 4)) throw new IllegalArgumentException("icon mask byte count");
            rgba = rgba.clone();
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }

        public int width() {
            return key.width();
        }

        public int height() {
            return key.height();
        }

        public int byteSize() {
            return rgba.length;
        }
    }

    /**
     * Limits retained CPU masks only. A valid mask larger than maxBytes is returned uncached;
     * maxSide bounds a single job. GPU storage and returned upload copies need separate budgets.
     */
    public record Budget(int maxEntries, long maxBytes, int maxSide) {

        public Budget {
            if (maxEntries < 0 || maxBytes < 0 || maxSide < 1 || maxSide > MAX_SIDE) throw new IllegalArgumentException("invalid icon raster budget");
        }

        public static Budget defaults() {
            return new Budget(128, 4L * 1024 * 1024, 256);
        }
    }

    public record Stats(int entries, long bytes, long hits, long misses, long rasterizations, long evictions, long bypasses) {}

    private static final Map<Icon, Shape> SHAPES = shapes();
    private final Budget budget;
    private final LinkedHashMap<Key, Mask> cache = new LinkedHashMap<>(16, .75f, true);
    private long bytes, hits, misses, rasterizations, evictions, bypasses;

    public StudioIconRasterizer() {
        this(Budget.defaults());
    }

    public StudioIconRasterizer(Budget budget) {
        this.budget = Objects.requireNonNull(budget);
    }

    public Stats stats() {
        return new Stats(cache.size(), bytes, hits, misses, rasterizations, evictions, bypasses);
    }

    public void clear() {
        cache.clear();
        bytes = 0;
    }

    public Mask mask(Icon icon, int pixelWidth, int pixelHeight) {
        var key = new Key(icon, pixelWidth, pixelHeight, StudioIcon.GEOMETRY_VERSION, RASTER_VERSION);
        if (pixelWidth > budget.maxSide() || pixelHeight > budget.maxSide()) throw new IllegalArgumentException("icon raster exceeds per-job budget");
        var previous = cache.get(key);
        if (previous != null) {
            hits++;
            return previous;
        }
        misses++;
        var result = rasterize(key);
        rasterizations++;
        long weight = weight(result);
        if (budget.maxEntries() == 0 || weight > budget.maxBytes()) {
            bypasses++;
            return result;
        }
        while (!cache.isEmpty() && (cache.size() >= budget.maxEntries() || bytes + weight > budget.maxBytes())) {
            var oldest = cache.entrySet().iterator();
            var removed = oldest.next().getValue();
            oldest.remove();
            bytes -= weight(removed);
            evictions++;
        }
        cache.put(key, result);
        bytes += weight;
        return result;
    }

    private static long weight(Mask mask) {
        return 96L + mask.byteSize();
    }

    private static Mask rasterize(Key key) {
        var image = new BufferedImage(key.width(), key.height(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            graphics.scale(key.width() / (double) StudioIcon.SIZE, key.height() / (double) StudioIcon.SIZE);
            graphics.setColor(Color.WHITE);
            graphics.fill(SHAPES.get(key.icon()));
        } finally {
            graphics.dispose();
        }
        var rgba = new byte[key.width() * key.height() * 4];
        for (int y = 0, index = 0; y < key.height(); y++) for (int x = 0; x < key.width(); x++) {
            rgba[index++] = (byte) 255;
            rgba[index++] = (byte) 255;
            rgba[index++] = (byte) 255;
            rgba[index++] = (byte) (image.getRGB(x, y) >>> 24);
        }
        return new Mask(key, rgba);
    }

    private static Map<Icon, Shape> shapes() {
        var shapes = new EnumMap<Icon, Shape>(Icon.class);
        for (var icon : Icon.values()) {
            var union = new Area();
            for (var shape : StudioIcon.glyph(icon).shapes()) {
                if (shape instanceof StudioIcon.Fill fill) union.add(new Area(new Rectangle2D.Double(fill.x(), fill.y(), fill.width(), fill.height())));
                else if (shape instanceof StudioIcon.Stroke stroke) {
                    var path = new Path2D.Double();
                    var first = stroke.points().get(0);
                    path.moveTo(first.x(), first.y());
                    for (int i = 1; i < stroke.points().size(); i++) {
                        var point = stroke.points().get(i);
                        path.lineTo(point.x(), point.y());
                    }
                    if (stroke.closed()) path.closePath();
                    union.add(new Area(new BasicStroke((float) stroke.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(path)));
                }
            }
            // Union before antialiasing avoids dark seams or doubled coverage where strokes cross.
            shapes.put(icon, union);
        }
        return Map.copyOf(shapes);
    }
}
