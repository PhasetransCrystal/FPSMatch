package com.ptcrys.fpsmatch.common.client.camera.rig;

import com.ptcrys.fpsmatch.common.client.camera.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.function.Supplier;

/** Optional collision constraint applied after all positional effects of a rig. */
public record CollisionRig(CameraRig delegate, Supplier<Vec3> anchor, double inset) implements CameraRig {
    public CollisionRig {
        if (!Double.isFinite(inset) || inset < 0) throw new IllegalArgumentException("Invalid camera inset");
    }

    @Override public void turn(float yaw, float pitch) { delegate.turn(yaw, pitch); }

    @Override public CameraFrame sample(double ticks) {
        CameraFrame frame = delegate.sample(ticks);
        Minecraft mc = Minecraft.getInstance();
        if (frame == null || frame.pose() == null || mc.level == null || mc.player == null) return frame;
        Vec3 start = anchor.get();
        if (start == null) return frame;
        CameraPose pose = frame.pose();
        HitResult hit = mc.level.clip(new ClipContext(start, pose.position(), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK) return frame;
        Vec3 direction = pose.position().subtract(start).normalize();
        Vec3 position = start.add(direction.scale(Math.max(0, start.distanceTo(hit.getLocation()) - inset)));
        return new CameraFrame(frame.entity(), new CameraPose(position, pose.yaw(), pose.pitch(), pose.roll(), pose.fov()), frame.fade());
    }
}
