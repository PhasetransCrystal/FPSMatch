package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.TextSize;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Owner-thread measurement contract for Studio composition and its host paint adapter.
 * Sizes are logical Studio pixels; the host applies its density transform once.
 */
public final class StudioTextLayout {

    /** Optional whole-line shaping geometry; all positions are logical pixels. */
    public interface LineGeometry {

        float advance();

        float caretX(int utf16Index);

        int hitTest(float x);

        int boundary(int utf16Index);

        List<Span> selectionSpans(int anchor, int caret);

        long cacheWeight();
    }

    public record Span(float x, float width) {}

    public interface Metrics {

        int width(String text, TextSize size);

        default LineGeometry geometry(String text, TextSize size) {
            return null;
        }

        default int lineHeight(TextSize size) {
            return (int) Math.ceil(size.height * 1.5);
        }

        /**
         * Cumulative logical widths at Unicode code point boundaries, including initial zero.
         * Return codePointCount + 1 entries, each equal to width of that complete prefix;
         * apply pixel rounding to the cumulative advance, not to individual glyph advances.
         * Hosts should override with one shaping/measurement pass. The compatibility fallback
         * preserves contextual prefix measurement but may scan quadratic total text.
         */
        default int[] prefixAdvances(String text, TextSize size) {
            int[] advances = new int[text.codePointCount(0, text.length()) + 1];
            for (int slot = 1, index = 0; slot < advances.length; slot++) {
                index += Character.charCount(text.codePointAt(index));
                advances[slot] = width(text.substring(0, index), size);
            }
            return advances;
        }
    }

    public record Lines(List<String> lines, int width, int height, int lineHeight, boolean overflow) {

        public Lines {
            lines = List.copyOf(lines);
        }
    }

    /** Weight is a conservative retained-payload estimate, not a JVM heap measurement. */
    public record CacheBudget(int maxEntries, long maxWeight, int maxTextLength) {

        public CacheBudget {
            if (maxEntries < 0 || maxWeight < 0 || maxTextLength < 0) throw new IllegalArgumentException("negative cache budget");
        }

        public static CacheBudget defaults() {
            return new CacheBudget(2048, 1_048_576, 8192);
        }
    }

    /** Counters are cumulative; clearing or changing generation releases retained values only. */
    public record CacheStats(int entries, long weight, long hits, long misses, long bypasses, long evictions, long generation) {}

    private enum Operation {
        WIDTH,
        HEIGHT,
        WRAP,
        ELLIPSIS,
        LINE
    }

    private record Key(Operation operation, String text, TextSize size, int width) {}

    private record Cached(Object value, long weight) {}

    private final Metrics metrics;
    private final CacheBudget budget;
    private final LinkedHashMap<Key, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private long generation, retainedWeight, hits, misses, bypasses, evictions;
    private int bypassDepth;

    public StudioTextLayout(Metrics metrics) {
        this(metrics, CacheBudget.defaults());
    }

    public StudioTextLayout(Metrics metrics, CacheBudget budget) {
        this.metrics = Objects.requireNonNull(metrics);
        this.budget = Objects.requireNonNull(budget);
    }

    public long generation() {
        return generation;
    }

    /** Call on font/resource reload even if the host retains the same font object. */
    public void generation(long value) {
        if (generation != value) {
            generation = value;
            clearCache();
        }
    }

    public void clearCache() {
        cache.clear();
        retainedWeight = 0;
    }

    public CacheStats cacheStats() {
        return new CacheStats(cache.size(), retainedWeight, hits, misses, bypasses, evictions, generation);
    }

    /** The same scale used by StudioRenderer: TextSize.height / native font line height. */
    public static StudioTextLayout scaled(ToIntFunction<String> nativeWidth, int nativeLineHeight) {
        Objects.requireNonNull(nativeWidth);
        if (nativeLineHeight <= 0) throw new IllegalArgumentException("font line height must be positive");
        return new StudioTextLayout((text, size) -> (int) Math.ceil(nativeWidth.applyAsInt(text) * (double) size.height / nativeLineHeight));
    }

    public int width(String value, TextSize size) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(size);
        return cached(new Key(Operation.WIDTH, value, size, 0), () -> {
            int width = metrics.width(value, size);
            if (width < 0) throw new IllegalArgumentException("negative font measurement");
            return width;
        }, ignored -> 4);
    }

    public int lineHeight(TextSize size) {
        Objects.requireNonNull(size);
        return cached(new Key(Operation.HEIGHT, "", size, 0), () -> {
            int height = metrics.lineHeight(size);
            if (height < size.height) throw new IllegalArgumentException("line height smaller than glyph height");
            return height;
        }, ignored -> 4);
    }

    public StudioTextLine line(String value, TextSize size) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(size);
        return cached(new Key(Operation.LINE, value, size, 0), () -> {
            var geometry = metrics.geometry(value, size);
            if (geometry != null) return new StudioTextLine(value, size, lineHeight(size), generation, geometry);
            int count = value.codePointCount(0, value.length());
            int[] boundaries = new int[count + 1];
            int[] advances = Objects.requireNonNull(metrics.prefixAdvances(value, size), "null font prefix advances");
            if (advances.length != count + 1 || advances[0] != 0)
                throw new IllegalArgumentException("font prefix advances must include zero and every code point boundary");
            for (int slot = 1, index = 0; slot <= count; slot++) {
                index += Character.charCount(value.codePointAt(index));
                boundaries[slot] = index;
                if (advances[slot] < 0) throw new IllegalArgumentException("negative font prefix advance");
            }
            return new StudioTextLine(value, size, lineHeight(size), generation, boundaries, advances);
        }, StudioTextLine::cacheWeight);
    }

    /**
     * Hard newlines are retained; words wrap at locale-neutral line boundaries.
     * A word wider than the column breaks at character boundaries, never UTF-16 halves.
     */
    public Lines wrap(String value, int maximumWidth, TextSize size) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(size);
        if (maximumWidth < 0) throw new IllegalArgumentException("negative text width");
        return cached(new Key(Operation.WRAP, value, size, maximumWidth), () -> wrapUncached(value, maximumWidth, size),
                lines -> 40L + lines.lines().stream().mapToLong(line -> 32L + 2L * line.length()).sum());
    }

    private Lines wrapUncached(String value, int maximumWidth, TextSize size) {
        var output = new ArrayList<String>();
        for (String paragraph : value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (paragraph.isEmpty() || maximumWidth == 0) {
                output.add(paragraph);
                continue;
            }
            var characters = BreakIterator.getCharacterInstance(Locale.ROOT);
            var words = BreakIterator.getLineInstance(Locale.ROOT);
            characters.setText(paragraph);
            words.setText(paragraph);
            int start = 0;
            while (start < paragraph.length()) {
                int end = start, next = characters.following(start);
                while (next != BreakIterator.DONE && width(paragraph.substring(start, next), size) <= maximumWidth) {
                    end = next;
                    next = characters.next();
                }
                if (end == start) end = characters.following(start);
                else if (end < paragraph.length() && !words.isBoundary(end)) {
                    int boundary = words.preceding(end);
                    if (boundary > start) end = boundary;
                }
                output.add(paragraph.substring(start, end).stripTrailing());
                start = end;
                while (start < paragraph.length() && Character.isWhitespace(paragraph.codePointAt(start)))
                    start += Character.charCount(paragraph.codePointAt(start));
            }
        }
        int width = output.stream().mapToInt(line -> width(line, size)).max().orElse(0);
        int lineHeight = lineHeight(size);
        return new Lines(output, width, Math.multiplyExact(output.size(), lineHeight), lineHeight, width > maximumWidth);
    }

    /** Paint adapters may use this for single-line overflow without a second font heuristic. */
    public String ellipsize(String value, int maximumWidth, TextSize size) {
        Objects.requireNonNull(value);
        Objects.requireNonNull(size);
        if (maximumWidth < 0) throw new IllegalArgumentException("negative text width");
        return cached(new Key(Operation.ELLIPSIS, value, size, maximumWidth), () -> ellipsizeUncached(value, maximumWidth, size),
                result -> 24L + 2L * result.length());
    }

    private String ellipsizeUncached(String value, int maximumWidth, TextSize size) {
        if (width(value, size) <= maximumWidth) return value;
        String suffix = "…";
        if (width(suffix, size) > maximumWidth) return "";
        var characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(value);
        int end = 0;
        for (int next = characters.next(); next != BreakIterator.DONE; next = characters.next()) {
            if (width(value.substring(0, next) + suffix, size) > maximumWidth) break;
            end = next;
        }
        return value.substring(0, end) + suffix;
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(Key key, Supplier<T> compute, ToLongFunction<T> weight) {
        if (bypassDepth > 0 || budget.maxEntries() == 0 || minimumWeight(key) > budget.maxWeight() || key.text().length() > budget.maxTextLength()) {
            bypasses++;
            bypassDepth++;
            try {
                return compute.get();
            } finally {
                bypassDepth--;
            }
        }
        var existing = cache.get(key);
        if (existing != null) {
            hits++;
            return (T) existing.value();
        }
        misses++;
        T value = compute.get();
        long retained = 64L + 2L * key.text().length() + weight.applyAsLong(value);
        if (retained > budget.maxWeight()) {
            bypasses++;
            return value;
        }
        while (!cache.isEmpty() && (cache.size() >= budget.maxEntries() || retainedWeight + retained > budget.maxWeight())) {
            var oldest = cache.entrySet().iterator();
            var entry = oldest.next();
            retainedWeight -= entry.getValue().weight();
            oldest.remove();
            evictions++;
        }
        cache.put(key, new Cached(value, retained));
        retainedWeight += retained;
        return value;
    }

    private static long minimumWeight(Key key) {
        long base = 64L + 2L * key.text().length();
        return base + switch (key.operation()) {
            case WIDTH, HEIGHT -> 4L;
            case WRAP -> 40L;
            case ELLIPSIS -> 24L;
            case LINE -> 48L + 2L * key.text().length() + 8L * (key.text().codePointCount(0, key.text().length()) + 1L);
        };
    }
}
