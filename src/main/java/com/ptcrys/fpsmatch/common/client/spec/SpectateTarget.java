package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.world.phys.Vec3;

public record SpectateTarget(SpectateMode mode, int entityId, Vec3 anchor, float yaw, float pitch, float orbitRadius) {

    public SpectateTarget {
        mode = mode == null ? SpectateMode.FREE : mode;
        anchor = anchor == null ? Vec3.ZERO : anchor;
        orbitRadius = orbitRadius <= 0.0f ? SpectatorCameraMath.DEFAULT_ORBIT_RADIUS : orbitRadius;
    }
}
