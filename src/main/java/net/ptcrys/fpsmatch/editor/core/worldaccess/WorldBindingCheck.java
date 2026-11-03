package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentities;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Single-use disk verification without a retained host or controller. */
public final class WorldBindingCheck {

    final Object issuer;
    final WorldBinding binding;
    final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final WorldIdentitySnapshot snapshot;

    WorldBindingCheck(Object issuer, WorldBinding binding, WorldIdentitySnapshot snapshot) {
        this.issuer = issuer;
        this.binding = binding;
        this.snapshot = snapshot;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public WorldBindingCheckResult verify(BooleanSupplier cancelled) throws WorldIdentityException {
        if (!started.compareAndSet(false, true)) {
            throw new WorldBindingException(WorldBindingException.Code.STALE, "Identity check already attempted");
        }
        try {
            Objects.requireNonNull(cancelled, "cancelled");
            WorldIdentities.verify(snapshot, () -> cancellation(cancelled));
            return new WorldBindingCheckResult(this);
        } catch (WorldIdentityException failure) {
            cancel();
            if (failure.code() != WorldIdentityException.Code.CANCELLED) {
                binding.checkState.compareAndSet(this, WorldBinding.REVOKED);
            }
            throw failure;
        } catch (RuntimeException | Error failure) {
            cancel();
            throw failure;
        }
    }

    private boolean cancellation(BooleanSupplier external) {
        if (cancelled.get() || binding.invalid() || binding.checkState.get() != this) return true;
        if (external.getAsBoolean()) cancelled.set(true);
        return cancelled.get() || binding.invalid() || binding.checkState.get() != this;
    }
}
