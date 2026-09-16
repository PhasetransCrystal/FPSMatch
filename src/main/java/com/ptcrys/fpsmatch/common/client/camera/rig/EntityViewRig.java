package com.ptcrys.fpsmatch.common.client.camera.rig;

import com.ptcrys.fpsmatch.common.client.camera.*;
import net.minecraft.world.entity.Entity;
import java.util.function.Supplier;

public record EntityViewRig(Supplier<Entity> target) implements CameraRig {
    @Override public CameraFrame sample(double ticks) { return CameraFrame.attached(target.get()); }
}
