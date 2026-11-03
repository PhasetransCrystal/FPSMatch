package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.Visual;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.WorkbenchTheme;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Retains compiled pictures independently of callback identities and layout publication.
 * Hover/press/caret only rebuild their own picture; budgets count commands and retained text.
 */
public final class StudioPaintCache {

    private record Key(Node node, Visual visual, WorkbenchTheme theme, boolean hover, boolean focus, boolean pressed, StudioPainter.Input input, long generation) {}

    private record Entry(Key key, StudioCanvas.Picture picture, long weight) {}

    public record Stats(long compilations, long hits, int entries, long weight) {}

    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(64, .75f, true);
    private final StudioPainter painter;
    private final int maximumEntries;
    private final long maximumWeight;
    private long weight, compilations, hits;

    public StudioPaintCache(StudioPainter painter) {
        this(painter, 4096, 262144);
    }

    public StudioPaintCache(StudioPainter painter, int maximumEntries, long maximumWeight) {
        this.painter = Objects.requireNonNull(painter);
        if (maximumEntries < 0 || maximumWeight < 0) throw new IllegalArgumentException("negative paint cache budget");
        this.maximumEntries = maximumEntries;
        this.maximumWeight = maximumWeight;
    }

    public StudioCanvas.Picture picture(Node node, Visual visual, WorkbenchTheme theme, boolean hover, boolean focus, boolean pressed, StudioPainter.Input input, long generation) {
        var key = new Key(node, visual, theme, hover, focus, pressed, input, generation);
        var old = entries.get(node.id());
        if (old != null && old.key().equals(key)) {
            hits++;
            return old.picture();
        }
        // A failed compiler must not evict the last usable picture.
        var picture = painter.compile(node, visual, theme, hover, focus, pressed, input);
        compilations++;
        long cost = picture.weight() + node.id().length() + node.name().length() + node.value().length() + node.reason().length();
        for (String line : visual.lines()) cost += line.length();
        if (input != null) cost += input.text().length();
        if (maximumEntries > 0 && cost <= maximumWeight) {
            if (old != null) {
                entries.remove(node.id());
                weight -= old.weight();
            }
            while (!entries.isEmpty() && (entries.size() >= maximumEntries || weight > maximumWeight - cost)) {
                var first = entries.entrySet().iterator();
                var removed = first.next();
                weight -= removed.getValue().weight();
                first.remove();
            }
            entries.put(node.id(), new Entry(key, picture, cost));
            weight += cost;
        }
        // An oversized replacement is usable for this frame without evicting a smaller
        // cached state of the same control. Exact key equality still prevents stale hits.
        return picture;
    }

    public void retain(Set<String> ids) {
        for (Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!ids.contains(entry.getKey())) {
                weight -= entry.getValue().weight();
                iterator.remove();
            }
        }
    }

    public void clear() {
        entries.clear();
        weight = 0;
    }

    public Stats stats() {
        return new Stats(compilations, hits, entries.size(), weight);
    }
}
