package com.ptcrys.fpsmatch.common.client.camera;

import com.ptcrys.fpsmatch.mixin.spec.teammate.CameraInvokerMixin;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** The only FPSMatch/BO writer of camera entities and custom camera poses. */
public final class CameraBackend {
    private static Entity ghost;
    private static boolean binding;
    private static long bindingRevision;

    private CameraBackend() {}
    public static boolean isBinding() { return binding; }
    static long bindingRevision() { return bindingRevision; }

    static boolean bind(CameraFrame frame) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        Entity entity = frame == null ? mc.player : frame.entity();
        if (frame != null && entity == null && frame.pose() != null) {
            if (ghost == null || ghost.level() != mc.level || ghost.isRemoved()) {
                ghost = EntityType.MARKER.create(mc.level);
            }
            if (ghost == null) return false;
            CameraPose pose = frame.pose();
            ghost.moveTo(pose.position().x, pose.position().y, pose.position().z, pose.yaw(), pose.pitch());
            ghost.setOldPosAndRot();
            entity = ghost;
        }
        if (entity == null || entity.level() != mc.level || entity.isRemoved()) return false;
        if (mc.getCameraEntity() != entity) {
            binding = true;
            try { mc.setCameraEntity(entity); ++bindingRevision; }
            finally { binding = false; }
        }
        return true;
    }

    static void apply(Camera camera, CameraFrame frame) {
        if (frame == null || frame.pose() == null) return;
        CameraPose pose = frame.pose();
        CameraInvokerMixin access = (CameraInvokerMixin) camera;
        access.invokeSetPosition(pose.position().x, pose.position().y, pose.position().z);
        access.invokeSetRotation(pose.yaw(), pose.pitch());
    }

    static void clear() { ghost = null; }
}
