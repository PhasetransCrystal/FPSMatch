package net.ptcrys.fpsmatch.editor.common.text.java2d;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;
import net.ptcrys.fpsmatch.editor.common.ui.component.StudioTextLayout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owner-thread Studio adapter. Layout is always logical; density only affects coverage rasters.
 * Default preference is installed Microsoft YaHei UI, then Microsoft YaHei, then JDK Dialog.
 * Every source is explicitly platform-dependent, not an embedded resource font.
 */
public final class StudioJava2dText implements StudioTextLayout.Metrics, AutoCloseable {

    public enum FontSource {
        INSTALLED_SYSTEM,
        JDK_LOGICAL,
        CALLER_PROVIDED
    }

    public record FontProfile(String preferredFamily, FontSource source, List<String> fallbackFamilies) {

        public FontProfile {
            Objects.requireNonNull(preferredFamily);
            Objects.requireNonNull(source);
            fallbackFamilies = List.copyOf(fallbackFamilies);
        }
    }

    public record Budget(int maxShapeEntries, long maxShapeWeight, int maxShapeTextLength,
                         int maxRasterEntries, long maxRasterBytes) {

        public Budget {
            if (maxShapeEntries < 0 || maxShapeWeight < 0 || maxShapeTextLength < 0 || maxRasterEntries < 0 || maxRasterBytes < 0)
                throw new IllegalArgumentException("negative text cache budget");
        }

        public static Budget defaults() {
            return new Budget(1024, 8L * 1024 * 1024, 8192, 256, 8L * 1024 * 1024);
        }
    }

    /** Shape weight conservatively accounts for payload; it is not a JDK heap measurement. */
    public record CacheStats(int shapeEntries, long shapeWeight, int rasterEntries, long rasterBytes,
                             long shapeHits, long shapeMisses, long rasterHits, long rasterMisses,
                             long shapeBypasses, long rasterBypasses, long shapeEvictions, long rasterEvictions,
                             long layoutCalls, long rasterCalls, long generation) {}

    public static final class ShapedLine implements StudioTextLayout.LineGeometry {

        private final Object owner;
        private final long identity;
        private final TextLine line;
        private final TextSize size;

        private ShapedLine(StudioJava2dText owner, TextLine line, TextSize size) {
            this.owner = owner.ownerToken;
            this.line = line;
            this.size = size;
            identity = IDENTITIES.updateAndGet(Math::incrementExact);
        }

        public long identity() {
            return identity;
        }

        public String text() {
            return line.text();
        }

        public TextSize size() {
            return size;
        }

        @Override
        public float advance() {
            return line.advance();
        }

        public float ascent() {
            return line.ascent();
        }

        public float descent() {
            return line.descent();
        }

        public float inkLeft() {
            return line.inkLeft();
        }

        public float inkRight() {
            return line.inkRight();
        }

        public List<FontFaceId> usedFonts() {
            return line.usedFonts();
        }

        @Override
        public float caretX(int index) {
            return line.caretX(line.boundary(index));
        }

        @Override
        public int hitTest(float x) {
            return line.hitTest(x);
        }

        @Override
        public int boundary(int index) {
            return line.boundary(index);
        }

        @Override
        public List<StudioTextLayout.Span> selectionSpans(int anchor, int caret) {
            return line.selectionSpans(anchor, caret).stream().map(s -> new StudioTextLayout.Span(s.x(), s.width())).toList();
        }

        @Override
        public long cacheWeight() {
            return 256L + 96L * line.text().length();
        }
    }

    private record ShapeKey(String text, TextSize size) {}

    private record RasterKey(long identity, float density, float x, float width) {}

    private static final AtomicLong IDENTITIES = new AtomicLong();
    private final Java2dTextBackend backend;
    private final Object ownerToken = new Object();
    private final List<FontFaceId> faces;
    private final FontProfile fontProfile;
    private final Budget budget;
    private final LinkedHashMap<ShapeKey, ShapedLine> shapes = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<RasterKey, Raster> rasters = new LinkedHashMap<>(16, .75f, true);
    private long shapeWeight, rasterBytes, shapeHits, shapeMisses, rasterHits, rasterMisses,
            shapeBypasses, rasterBypasses, shapeEvictions, rasterEvictions, layoutCalls, rasterCalls, generation;
    private boolean closed;

    public StudioJava2dText() {
        this(Budget.defaults());
    }

    public StudioJava2dText(Budget budget) {
        this(new Java2dTextBackend(), budget);
    }

    StudioJava2dText(Java2dTextBackend backend, Budget budget) {
        this(backend, budget, List.of("Microsoft YaHei UI", "Microsoft YaHei"));
    }

    /** Package-private preference seam for deterministic selector tests with physical fixtures. */
    StudioJava2dText(Java2dTextBackend backend, Budget budget, List<String> preferredFamilies) {
        this.backend = Objects.requireNonNull(backend);
        this.budget = Objects.requireNonNull(budget);
        var primaryId = new EditorId("studio", "installed-primary");
        java.util.Optional<InstalledFont> selected = java.util.Optional.empty();
        for (String family : preferredFamilies) {
            selected = backend.registerInstalled(primaryId, family);
            if (selected.isPresent()) break;
        }
        var dialog = backend.registerLogical(new EditorId("studio", "dialog"), "Dialog");
        if (selected.isPresent()) {
            var installed = selected.orElseThrow();
            this.faces = List.of(installed.face(), dialog);
            this.fontProfile = new FontProfile(installed.family(), FontSource.INSTALLED_SYSTEM, List.of("Dialog"));
        } else {
            this.faces = List.of(dialog);
            this.fontProfile = new FontProfile("Dialog", FontSource.JDK_LOGICAL, List.of());
        }
    }

    public StudioJava2dText(Java2dTextBackend backend, List<FontFaceId> faces, Budget budget) {
        this.backend = Objects.requireNonNull(backend);
        this.faces = List.copyOf(faces);
        this.budget = Objects.requireNonNull(budget);
        // Validate configuration before it can become a live measurement provider.
        backend.layout("", this.faces, TextSize.BODY.height, 1f);
        this.fontProfile = new FontProfile(backend.registeredFamily(this.faces.get(0)), FontSource.CALLER_PROVIDED,
                this.faces.subList(1, this.faces.size()).stream().map(backend::registeredFamily).toList());
    }

    public FontProfile fontProfile() {
        return fontProfile;
    }

    public ShapedLine line(String text, TextSize size) {
        requireOpen();
        Objects.requireNonNull(text);
        Objects.requireNonNull(size);
        var key = new ShapeKey(text, size);
        var found = shapes.get(key);
        if (found != null) {
            shapeHits++;
            return found;
        }
        shapeMisses++;
        var result = new ShapedLine(this, backend.layout(text, faces, size.height, 1f), size);
        layoutCalls++;
        long weight = shapeWeight(key, result);
        if (budget.maxShapeEntries() == 0 || text.length() > budget.maxShapeTextLength() || weight > budget.maxShapeWeight()) {
            shapeBypasses++;
            return result;
        }
        while (!shapes.isEmpty() && (shapes.size() >= budget.maxShapeEntries() || shapeWeight + weight > budget.maxShapeWeight())) {
            var entries = shapes.entrySet().iterator();
            var old = entries.next();
            shapeWeight -= shapeWeight(old.getKey(), old.getValue());
            entries.remove();
            shapeEvictions++;
        }
        shapes.put(key, result);
        shapeWeight += weight;
        return result;
    }

    @Override
    public int width(String text, TextSize size) {
        return (int) Math.ceil(line(text, size).advance());
    }

    @Override
    public int lineHeight(TextSize size) {
        return (int) Math.ceil(size.height * 1.5);
    }

    @Override
    public ShapedLine geometry(String text, TextSize size) {
        return line(text, size);
    }

    public Raster raster(ShapedLine line, float density, float windowX, float windowWidth) {
        requireOpen();
        Objects.requireNonNull(line);
        if (line.owner != ownerToken) throw new IllegalArgumentException("line belongs to another text adapter");
        var key = new RasterKey(line.identity(), density, windowX, windowWidth);
        var found = rasters.get(key);
        if (found != null) {
            rasterHits++;
            return found;
        }
        rasterMisses++;
        var result = line.line.rasterize(density, windowX, windowWidth);
        rasterCalls++;
        long bytes = 4L * result.width() * result.height();
        if (budget.maxRasterEntries() == 0 || bytes > budget.maxRasterBytes()) {
            rasterBypasses++;
            return result;
        }
        while (!rasters.isEmpty() && (rasters.size() >= budget.maxRasterEntries() || rasterBytes + bytes > budget.maxRasterBytes())) {
            var entries = rasters.entrySet().iterator();
            var old = entries.next().getValue();
            rasterBytes -= 4L * old.width() * old.height();
            entries.remove();
            rasterEvictions++;
        }
        rasters.put(key, result);
        rasterBytes += bytes;
        return result;
    }

    public CacheStats stats() {
        return new CacheStats(shapes.size(), shapeWeight, rasters.size(), rasterBytes, shapeHits, shapeMisses, rasterHits, rasterMisses, shapeBypasses, rasterBypasses, shapeEvictions, rasterEvictions, layoutCalls, rasterCalls, generation);
    }

    /** Releases all retained CPU results. The adapter is reusable; identities are never recycled. */
    public void clear() {
        shapes.clear();
        rasters.clear();
        shapeWeight = 0;
        rasterBytes = 0;
        generation++;
    }

    @Override
    public void close() {
        clear();
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("text adapter is closed");
    }

    private static long shapeWeight(ShapeKey key, ShapedLine line) {
        return 64L + 2L * key.text().length() + line.cacheWeight();
    }
}
