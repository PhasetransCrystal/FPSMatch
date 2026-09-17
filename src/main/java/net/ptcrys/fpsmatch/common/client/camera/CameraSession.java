package net.ptcrys.fpsmatch.common.client.camera;

import net.ptcrys.fpsmatch.common.camera.CameraEndReason;
import net.ptcrys.fpsmatch.common.camera.CameraOwnership;
import net.ptcrys.fpsmatch.common.camera.SequenceClock;

import net.minecraft.client.multiplayer.ClientLevel;

import java.util.function.BooleanSupplier;

public final class CameraSession implements AutoCloseable {

    final CameraRig rig;
    final CameraPolicy policy;
    final SequenceClock clock;
    final BooleanSupplier valid;
    final ClientLevel level;
    final int duration;
    final CameraLifetime lifetime;
    CameraOwnership.Lease<CameraSession> lease;
    long bindingRevision = -1;
    Runnable onBound = () -> {};

    CameraSession(CameraRig rig, CameraPolicy policy, SequenceClock clock, BooleanSupplier valid, ClientLevel level, int duration, CameraLifetime lifetime) {
        this.rig = rig;
        this.policy = policy;
        this.clock = clock;
        this.valid = valid;
        this.level = level;
        this.duration = duration;
        this.lifetime = lifetime;
    }

    public boolean isActive() {
        return CameraDirector.isActive(this);
    }

    public SequenceClock clock() {
        return clock;
    }

    /** Rebind scene effects after Minecraft resets entity-specific post processing. */
    public void onCameraBound(Runnable action) {
        onBound = java.util.Objects.requireNonNull(action);
    }

    public void stop(CameraEndReason reason) {
        CameraDirector.stop(this, reason);
    }

    @Override
    public void close() {
        stop(CameraEndReason.CANCELLED);
    }
}
