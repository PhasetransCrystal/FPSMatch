package com.ptcrys.fpsmatch.common.client.spec;

import com.ptcrys.fpsmatch.common.client.camera.rig.OrbitRig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/** Compatibility facade for spectator input; camera writes belong to CameraDirector. */
public final class SpectatorCameraController {
    private static final OrbitRig RIG = new OrbitRig(SpectateState::getTarget);
    private SpectatorCameraController() {}
    public static OrbitRig rig() { return RIG; }
    public static void reset() { RIG.reset(); }
    public static void applyAngles(float yaw, float pitch) {
        if (SpectateState.isRestricted()) RIG.turn(yaw, pitch);
    }
    public static void setAngles(float yaw, float pitch) { RIG.setAngles(yaw, pitch); }
    public static float yaw() { return RIG.yaw(); }
    public static float pitch() { return RIG.pitch(); }
    public static Entity resolveEntity() {
        SpectateTarget target = SpectateState.getTarget();
        Minecraft mc = Minecraft.getInstance();
        return target == null || mc.level == null || target.entityId() < 0 ? null : mc.level.getEntity(target.entityId());
    }
}
