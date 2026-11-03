package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One explicit, single-use identity read, safe to pass to an I/O executor. */
public final class WorldBindingRequest {

    final Object issuer;
    final Path root;
    final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final UUID requestId;
    private final UUID actorId;
    private final Map<EditorId, EditorId> dimensions;

    WorldBindingRequest(Object issuer, Path root, UUID requestId, UUID actorId,
                        Map<EditorId, EditorId> dimensions) {
        this.issuer = issuer;
        this.root = root;
        this.requestId = requestId;
        this.actorId = actorId;
        this.dimensions = dimensions;
    }

    public UUID requestId() {
        return requestId;
    }

    public UUID actorId() {
        return actorId;
    }

    public Map<EditorId, EditorId> dimensions() {
        return dimensions;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public WorldBindingRead read(FileIdentityProvider provider, BooleanSupplier cancelled)
                                                                                           throws WorldIdentityException {
        if (!started.compareAndSet(false, true)) {
            throw new WorldBindingException(WorldBindingException.Code.STALE, "Identity read already attempted");
        }
        try {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(cancelled, "cancelled");
            return new WorldBindingRead(this, WorldIdentities.read(root, provider,
                    () -> cancellation(cancelled)));
        } catch (WorldIdentityException | RuntimeException | Error failure) {
            cancel();
            throw failure;
        }
    }

    private boolean cancellation(BooleanSupplier external) {
        if (cancelled.get()) return true;
        if (external.getAsBoolean()) cancelled.set(true);
        return cancelled.get();
    }
}
