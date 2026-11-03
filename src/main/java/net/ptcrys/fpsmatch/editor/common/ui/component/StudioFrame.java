package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;

import java.util.*;
import java.util.function.Consumer;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/**
 * A composed, host-neutral frame. Stable IDs bind drawing, hit-testing, focus, raw values and events.
 * ControlTree is the single interaction snapshot; presentation never creates a second hit geometry.
 * Clips nest by intersection and scopes restore their caller, including exceptional exits.
 */
public final class StudioFrame {

    private final List<Node> nodes = new ArrayList<>();
    private final Map<String, Visual> visuals = new HashMap<>();
    private final Map<String, Runnable> actions = new HashMap<>(), blur = new HashMap<>();
    private final Map<String, Consumer<String>> changes = new HashMap<>();
    private final Map<String, String> groups = new HashMap<>(), details = new HashMap<>();
    private Bounds clip, fixedClip, contentClip;
    private StudioFocusNavigation.Region revealRegion;
    private final Map<String, StudioFocusNavigation.Target> focusTargets = new HashMap<>();
    private final List<StudioFocusNavigation.Entry> focusSequence = new ArrayList<>();
    private final Map<String, StudioFocusNavigation.FocusRef> virtualTargets = new HashMap<>();
    private final List<StudioScrollState.Region> scrolls = new ArrayList<>();

    public record Snapshot(List<Node> nodes, Map<String, Visual> visuals, Map<String, Runnable> actions,
                           Map<String, Consumer<String>> changes, Map<String, Runnable> blur, Map<String, String> groups,
                           Map<String, String> details, List<StudioScrollState.Region> scrolls,
                           Map<String, StudioFocusNavigation.Target> focusTargets,
                           List<StudioFocusNavigation.Entry> focusSequence, Map<String, StudioFocusNavigation.FocusRef> virtualTargets) {

        public Snapshot {
            nodes = List.copyOf(nodes);
            visuals = Map.copyOf(visuals);
            actions = Map.copyOf(actions);
            changes = Map.copyOf(changes);
            blur = Map.copyOf(blur);
            groups = Map.copyOf(groups);
            details = Map.copyOf(details);
            scrolls = List.copyOf(scrolls);
            focusTargets = Map.copyOf(focusTargets);
            focusSequence = List.copyOf(focusSequence);
            virtualTargets = Map.copyOf(virtualTargets);
        }

        public Snapshot(List<Node> nodes, Map<String, Visual> visuals, Map<String, Runnable> actions, Map<String, Consumer<String>> changes,
                        Map<String, Runnable> blur, Map<String, String> groups, Map<String, String> details, List<StudioScrollState.Region> scrolls,
                        Map<String, StudioFocusNavigation.Target> focusTargets) {
            this(nodes, visuals, actions, changes, blur, groups, details, scrolls, focusTargets,
                    nodes.stream().map(n -> (StudioFocusNavigation.Entry) new StudioFocusNavigation.Control(n.id())).toList(), Map.of());
        }

        public Snapshot(List<Node> nodes, Map<String, Visual> visuals, Map<String, Runnable> actions,
                        Map<String, Consumer<String>> changes, Map<String, Runnable> blur, Map<String, String> groups,
                        Map<String, String> details, List<StudioScrollState.Region> scrolls) {
            this(nodes, visuals, actions, changes, blur, groups, details, scrolls, Map.of());
        }

        public Snapshot withNodes(List<Node> nodes) {
            return new Snapshot(nodes, visuals, actions, changes, blur, groups, details, scrolls, focusTargets, focusSequence, virtualTargets);
        }

        /** Validate cross references before geometry or editor preparation can be committed. */
        public void validate() {
            var byId = new HashMap<String, Node>();
            for (var node : nodes) if (byId.put(node.id(), node) != null) throw new IllegalArgumentException("duplicate semantic id: " + node.id());
            for (var map : List.of(visuals, actions, changes, blur, groups, details, focusTargets))
                for (var id : map.keySet()) if (!byId.containsKey(id)) throw new IllegalArgumentException("metadata without node: " + id);
            for (var id : changes.keySet()) if (byId.get(id).role() != Role.TEXT_FIELD) throw new IllegalArgumentException("text binding on non-input: " + id);
            for (var entry : focusTargets.entrySet()) if (entry.getValue().region().layer() != byId.get(entry.getKey()).layer())
                throw new IllegalArgumentException("focus region layer differs from node: " + entry.getKey());
            var ranges = new HashMap<String, StudioFocusNavigation.VirtualRange>();
            var controls = new HashSet<String>();
            for (var entry : focusSequence) {
                if (entry instanceof StudioFocusNavigation.VirtualRange range) {
                    if (ranges.put(range.id(), range) != null) throw new IllegalArgumentException("duplicate virtual range: " + range.id());
                } else if (entry instanceof StudioFocusNavigation.Control control) {
                    if (!byId.containsKey(control.id()) || !controls.add(control.id())) throw new IllegalArgumentException("invalid focus control: " + control.id());
                }
            }
            var mounted = new HashSet<StudioFocusNavigation.FocusRef>();
            for (var entry : virtualTargets.entrySet()) {
                var node = byId.get(entry.getKey());
                var ref = entry.getValue();
                var range = ranges.get(ref.rangeId());
                if (node == null || range == null || !mounted.add(ref)) throw new IllegalArgumentException("invalid virtual binding: " + entry.getKey());
                int index = range.source().focusIndex().indexOf(ref.rowKey(), ref.slotKey());
                if (index < 0 || range.source().focusIndex().at(index).slot().role() != node.role() || ref.role() != node.role() || range.layer() != node.layer())
                    throw new IllegalArgumentException("virtual binding differs from source: " + entry.getKey());
            }
        }
    }

    /** Immutable publication candidate; a host validates nodes before replacing its live snapshot. */
    public Snapshot snapshot() {
        return new Snapshot(nodes, visuals, actions, changes, blur, groups, details, scrolls, focusTargets, focusSequence, virtualTargets);
    }

    public void clear() {
        nodes.clear();
        visuals.clear();
        actions.clear();
        blur.clear();
        changes.clear();
        groups.clear();
        details.clear();
        scrolls.clear();
        focusTargets.clear();
        focusSequence.clear();
        virtualTargets.clear();
        clip = null;
        fixedClip = null;
        contentClip = null;
        revealRegion = null;
    }

    public void virtualRange(StudioFocusNavigation.VirtualRange range) {
        focusSequence.add(Objects.requireNonNull(range));
    }

    public void virtualTarget(String nodeId, String rangeId, String rowKey, String slotKey) {
        var node = nodes.stream().filter(n -> n.id().equals(nodeId)).findFirst().orElseThrow(() -> new IllegalArgumentException("virtual binding without node: " + nodeId));
        var ref = new StudioFocusNavigation.FocusRef(rangeId, rowKey, slotKey, null, node.role());
        if (virtualTargets.putIfAbsent(nodeId, ref) != null) throw new IllegalArgumentException("duplicate virtual binding: " + nodeId);
    }

    public List<StudioScrollState.Region> scrolls() {
        return List.copyOf(scrolls);
    }

    public void scroll(StudioScrollState.Region region) {
        scrolls.add(clip == null ? region : region.clipped(clip));
    }

    public List<Node> nodes() {
        return List.copyOf(nodes);
    }

    public Visual visual(String id) {
        return visuals.getOrDefault(id, Visual.of(Kind.LABEL));
    }

    public void visual(String id, Visual visual) {
        visuals.put(id, visual);
    }

    public Map<String, Runnable> actions() {
        return Map.copyOf(actions);
    }

    public Map<String, Runnable> blur() {
        return Map.copyOf(blur);
    }

    public Map<String, Consumer<String>> changes() {
        return Map.copyOf(changes);
    }

    public Map<String, String> groups() {
        return Map.copyOf(groups);
    }

    public Map<String, String> details() {
        return Map.copyOf(details);
    }

    public void clip(Bounds value) {
        clip = value;
        fixedClip = value;
        contentClip = null;
    }

    public Bounds clip() {
        return clip;
    }

    public Scope clipped(Bounds bounds) {
        Bounds previous = clip, previousFixed = fixedClip;
        clip = clip == null ? bounds : clip.intersect(bounds);
        fixedClip = fixedClip == null ? bounds : fixedClip.intersect(bounds);
        return () -> {
            clip = previous;
            fixedClip = previousFixed;
        };
    }

    /**
     * A component-local clip moves with body content. Painting still intersects every clip, while
     * reveal metadata clips the target's content geometry instead of shrinking the fixed aperture.
     */
    public Scope contentClipped(Bounds bounds) {
        Bounds previous = clip, previousContent = contentClip;
        clip = clip == null ? bounds : clip.intersect(bounds);
        contentClip = contentClip == null ? bounds : contentClip.intersect(bounds);
        return () -> {
            clip = previous;
            contentClip = previousContent;
        };
    }

    /** Only the innermost declared body owns a target; closing the scope restores its caller. */
    public Scope revealScope(StudioFocusNavigation.Region region) {
        Objects.requireNonNull(region);
        var previous = revealRegion;
        var previousFixed = fixedClip;
        var previousContent = contentClip;
        revealRegion = new StudioFocusNavigation.Region(region.id(), clip == null ? region.viewport() : region.viewport().intersect(clip), region.offset(), region.maximum(), region.layer(), region.change());
        fixedClip = revealRegion.viewport();
        contentClip = null;
        return () -> {
            revealRegion = previous;
            fixedClip = previousFixed;
            contentClip = previousContent;
        };
    }

    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }

    public void emit(String id, Role role, String name, String value, Bounds bounds, boolean enabled, boolean selected, boolean readOnly, String reason, int layer, Visual visual) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(visual);
        boolean barrier = role == Role.PANEL && switch (visual.kind()) {
            case PANEL, HEADER, TOOLBAR, INLINE_CARD, MENU, DIALOG -> true;
            default -> false;
        };
        var node = new Node(id, role, name, value, bounds, enabled, selected, readOnly, reason, layer, clip == null ? bounds : clip, barrier);
        nodes.add(node);
        visuals.put(id, visual);
        focusSequence.add(new StudioFocusNavigation.Control(id));
        if (revealRegion != null && revealRegion.layer() == layer && StudioFocusNavigation.interactive(node)) {
            var viewport = fixedClip == null ? revealRegion.viewport() : revealRegion.viewport().intersect(fixedClip);
            var target = contentClip == null ? bounds : bounds.intersect(contentClip);
            if (viewport.width() > 0 && viewport.height() > 0 && target.width() > 0 && target.height() > 0) focusTargets.put(id, new StudioFocusNavigation.Target(revealRegion, viewport,
                    new Bounds(target.x(), target.y() + revealRegion.offset(), target.width(), target.height())));
        }
    }

    public void action(String id, Runnable action) {
        actions.put(id, Objects.requireNonNull(action));
    }

    public void input(String id, Consumer<String> change) {
        changes.put(id, Objects.requireNonNull(change));
    }

    public void blur(String id, String group, Runnable commit) {
        blur.put(id, Objects.requireNonNull(commit));
        groups.put(id, group);
    }

    public void detail(String id, String text) {
        details.put(id, text);
    }
}
