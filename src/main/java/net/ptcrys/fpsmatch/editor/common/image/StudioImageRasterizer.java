package net.ptcrys.fpsmatch.editor.common.image;

import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Owner-thread local-image decoding. No filesystem, network, window or GPU owner lives here.
 * One bounded source decode per frame; subsequent misses temporarily use the caller fallback.
 */
public final class StudioImageRasterizer {

    public static final int MAX_SIDE = 512, MAX_RESOURCE_LENGTH = 512, RASTER_VERSION = 1;
    public static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024, MAX_SOURCE_SIDE = 4096, MAX_SOURCE_PIXELS = 4 * 1024 * 1024;

    @FunctionalInterface
    public interface Source {

        InputStream open(String resource) throws IOException;
    }

    public record Key(String resource, int width, int height, int version) {

        public Key {
            Objects.requireNonNull(resource);
            if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE || version != RASTER_VERSION)
                throw new IllegalArgumentException("invalid image raster key");
        }
    }

    /** Original-colour, top-left-origin straight-alpha RGBA8; never a device handle. */
    public record Raster(Key key, byte[] rgba) {

        public Raster {
            Objects.requireNonNull(key);
            Objects.requireNonNull(rgba);
            if (rgba.length != 4L * key.width() * key.height()) throw new IllegalArgumentException("image byte count");
            rgba = rgba.clone();
        }

        @Override
        public byte[] rgba() {
            return rgba.clone();
        }

        public int byteSize() {
            return rgba.length;
        }
    }

    public record Budget(int maxEntries, long maxBytes, int maxFailures) {

        public Budget {
            if (maxEntries < 0 || maxBytes < 0 || maxFailures < 0) throw new IllegalArgumentException("negative image budget");
        }

        public static Budget defaults() {
            return new Budget(128, 8L * 1024 * 1024, 128);
        }
    }

    public record Stats(int entries, long bytes, int negativeEntries, long hits, long negativeHits, long decodes, long failures, long deferred, long evictions, long bypasses) {}

    private final Source source;
    private final Budget budget;
    private final LinkedHashMap<Key, Raster> cache = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Boolean> failed = new LinkedHashMap<>(16, .75f, true);
    private long bytes, hits, negativeHits, decodes, failures, deferred, evictions, bypasses;
    private int remainingDecodes;

    public StudioImageRasterizer(Source source) {
        this(source, Budget.defaults());
    }

    public StudioImageRasterizer(Source source, Budget budget) {
        this.source = Objects.requireNonNull(source);
        this.budget = Objects.requireNonNull(budget);
    }

    public void beginFrame() {
        remainingDecodes = 1;
    }

    public Stats stats() {
        return new Stats(cache.size(), bytes, failed.size(), hits, negativeHits, decodes, failures, deferred, evictions, bypasses);
    }

    /** Reload and renderer removal clear both positive and negative state. */
    public void clear() {
        cache.clear();
        failed.clear();
        bytes = 0;
        remainingDecodes = 0;
    }

    public Optional<Raster> raster(String resource, int width, int height) {
        var key = new Key(resource, width, height, RASTER_VERSION);
        if (resource.isBlank() || resource.length() > MAX_RESOURCE_LENGTH) return Optional.empty();
        var previous = cache.get(key);
        if (previous != null) {
            hits++;
            return Optional.of(previous);
        }
        if (failed.get(resource) != null) {
            negativeHits++;
            return Optional.empty();
        }
        if (remainingDecodes == 0) {
            deferred++;
            return Optional.empty();
        }
        remainingDecodes--;
        decodes++;
        final Raster raster;
        try {
            raster = decode(key);
        } catch (IOException | RuntimeException failure) {
            failures++;
            if (budget.maxFailures() > 0) {
                while (failed.size() >= budget.maxFailures()) {
                    var oldest = failed.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                failed.put(resource, Boolean.TRUE);
            }
            return Optional.empty();
        }
        long weight = 96L + 2L * resource.length() + raster.byteSize();
        if (budget.maxEntries() == 0 || weight > budget.maxBytes()) {
            bypasses++;
            return Optional.of(raster);
        }
        while (!cache.isEmpty() && (cache.size() >= budget.maxEntries() || bytes > budget.maxBytes() - weight)) {
            var oldest = cache.values().iterator();
            var removed = oldest.next();
            oldest.remove();
            bytes -= 96L + 2L * removed.key().resource().length() + removed.byteSize();
            evictions++;
        }
        cache.put(key, raster);
        bytes += weight;
        return Optional.of(raster);
    }

    private Raster decode(Key key) throws IOException {
        final byte[] encoded;
        try (var input = source.open(key.resource())) {
            if (input == null) throw new IOException("image resource is unavailable");
            encoded = input.readNBytes(MAX_SOURCE_BYTES + 1);
            if (encoded.length > MAX_SOURCE_BYTES) throw new IOException("image resource exceeds byte budget");
        }
        // Explicit memory stream avoids ImageIO temporary-file caching and any path access.
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(encoded))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("unsupported image resource");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SOURCE_SIDE || height > MAX_SOURCE_SIDE || (long) width * height > MAX_SOURCE_PIXELS)
                    throw new IOException("image resource exceeds pixel budget");
                var decoded = reader.read(0);
                if (decoded == null) throw new IOException("image resource could not be decoded");
                try {
                    return fit(key, decoded);
                } finally {
                    decoded.flush();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private static Raster fit(Key key, BufferedImage source) {
        var output = new BufferedImage(key.width(), key.height(), BufferedImage.TYPE_INT_ARGB);
        var graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            double scale = Math.max(key.width() / (double) source.getWidth(), key.height() / (double) source.getHeight());
            var transform = AffineTransform.getTranslateInstance((key.width() - source.getWidth() * scale) / 2, (key.height() - source.getHeight() * scale) / 2);
            transform.scale(scale, scale);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        try {
            byte[] rgba = new byte[key.width() * key.height() * 4];
            for (int y = 0, index = 0; y < key.height(); y++) for (int x = 0; x < key.width(); x++) {
                int argb = output.getRGB(x, y);
                rgba[index++] = (byte) (argb >>> 16);
                rgba[index++] = (byte) (argb >>> 8);
                rgba[index++] = (byte) argb;
                rgba[index++] = (byte) (argb >>> 24);
            }
            return new Raster(key, rgba);
        } finally {
            output.flush();
        }
    }
}
