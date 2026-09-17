package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.world.phys.Vec3;

public final class SpectatorCameraMath {

    public static final float MIN_PITCH = -75.0f;
    public static final float MAX_PITCH = 75.0f;
    public static final float DEFAULT_ORBIT_RADIUS = 4.0f;

    private SpectatorCameraMath() {}

    public static float clampPitch(float pitch) {
        return Math.max(MIN_PITCH, Math.min(MAX_PITCH, pitch));
    }

    public static Vec3 orbitPosition(Vec3 center, float yaw, float pitch, float radius) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(clampPitch(pitch));
        double horizontal = radius * Math.cos(pitchRadians);
        return center.add(Math.sin(yawRadians) * horizontal, -Math.sin(pitchRadians) * radius, -Math.cos(yawRadians) * horizontal);
    }
}
