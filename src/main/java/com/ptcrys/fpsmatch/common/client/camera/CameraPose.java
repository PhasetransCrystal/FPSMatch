package com.ptcrys.fpsmatch.common.client.camera;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** NaN FOV means inherit the player's current FOV. */
public record CameraPose(Vec3 position, float yaw, float pitch, float roll, double fov) {
    public CameraPose {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Float.isFinite(roll) || (!Double.isNaN(fov) && (!Double.isFinite(fov) || fov <= 0 || fov >= 180))) {
            throw new IllegalArgumentException("Invalid camera pose");
        }
    }
    public CameraPose(Vec3 position, float yaw, float pitch) {
        this(position, yaw, pitch, 0, Double.NaN);
    }

    public CameraPose blend(CameraPose to, double progress) {
        float t = (float) Mth.clamp(progress, 0, 1);
        return new CameraPose(position.lerp(to.position, t),
                yaw + Mth.wrapDegrees(to.yaw - yaw) * t,
                Mth.lerp(t, pitch, to.pitch), roll + Mth.wrapDegrees(to.roll - roll) * t,
                Double.isNaN(fov) || Double.isNaN(to.fov) ? to.fov : Mth.lerp(t, fov, to.fov));
    }

    public CameraPose lookAt(Vec3 target) {
        Vec3 delta = target.subtract(position);
        if (delta.lengthSqr() < 1.0E-10) return this;
        return new CameraPose(position, (float) Math.toDegrees(Math.atan2(-delta.x, delta.z)),
                (float) Math.toDegrees(Math.atan2(-delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z))), roll, fov);
    }
}
