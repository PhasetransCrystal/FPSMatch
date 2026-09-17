package net.ptcrys.fpsmatch.common.client.camera.rig;

import net.ptcrys.fpsmatch.common.client.camera.*;
import net.ptcrys.fpsmatch.common.client.spec.SpectateTarget;
import net.ptcrys.fpsmatch.common.client.spec.SpectatorCameraMath;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** Instance-owned orbit state; sampling is called once per rendered frame by the director. */
public final class OrbitRig implements CameraRig {

    private final Supplier<SpectateTarget> target;
    private float yaw, pitch, targetYaw, targetPitch;
    private double radius = SpectatorCameraMath.DEFAULT_ORBIT_RADIUS;
    private long lastFrame;

    public OrbitRig(Supplier<SpectateTarget> target) {
        this.target = target;
    }

    public void reset() {
        yaw = pitch = targetYaw = targetPitch = 0;
        radius = SpectatorCameraMath.DEFAULT_ORBIT_RADIUS;
        lastFrame = 0;
    }

    public void setAngles(float yaw, float pitch) {
        this.yaw = targetYaw = yaw;
        this.pitch = targetPitch = SpectatorCameraMath.clampPitch(pitch);
        lastFrame = 0;
    }

    @Override
    public void turn(float yaw, float pitch) {
        targetYaw += yaw;
        targetPitch = SpectatorCameraMath.clampPitch(targetPitch + pitch);
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    @Override
    public CameraFrame sample(double ticks) {
        SpectateTarget view = target.get();
        if (view == null) return null;
        long now = System.nanoTime();
        double dt = lastFrame == 0 ? 0.016 : Math.min(0.05, (now - lastFrame) / 1.0E9);
        lastFrame = now;
        float alpha = (float) (1 - Math.exp(-22 * dt));
        yaw += net.minecraft.util.Mth.wrapDegrees(targetYaw - yaw) * alpha;
        pitch += (targetPitch - pitch) * alpha;
        Vec3 anchor = view.anchor();
        Vec3 direction = SpectatorCameraMath.orbitPosition(anchor, yaw, pitch, 1).subtract(anchor);
        double wanted = view.orbitRadius() > 0 ? view.orbitRadius() : SpectatorCameraMath.DEFAULT_ORBIT_RADIUS;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.player != null) {
            HitResult hit = mc.level.clip(new ClipContext(anchor, anchor.add(direction.scale(wanted)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) wanted = Math.max(0, anchor.distanceTo(hit.getLocation()) - 0.18);
        }
        // Never smooth through a wall. Only the outward recovery is gradual.
        radius = Math.min(wanted, radius + 4 * dt);
        CameraPose pose = new CameraPose(anchor.add(direction.scale(radius)), yaw, pitch).lookAt(anchor);
        return new CameraFrame(mc.player, pose, 0);
    }
}
