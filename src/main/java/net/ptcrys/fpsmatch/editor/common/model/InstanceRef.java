package net.ptcrys.fpsmatch.editor.common.model;

/**
 * Reference to one runtime instance of a map definition. The incarnation distinguishes successive
 * generations of the same instance id, so a stale target can never be mistaken for a live one.
 */
public record InstanceRef(WorldRef world, Ids.InstanceId instanceId, Ids.IncarnationId incarnationId) {

    public InstanceRef {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        if (instanceId == null) {
            throw new IllegalArgumentException("instanceId must not be null");
        }
        if (incarnationId == null) {
            throw new IllegalArgumentException("incarnationId must not be null");
        }
    }
}
