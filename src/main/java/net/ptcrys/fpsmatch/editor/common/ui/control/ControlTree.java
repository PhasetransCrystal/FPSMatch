package net.ptcrys.fpsmatch.editor.common.ui.control;

import net.ptcrys.fpsmatch.editor.common.ui.input.EditSessionStore;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;

import java.util.*;

/** Retained semantic identities and edit state; a published frame is also the hit-test snapshot. */
public final class ControlTree {

    public enum Role {
        BUTTON,
        TAB,
        TREE_ITEM,
        TEXT_FIELD,
        LABEL,
        PANEL
    }

    public record Bounds(int x, int y, int width, int height) {

        public Bounds {
            if (width < 0 || height < 0) throw new IllegalArgumentException("negative control size");
        }

        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + width && py < y + height;
        }

        public Bounds intersect(Bounds other) {
            int left = Math.max(x, other.x), top = Math.max(y, other.y);
            return new Bounds(left, top, Math.max(0, Math.min(x + width, other.x + other.width) - left), Math.max(0, Math.min(y + height, other.y + other.height) - top));
        }
    }

    /**
     * Opaque surfaces block lower painted controls at the same layer. Decorative geometry uses
     * pointerBarrier=false; it does not create a second hit geometry or a keyboard focus target.
     */
    public record Node(String id, Role role, String name, String value, Bounds bounds,
                       boolean enabled, boolean selected, boolean readOnly, String reason, int layer, Bounds clip,
                       boolean pointerBarrier) {

        public Node(String id, Role role, String name, String value, Bounds bounds,
                    boolean enabled, boolean selected, boolean readOnly, String reason, int layer, Bounds clip) {
            this(id, role, name, value, bounds, enabled, selected, readOnly, reason, layer, clip, role == Role.PANEL);
        }

        public Node(String id, Role role, String name, String value, Bounds bounds,
                    boolean enabled, boolean selected, boolean readOnly, String reason, int layer) {
            this(id, role, name, value, bounds, enabled, selected, readOnly, reason, layer, bounds, role == Role.PANEL);
        }

        public Node {
            Objects.requireNonNull(id);
            Objects.requireNonNull(role);
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            Objects.requireNonNull(bounds);
            Objects.requireNonNull(reason);
            Objects.requireNonNull(clip);
        }

        public Bounds visibleBounds() {
            return bounds.intersect(clip);
        }

        public boolean focusable() {
            var visible = visibleBounds();
            return visible.width > 0 && visible.height > 0 && role != Role.LABEL && role != Role.PANEL;
        }
    }

    /** Geometry is computed once per publication and shared by rendering and pointer routing. */
    public record Geometry(Node node, Bounds visibleBounds, int paintIndex, boolean focusable) {}

    /** Cumulative operation counts, independent of frame rate and wall-clock timing. */
    public record Work(long geometryBuilds, long indexedLookups, long pointerCandidatesVisited) {}

    public static final class Frame {

        final List<Node> frame, focusOrder, interactiveOrder;
        final List<Geometry> visible, pointerOrder;
        final Map<String, Geometry> byId;
        final Map<String, Integer> focusIndices;
        final int topLayer;

        private Frame(List<Node> frame, List<Geometry> visible, Map<String, Geometry> byId,
                      List<Node> focusOrder, Map<String, Integer> focusIndices, List<Node> interactiveOrder,
                      List<Geometry> pointerOrder, int topLayer) {
            this.frame = List.copyOf(frame);
            this.visible = List.copyOf(visible);
            this.byId = Map.copyOf(byId);
            this.focusOrder = List.copyOf(focusOrder);
            this.focusIndices = Map.copyOf(focusIndices);
            this.interactiveOrder = List.copyOf(interactiveOrder);
            this.pointerOrder = List.copyOf(pointerOrder);
            this.topLayer = topLayer;
        }

        public List<Node> nodes() {
            return frame;
        }

        public List<Node> snapshot() {
            return frame;
        }

        public List<Geometry> visibleGeometry() {
            return visible;
        }

        public List<Node> focusOrder() {
            return focusOrder;
        }

        public List<Node> interactiveOrder() {
            return interactiveOrder;
        }

        public int topLayer() {
            return topLayer;
        }

        public Optional<Geometry> geometry(String id) {
            return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
        }

        public Optional<Node> node(String id) {
            return geometry(id).map(Geometry::node);
        }

        public Optional<Node> hit(double x, double y) {
            for (int i = pointerOrder.size() - 1; i >= 0; i--) {
                var target = pointerOrder.get(i);
                if (target.visibleBounds().contains(x, y)) return target.focusable() ? Optional.of(target.node()) : Optional.empty();
            }
            return Optional.empty();
        }
    }

    private final EditSessionStore sessions;
    private Frame published = new Frame(List.of(), List.of(), Map.of(), List.of(), Map.of(), List.of(), List.of(), 0);
    private int focusIndex = -1;
    private long geometryBuilds, indexedLookups, pointerCandidatesVisited;

    public ControlTree() {
        this(new EditSessionStore());
    }

    public ControlTree(EditSessionStore sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    public EditSessionStore sessions() {
        return sessions;
    }

    public Frame frame() {
        return published;
    }

    public SingleLineText text(String id, String initial) {
        return sessions.synchronize("", id, initial);
    }

    public void publish(List<Node> nodes) {
        commit(prepare(nodes));
    }

    /** Builds all geometry without touching live focus, editing state or the published frame. */
    public Frame prepare(List<Node> nodes) {
        Set<String> ids = new HashSet<>();
        var frame = new ArrayList<Node>(nodes.size());
        for (Node node : nodes) {
            if (!ids.add(node.id())) throw new IllegalArgumentException("duplicate semantic id: " + node.id());
            frame.add(node);
        }
        // Construction can append base panels after drawers. Drawing and hit testing must
        // consume the same stable back-to-front layer order, including non-interactive panels.
        frame.sort(Comparator.comparingInt(Node::layer));
        int topLayer = frame.isEmpty() ? 0 : frame.get(frame.size() - 1).layer();
        var byId = new HashMap<String, Geometry>();
        var visible = new ArrayList<Geometry>();
        var pointerOrder = new ArrayList<Geometry>();
        var focusOrder = new ArrayList<Node>();
        var focusIndices = new HashMap<String, Integer>();
        var interactiveOrder = new ArrayList<Node>();
        for (int i = 0; i < frame.size(); i++) {
            Node node = frame.get(i);
            Bounds bounds = node.visibleBounds();
            boolean hasArea = bounds.width() > 0 && bounds.height() > 0;
            boolean interactive = node.role() != Role.LABEL && node.role() != Role.PANEL;
            var geometry = new Geometry(node, bounds, i, hasArea && interactive);
            byId.put(node.id(), geometry);
            if (hasArea) visible.add(geometry);
            if (node.layer() != topLayer) continue;
            // Disabled controls remain focusable so their reason can be inspected.
            if (geometry.focusable()) {
                focusIndices.put(node.id(), focusOrder.size());
                focusOrder.add(node);
            }
            if (interactive && node.bounds().width() > 0 && node.bounds().height() > 0) interactiveOrder.add(node);
            if (hasArea && (interactive || node.pointerBarrier())) pointerOrder.add(geometry);
        }
        return new Frame(frame, visible, byId, focusOrder, focusIndices, interactiveOrder, pointerOrder, topLayer);
    }

    /** Owner-thread publication of already validated immutable geometry; no callbacks run. */
    public void commit(Frame next) {
        Objects.requireNonNull(next);
        String focusedId = focusIndex < 0 ? null : published.focusOrder.get(focusIndex).id();
        focusIndex = focusedId == null ? -1 : next.focusIndices.getOrDefault(focusedId, -1);
        published = next;
        geometryBuilds += next.frame.size();
    }

    public List<Node> snapshot() {
        return published.frame;
    }

    public List<Geometry> visibleGeometry() {
        return published.visible;
    }

    /** Top-layer visible keyboard targets in paint order. */
    public List<Node> focusOrder() {
        return published.focusOrder;
    }

    /** Top-layer controls with original area, including clipped-out keyboard reveal targets. */
    public List<Node> interactiveOrder() {
        return published.interactiveOrder;
    }

    public Optional<Geometry> geometry(String id) {
        indexedLookups++;
        return id == null ? Optional.empty() : Optional.ofNullable(published.byId.get(id));
    }

    public Optional<Node> node(String id) {
        return geometry(id).map(Geometry::node);
    }

    public Optional<Node> focused() {
        return focusIndex < 0 ? Optional.empty() : Optional.of(published.focusOrder.get(focusIndex));
    }

    public String focusedId() {
        return focusIndex < 0 ? null : published.focusOrder.get(focusIndex).id();
    }

    public void focus(String id) {
        indexedLookups++;
        focusIndex = id == null ? -1 : published.focusIndices.getOrDefault(id, -1);
    }

    public int topLayer() {
        return published.topLayer;
    }

    public Work work() {
        return new Work(geometryBuilds, indexedLookups, pointerCandidatesVisited);
    }

    public Optional<Node> hit(double x, double y) {
        var targets = published.pointerOrder;
        for (int i = targets.size() - 1; i >= 0; i--) {
            Geometry geometry = targets.get(i);
            pointerCandidatesVisited++;
            if (geometry.visibleBounds().contains(x, y)) {
                return geometry.focusable() ? Optional.of(geometry.node()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Routes compound input such as a scroll viewport through its published paint owner.
     * Coordinates may lie in the owner's larger viewport; later controls and opaque surfaces
     * still occlude it, while labels and explicitly transparent decorations do not.
     */
    public boolean pointerExposed(String ownerId, double x, double y) {
        Frame current = published;
        indexedLookups++;
        Geometry owner = ownerId == null ? null : current.byId.get(ownerId);
        if (owner == null || owner.node().layer() != current.topLayer) return false;
        for (int i = current.pointerOrder.size() - 1; i >= 0; i--) {
            Geometry geometry = current.pointerOrder.get(i);
            if (geometry.paintIndex() <= owner.paintIndex()) break;
            pointerCandidatesVisited++;
            if (geometry.visibleBounds().contains(x, y)) return false;
        }
        return true;
    }

    public void cycleFocus(boolean backwards) {
        Frame current = published;
        int size = current.focusOrder.size();
        if (size == 0) {
            focusIndex = -1;
            return;
        }
        int index = focusIndex;
        focusIndex = index < 0 ? (backwards ? size - 1 : 0) : Math.floorMod(index + (backwards ? -1 : 1), size);
    }

    public void clearText() {
        sessions.clear();
        focusIndex = -1;
    }

    public void removeTextPrefix(String prefix) {
        sessions.releasePrefix("", prefix);
    }
}
