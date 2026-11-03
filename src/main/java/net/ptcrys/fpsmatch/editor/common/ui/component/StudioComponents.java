package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.*;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Insets;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Length;
import net.ptcrys.fpsmatch.editor.common.ui.layout.FlowLayout.Style;

import java.util.*;
import java.util.function.*;

import static net.ptcrys.fpsmatch.editor.common.ui.component.StudioComponent.*;

/**
 * Composable Studio controls. Callers provide localized data, stable identity and domain events;
 * this layer owns padding, alignment, overflow, semantic roles, selection and subcontrol IDs.
 * No game, document type, persistence or renderer is referenced here. A composition may be nested
 * inside any StudioFrame clip and can be consumed by another host renderer.
 */
@SuppressWarnings("try") // Clip scopes are intentionally used for their close/restore effect.
public final class StudioComponents {

    /** Shared publication/input adapter. Window activation does not erase the logical edit target. */
    public static final class InputBridge {

        private final ControlTree tree;

        private record Bindings(Map<String, SingleLineText> texts, Map<String, Consumer<String>> changes,
                                Map<String, Runnable> blur, Map<String, String> groups,
                                Map<String, StudioFocusNavigation.Target> focusTargets, StudioFocusNavigation.Order order) {}

        private Bindings bindings;
        private StudioRuntime.Published published;
        private Map<String, SingleLineText> capturedEditors;
        private String sessionScope = "";
        private long frameId;
        private long mountGeneration;
        private StudioFocusNavigation.Sequence sequence;
        private long scopeEpoch, sequenceRevision, requestSerial;
        private StudioFocusNavigation.FocusRef logicalActual;
        private Origin detachedOrigin;
        private boolean logicalReflow;

        private record LogicalNavigation(StudioFocusNavigation.RevealRequest request, Origin origin, boolean passive, Navigation ordinary) {}

        private LogicalNavigation logicalPending;

        private static final class Origin {

            private final String id, group, region, scope;
            private final Runnable commit;
            private final int layer;
            private final StudioRuntime.Mount mount;
            private final StudioFocusNavigation.FocusRef logical;
            private boolean settled;

            private Origin(String id, String group, Runnable commit, int layer, String region, String scope, StudioRuntime.Mount mount, StudioFocusNavigation.FocusRef logical) {
                this.id = id;
                this.group = group;
                this.commit = commit;
                this.layer = layer;
                this.region = region;
                this.scope = scope;
                this.mount = mount;
                this.logical = logical;
            }

            String id() {
                return id;
            }

            String group() {
                return group;
            }

            Runnable commit() {
                return commit;
            }
        }

        private record Navigation(String id, Role role, StudioFocusNavigation.Target target, Origin origin,
                                  String expectedFocus, int requestedOffset, IntConsumer requestedChange, boolean reflow) {}

        private record KeyboardAnchor(String id, Role role, String region, int layer) {}

        private Navigation pending;
        private KeyboardAnchor anchor;
        private Origin deferredGroupOrigin;
        private Origin executingOrigin;
        private String inactiveBlurred;
        private boolean active = true;

        public InputBridge(ControlTree tree) {
            this.tree = Objects.requireNonNull(tree);
            bindings = new Bindings(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), StudioFocusNavigation.Order.prepare(tree, Map.of()));
            var mounts = new HashMap<String, StudioRuntime.Mount>();
            for (var node : tree.snapshot()) mounts.put(node.id(), new StudioRuntime.Mount(sessionScope, node.id(), node.role(), ++mountGeneration));
            published = new StudioRuntime.Published(0, new StudioFrame().snapshot().withNodes(tree.snapshot()), tree.frame(), Map.of(), Map.of(), focusedId(), mounts);
            sequence = StudioFocusNavigation.Sequence.prepare(tree, published.snapshot());
        }

        public Map<String, SingleLineText> visibleText() {
            return bindings.texts();
        }

        public StudioRuntime.Published frame() {
            return published;
        }

        public String sessionScope() {
            return sessionScope;
        }

        public StudioFocusNavigation.RevealRequest requestedFocus() {
            return logicalPending == null ? null : logicalPending.request();
        }

        public StudioFocusNavigation.FocusRef currentLogicalFocus() {
            if (logicalPending != null) return logicalPending.request().target();
            if (pending != null) return sequence.refForNode(pending.id());
            var actual = sequence.refForNode(focusedId());
            if (actual != null) return actual;
            if (logicalActual != null && sequence.indexOf(logicalActual) >= 0) return logicalActual;
            return null;
        }

        public boolean isCurrent(StudioFocusNavigation.RevealRequest request) {
            return request != null && logicalPending != null && request.equals(logicalPending.request()) && request.scopeEpoch() == scopeEpoch && request.sequenceRevision() == sequenceRevision && request.scope().equals(sessionScope);
        }

        public boolean actionReady(String id) {
            return logicalPending == null && pending == null && id != null && tree.geometry(id).map(g -> g.focusable() && g.node().enabled() && g.node().layer() == tree.topLayer()).orElse(false);
        }

        /** Requests a logical destination for row-key navigation without inventing a mounted node. */
        public boolean requestFocus(StudioFocusNavigation.FocusRef ref) {
            if (!active || sequence.indexOf(ref) < 0) return false;
            try {
                Origin source = logicalPending == null ? origin(focusedId()) : logicalPending.origin();
                logicalReflow = false;
                startLogical(ref, source, false);
                return true;
            } finally {
                captureEdits();
            }
        }

        public void restoreFocus(String id) {
            logicalPending = null;
            pending = null;
            anchor = null;
            detachedOrigin = null;
            tree.focus(id);
            logicalActual = sequence.refForNode(focusedId());
            captureEdits();
        }

        public StudioRuntime.Published captureEdits() {
            String focused = focusedId();
            boolean changed = capturedEditors != bindings.texts();
            if (!changed) for (var entry : bindings.texts().entrySet()) {
                var previous = published.edits().get(entry.getKey());
                if (previous == null || previous.revision() != entry.getValue().revision()) {
                    changed = true;
                    break;
                }
            }
            if (!changed && Objects.equals(published.focusedId(), focused)) return published;
            Map<String, SingleLineText.Snapshot> edits = published.edits();
            if (changed) {
                var copy = new HashMap<String, SingleLineText.Snapshot>();
                bindings.texts().forEach((id, text) -> copy.put(id, text.snapshot()));
                edits = Map.copyOf(copy);
            }
            capturedEditors = bindings.texts();
            published = new StudioRuntime.Published(published.frameId(), published.snapshot(), published.geometry(), edits, published.diagnostics(), focused, published.mounts());
            return published;
        }

        public void publish(StudioFrame.Snapshot next) {
            publish(next, sessionScope);
        }

        public void publish(StudioFrame.Snapshot next, String nextScope) {
            publish(next, nextScope, () -> {});
        }

        public void publish(StudioFrame.Snapshot next, String nextScope, Runnable afterCommit) {
            Objects.requireNonNull(nextScope);
            Objects.requireNonNull(afterCommit);
            next.validate();
            String old = focusedId();
            Origin previous = origin(old);
            Navigation waiting = pending;
            var oldLogical = currentLogicalFocus();
            boolean wasLogical = logicalPending != null || virtualRef(oldLogical) || virtualOrigin(previous);
            boolean sourceChanged = waiting != null && !Objects.equals(waiting.expectedFocus(), old);
            var values = new HashMap<String, String>();
            for (var node : next.nodes()) if (node.role() == Role.TEXT_FIELD) values.put(node.id(), node.value());
            var edits = tree.sessions().prepare(nextScope, values);
            var nodes = new ArrayList<Node>();
            for (var node : next.nodes()) nodes.add(edits.diagnostics().containsKey(node.id()) ? new Node(node.id(), node.role(), node.name(), node.value(), node.bounds(), node.enabled(), node.selected(), true, node.reason(), node.layer(), node.clip(), node.pointerBarrier()) : node);
            var geometry = tree.prepare(nodes);
            next = next.withNodes(geometry.nodes());
            if (!edits.diagnostics().isEmpty()) {
                var changes = new HashMap<>(next.changes());
                var blur = new HashMap<>(next.blur());
                edits.diagnostics().keySet().forEach(id -> {
                    changes.remove(id);
                    blur.remove(id);
                });
                next = new StudioFrame.Snapshot(next.nodes(), next.visuals(), next.actions(), changes, blur, next.groups(), next.details(), next.scrolls(), next.focusTargets(), next.focusSequence(), next.virtualTargets());
            }
            var stagedTree = new ControlTree();
            stagedTree.commit(geometry);
            var order = StudioFocusNavigation.Order.prepare(stagedTree, next.focusTargets());
            var nextSequence = StudioFocusNavigation.Sequence.prepare(stagedTree, next);
            long nextSequenceRevision = sequence.sameRevision(nextSequence) ? sequenceRevision : Math.incrementExact(sequenceRevision);
            boolean contextChanged = !sessionScope.equals(nextScope) || tree.topLayer() != geometry.topLayer();
            long nextScopeEpoch = contextChanged ? Math.incrementExact(scopeEpoch) : scopeEpoch;
            var nextBindings = new Bindings(edits.editors(), next.changes(), next.blur(), next.groups(), next.focusTargets(), order);
            var mounts = new HashMap<String, StudioRuntime.Mount>();
            long nextMountGeneration = mountGeneration;
            for (var node : geometry.nodes()) {
                var mount = published.mount(node.id());
                if (mount == null || !mount.scope().equals(nextScope) || mount.role() != node.role())
                    mount = new StudioRuntime.Mount(nextScope, node.id(), node.role(), nextMountGeneration = Math.incrementExact(nextMountGeneration));
                mounts.put(node.id(), mount);
            }
            var nextPublished = new StudioRuntime.Published(frameId + 1, next, geometry, edits.snapshots(), edits.diagnostics(), null, mounts);
            boolean scopeChanged = !sessionScope.equals(nextScope);
            // Every fallible preparation precedes this callback-free owner-thread commit.
            edits.validate();
            edits.commit();
            tree.commit(geometry);
            bindings = nextBindings;
            sessionScope = nextScope;
            published = nextPublished;
            mountGeneration = nextMountGeneration;
            capturedEditors = bindings.texts();
            long committed = ++frameId;
            captureEdits();
            sequence = nextSequence;
            sequenceRevision = nextSequenceRevision;
            scopeEpoch = nextScopeEpoch;
            try {
                afterCommit.run();
                if (frameId != committed) return;
                if (wasLogical || logicalPending != null) {
                    publishLogical(oldLogical, previous, contextChanged);
                    return;
                }
                logicalActual = null;
                if (scopeChanged) {
                    pending = null;
                    anchor = null;
                    tree.focus(null);
                    captureEdits();
                    commit(previous);
                    return;
                }
                if (previous != null && published.diagnostics().containsKey(previous.id())) retire(previous);
                if (deferredGroupOrigin != null && published.diagnostics().containsKey(deferredGroupOrigin.id())) retire(deferredGroupOrigin);
                if (deferredGroupOrigin != null && !sameOriginContext(deferredGroupOrigin)) commit(deferredGroupOrigin);
                if (frameId != committed) return;
                if (waiting != null) {
                    var node = tree.node(waiting.id()).orElse(null);
                    var target = bindings.focusTargets().get(waiting.id());
                    if (sourceChanged || !active || node == null || node.layer() != tree.topLayer() || node.role() != waiting.role() || target == null || !(waiting.reflow() ? sameRegion(waiting.target(), target) : waiting.target().sameContext(target)) || !StudioFocusNavigation.revealable(node, target)) {
                        pending = null;
                        anchor = null;
                        leaving(waiting.origin(), focusedId());
                        if (frameId != committed) return;
                        if (previous != null && (waiting.origin() == null || !previous.id().equals(waiting.origin().id())) && tree.focused().isEmpty()) commit(previous);
                        return;
                    }
                    if (StudioFocusNavigation.ready(node, target)) {
                        pending = null;
                        tree.focus(node.id());
                        remember(node, target);
                        leaving(waiting.origin(), focusedId());
                        return;
                    }
                    int requested = StudioFocusNavigation.revealOffset(target);
                    boolean changed = target.region().offset() != waiting.target().region().offset() || target.region().change() != waiting.requestedChange() || requested != waiting.requestedOffset();
                    pending = new Navigation(node.id(), node.role(), target, waiting.origin(), focusedId(), requested, target.region().change(), waiting.reflow());
                    if (changed) target.region().change().accept(requested);
                    return;
                }
                if (anchor != null) {
                    var remembered = anchor;
                    var node = tree.node(remembered.id()).orElse(null);
                    var target = bindings.focusTargets().get(remembered.id());
                    if (!active || !Objects.equals(old, remembered.id()) || node == null || node.role() != remembered.role() || node.layer() != tree.topLayer() || node.layer() != remembered.layer() || target == null || !target.region().id().equals(remembered.region()) || !StudioFocusNavigation.revealable(node, target)) anchor = null;
                    else if (!StudioFocusNavigation.ready(node, target)) {
                        int requested = StudioFocusNavigation.revealOffset(target);
                        anchor = null;
                        pending = new Navigation(node.id(), node.role(), target, previous, focusedId(), requested, target.region().change(), true);
                        target.region().change().accept(requested);
                        return;
                    }
                }
                if (old != null && tree.focused().isEmpty()) commit(previous);
            } finally {
                captureEdits();
            }
        }

        private void remember(Node node, StudioFocusNavigation.Target target) {
            anchor = target == null ? null : new KeyboardAnchor(node.id(), node.role(), target.region().id(), node.layer());
        }

        private boolean logicalMode() {
            return sequence.hasVirtualRanges() || logicalPending != null || logicalActual != null && logicalActual.virtual();
        }

        private static boolean virtualRef(StudioFocusNavigation.FocusRef ref) {
            return ref != null && ref.virtual();
        }

        private static boolean virtualOrigin(Origin origin) {
            return origin != null && virtualRef(origin.logical);
        }

        private Node readyLogical(StudioFocusNavigation.FocusRef ref) {
            String id = sequence.mountedId(ref);
            var node = id == null ? null : tree.node(id).orElse(null);
            if (node == null || node.role() != ref.role() || node.layer() != tree.topLayer() || !node.focusable()) return null;
            if (!ref.virtual()) return StudioFocusNavigation.ready(node, bindings.focusTargets().get(id)) ? node : null;
            var range = sequence.range(ref);
            if (range == null) return null;
            var visible = node.visibleBounds().intersect(range.viewport());
            var bounds = node.bounds();
            if (visible.width() == 0 || visible.height() == 0) return null;
            boolean ready = bounds.height() > range.viewport().height() ? visible.y() == range.viewport().y() && visible.height() == range.viewport().height() : visible.y() == bounds.y() && visible.height() == bounds.height();
            return ready ? node : null;
        }

        private StudioFocusNavigation.RevealRequest request(StudioFocusNavigation.FocusRef ref) {
            return new StudioFocusNavigation.RevealRequest(sessionScope, scopeEpoch, sequenceRevision, requestSerial = Math.incrementExact(requestSerial), ref, sequence.rowIndex(ref));
        }

        private void revealLogical() {
            if (logicalPending == null) return;
            var request = logicalPending.request();
            var ref = request.target();
            if (ref.virtual()) {
                var range = sequence.range(ref);
                if (range != null && isCurrent(request)) range.reveal().accept(request);
            } else {
                var target = bindings.focusTargets().get(ref.nodeId());
                if (target != null && isCurrent(request)) target.region().change().accept(StudioFocusNavigation.revealOffset(target));
            }
        }

        private void settleLogical(Node node) {
            var navigation = logicalPending;
            logicalPending = null;
            detachedOrigin = null;
            logicalReflow = false;
            tree.focus(node.id());
            logicalActual = navigation.request().target();
            captureEdits();
            if (!navigation.passive()) leaving(navigation.origin(), node.id());
        }

        private void startLogical(StudioFocusNavigation.FocusRef ref, Origin origin, boolean passive) {
            pending = null;
            anchor = null;
            logicalPending = null;
            var target = ref.virtual() ? null : bindings.focusTargets().get(ref.nodeId());
            if (!ref.virtual() && !virtualOrigin(origin)) {
                var node = tree.node(ref.nodeId()).orElseThrow();
                logicalActual = null;
                if (StudioFocusNavigation.ready(node, target)) {
                    detachedOrigin = null;
                    tree.focus(node.id());
                    remember(node, target);
                    leaving(origin, node.id());
                    return;
                }
                int requested = StudioFocusNavigation.revealOffset(target);
                pending = new Navigation(node.id(), node.role(), target, origin, focusedId(), requested, target.region().change(), passive);
                target.region().change().accept(requested);
                return;
            }
            Navigation ordinary = target == null ? null : new Navigation(ref.nodeId(), ref.role(), target, origin, focusedId(), StudioFocusNavigation.revealOffset(target), target.region().change(), passive);
            logicalPending = new LogicalNavigation(request(ref), origin, passive, ordinary);
            var ready = readyLogical(ref);
            if (ready != null) settleLogical(ready);
            else revealLogical();
        }

        private void publishLogical(StudioFocusNavigation.FocusRef previousRef, Origin previous, boolean contextChanged) {
            pending = null;
            anchor = null;
            if (contextChanged) {
                logicalPending = null;
                logicalActual = null;
                detachedOrigin = null;
                logicalReflow = false;
                tree.focus(null);
                if (previous != null) retire(previous);
                if (deferredGroupOrigin != null) retire(deferredGroupOrigin);
                return;
            }
            if (previous != null && published.diagnostics().containsKey(previous.id())) retire(previous);
            if (deferredGroupOrigin != null && published.diagnostics().containsKey(deferredGroupOrigin.id())) retire(deferredGroupOrigin);
            if (detachedOrigin != null && published.diagnostics().containsKey(detachedOrigin.id())) retire(detachedOrigin);
            if (logicalPending != null && logicalPending.origin() != null && published.diagnostics().containsKey(logicalPending.origin().id())) retire(logicalPending.origin());
            if (deferredGroupOrigin != null && deferredGroupOrigin.logical != null && sequence.indexOf(deferredGroupOrigin.logical) < 0) retire(deferredGroupOrigin);
            if (logicalPending != null && logicalPending.origin() != null && logicalPending.origin().logical != null && sequence.indexOf(logicalPending.origin().logical) < 0) retire(logicalPending.origin());
            if (logicalActual == null && previousRef != null && logicalPending == null) logicalActual = previousRef;
            if (logicalActual != null && sequence.indexOf(logicalActual) < 0) {
                logicalActual = null;
                detachedOrigin = null;
                tree.focus(null);
            }
            if (logicalActual == null && logicalPending == null) logicalActual = sequence.refForNode(focusedId());
            if (logicalActual != null) {
                String mounted = sequence.mountedId(logicalActual);
                if (!Objects.equals(mounted, focusedId()) || readyLogical(logicalActual) == null) {
                    tree.focus(null);
                    if (detachedOrigin == null) detachedOrigin = previous;
                }
            }
            if (logicalPending != null) {
                var waiting = logicalPending;
                var ref = waiting.request().target();
                if (!active || sequence.indexOf(ref) < 0) {
                    logicalPending = null;
                    logicalReflow = false;
                    return;
                }
                boolean revised = waiting.request().sequenceRevision() != sequenceRevision;
                var ordinary = waiting.ordinary();
                boolean changed = false;
                if (ordinary != null) {
                    var target = bindings.focusTargets().get(ref.nodeId());
                    var node = tree.node(ref.nodeId()).orElse(null);
                    boolean reflow = logicalReflow || ordinary.reflow();
                    if (target == null || node == null || !(reflow ? sameRegion(ordinary.target(), target) : ordinary.target().sameContext(target)) || !StudioFocusNavigation.revealable(node, target)) {
                        logicalPending = null;
                        logicalReflow = false;
                        return;
                    }
                    int requested = StudioFocusNavigation.revealOffset(target);
                    changed = target.region().offset() != ordinary.target().region().offset() || target.region().change() != ordinary.requestedChange() || requested != ordinary.requestedOffset();
                    ordinary = new Navigation(node.id(), node.role(), target, waiting.origin(), focusedId(), requested, target.region().change(), reflow);
                }
                logicalPending = new LogicalNavigation(revised ? request(ref) : waiting.request(), waiting.origin(), waiting.passive(), ordinary);
                var ready = readyLogical(ref);
                if (ready != null) {
                    settleLogical(ready);
                    return;
                }
                if (revised || logicalReflow || changed) {
                    logicalReflow = false;
                    revealLogical();
                }
                return;
            }
            if (logicalActual != null && readyLogical(logicalActual) != null) {
                tree.focus(sequence.mountedId(logicalActual));
                detachedOrigin = null;
                logicalReflow = false;
                return;
            }
            if (logicalReflow && logicalActual != null && active) {
                logicalReflow = false;
                startLogical(logicalActual, previous, true);
            }
        }

        private void cycleLogical(boolean backwards) {
            if (pending != null && !Objects.equals(pending.expectedFocus(), focusedId())) cancelNavigation();
            var ref = sequence.next(currentLogicalFocus(), backwards);
            Origin source = logicalPending != null ? logicalPending.origin() : pending != null ? pending.origin() : origin(focusedId());
            logicalReflow = false;
            if (ref == null) {
                logicalPending = null;
                return;
            }
            startLogical(ref, source, false);
        }

        private static boolean sameRegion(StudioFocusNavigation.Target first, StudioFocusNavigation.Target second) {
            return first.region().id().equals(second.region().id()) && first.region().layer() == second.region().layer();
        }

        /**
         * Preserve the current edit target across host resize/density reflow. Geometry may change,
         * but stable control identity, document region and layer must still match on publication.
         */
        public void reflow() {
            if (!active) return;
            if (logicalPending != null || virtualRef(currentLogicalFocus()) || virtualOrigin(origin(focusedId()))) {
                logicalReflow = true;
                return;
            }
            if (pending != null) {
                var waiting = pending;
                pending = new Navigation(waiting.id(), waiting.role(), waiting.target(), waiting.origin(), waiting.expectedFocus(), waiting.requestedOffset(), waiting.requestedChange(), true);
                return;
            }
            var node = tree.focused().orElse(null);
            if (node != null) remember(node, bindings.focusTargets().get(node.id()));
        }

        private String focusedId() {
            return tree.focusedId();
        }

        private Origin origin(String id) {
            if (executingOrigin != null && Objects.equals(id, executingOrigin.id()) && published.isCurrent(executingOrigin.mount)) return executingOrigin;
            if (deferredGroupOrigin != null) return deferredGroupOrigin;
            if (detachedOrigin != null) return detachedOrigin;
            if (id == null) return null;
            var node = tree.node(id).orElse(null);
            var target = bindings.focusTargets().get(id);
            return new Origin(id, bindings.groups().get(id), bindings.blur().get(id), node == null ? tree.topLayer() : node.layer(), target == null ? null : target.region().id(), sessionScope, published.mount(id), sequence.refForNode(id));
        }

        private boolean sameOriginContext(Origin source) {
            var node = tree.node(source.id()).orElse(null);
            var target = bindings.focusTargets().get(source.id());
            return published.isCurrent(source.mount) && node != null && node.layer() == source.layer && node.layer() == tree.topLayer() && bindings.blur().containsKey(source.id()) && Objects.equals(source.group(), bindings.groups().get(source.id())) && Objects.equals(source.region, target == null ? null : target.region().id());
        }

        private void retire(Origin source) {
            source.settled = true;
            if (deferredGroupOrigin == source) deferredGroupOrigin = null;
        }

        private void commit(Origin origin) {
            if (origin == null || origin.settled) return;
            retire(origin);
            if (origin.commit() == null || !active && origin.id().equals(inactiveBlurred)) return;
            if (!active) inactiveBlurred = origin.id();
            captureEdits();
            var previous = executingOrigin;
            executingOrigin = origin;
            try {
                origin.commit().run();
            } finally {
                executingOrigin = previous;
            }
        }

        private void leaving(Origin old, String next) {
            if (old == null || old.settled) return;
            if (Objects.equals(old.id(), next)) {
                if (deferredGroupOrigin == old) retire(old);
                return;
            }
            if (old.commit() != null && old.group() != null && next != null && next.startsWith(old.group())) {
                deferredGroupOrigin = old;
                return;
            }
            commit(old);
        }

        /**
         * Notify only immediately before a confirmed enabled action executes. Its callback owns
         * apply/discard semantics; merely focusing or pressing the action must not retire the source.
         */
        public void beforeAction(String id) {
            cancelNavigation();
            var source = deferredGroupOrigin;
            if (source != null && source.group() != null && id != null && id.startsWith(source.group())) retire(source);
        }

        /** Cancel pending reveal and keyboard layout-following on pointer scroll or context change. */
        public void cancelNavigation() {
            logicalPending = null;
            logicalReflow = false;
            var waiting = pending;
            pending = null;
            anchor = null;
            if (waiting != null) leaving(waiting.origin(), focusedId());
        }

        /** Failure-shell cancellation: no blur, reveal or domain callback; editing data survives. */
        public void abortNavigation() {
            pending = null;
            anchor = null;
            deferredGroupOrigin = null;
            logicalPending = null;
            logicalReflow = false;
            detachedOrigin = null;
        }

        public void focus(String id) {
            Origin old = logicalPending != null ? logicalPending.origin() : pending == null ? origin(focusedId()) : pending.origin();
            long before = frameId;
            logicalPending = null;
            logicalReflow = false;
            detachedOrigin = null;
            var requested = published.mount(id);
            pending = null;
            anchor = null;
            try {
                leaving(old, id);
                if (before == frameId || published.isCurrent(requested)) tree.focus(id);
            } finally {
                logicalActual = sequence.refForNode(focusedId());
                captureEdits();
            }
        }

        public void cycleFocus(boolean backwards) {
            try {
                if (!active) {
                    cancelNavigation();
                    return;
                }
                if (logicalMode()) {
                    cycleLogical(backwards);
                    return;
                }
                if (pending != null && !Objects.equals(pending.expectedFocus(), focusedId())) cancelNavigation();
                Origin old = pending == null ? origin(focusedId()) : pending.origin();
                String anchor = pending == null ? focusedId() : pending.id();
                this.anchor = null;
                var node = bindings.order().next(anchor, backwards).orElse(null);
                if (node == null) {
                    pending = null;
                    tree.focus(null);
                    leaving(old, null);
                    return;
                }
                var target = bindings.focusTargets().get(node.id());
                if (StudioFocusNavigation.ready(node, target)) {
                    pending = null;
                    tree.focus(node.id());
                    remember(node, target);
                    leaving(old, node.id());
                    return;
                }
                int requested = StudioFocusNavigation.revealOffset(target);
                pending = new Navigation(node.id(), node.role(), target, old, focusedId(), requested, target.region().change(), false);
                target.region().change().accept(requested);
            } finally {
                captureEdits();
            }
        }

        public boolean windowActive(boolean value) {
            boolean lost = active && !value;
            active = value;
            if (value) inactiveBlurred = null;
            else cancelNavigation();
            if (lost) commit(origin(focusedId()));
            return lost;
        }

        public boolean insert(String value) {
            if (pending != null || logicalPending != null) cancelNavigation();
            var node = tree.focused().orElse(null);
            if (node == null || node.role() != Role.TEXT_FIELD || !node.enabled() || node.readOnly() || !bindings.texts().containsKey(node.id())) return false;
            if (bindings.texts().get(node.id()).insert(value)) changed(node.id());
            captureEdits();
            return true;
        }

        public void changed(String id) {
            if (pending != null || logicalPending != null) cancelNavigation();
            inactiveBlurred = null;
            captureEdits();
            var change = bindings.changes().get(id);
            try {
                if (change != null && bindings.texts().containsKey(id)) change.accept(bindings.texts().get(id).text());
            } finally {
                captureEdits();
            }
        }
    }

    public record Action(String id, String label, Icon icon, Variant variant, boolean enabled, boolean selected, String reason, Runnable invoke) {

        public Action {
            Objects.requireNonNull(invoke);
        }

        public static Action plain(String id, String label, Runnable invoke) {
            return new Action(id, label, Icon.NONE, Variant.DEFAULT, true, false, "", invoke);
        }
    }

    public enum TabInsertion {
        NONE,
        BEFORE,
        AFTER
    }

    public record DocumentTab(Action activate, boolean dirty, String closeLabel, Runnable close, boolean dragging, TabInsertion insertion) {

        public DocumentTab(Action activate, boolean dirty, String closeLabel, Runnable close) {
            this(activate, dirty, closeLabel, close, false, TabInsertion.NONE);
        }

        public DocumentTab {
            Objects.requireNonNull(activate);
            Objects.requireNonNull(closeLabel);
            Objects.requireNonNull(close);
            Objects.requireNonNull(insertion);
        }
    }

    public record TreeRow(String id, String label, Icon icon, int depth, boolean branch, boolean expanded, boolean selected, Runnable activate, Runnable toggle) {}

    public enum FieldKind {
        TEXT,
        CHOICE,
        TOGGLE
    }

    public record Field(String id, String label, String raw, String display, String hint, FieldKind kind, int labelColor,
                        boolean enabled, boolean invalid, Consumer<String> change, Runnable choose, Runnable commit) {}

    public record FieldActions(String id, String applyLabel, String discardLabel, boolean enabled, Runnable apply, Runnable discard, String error) {}

    public record FieldGeometry(Bounds label, List<String> labelLines, TextSize labelSize, Bounds control, Bounds hint, List<String> hintLines) {

        public FieldGeometry {
            labelLines = List.copyOf(labelLines);
            hintLines = List.copyOf(hintLines);
        }
    }

    public record FieldRowLayout(int width, int height, List<FieldGeometry> fields, Bounds apply, Bounds discard, Bounds error, List<String> errorLines) {

        public FieldRowLayout {
            fields = List.copyOf(fields);
            errorLines = List.copyOf(errorLines);
        }
    }

    public record SectionIntro(String title, String note, String badge) {}

    public record CardHeader(String title, String description, boolean expanded, boolean framed, List<List<Action>> rows, Runnable toggle) {

        public CardHeader {
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    public record Column(String id, String label, int rows, Runnable select) {}

    public record Slot(String id, String column, int row, String title, List<String> details, boolean empty, boolean selected,
                       boolean peer, boolean dimmed, boolean enabled, String reason, Runnable activate, String badge) {

        public Slot(String id, String column, int row, String title, List<String> details, boolean empty, boolean selected, boolean peer, boolean dimmed, boolean enabled, String reason, Runnable activate) {
            this(id, column, row, title, details, empty, selected, peer, dimmed, enabled, reason, activate, "");
        }

        public Slot {
            details = List.copyOf(details);
            Objects.requireNonNull(badge);
        }
    }

    @FunctionalInterface
    public interface RangeText {

        String format(int first, int last, int total);
    }

    public record GridResult(int firstColumn, int columnCount, StudioLayout.Window rows) {}

    private final StudioFrame frame;
    private final StudioMetrics metrics;
    private final ToIntFunction<String> measure;
    private final Runnable invalidate;
    private final StudioTextLayout textLayout;
    private final StudioView view;

    private record TabView(int requestedOffset, int reportedOffset, int first, String selected, List<String> ids, boolean browsing) {}

    private final Map<String, TabView> tabViews = new HashMap<>();

    public StudioComponents(StudioFrame frame, StudioMetrics metrics, ToIntFunction<String> measure) {
        this(frame, metrics, measure, () -> {});
    }

    /** Invalidate asks the host to publish a new frame after component-owned view navigation. */
    public StudioComponents(StudioFrame frame, StudioMetrics metrics, ToIntFunction<String> measure, Runnable invalidate) {
        this(frame, metrics, StudioTextLayout.scaled(measure, TextSize.BODY.height), invalidate);
    }

    public StudioComponents(StudioFrame frame, StudioMetrics metrics, StudioTextLayout textLayout) {
        this(frame, metrics, textLayout, () -> {});
    }

    /** Shares the host's measurement cache and font generation with component composition. */
    public StudioComponents(StudioFrame frame, StudioMetrics metrics, StudioTextLayout textLayout, Runnable invalidate) {
        this.frame = Objects.requireNonNull(frame);
        this.metrics = Objects.requireNonNull(metrics);
        this.textLayout = Objects.requireNonNull(textLayout);
        this.measure = value -> this.textLayout.width(value, TextSize.BODY);
        this.invalidate = Objects.requireNonNull(invalidate);
        this.view = new StudioView(textLayout, metrics);
    }

    public StudioFrame frame() {
        return frame;
    }

    public StudioView view() {
        return view;
    }

    public StudioMetrics metrics() {
        return metrics;
    }

    /** Matches the renderer's body text padding and icon advance. */
    public int captionWidth(String label, Icon icon) {
        return measure.applyAsInt(label) + 12 + (icon == Icon.NONE ? 0 : 16);
    }

    public void text(String id, String text, Bounds bounds, int layer, TextSize size, Variant variant) {
        frame.emit(id, Role.LABEL, text, "", bounds, true, false, false, "", layer, Visual.of(Kind.LABEL).size(size).variant(variant));
    }

    public List<String> wrap(String value, int width) {
        return wrap(value, width, TextSize.BODY);
    }

    public List<String> wrap(String value, int width, TextSize size) {
        if (value.isEmpty()) return List.of();
        if (width <= 0) return List.of(value);
        var lines = textLayout.wrap(value, width, size).lines();
        // Existing paragraph layout treats a final LF as a terminator, not another row.
        return value.endsWith("\n") ? List.copyOf(lines.subList(0, lines.size() - 1)) : lines;
    }

    public int paragraph(String id, String value, Bounds bounds, int layer, Variant variant) {
        return paragraph(id, value, bounds, layer, TextSize.BODY, variant);
    }

    public int paragraph(String id, String value, Bounds bounds, int layer, TextSize size, Variant variant) {
        var lines = wrap(value, bounds.width(), size);
        try (var ignored = frame.contentClipped(bounds)) {
            for (int i = 0; i < lines.size(); i++) text(id + "/" + i, lines.get(i), new Bounds(bounds.x(), bounds.y() + i * 13, bounds.width(), 13), layer, size, variant);
        }
        return lines.size() * 13;
    }

    public int noticeHeight(String message, int width) {
        return 24 + wrap(message, Math.max(0, width - 12)).size() * 13;
    }

    /** One event link and one reason, with all text measured before a task reserves its footer. */
    public int notice(String id, Action event, String message, Bounds bounds, int layer) {
        button(event, new Bounds(bounds.x() + 6, bounds.y(), Math.max(0, Math.min(bounds.width() - 12, captionWidth(event.label(), event.icon()))), 20), layer, Kind.BUTTON);
        paragraph(id + "/reason", message, new Bounds(bounds.x() + 6, bounds.y() + 24, Math.max(0, bounds.width() - 12), Math.max(0, bounds.height() - 24)), layer, Variant.DANGER);
        return noticeHeight(message, bounds.width());
    }

    public int breadcrumb(String id, List<String> labels, Bounds bounds, int layer) {
        String path = String.join(" › ", labels);
        int height = paragraph(id, path, bounds, layer, Variant.MUTED);
        for (int i = 0; i < wrap(path, bounds.width()).size(); i++) frame.detail(id + "/" + i, path);
        return Math.min(bounds.height(), height);
    }

    public int introHeight(SectionIntro intro, int width) {
        return 25 + wrap(intro.note(), Math.max(0, width - 12)).size() * 13;
    }

    public void sectionIntro(String id, SectionIntro intro, Bounds bounds, int layer) {
        surface(id + "/line", new Bounds(bounds.x(), bounds.y(), bounds.width(), 1), layer, Kind.SEPARATOR);
        int badgeWidth = intro.badge().isEmpty() ? 0 : measure.applyAsInt(intro.badge()) + 8;
        text(id + "/title", intro.title(), new Bounds(bounds.x() + 6, bounds.y() + 7, Math.max(0, bounds.width() - 12 - badgeWidth), 13), layer, TextSize.BODY, Variant.DEFAULT);
        if (badgeWidth > 0) text(id + "/badge", intro.badge(), new Bounds(bounds.x() + bounds.width() - badgeWidth - 6, bounds.y() + 7, badgeWidth, 13), layer, TextSize.SMALL, Variant.MUTED);
        paragraph(id + "/note", intro.note(), new Bounds(bounds.x() + 6, bounds.y() + 25, Math.max(0, bounds.width() - 12), Math.max(0, bounds.height() - 25)), layer, Variant.MUTED);
    }

    public int cardHeaderHeight(CardHeader header, int width) {
        return header.expanded() ? 29 + wrap(header.description(), Math.max(0, width - 12), TextSize.SMALL).size() * 13 + header.rows().size() * 26 : 23;
    }

    public void detailCard(String id, CardHeader header, Bounds bounds, int layer) {
        disclosure(id, header.title(), "", header.expanded(), header.framed(), bounds, layer, header.toggle());
        if (!header.expanded()) return;
        int y = bounds.y() + 26;
        if (!header.description().isEmpty()) {
            int height = wrap(header.description(), Math.max(0, bounds.width() - 12), TextSize.SMALL).size() * 13;
            y += paragraph(id + "/context", header.description(), new Bounds(bounds.x() + 6, y, Math.max(0, bounds.width() - 12), height), layer, TextSize.SMALL, Variant.MUTED);
        }
        for (int i = 0; i < header.rows().size(); i++) {
            var row = header.rows().get(i);
            var area = new Bounds(bounds.x(), y, bounds.width(), 26);
            if (row.stream().anyMatch(action -> action.icon() != Icon.NONE)) commandBar(id + "/actions/" + i, area, layer, row.stream().filter(action -> action.icon() == Icon.NONE).toList(), row.stream().filter(action -> action.icon() != Icon.NONE).toList());
            else toolbar(id + "/actions/" + i, area, layer, row, false);
            y += 26;
        }
    }

    public int menu(String id, List<Action> entries, Bounds bounds, int layer) {
        try (var ignored = frame.contentClipped(bounds)) {
            int y = bounds.y();
            for (var entry : entries) {
                button(entry, new Bounds(bounds.x(), y, bounds.width(), metrics.row() + 3), layer, Kind.BUTTON);
                y += metrics.row() + 5;
            }
        }
        return entries.size() * (metrics.row() + 5);
    }

    public record WizardArea(Bounds navigation, Bounds content) {}

    public record TaskArea(Bounds content, Bounds footer) {}

    public record ConfirmationText(String text, Variant variant) {

        public ConfirmationText {
            Objects.requireNonNull(text);
            Objects.requireNonNull(variant);
        }
    }

    /** Callers supply localized reasons and ordered actions; ownership and save state stay outside UI. */
    public record Confirmation(String title, List<ConfirmationText> messages, Action close, List<Action> actions) {

        public Confirmation {
            Objects.requireNonNull(title);
            messages = List.copyOf(messages);
            Objects.requireNonNull(close);
            actions = List.copyOf(actions);
        }
    }

    public record ConfirmationParagraph(List<String> lines, Variant variant, int y) {

        public ConfirmationParagraph {
            lines = List.copyOf(lines);
        }
    }

    public record ConfirmationLayout(Bounds bounds, Bounds header, Bounds title, Bounds close, Bounds body, Bounds footer,
                                     List<Bounds> actions, List<ConfirmationParagraph> paragraphs, int contentHeight, String initialFocus) {

        public ConfirmationLayout {
            actions = List.copyOf(actions);
            paragraphs = List.copyOf(paragraphs);
        }

        public int maximumScroll() {
            return Math.max(0, contentHeight - body.height());
        }

        public int clampScroll(int offset) {
            return Math.max(0, Math.min(offset, maximumScroll()));
        }
    }

    /**
     * The approved 650px confirmation cap is 433 logical units at the reference compact host scale.
     * Content determines height; only the body scrolls when the available window is shorter.
     */
    public ConfirmationLayout measureConfirmation(Confirmation confirmation, Bounds viewport) {
        int marginX = Math.min(viewport.width() < 480 ? 6 : 14, viewport.width() / 2);
        int marginY = Math.min(viewport.height() < 300 || viewport.width() < 480 ? 12 : 30, Math.max(0, (viewport.height() - 100) / 2));
        int width = Math.min(433, Math.max(0, viewport.width() - marginX * 2)), availableHeight = Math.max(0, viewport.height() - marginY * 2);
        int inset = Math.min(11, width / 4), innerWidth = Math.max(0, width - inset * 2), gap = 5;
        int[] widths = confirmation.actions().stream().mapToInt(action -> Math.min(innerWidth, Math.max(metrics.row(), captionWidth(action.label(), action.icon())))).toArray();
        var rows = new ArrayList<List<Integer>>();
        var row = new ArrayList<Integer>();
        int used = 0;
        for (int i = 0; i < widths.length; i++) {
            if (!row.isEmpty() && used + gap + widths[i] > innerWidth) {
                rows.add(List.copyOf(row));
                row.clear();
                used = 0;
            }
            used += (row.isEmpty() ? 0 : gap) + widths[i];
            row.add(i);
        }
        if (!row.isEmpty()) rows.add(List.copyOf(row));
        int footerHeight = rows.isEmpty() ? 0 : 17 + rows.size() * metrics.row() + (rows.size() - 1) * gap;
        int headerHeight = Math.min(36, Math.max(0, availableHeight - footerHeight));
        int bodyInset = Math.min(14, width / 4), bodyWidth = Math.max(0, width - bodyInset * 2), textWidth = Math.max(1, bodyWidth - 10);
        var paragraphs = new ArrayList<ConfirmationParagraph>();
        int contentHeight = 0;
        for (var message : confirmation.messages()) {
            var lines = wrap(message.text(), textWidth);
            if (lines.isEmpty()) continue;
            if (!paragraphs.isEmpty()) contentHeight += 8;
            paragraphs.add(new ConfirmationParagraph(lines, message.variant(), contentHeight));
            contentHeight += lines.size() * 13;
        }
        int padding = Math.min(12, Math.max(0, (availableHeight - headerHeight - footerHeight) / 2));
        int bodyHeight = Math.min(contentHeight, Math.max(0, availableHeight - headerHeight - footerHeight - padding * 2));
        int height = headerHeight + padding * 2 + bodyHeight + footerHeight, x = viewport.x() + (viewport.width() - width) / 2, y = viewport.y() + marginY;
        var bounds = new Bounds(x, y, width, height);
        var header = new Bounds(x, y, width, headerHeight);
        var title = new Bounds(x + inset, y + 11, Math.max(0, innerWidth - metrics.row() - gap), 13);
        var close = new Bounds(x + width - inset - Math.min(metrics.row(), innerWidth), y + 8, Math.min(metrics.row(), innerWidth), metrics.row());
        var body = new Bounds(x + bodyInset, y + headerHeight + padding, bodyWidth, bodyHeight);
        var footer = new Bounds(x, y + height - footerHeight, width, footerHeight);
        var buttons = new ArrayList<Bounds>();
        int by = footer.y() + 9;
        for (var indices : rows) {
            int rowWidth = indices.stream().mapToInt(index -> widths[index]).sum() + gap * (indices.size() - 1), bx = x + width - inset - rowWidth;
            for (int index : indices) {
                buttons.add(new Bounds(bx, by, widths[index], metrics.row()));
                bx += widths[index] + gap;
            }
            by += metrics.row() + gap;
        }
        String focus = confirmation.actions().stream().filter(Action::enabled).map(Action::id).findFirst().orElse(confirmation.close().id());
        return new ConfirmationLayout(bounds, header, title, close, body, footer, buttons, paragraphs, contentHeight, focus);
    }

    /** Shared confirmation chrome, measured text, fixed compact footer and existing pixel scrolling. */
    public ConfirmationLayout confirmationDialog(String id, Confirmation confirmation, Bounds viewport, int layer, int offset, IntConsumer scroll) {
        var layout = measureConfirmation(confirmation, viewport);
        int position = layout.clampScroll(offset);
        try (var outer = frame.clipped(viewport)) {
            surface(id, layout.bounds(), layer, Kind.DIALOG);
            try (var header = frame.clipped(layout.header())) {
                text(id + "/title", confirmation.title(), layout.title(), layer, TextSize.BODY, Variant.DEFAULT);
                frame.detail(id + "/title", confirmation.title());
                button(confirmation.close(), layout.close(), layer, Kind.ICON);
                surface(id + "/header-line", new Bounds(layout.header().x(), layout.header().y() + Math.max(0, layout.header().height() - 1), layout.header().width(), 1), layer, Kind.SEPARATOR);
            }
            try (var body = frame.clipped(layout.body())) {
                for (int p = 0; p < layout.paragraphs().size(); p++) {
                    var paragraph = layout.paragraphs().get(p);
                    for (int line = 0; line < paragraph.lines().size(); line++) text(id + "/message/" + p + "/" + line, paragraph.lines().get(line), new Bounds(layout.body().x(), layout.body().y() + paragraph.y() + line * 13 - position, Math.max(0, layout.body().width() - 10), 13), layer, TextSize.BODY, paragraph.variant());
                }
                pixelScroll(id + "/scroll", layout.body(), position, layout.contentHeight(), layer, scroll);
            }
            try (var footer = frame.clipped(layout.footer())) {
                if (!confirmation.actions().isEmpty()) surface(id + "/footer-line", new Bounds(layout.footer().x(), layout.footer().y(), layout.footer().width(), 1), layer, Kind.SEPARATOR);
                for (int i = 0; i < confirmation.actions().size(); i++) {
                    var action = confirmation.actions().get(i);
                    button(action, layout.actions().get(i), layer, Kind.BUTTON);
                    frame.detail(action.id(), action.label());
                }
            }
        }
        return layout;
    }

    /** Persistent form navigation stays above the independently scrolling field body. */
    public Bounds formDocument(String id, List<String> path, String title, Action back, List<Action> pages,
                               String searchLabel, String search, Consumer<String> searchChange, Bounds bounds, int layer) {
        int inset = bounds.width() < 600 ? 8 : 14;
        boolean compact = bounds.height() < 125;
        var content = new ArrayList<StudioView.Element>();
        if (bounds.height() >= 170) content.add(view.text(id + "/path", String.join(" › ", path), TextSize.BODY, Variant.MUTED));
        if (!compact) content.add(view.row(id + "/heading", Style.defaults().gap(metrics.fieldGap()), List.of(
                view.text(id + "/title", title, TextSize.HEADING, Variant.DEFAULT), view.button(back, bounds.width() < 400 ? Kind.ICON : Kind.BUTTON))));
        var navigation = view.placeholder(id + "/pages-slot", Style.defaults().height(Length.fixed(metrics.row())));
        content.add(compact ? view.row(id + "/navigation", Style.defaults().gap(metrics.gap()), List.of(navigation, view.button(back, Kind.ICON))) : navigation);
        content.add(view.input(new StudioView.Input(id + "/search", searchLabel, search, true, false, false, "", null, searchChange, null)));
        content.add(view.placeholder(id + "/body", Style.fill()));
        var root = view.surface(id + "/surface", Kind.PANEL, Style.fill().padding(new Insets(metrics.gap(), inset, metrics.gap(), inset)), List.of(
                view.column(id + "/content", Style.fill().width(Length.stretch().limits(0, 533)).gap(metrics.gap()), content)));
        var layout = view.layout(root, bounds);
        try (var ignored = frame.contentClipped(bounds)) {
            layout.emit(frame, layer);
            tabs(id + "/pages", layout.contentBounds(id + "/pages-slot"), layer, pages, false, 0);
        }
        return layout.contentBounds(id + "/body").intersect(bounds);
    }

    /** The host chooses compact navigation; actual usable width decides whether two cells fit. */
    public List<Bounds> formDocumentColumns(Bounds bounds, boolean wide) {
        int gap = metrics.gap() * 3, minimum = metrics.row() * 9 + metrics.padding() * 2;
        int count = wide && bounds.width() >= minimum * 2 + gap ? 2 : 1;
        var columns = java.util.stream.IntStream.range(0, count).mapToObj(index -> view.placeholder("form-column/" + index, Style.fill())).toList();
        var layout = view.layout(view.row("form-columns", Style.fill().gap(gap), columns), bounds);
        return java.util.stream.IntStream.range(0, count).mapToObj(index -> layout.contentBounds("form-column/" + index)).toList();
    }

    /** Shared task chrome keeps status/content scrolling separate from persistent task actions. */
    public TaskArea taskDialog(String id, String title, Bounds bounds, int layer, List<Action> steps, Action close) {
        return taskDialog(id, title, bounds, layer, steps, close, 64);
    }

    public TaskArea taskDialog(String id, String title, Bounds bounds, int layer, List<Action> steps, Action close, int footerHeight) {
        surface(id, bounds, layer, Kind.DIALOG);
        text(id + "/title", title, new Bounds(bounds.x() + 14, bounds.y() + 12, Math.max(0, bounds.width() - 60), 18), layer, TextSize.HEADING, Variant.DEFAULT);
        button(close, new Bounds(bounds.x() + Math.max(0, bounds.width() - 30), bounds.y() + 7, 22, 22), layer, Kind.ICON);
        tabs(id + "/steps", new Bounds(bounds.x() + 14, bounds.y() + 36, Math.max(0, bounds.width() - 28), 22), layer, steps, false, 0);
        int footerTop = Math.max(bounds.y() + 65, bounds.y() + bounds.height() - Math.max(40, footerHeight));
        var content = new Bounds(bounds.x() + 8, bounds.y() + 65, Math.max(0, bounds.width() - 16), Math.max(0, footerTop - bounds.y() - 69));
        var footer = new Bounds(bounds.x() + 8, footerTop, Math.max(0, bounds.width() - 16), Math.max(0, bounds.y() + bounds.height() - footerTop));
        surface(id + "/footer-line", new Bounds(footer.x(), footer.y(), footer.width(), 1), layer, Kind.SEPARATOR);
        return new TaskArea(content, footer);
    }

    /** Source forms measure and paint against the same responsive adapter/content split. */
    public WizardArea wizardArea(Bounds bounds) {
        boolean stacked = bounds.width() < 420;
        int navigation = stacked ? Math.min(26, bounds.height()) : Math.min(145, Math.max(108, bounds.width() / 4));
        Bounds nav = stacked ? new Bounds(bounds.x(), bounds.y(), bounds.width(), navigation) : new Bounds(bounds.x(), bounds.y(), navigation, bounds.height());
        Bounds content = stacked ? new Bounds(bounds.x(), bounds.y() + navigation + 8, bounds.width(), Math.max(0, bounds.height() - navigation - 8)) : new Bounds(bounds.x() + navigation + 12, bounds.y(), Math.max(0, bounds.width() - navigation - 12), bounds.height());
        return new WizardArea(nav, content);
    }

    public WizardArea wizard(String id, Bounds bounds, int layer, List<Action> steps) {
        var area = wizardArea(bounds);
        var nav = area.navigation();
        surface(id + "/navigation", nav, layer, Kind.PANEL);
        if (bounds.width() < 420) tabs(id + "/steps", nav, layer, steps, false, 0);
        else menu(id + "/steps", steps, StudioLayout.inset(nav, 6, 6), layer);
        return area;
    }

    public void surface(String id, Bounds bounds, int layer, Kind kind) {
        if (kind == Kind.MENU || kind == Kind.DIALOG) frame.emit(id + "/shadow", Role.PANEL, "", "", new Bounds(bounds.x() + 2, bounds.y() + 3, bounds.width(), bounds.height()), true, false, false, "", layer, Visual.of(Kind.SHADOW));
        frame.emit(id, Role.PANEL, "", "", bounds, true, false, false, "", layer, Visual.of(kind));
    }

    public void button(Action action, Bounds bounds, int layer, Kind kind) {
        Role role = kind == Kind.TAB || kind == Kind.DOCUMENT_TAB ? Role.TAB : kind == Kind.TREE_ROW ? Role.TREE_ITEM : Role.BUTTON;
        frame.emit(action.id(), role, action.label(), "", bounds, action.enabled(), action.selected(), false, action.reason(), layer, Visual.of(kind).variant(action.variant()).icon(action.icon()));
        frame.action(action.id(), action.invoke());
    }

    public Bounds panel(String id, String title, Bounds bounds, int layer, List<Action> actions) {
        surface(id, bounds, layer, Kind.PANEL);
        Bounds header = new Bounds(bounds.x(), bounds.y(), bounds.width(), Math.min(metrics.header(), bounds.height()));
        surface(id + "/header", header, layer, Kind.HEADER);
        int end = bounds.x() + bounds.width() - 2;
        try (var ignored = frame.contentClipped(header)) {
            for (int i = actions.size() - 1; i >= 0; i--) {
                end -= metrics.row();
                button(actions.get(i), new Bounds(end, bounds.y() + 2, metrics.row(), metrics.row()), layer, Kind.ICON);
            }
        }
        frame.emit(id + "/title", Role.LABEL, title, "", new Bounds(bounds.x() + 3, bounds.y() + 3, Math.max(0, Math.min(captionWidth(title, Icon.NONE), end - bounds.x() - 6)), metrics.row() - 2), true, true, false, "", layer, Visual.of(Kind.TAB));
        return new Bounds(bounds.x(), bounds.y() + header.height(), bounds.width(), Math.max(0, bounds.height() - header.height()));
    }

    /** A wrapping toolbar measures captions through the same host font used for drawing. */
    public int toolbar(String id, Bounds bounds, int layer, List<Action> actions, boolean wrap) {
        int x = bounds.x() + metrics.padding(), y = bounds.y() + 3, right = bounds.x() + bounds.width() - metrics.padding();
        int desired = actions.stream().mapToInt(action -> Math.max(metrics.row(), captionWidth(action.label(), action.icon()))).sum() + Math.max(0, actions.size() - 1) * 2;
        if (!wrap && desired > Math.max(0, right - x)) {
            var boxes = StudioLayout.columns(StudioLayout.inset(bounds, metrics.padding(), 3), actions.size(), 2);
            try (var ignored = frame.contentClipped(bounds)) {
                for (int i = 0; i < actions.size(); i++) {
                    var action = actions.get(i);
                    var box = boxes.get(i);
                    button(action, box, layer, action.icon() != Icon.NONE && box.width() < measure.applyAsInt(action.label()) + 28 ? Kind.ICON : Kind.BUTTON);
                }
            }
            return metrics.row() + 6;
        }
        try (var ignored = frame.contentClipped(bounds)) {
            for (var action : actions) {
                int width = Math.max(metrics.row(), captionWidth(action.label(), action.icon()));
                width = Math.min(width, Math.max(metrics.row(), bounds.width() - metrics.padding() * 2));
                if (wrap && x > bounds.x() + metrics.padding() && x + width > right) {
                    x = bounds.x() + metrics.padding();
                    y += metrics.row() + metrics.gap();
                }
                button(action, new Bounds(x, y, Math.max(0, Math.min(width, right - x)), metrics.row()), layer, Kind.BUTTON);
                x += width + 2;
            }
        }
        return y - bounds.y() + metrics.row() + 3;
    }

    /** Text modes at the start and compact commands at the end share a non-overlapping row. */
    public void commandBar(String id, Bounds bounds, int layer, List<Action> modes, List<Action> commands) {
        int commandWidth = Math.min(bounds.width() / 2, commands.size() * (metrics.row() + 1));
        toolbar(id + "/modes", new Bounds(bounds.x(), bounds.y(), Math.max(0, bounds.width() - commandWidth), bounds.height()), layer, modes, false);
        var boxes = StudioLayout.columns(new Bounds(bounds.x() + bounds.width() - commandWidth, bounds.y() + 3, commandWidth, Math.max(0, bounds.height() - 3)), commands.size(), 1);
        try (var ignored = frame.contentClipped(bounds)) {
            for (int i = 0; i < commands.size(); i++) button(commands.get(i), boxes.get(i), layer, Kind.ICON);
        }
    }

    public void titleBar(String id, String title, Bounds bounds, int layer, List<Action> actions) {
        int desired = actions.stream().mapToInt(action -> captionWidth(action.label(), action.icon()) + 2).sum();
        int actionWidth = Math.min(Math.max(0, bounds.width() - Math.min(100, bounds.width() / 3)), desired);
        text(id + "/title", title, new Bounds(bounds.x(), bounds.y() + 1, Math.max(0, bounds.width() - actionWidth - 8), bounds.height()), layer, TextSize.TITLE, Variant.DEFAULT);
        tabs(id + "/actions", new Bounds(bounds.x() + bounds.width() - actionWidth, bounds.y(), actionWidth, bounds.height()), layer, actions, false, 0);
    }

    /**
     * Shared tab/action strip. Overflow arrows browse stable identities without invoking actions.
     * A changed selection or caller offset reveals its target; ordinary republication retains an
     * explicitly browsed window, including strips whose actions have no selected item.
     */
    public int tabs(String id, Bounds bounds, int layer, List<Action> tabs, boolean documents, int offset) {
        return tabStrip(id, bounds, layer, tabs, documents, offset, 0, (action, box) -> button(action, box, layer, documents ? Kind.DOCUMENT_TAB : Kind.TAB));
    }

    /**
     * Dock titles use ordinary tab overflow and isolated header actions. The source marker is
     * decorative, so it cannot capture a release intended for a title or command.
     */
    public int dockHeader(String id, Bounds bounds, int layer, List<Action> tabs, List<Action> actions, String dragging, int offset) {
        surface(id + "/surface", bounds, layer, Kind.HEADER);
        int actionWidth = Math.min(bounds.width(), actions.size() * 20);
        var strip = new Bounds(bounds.x() + Math.min(2, bounds.width()), bounds.y() + 2, Math.max(0, bounds.width() - actionWidth - 4), Math.max(0, bounds.height() - 4));
        boolean revealSource = tabs.stream().anyMatch(action -> action.id().equals(dragging));
        var visibleTabs = revealSource ? tabs.stream().map(action -> new Action(action.id(), action.label(), action.icon(), action.variant(), action.enabled(), action.id().equals(dragging), action.reason(), action.invoke())).toList() : tabs;
        int first = tabStrip(id, strip, layer, visibleTabs, false, offset, 0, (action, box) -> {
            button(action, box, layer, Kind.TAB);
            frame.detail(action.id(), action.label());
            if (action.id().equals(dragging)) {
                frame.visual(action.id(), frame.visual(action.id()).flags(false, false, true, false));
                frame.emit(action.id() + "/drag", Role.PANEL, "", "", new Bounds(box.x(), box.y() + Math.max(0, box.height() - 2), box.width(), Math.min(2, box.height())), true, false, false, "", layer, Visual.of(Kind.BUTTON).variant(Variant.PRIMARY));
            }
        });
        try (var ignored = frame.contentClipped(bounds)) {
            for (int i = 0; i < actions.size(); i++) {
                var action = actions.get(i);
                button(action, new Bounds(bounds.x() + bounds.width() - actionWidth + i * 20, bounds.y() + 2, Math.min(20, actionWidth), Math.max(0, bounds.height() - 4)), layer, Kind.ICON);
                frame.detail(action.id(), action.label());
            }
        }
        return first;
    }

    /** Actual empty slots and selected target outline, using the same theme accent as tabs. */
    public void dockTarget(String id, StudioDocking.Region region, int layer, boolean selected, String emptyLabel) {
        var bounds = region.bounds();
        if (region.empty()) {
            surface(id + "/empty", bounds, layer, Kind.PANEL);
            paragraph(id + "/hint", emptyLabel, new Bounds(bounds.x() + 8, bounds.y() + Math.min(30, bounds.height()), Math.max(0, bounds.width() - 16), Math.max(0, bounds.height() - 30)), layer, Variant.MUTED);
        }
        if (!selected) return;
        int stroke = Math.min(2, Math.min(bounds.width(), bounds.height()));
        if (stroke == 0) return;
        var edges = List.of(new Bounds(bounds.x(), bounds.y(), bounds.width(), stroke), new Bounds(bounds.x(), bounds.y() + bounds.height() - stroke, bounds.width(), stroke), new Bounds(bounds.x(), bounds.y(), stroke, bounds.height()), new Bounds(bounds.x() + bounds.width() - stroke, bounds.y(), stroke, bounds.height()));
        for (int i = 0; i < edges.size(); i++) frame.emit(id + "/edge/" + i, Role.PANEL, "", "", edges.get(i), true, false, false, "", layer, Visual.of(Kind.BUTTON).variant(Variant.PRIMARY));
    }

    /** Compound document tabs share overflow with ordinary strips, but close has its own hit target. */
    public int documentTabs(String id, Bounds bounds, int layer, List<DocumentTab> tabs, int offset) {
        var entries = new LinkedHashMap<String, DocumentTab>();
        for (var tab : tabs) {
            if (entries.put(tab.activate().id(), tab) != null) throw new IllegalArgumentException("duplicate document tab");
        }
        return tabStrip(id, bounds, layer, tabs.stream().map(DocumentTab::activate).toList(), true, offset, 32, (action, box) -> documentTab(entries.get(action.id()), box, layer));
    }

    private void documentTab(DocumentTab tab, Bounds bounds, int layer) {
        var action = tab.activate();
        String id = action.id();
        frame.emit(id + "/surface", Role.PANEL, "", "", bounds, true, action.selected(), false, "", layer, Visual.of(Kind.DOCUMENT_TAB).flags(false, false, tab.dragging(), false));
        int closeWidth = Math.min(22, bounds.width()), closeX = bounds.x() + bounds.width() - closeWidth;
        // The fixed marker slot keeps titles still when raw input or a save changes the dirty flag.
        Bounds body = new Bounds(bounds.x() + Math.min(6, bounds.width()), bounds.y(), Math.max(0, bounds.width() - closeWidth - 18), bounds.height());
        frame.emit(id, Role.TAB, action.label(), "", body, action.enabled(), action.selected(), false, action.reason(), layer, Visual.of(Kind.LABEL).icon(action.icon()).variant(action.selected() ? Variant.DEFAULT : Variant.MUTED).flags(false, false, tab.dragging(), false));
        frame.action(id, action.invoke());
        frame.detail(id, action.label());
        if (tab.dirty() && bounds.width() > closeWidth + 10) text(id + "/dirty", "•", new Bounds(closeX - 10, bounds.y() + 6, 8, 12), layer, TextSize.BODY, Variant.MUTED);
        button(new Action(id + "/close", tab.closeLabel(), Icon.CLOSE, Variant.MUTED, true, false, "", tab.close()), new Bounds(closeX, bounds.y() + 2, closeWidth, Math.max(0, bounds.height() - 4)), layer, Kind.ICON);
        if (tab.insertion() != TabInsertion.NONE) {
            int markerWidth = Math.min(2, bounds.width()), markerX = tab.insertion() == TabInsertion.AFTER ? bounds.x() + bounds.width() - markerWidth : bounds.x();
            frame.emit(id + "/drop", Role.PANEL, "", "", new Bounds(markerX, bounds.y() + 2, markerWidth, Math.max(0, bounds.height() - 4)), true, false, false, "", layer, Visual.of(Kind.BUTTON).variant(Variant.PRIMARY));
        }
    }

    private int tabStrip(String id, Bounds bounds, int layer, List<Action> tabs, boolean documents, int offset, int trailingWidth, BiConsumer<Action, Bounds> emit) {
        surface(id + "/bar", bounds, layer, Kind.HEADER);
        if (tabs.isEmpty()) {
            tabViews.remove(id);
            return 0;
        }
        int cap = documents ? 190 : 140, gap = documents ? 0 : 2;
        int[] widths = tabs.stream().mapToInt(action -> Math.min(cap, Math.max(metrics.row() + trailingWidth, captionWidth(action.label(), action.icon()) + trailingWidth))).toArray();
        boolean overflow = Arrays.stream(widths).sum() + gap * (tabs.size() - 1) > bounds.width();
        int navigationWidth = overflow ? Math.min(36, bounds.width() / 2) : 0;
        Bounds area = new Bounds(bounds.x(), bounds.y(), bounds.width() - navigationWidth, bounds.height());
        int first = Math.max(0, Math.min(offset, tabs.size() - 1));
        int selected = -1;
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).selected()) selected = i;
        String selectedId = selected < 0 ? null : tabs.get(selected).id();
        var ids = tabs.stream().map(Action::id).toList();
        var previousView = tabViews.get(id);
        boolean browsing = false;
        if (previousView != null && (offset == previousView.requestedOffset() || offset == previousView.reportedOffset())) {
            int anchor = ids.indexOf(previousView.ids().get(previousView.first()));
            first = anchor < 0 ? Math.min(previousView.first(), tabs.size() - 1) : anchor;
            browsing = previousView.browsing() && Objects.equals(selectedId, previousView.selected());
        }
        if (!overflow) {
            first = 0;
            browsing = false;
        } else if (selected >= 0 && !browsing) {
            if (selected < first) first = selected;
            int need = 0;
            for (int i = first; i <= selected; i++) need += widths[i] + (i == first ? 0 : gap);
            while (first < selected && need > area.width()) {
                need -= widths[first++] + gap;
            }
        }
        var view = new TabView(offset, first, first, selectedId, ids, browsing);
        tabViews.put(id, view);
        try (var ignored = frame.contentClipped(bounds)) {
            int x = area.x();
            for (int i = first; i < tabs.size(); i++) {
                int remaining = area.x() + area.width() - x;
                if (remaining <= 0 || i > first && trailingWidth > 0 && remaining < metrics.row() + trailingWidth) break;
                int width = Math.min(widths[i], remaining);
                emit.accept(tabs.get(i), new Bounds(x, area.y(), width, area.height()));
                x += width + gap;
                if (width < widths[i]) break;
            }
            if (overflow) {
                int before = Math.max(0, first - 1), after = Math.min(tabs.size() - 1, first + 1), arrowWidth = navigationWidth / 2;
                button(new Action(id + "/previous", tabs.get(before).label(), Icon.PREVIOUS, Variant.MUTED, first > 0, false, "", () -> browseTabs(id, view, before)), new Bounds(area.x() + area.width(), bounds.y() + 2, arrowWidth, metrics.row()), layer, Kind.ICON);
                button(new Action(id + "/next", tabs.get(after).label(), Icon.NEXT, Variant.MUTED, first < tabs.size() - 1, false, "", () -> browseTabs(id, view, after)), new Bounds(area.x() + area.width() + arrowWidth, bounds.y() + 2, navigationWidth - arrowWidth, metrics.row()), layer, Kind.ICON);
            }
        }
        return first;
    }

    private void browseTabs(String id, TabView view, int first) {
        tabViews.put(id, new TabView(view.requestedOffset(), view.reportedOffset(), first, view.selected(), view.ids(), true));
        invalidate.run();
    }

    public void treeRow(TreeRow row, Bounds bounds, int layer) {
        int indent = Math.min(Math.max(0, row.depth()) * metrics.treeIndent(), Math.max(0, bounds.width() - 65));
        if (row.selected()) frame.emit(row.id() + "/selection", Role.PANEL, "", "", bounds, true, true, false, "", layer, Visual.of(Kind.TREE_ROW));
        int arrow = metrics.row() - 5;
        if (row.branch()) button(new Action(row.id() + "/expand", "", row.expanded() ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, Variant.MUTED, true, false, "", row.toggle()), new Bounds(bounds.x() + indent, bounds.y(), arrow, bounds.height()), layer, Kind.ICON);
        button(new Action(row.id(), row.label(), row.icon(), Variant.DEFAULT, true, row.selected(), "", row.activate()), new Bounds(bounds.x() + indent + arrow, bounds.y(), Math.max(0, bounds.width() - indent - arrow), bounds.height()), layer, Kind.TREE_ROW);
    }

    public void input(String id, String label, String raw, Bounds bounds, int layer, boolean editable, boolean invalid, Consumer<String> change) {
        frame.emit(id, Role.TEXT_FIELD, label, raw, bounds, true, false, !editable, "", layer, Visual.of(Kind.INPUT).flags(false, false, false, invalid));
        frame.input(id, change);
    }

    /**
     * Labels, vectors, choices, toggles, validation and explicit row actions share one form layout.
     * The action-group prefix suppresses blur only for this row's Apply/Discard controls.
     */
    public int fieldRowHeight(int count, boolean toggle, boolean pending, boolean error, int width) {
        if (count < 1) throw new IllegalArgumentException("empty field row");
        var fields = java.util.stream.IntStream.range(0, count).mapToObj(index -> minimumField(index, toggle ? FieldKind.TOGGLE : FieldKind.TEXT)).toList();
        var actions = pending || error ? new FieldActions("measure", "", "", true, pending ? () -> {} : null, () -> {}, error ? "x" : "") : null;
        return measureDocumentFields(fields, actions, width).height();
    }

    /**
     * Minimum input geometry when no labels or hints are available. For real content use
     * measureDocumentFields and its exact FieldGeometry.control bounds.
     */
    public Bounds fieldControlBounds(List<FieldKind> fields, int index, Bounds bounds) {
        var values = java.util.stream.IntStream.range(0, fields.size()).mapToObj(i -> minimumField(i, fields.get(i))).toList();
        return translate(measureDocumentFields(values, null, bounds.width()).fields().get(index).control(), bounds.x(), bounds.y());
    }

    private static Field minimumField(int index, FieldKind kind) {
        return new Field("studio-measure/" + index, "", "", "", "", kind, 0, true, false, value -> {}, () -> {}, () -> {});
    }

    /** Document fields reserve the same measured lines and controls that are later drawn. */
    public FieldRowLayout measureDocumentFields(List<Field> fields, FieldActions actions, int width) {
        if (fields.isEmpty()) throw new IllegalArgumentException("empty field row");
        String id = fields.get(0).id() + "/measured-row";
        var layout = view.layoutContent(view.fields(id, fields, actions), 0, 0, width);
        var result = new ArrayList<FieldGeometry>();
        var empty = new Bounds(0, 0, 0, 0);
        for (var field : fields) {
            TextSize size = field.kind() == FieldKind.TOGGLE ? TextSize.BODY : TextSize.SMALL;
            var label = layout.contentBounds(field.id() + "/label");
            var hint = field.hint().isBlank() ? empty : layout.contentBounds(field.id() + "/hint");
            result.add(new FieldGeometry(label, textLayout.wrap(field.label(), label.width(), size).lines(), size,
                    layout.contentBounds(field.id()), hint, field.hint().isBlank() ? List.of() : textLayout.wrap(field.hint(), hint.width(), TextSize.SMALL).lines()));
        }
        boolean pending = actions != null && actions.apply() != null, failed = actions != null && !actions.error().isBlank();
        var error = failed ? layout.contentBounds(id + "/error") : empty;
        return new FieldRowLayout(width, layout.contentHeight(), result, pending ? layout.contentBounds(id + "/apply") : null,
                pending ? layout.contentBounds(id + "/discard") : null, error, failed ? textLayout.wrap(actions.error(), error.width(), TextSize.SMALL).lines() : List.of());
    }

    private static Bounds translate(Bounds bounds, int x, int y) {
        return new Bounds(bounds.x() + x, bounds.y() + y, bounds.width(), bounds.height());
    }

    private void fieldLines(String id, List<String> lines, Bounds bounds, int x, int y, int layer, TextSize size, Variant variant, Integer color) {
        if (lines.isEmpty()) return;
        var visual = Visual.of(Kind.LABEL).size(size).variant(variant).lines(lines);
        if (color != null) visual = visual.color(color);
        frame.emit(id, Role.LABEL, String.join("\n", lines), "", translate(bounds, x, y), true, false, false, "", layer, visual);
    }

    public void documentFields(String id, List<Field> fields, FieldActions actions, FieldRowLayout layout, int x, int y, int layer) {
        if (fields.size() != layout.fields().size()) throw new IllegalArgumentException("field layout differs");
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var geometry = layout.fields().get(i);
            fieldLines(field.id() + "/label", geometry.labelLines(), geometry.label(), x, y, layer, geometry.labelSize(), field.kind() == FieldKind.TOGGLE ? Variant.DEFAULT : Variant.MUTED, field.kind() == FieldKind.TOGGLE ? null : field.labelColor());
            var control = translate(geometry.control(), x, y);
            if (field.kind() == FieldKind.TEXT) {
                input(field.id(), field.label(), field.raw(), control, layer, field.enabled(), field.invalid(), field.change());
                frame.blur(field.id(), id + "/", field.commit());
            } else button(new Action(field.id(), field.kind() == FieldKind.TOGGLE ? field.label() : field.display(), field.kind() == FieldKind.CHOICE ? Icon.CHEVRON_DOWN : Icon.NONE, Variant.OUTLINED, field.enabled(), field.kind() == FieldKind.TOGGLE && Boolean.parseBoolean(field.raw()), field.hint(), field.choose()), control, layer, field.kind() == FieldKind.TOGGLE ? Kind.CHECKBOX : Kind.BUTTON);
            fieldLines(field.id() + "/hint", geometry.hintLines(), geometry.hint(), x, y, layer, TextSize.SMALL, Variant.MUTED, null);
            frame.detail(field.id(), field.label() + (field.hint().isBlank() ? "" : " · " + field.hint()));
        }
        if (actions != null) {
            if (layout.apply() != null) {
                button(new Action(id + "/apply", actions.applyLabel(), Icon.NONE, Variant.OUTLINED, actions.enabled(), false, "", actions.apply()), translate(layout.apply(), x, y), layer, Kind.BUTTON);
                button(Action.plain(id + "/discard", actions.discardLabel(), actions.discard()), translate(layout.discard(), x, y), layer, Kind.BUTTON);
            }
            fieldLines(id + "/error", layout.errorLines(), layout.error(), x, y, layer, TextSize.SMALL, Variant.DANGER, null);
        }
    }

    public int fieldRow(String id, List<Field> fields, FieldActions actions, Bounds bounds, int layer) {
        var layout = view.layoutContent(view.fields(id, fields, actions), bounds.x(), bounds.y(), bounds.width());
        if (bounds.width() > 0) layout.emit(frame, layer);
        return layout.contentHeight();
    }

    /** Scrollable single-column form, sharing field semantics with inline inspector rows. */
    public int form(String id, List<Field> fields, Bounds bounds, int layer, int offset, IntConsumer change) {
        return formRows(id, fields.stream().map(List::of).toList(), bounds, layer, offset, change);
    }

    /**
     * One scrolling body, matching st-dock-body: the caller keeps any fixed dock header outside.
     * Measure content against max(0, viewport.width()-8); the same reserved rail is used even when
     * no scrollbar is needed. Composition never invokes change: the returned offset is clamped,
     * while the shared scrollbar invokes change only in response to input. Empty viewports emit
     * no controls or scroll regions. Nested clips restore their caller even if content throws.
     * IDs must identify the current document/page context: keyboard reveal uses them to reject
     * delayed focus after context changes. All interactive children retain clipped semantic nodes.
     * Ancestor clipping supplies the vertical origin, height and scroll range; declared horizontal
     * layout remains unchanged. Empty effective apertures never invoke the content callback.
     */
    public int scrollContent(String id, Bounds viewport, int contentHeight, int layer, int offset, IntConsumer change, Consumer<Bounds> content) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(viewport);
        Objects.requireNonNull(change);
        Objects.requireNonNull(content);
        if (contentHeight < 0) throw new IllegalArgumentException("negative content height");
        var effective = frame.clip() == null ? viewport : viewport.intersect(frame.clip());
        int position = Math.max(0, Math.min(offset, Math.max(0, contentHeight - effective.height())));
        if (effective.width() == 0 || effective.height() == 0) return position;
        int contentWidth = Math.max(0, viewport.width() - 8);
        var scrollingViewport = new Bounds(viewport.x(), effective.y(), viewport.width(), effective.height());
        try (var viewportClip = frame.clipped(scrollingViewport)) {
            var contentViewport = new Bounds(viewport.x(), effective.y(), contentWidth, effective.height());
            if (contentWidth > 0 && contentHeight > 0 && contentViewport.intersect(frame.clip()).width() > 0) try (var contentClip = frame.clipped(contentViewport);
                    var reveal = frame.revealScope(new StudioFocusNavigation.Region(id, frame.clip(), position, Math.max(0, contentHeight - effective.height()), layer, change))) {
                        content.accept(new Bounds(viewport.x(), effective.y() - position, contentWidth, contentHeight));
                    }
            pixelScroll(id + "/scroll", scrollingViewport, position, contentHeight, layer, change);
        }
        return position;
    }

    /** Measures declared field groups at their actual content width, without reserving a scroll rail. */
    public int formRowsHeight(List<List<Field>> rows, int width) {
        if (width < 0) throw new IllegalArgumentException("negative form width");
        return formRowsLayout(rows, width).contentHeight();
    }

    private StudioView.Layout formRowsLayout(List<List<Field>> rows, int width) {
        var groups = rows.stream().filter(row -> !row.isEmpty()).map(row -> view.fields(row.get(0).id() + "/row", row, null)).toList();
        String id = rows.stream().filter(row -> !row.isEmpty()).findFirst().map(row -> row.get(0).id() + "/form-rows").orElse("studio/empty-form-rows");
        return view.layoutContent(view.column(id, Style.defaults(), groups), 0, 0, width);
    }

    /**
     * Composes non-scrolling field groups using only bounds' origin and actual content width.
     * The caller's current clip controls visibility; bounds.height does not add another clip.
     * Empty rows occupy no space. The returned height matches formRowsHeight even when rows are
     * clipped away or the width is zero; zero width publishes no controls or callbacks.
     * Positive-width rows retain their semantic nodes outside the clip for keyboard reveal;
     * ControlTree still excludes those invisible nodes from actual focus and hit testing.
     */
    public int formRowsContent(List<List<Field>> rows, Bounds bounds, int layer) {
        var layout = formRowsLayout(rows, bounds.width());
        if (bounds.width() > 0) layout.translated(bounds.x(), bounds.y()).emit(frame, layer);
        return layout.contentHeight();
    }

    /** A scrolling wrapper over the same non-scrolling field groups and their stable identities. */
    public int formRows(String id, List<List<Field>> rows, Bounds bounds, int layer, int offset, IntConsumer change) {
        var layout = formRowsLayout(rows, Math.max(0, bounds.width() - 8));
        return scrollContent(id, bounds, layout.contentHeight(), layer, offset, change, body -> layout.translated(body.x(), body.y()).emit(frame, layer));
    }

    public void disclosure(String id, String title, String subtitle, boolean expanded, boolean card, Bounds bounds, int layer, Runnable toggle) {
        surface(id + "/surface", bounds, layer, card ? Kind.INLINE_CARD : Kind.SECTION);
        button(new Action(id, title, expanded ? Icon.CHEVRON_DOWN : Icon.CHEVRON_RIGHT, Variant.DEFAULT, true, false, "", toggle), new Bounds(bounds.x() + 4, bounds.y() + 3, Math.max(0, bounds.width() - 8), metrics.row()), layer, Kind.BUTTON);
        if (!subtitle.isEmpty()) text(id + "/subtitle", subtitle, new Bounds(bounds.x() + 17, bounds.y() + metrics.row() + 3, Math.max(0, bounds.width() - 23), 12), layer, TextSize.SMALL, Variant.MUTED);
    }

    public void scrollbar(String id, Bounds viewport, int offset, int shown, int total, int layer, IntConsumer change) {
        scrollbar(id, viewport, offset, shown, total, layer, 1, change);
    }

    public void pixelScroll(String id, Bounds viewport, int offset, int contentHeight, int layer, IntConsumer change) {
        scrollbar(id, viewport, offset, viewport.height(), contentHeight, layer, metrics.row(), change);
    }

    private void scrollbar(String id, Bounds viewport, int offset, int shown, int total, int layer, int step, IntConsumer change) {
        if (total <= shown || viewport.height() <= 0) return;
        int maximum = total - shown, thumbHeight = Math.min(viewport.height(), Math.max(16, viewport.height() * shown / total));
        var track = new Bounds(viewport.x() + Math.max(0, viewport.width() - 7), viewport.y(), Math.min(7, viewport.width()), viewport.height());
        var thumb = new Bounds(track.x() + 1, track.y() + (int) Math.round((track.height() - thumbHeight) * Math.max(0, Math.min(maximum, offset)) / (double) maximum), Math.max(0, track.width() - 2), thumbHeight);
        surface(id + "/track", track, layer, Kind.SCROLL_TRACK);
        surface(id + "/thumb", thumb, layer, Kind.SCROLL_THUMB);
        frame.scroll(new StudioScrollState.Region(viewport, track, thumb, offset, maximum, layer, change, viewport, step, id + "/track"));
    }

    /**
     * A domain-neutral fixed-position card grid. Empty positions remain callbacks supplied by the
     * caller; composition never allocates objects or derives ownership from an identifier.
     */
    public GridResult slotGrid(String id, List<Column> columns, List<Slot> slots, Bounds bounds, int layer, int firstColumn, int rowOffset,
                               IntConsumer changeColumn, IntConsumer changeRow, RangeText range) {
        int count = Math.max(1, Math.min(columns.size(), Math.max(1, (bounds.width() - 12) / 76))), first = Math.max(0, Math.min(firstColumn, Math.max(0, columns.size() - count)));
        int top = bounds.y();
        if (count < columns.size()) {
            int selected = first;
            button(new Action(id + "/previous", "", Icon.PREVIOUS, Variant.DEFAULT, first > 0, false, "", () -> changeColumn.accept(Math.max(0, selected - 1))), new Bounds(bounds.x(), top, 20, 20), layer, Kind.ICON);
            text(id + "/columns", columns.get(first).label(), new Bounds(bounds.x() + 24, top + 4, Math.max(0, bounds.width() - 48), 12), layer, TextSize.SMALL, Variant.MUTED);
            button(new Action(id + "/next", "", Icon.NEXT, Variant.DEFAULT, first + count < columns.size(), false, "", () -> changeColumn.accept(Math.min(columns.size() - count, selected + 1))), new Bounds(bounds.x() + bounds.width() - 20, top, 20, 20), layer, Kind.ICON);
            top += 23;
        }
        int total = columns.stream().mapToInt(Column::rows).max().orElse(0), bodyHeight = Math.max(0, bounds.y() + bounds.height() - top - 30);
        int pitch = Math.max(48, Math.min(metrics.slotHeight() + metrics.gap(), total == 0 ? metrics.slotHeight() + metrics.gap() : (bodyHeight + metrics.gap()) / total));
        Bounds body = new Bounds(bounds.x(), top + 18, bounds.width(), bodyHeight);
        var window = StudioLayout.window(body, total, rowOffset, pitch - metrics.gap(), metrics.gap());
        var boxes = StudioLayout.columns(new Bounds(bounds.x(), top, Math.max(0, bounds.width() - 10), bodyHeight), count, metrics.gap());
        for (int ci = first; ci < Math.min(columns.size(), first + count); ci++) {
            var column = columns.get(ci);
            var cb = boxes.get(ci - first);
            if (column.select() != null) button(Action.plain(id + "/column/" + column.id(), column.label(), column.select()), new Bounds(cb.x(), top, cb.width(), 18), layer, Kind.BUTTON);
            else text(id + "/column/" + column.id(), (ci + 1) + " " + column.label(), new Bounds(cb.x(), top + 4, cb.width(), 12), layer, TextSize.SMALL, Variant.DEFAULT);
            try (var ignored = frame.contentClipped(body)) {
                for (var slot : slots) if (slot.column().equals(column.id()) && slot.row() >= window.offset() && slot.row() < window.end()) {
                    Bounds box = new Bounds(cb.x(), body.y() + (slot.row() - window.offset()) * pitch, cb.width(), pitch - metrics.gap());
                    var lines = new ArrayList<String>();
                    lines.add(Integer.toString(slot.row() + 1));
                    lines.addAll(slot.details().stream().limit(2).toList());
                    while (lines.size() < 3) lines.add("");
                    lines.add(slot.badge());
                    frame.emit(slot.id(), Role.BUTTON, slot.title(), "", box, slot.enabled(), slot.selected(), false, slot.reason(), layer, Visual.of(Kind.SLOT).lines(lines).flags(slot.empty(), slot.peer(), slot.dimmed(), false));
                    var tooltip = new ArrayList<>(slot.details());
                    if (!slot.badge().isBlank()) tooltip.add(slot.badge());
                    frame.action(slot.id(), slot.activate());
                    frame.detail(slot.id(), String.join(" · ", tooltip));
                }
            }
        }
        text(id + "/range", range.format(total == 0 ? 0 : window.offset() + 1, window.end(), total), new Bounds(bounds.x(), bounds.y() + bounds.height() - 12, bounds.width(), 12), layer, TextSize.SMALL, Variant.MUTED);
        scrollbar(id + "/scroll", body, window.offset(), window.visible(), total, layer, changeRow);
        return new GridResult(first, count, window);
    }
}
