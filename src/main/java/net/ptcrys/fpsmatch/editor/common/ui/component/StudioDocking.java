package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;

import java.util.*;

/**
 * Host-neutral title drag. A gesture captures identities, never callbacks or document data.
 * The host republishes the same clipped controls used for paint and applies at most one result.
 * Empty targets are ordinary regions supplied by the layout; the center is never inferred.
 */
public final class StudioDocking<P> {

    public enum Zone {

        LEFT_TOP("leftTop"),
        LEFT_BOTTOM("leftBottom"),
        RIGHT("right"),
        BOTTOM("bottom");

        private final String key;

        Zone(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Optional<Zone> fromKey(String key) {
            return Arrays.stream(values()).filter(zone -> zone.key.equals(key)).findFirst();
        }
    }

    public record Source<P>(P panel, String controlId, String group) {

        public Source {
            Objects.requireNonNull(panel);
            Objects.requireNonNull(controlId);
            Objects.requireNonNull(group);
        }
    }

    public record Region(Zone zone, Bounds bounds, boolean empty) {

        public Region {
            Objects.requireNonNull(zone);
            Objects.requireNonNull(bounds);
        }
    }

    /** Null target denotes an ordinary title activation; a non-null target denotes a move. */
    public record Completion<P>(P panel, Zone target) {

        public boolean move() {
            return target != null;
        }
    }

    private final double threshold;
    private Object context;
    private List<Source<P>> sources = List.of();
    private List<Region> regions = List.of();
    private Source<P> source;
    private Zone target;
    private double startX, startY, pointerX, pointerY;
    private boolean moved;

    public StudioDocking() {
        this(6);
    }

    public StudioDocking(double threshold) {
        if (!Double.isFinite(threshold) || threshold < 0) throw new IllegalArgumentException("invalid drag threshold");
        this.threshold = threshold;
    }

    public boolean pending() {
        return source != null;
    }

    public boolean moving() {
        return pending() && moved;
    }

    public Optional<Source<P>> source() {
        return Optional.ofNullable(source);
    }

    public Optional<Zone> target() {
        return Optional.ofNullable(target);
    }

    public List<Region> regions() {
        return regions;
    }

    /**
     * Replacing a workspace or any title identity cancels the captured gesture. Geometry alone
     * may change when empty slots appear, without invalidating the source.
     */
    public boolean reconcile(Object context, List<Source<P>> sources, List<Region> regions) {
        Objects.requireNonNull(sources);
        Objects.requireNonNull(regions);
        var unique = new HashSet<String>();
        var panels = new HashSet<P>();
        for (var entry : sources) if (!unique.add(entry.controlId()) || !panels.add(entry.panel())) throw new IllegalArgumentException("duplicate dock source");
        var zones = EnumSet.noneOf(Zone.class);
        for (var region : regions) if (!zones.add(region.zone())) throw new IllegalArgumentException("duplicate dock region");
        boolean cancelled = pending() && (!Objects.equals(this.context, context) || !this.sources.equals(sources) || target != null && !zones.contains(target));
        if (cancelled) cancel();
        this.context = context;
        this.sources = List.copyOf(sources);
        this.regions = List.copyOf(regions);
        if (moving()) target = targetAt(regions, pointerX, pointerY).orElse(null);
        return cancelled;
    }

    /**
     * Only the published title hit can start capture: action buttons, overflow and occluded
     * or clipped titles are deliberately excluded.
     */
    public boolean press(ControlTree tree, double x, double y, int button) {
        if (button != 0 || pending() || tree.topLayer() != 0) return false;
        var hit = tree.hit(x, y).filter(node -> node.enabled() && node.role() == ControlTree.Role.TAB);
        if (hit.isEmpty()) return false;
        var entry = sources.stream().filter(value -> value.controlId().equals(hit.orElseThrow().id())).findFirst();
        if (entry.isEmpty()) return false;
        source = entry.orElseThrow();
        startX = pointerX = x;
        startY = pointerY = y;
        moved = false;
        target = null;
        return true;
    }

    public boolean drag(double x, double y) {
        if (!pending()) return false;
        boolean before = moved;
        Zone previous = target;
        pointerX = x;
        pointerY = y;
        if (Math.hypot(x - startX, y - startY) > threshold) moved = true;
        target = moved ? targetAt(regions, x, y).orElse(null) : null;
        return before != moved || previous != target;
    }

    public Optional<Completion<P>> release(double x, double y, int button) {
        if (!pending() || button != 0) return Optional.empty();
        drag(x, y);
        var result = !moved ? new Completion<>(source.panel(), null) : target == null ? null : new Completion<>(source.panel(), target);
        cancel();
        return Optional.ofNullable(result);
    }

    public boolean cancel() {
        boolean changed = pending();
        source = null;
        target = null;
        moved = false;
        return changed;
    }

    public static Optional<Zone> targetAt(List<Region> regions, double x, double y) {
        return regions.stream().filter(region -> region.bounds().contains(x, y)).map(Region::zone).findFirst();
    }

    /** Unite actual header/body paint rectangles; never extend a target into a neighboring view. */
    public static Bounds regionBounds(Bounds header, Bounds body) {
        int x = Math.min(header.x(), body.x()), y = Math.min(header.y(), body.y());
        return new Bounds(x, y, Math.max(header.x() + header.width(), body.x() + body.width()) - x, Math.max(header.y() + header.height(), body.y() + body.height()) - y);
    }
}
