package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Opaque current local binding; it carries neither a host nor a filesystem path. */
public final class WorldBinding {

    final Object issuer;
    final AtomicBoolean cancelled;
    static final Object REVOKED = new Object();
    final AtomicReference<Object> checkState = new AtomicReference<>();
    private final UUID requestId;
    private final UUID actorId;
    private final UUID worldId;
    private final Map<EditorId, EditorId> dimensions;

    WorldBinding(WorldBindingRequest request, UUID worldId) {
        issuer = request.issuer;
        cancelled = request.cancelled;
        requestId = request.requestId();
        actorId = request.actorId();
        dimensions = request.dimensions();
        this.worldId = worldId;
    }

    public UUID requestId() {
        return requestId;
    }

    public UUID actorId() {
        return actorId;
    }

    public UUID worldId() {
        return worldId;
    }

    public Map<EditorId, EditorId> dimensions() {
        return dimensions;
    }

    boolean invalid() {
        return cancelled.get() || checkState.get() == REVOKED;
    }
}
