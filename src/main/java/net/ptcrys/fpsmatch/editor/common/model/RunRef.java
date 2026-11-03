package net.ptcrys.fpsmatch.editor.common.model;

/**
 * Reference to one run (match execution) on a specific instance incarnation. The opaque run id is
 * never ordered or reinterpreted by clients.
 */
public record RunRef(InstanceRef instance, Ids.RunId runId) {

    public RunRef {
        if (instance == null) {
            throw new IllegalArgumentException("instance must not be null");
        }
        if (runId == null) {
            throw new IllegalArgumentException("runId must not be null");
        }
    }
}
