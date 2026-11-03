package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Bounds;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Node;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Reveal metadata supplements keyboard order; ControlTree alone owns actual focus and hit geometry. */
public final class StudioFocusNavigation {

    private StudioFocusNavigation() {}

    public sealed interface Entry permits Control, VirtualRange {}

    public record Control(String id) implements Entry {

        public Control {
            Objects.requireNonNull(id);
        }
    }

    /** Logical identity. Virtual identities deliberately have no invented mounted node ID. */
    public record FocusRef(String rangeId, String rowKey, String slotKey, String nodeId, Role role) {

        public FocusRef {
            Objects.requireNonNull(role);
            if (rangeId == null) {
                Objects.requireNonNull(nodeId);
                if (rowKey != null || slotKey != null) throw new IllegalArgumentException("ordinary focus has row keys");
            } else {
                Objects.requireNonNull(rowKey);
                Objects.requireNonNull(slotKey);
                if (nodeId != null) throw new IllegalArgumentException("virtual focus has a node ID");
            }
        }

        public boolean virtual() {
            return rangeId != null;
        }
    }

    public record RevealRequest(String scope, long scopeEpoch, long sequenceRevision, long requestSerial, FocusRef target, int rowIndex) {

        public RevealRequest {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(target);
        }

        public String rangeId() {
            return target.rangeId();
        }

        public String rowKey() {
            return target.rowKey();
        }

        public String slotKey() {
            return target.slotKey();
        }
    }

    public record VirtualRange(String id, int layer, Bounds viewport, StudioRowSource<?> source, Consumer<RevealRequest> reveal) implements Entry {

        public VirtualRange {
            Objects.requireNonNull(id);
            Objects.requireNonNull(viewport);
            Objects.requireNonNull(source);
            Objects.requireNonNull(reveal);
        }
    }

    /** Ordered ordinary controls and indexed virtual ranges. Preparation never walks all rows. */
    public static final class Sequence {

        private record Part(int start, int size, FocusRef control, VirtualRange range) {}

        private record RangeSignature(String id, int layer, long revision, int size) {}

        private final List<Part> parts;
        private final Map<String, Part> ranges;
        private final Map<FocusRef, Integer> ordinary;
        private final Map<String, FocusRef> byNode;
        private final Map<FocusRef, String> mounted;
        private final List<Object> signature;
        private final int size;
        private final boolean declaredVirtual;

        private Sequence(List<Part> parts, Map<String, Part> ranges, Map<FocusRef, Integer> ordinary, Map<String, FocusRef> byNode, Map<FocusRef, String> mounted, List<Object> signature, int size, boolean declaredVirtual) {
            this.parts = List.copyOf(parts);
            this.ranges = Map.copyOf(ranges);
            this.ordinary = Map.copyOf(ordinary);
            this.byNode = Map.copyOf(byNode);
            this.mounted = Map.copyOf(mounted);
            this.signature = List.copyOf(signature);
            this.size = size;
            this.declaredVirtual = declaredVirtual;
        }

        public static Sequence prepare(ControlTree tree, StudioFrame.Snapshot frame) {
            var eligible = Order.prepare(tree, frame.focusTargets());
            var parts = new ArrayList<Part>();
            var ranges = new HashMap<String, Part>();
            var ordinary = new HashMap<FocusRef, Integer>();
            var byNode = new HashMap<String, FocusRef>();
            var mounted = new HashMap<FocusRef, String>();
            var signature = new ArrayList<Object>();
            boolean declared = frame.focusSequence().stream().anyMatch(e -> e instanceof VirtualRange);
            List<Entry> entries = declared ? frame.focusSequence() : eligible.candidates().stream().map(n -> (Entry) new Control(n.id())).toList();
            int count = 0;
            for (var entry : entries) {
                if (entry instanceof Control control) {
                    if (frame.virtualTargets().containsKey(control.id()) || eligible.indexOf(control.id()) < 0) continue;
                    var node = tree.node(control.id()).orElseThrow();
                    var ref = new FocusRef(null, null, null, node.id(), node.role());
                    if (ordinary.put(ref, count) != null) throw new IllegalArgumentException("duplicate ordinary focus: " + node.id());
                    parts.add(new Part(count, 1, ref, null));
                    count = Math.addExact(count, 1);
                    byNode.put(node.id(), ref);
                    mounted.put(ref, node.id());
                    signature.add(ref);
                } else if (entry instanceof VirtualRange range) {
                    if (range.layer() != tree.topLayer() || range.viewport().width() == 0 || range.viewport().height() == 0) continue;
                    int length = range.source().focusIndex().size();
                    var part = new Part(count, length, null, range);
                    if (ranges.put(range.id(), part) != null) throw new IllegalArgumentException("duplicate virtual range: " + range.id());
                    if (length > 0) parts.add(part);
                    count = Math.addExact(count, length);
                    signature.add(new RangeSignature(range.id(), range.layer(), range.source().revision(), length));
                }
            }
            for (var entry : frame.virtualTargets().entrySet()) {
                var ref = entry.getValue();
                if (!ranges.containsKey(ref.rangeId())) continue;
                byNode.put(entry.getKey(), ref);
                if (mounted.put(ref, entry.getKey()) != null) throw new IllegalArgumentException("duplicate mounted virtual target: " + ref);
            }
            return new Sequence(parts, ranges, ordinary, byNode, mounted, signature, count, declared);
        }

        public int size() {
            return size;
        }

        public boolean hasVirtualRanges() {
            return declaredVirtual;
        }

        public boolean sameRevision(Sequence other) {
            return signature.equals(other.signature);
        }

        public FocusRef refForNode(String nodeId) {
            return nodeId == null ? null : byNode.get(nodeId);
        }

        public String mountedId(FocusRef ref) {
            return ref == null ? null : mounted.get(ref);
        }

        public VirtualRange range(FocusRef ref) {
            var part = ref == null || !ref.virtual() ? null : ranges.get(ref.rangeId());
            return part == null ? null : part.range();
        }

        public int indexOf(FocusRef ref) {
            if (ref == null) return -1;
            if (!ref.virtual()) return ordinary.getOrDefault(ref, -1);
            var part = ranges.get(ref.rangeId());
            if (part == null) return -1;
            int local = part.range().source().focusIndex().indexOf(ref.rowKey(), ref.slotKey());
            if (local < 0) return -1;
            var target = part.range().source().focusIndex().at(local);
            return target.slot().role() == ref.role() ? part.start() + local : -1;
        }

        public int rowIndex(FocusRef ref) {
            var range = range(ref);
            return range == null ? -1 : range.source().indexOf(ref.rowKey());
        }

        public FocusRef at(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            int low = 0, high = parts.size() - 1;
            while (low < high) {
                int middle = (low + high + 1) >>> 1;
                if (parts.get(middle).start() <= index) low = middle;
                else high = middle - 1;
            }
            var part = parts.get(low);
            if (part.control() != null) return part.control();
            var target = part.range().source().focusIndex().at(index - part.start());
            return new FocusRef(part.range().id(), target.rowKey(), target.slot().key(), null, target.slot().role());
        }

        public FocusRef next(FocusRef anchor, boolean backwards) {
            if (size == 0) return null;
            int index = indexOf(anchor);
            return at(index < 0 ? (backwards ? size - 1 : 0) : Math.floorMod(index + (backwards ? -1 : 1), size));
        }
    }

    /** Immutable keyboard order prepared for one published tree and its reveal metadata. */
    public static final class Order {

        private final List<Node> candidates;
        private final Map<String, Integer> indices;

        private Order(List<Node> candidates, Map<String, Integer> indices) {
            this.candidates = List.copyOf(candidates);
            this.indices = Map.copyOf(indices);
        }

        public static Order prepare(ControlTree tree, Map<String, Target> targets) {
            Objects.requireNonNull(tree);
            Objects.requireNonNull(targets);
            var candidates = new ArrayList<Node>();
            var indices = new HashMap<String, Integer>();
            for (Node node : tree.interactiveOrder()) {
                if (tree.geometry(node.id()).orElseThrow().focusable() || revealable(node, targets.get(node.id()))) {
                    indices.put(node.id(), candidates.size());
                    candidates.add(node);
                }
            }
            return new Order(candidates, indices);
        }

        public List<Node> candidates() {
            return candidates;
        }

        public int size() {
            return candidates.size();
        }

        public int indexOf(String id) {
            return id == null ? -1 : indices.getOrDefault(id, -1);
        }

        /** A missing anchor enters at the first/last candidate; an existing anchor wraps. */
        public Optional<Node> next(String id, boolean backwards) {
            if (candidates.isEmpty()) return Optional.empty();
            int index = indexOf(id);
            int next = index < 0 ? (backwards ? candidates.size() - 1 : 0) : Math.floorMod(index + (backwards ? -1 : 1), candidates.size());
            return Optional.of(candidates.get(next));
        }
    }

    /** A document-scoped body identity and its effective viewport, after inherited clipping. */
    public record Region(String id, Bounds viewport, int offset, int maximum, int layer, IntConsumer change) {

        public Region {
            Objects.requireNonNull(id);
            Objects.requireNonNull(viewport);
            Objects.requireNonNull(change);
            if (maximum < 0 || offset < 0 || offset > maximum) throw new IllegalArgumentException("invalid reveal range");
        }
    }

    /** contentBounds undo body translation and include moving local clips; viewport includes fixed clips. */
    public record Target(Region region, Bounds viewport, Bounds contentBounds) {

        public Target {
            Objects.requireNonNull(region);
            Objects.requireNonNull(viewport);
            Objects.requireNonNull(contentBounds);
        }

        boolean sameContext(Target other) {
            return region.id().equals(other.region.id()) && region.layer() == other.region.layer() && region.maximum() == other.region.maximum() && region.viewport().equals(other.region.viewport()) && viewport.equals(other.viewport) && contentBounds.equals(other.contentBounds);
        }
    }

    static boolean interactive(Node node) {
        return node.role() != Role.LABEL && node.role() != Role.PANEL && node.bounds().width() > 0 && node.bounds().height() > 0;
    }

    static int revealOffset(Target target) {
        var bounds = target.contentBounds();
        var viewport = target.viewport();
        long delta = 0, top = (long) bounds.y() - target.region().offset();
        long bottom = top + bounds.height(), end = (long) viewport.y() + viewport.height();
        if (bounds.height() <= viewport.height()) {
            if (top < viewport.y()) delta = top - viewport.y();
            else if (bottom > end) delta = bottom - end;
        } else {
            // Oversized controls can receive focus once they cover the available vertical aperture.
            if (top > viewport.y()) delta = top - viewport.y();
            else if (bottom < end) delta = bottom - end;
        }
        return (int) Math.max(0, Math.min(target.region().maximum(), (long) target.region().offset() + delta));
    }

    static boolean revealable(Node node, Target target) {
        if (target == null || target.region().layer() != node.layer() || target.viewport().width() == 0 || target.viewport().height() == 0) return false;
        var bounds = target.contentBounds();
        var viewport = target.viewport();
        long top = (long) bounds.y() - revealOffset(target);
        return bounds.x() < (long) viewport.x() + viewport.width() && (long) bounds.x() + bounds.width() > viewport.x() && top < (long) viewport.y() + viewport.height() && top + bounds.height() > viewport.y();
    }

    static boolean ready(Node node, Target target) {
        return node.focusable() && (target == null || revealOffset(target) == target.region().offset());
    }

    static List<Node> candidates(ControlTree tree, Map<String, Target> targets) {
        return Order.prepare(tree, targets).candidates();
    }
}
