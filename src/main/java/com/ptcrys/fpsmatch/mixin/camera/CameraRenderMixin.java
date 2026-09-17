package com.ptcrys.fpsmatch.mixin.camera;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ptcrys.fpsmatch.common.client.camera.CameraDirector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class CameraRenderMixin {

    @Inject(method = { "bobHurt", "bobView" }, at = @At("HEAD"), cancellable = true)
    private void fpsmatch$viewEffects(PoseStack stack, float partialTick, CallbackInfo ci) {
        if (CameraDirector.policy().suppressViewEffects()) ci.cancel();
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$hands(PoseStack stack, Camera camera, float partialTick, CallbackInfo ci) {
        if (CameraDirector.policy().hideHands()) ci.cancel();
    }
}
