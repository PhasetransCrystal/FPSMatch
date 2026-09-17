package net.ptcrys.fpsmatch.common.client.camera;

import net.minecraft.world.entity.Entity;

/** Entity binding and pose override are independent: first-person spectators keep the real entity. */
public record CameraFrame(Entity entity, CameraPose pose, float fade) {

    public static CameraFrame independent(CameraPose pose) {
        return new CameraFrame(null, pose, 0);
    }

    public static CameraFrame attached(Entity entity) {
        return new CameraFrame(entity, null, 0);
    }
}
