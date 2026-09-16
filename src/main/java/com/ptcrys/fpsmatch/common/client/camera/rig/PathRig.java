package com.ptcrys.fpsmatch.common.client.camera.rig;

import com.ptcrys.fpsmatch.common.client.camera.*;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.Supplier;

public final class PathRig implements CameraRig {
    public record Keyframe(double tick, CameraPose pose) {}
    private final List<Keyframe> keys;
    private final Supplier<Vec3> lookAt;

    public PathRig(List<Keyframe> keys, Supplier<Vec3> lookAt) {
        if (keys.isEmpty()) throw new IllegalArgumentException("A path requires a keyframe");
        this.keys = List.copyOf(keys);
        this.lookAt = lookAt;
        for (int i = 0; i < keys.size(); i++) {
            if (!Double.isFinite(keys.get(i).tick) || keys.get(i).tick < 0
                    || (i > 0 && keys.get(i).tick <= keys.get(i - 1).tick)) {
                throw new IllegalArgumentException("Keyframe times must strictly increase");
            }
        }
    }

    @Override public CameraFrame sample(double ticks) {
        CameraPose pose = keys.get(0).pose;
        for (int i = 1; i < keys.size(); ++i) {
            Keyframe from = keys.get(i - 1), to = keys.get(i);
            double t = Math.max(0, Math.min(1, (ticks - from.tick) / (to.tick - from.tick)));
            pose = from.pose.blend(to.pose, t * t * (3 - 2 * t));
            if (ticks <= to.tick) break;
        }
        Vec3 target = lookAt == null ? null : lookAt.get();
        return CameraFrame.independent(target == null ? pose : pose.lookAt(target));
    }
}
