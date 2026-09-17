package com.ptcrys.fpsmatch.mixin.spec.teammate;

import net.minecraft.client.Camera;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraInvokerMixin {

    @Invoker("setPosition")
    void invokeSetPosition(double x, double y, double z);

    @Invoker("setRotation")
    void invokeSetRotation(float yaw, float pitch);
}
