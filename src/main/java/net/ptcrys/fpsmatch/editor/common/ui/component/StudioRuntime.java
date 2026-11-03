package net.ptcrys.fpsmatch.editor.common.ui.component;

import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree;
import net.ptcrys.fpsmatch.editor.common.ui.control.ControlTree.Role;
import net.ptcrys.fpsmatch.editor.common.ui.input.EditSessionStore;
import net.ptcrys.fpsmatch.editor.common.ui.input.SingleLineText;

import java.util.Map;
import java.util.Objects;

/**
 * Owner-thread publication boundary. Rendering consumes one immutable Published value;
 * mutable editors are exposed only through input for event handling. No IME bridge is implied.
 */
public final class StudioRuntime {

    /** A mounted control instance, independent of repaint/frame counters and edit identity. */
    public record Mount(String scope, String id, Role role, long generation) {

        public Mount {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(id);
            Objects.requireNonNull(role);
        }
    }

    public record Published(long frameId, StudioFrame.Snapshot snapshot, ControlTree.Frame geometry,
                            Map<String, SingleLineText.Snapshot> edits,
                            Map<String, EditSessionStore.Diagnostic> diagnostics, String focusedId,
                            Map<String, Mount> mounts) {

        public Published {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(geometry);
            edits = Map.copyOf(edits);
            diagnostics = Map.copyOf(diagnostics);
            mounts = Map.copyOf(mounts);
        }

        /** Null means that the semantic ID is not mounted in this publication. */
        public Mount mount(String id) {
            return id == null ? null : mounts.get(id);
        }

        public boolean isCurrent(Mount mount) {
            return mount != null && mount.equals(mounts.get(mount.id()));
        }
    }

    private final ControlTree tree;
    private final StudioComponents.InputBridge input;

    public StudioRuntime() {
        this(new ControlTree());
    }

    public StudioRuntime(ControlTree tree) {
        this.tree = Objects.requireNonNull(tree);
        input = new StudioComponents.InputBridge(tree);
    }

    public ControlTree tree() {
        return tree;
    }

    public StudioComponents.InputBridge input() {
        return input;
    }

    public EditSessionStore sessions() {
        return tree.sessions();
    }

    public Published frame() {
        return input.frame();
    }

    public Published publish(StudioFrame.Snapshot next) {
        input.publish(next);
        return frame();
    }

    public Published publish(String sessionScope, StudioFrame.Snapshot next) {
        input.publish(next, sessionScope);
        return frame();
    }

    /**
     * Commits companion prepared layout caches before blur/reveal effects may rebuild.
     * afterCommit must be a non-failing commit, never user code or additional preparation.
     */
    public Published publish(StudioFrame.Snapshot next, Runnable afterCommit) {
        input.publish(next, input.sessionScope(), afterCommit);
        return frame();
    }

    public Published publish(String sessionScope, StudioFrame.Snapshot next, Runnable afterCommit) {
        input.publish(next, sessionScope, afterCommit);
        return frame();
    }

    /** Call after event handlers mutate caret/selection directly; never mutate editors in render. */
    public Published captureEdits() {
        return input.captureEdits();
    }

    public void releasePrefix(String prefix) {
        sessions().releasePrefix(input.sessionScope(), prefix);
    }

    public void clearSessions() {
        sessions().clear();
    }
}
