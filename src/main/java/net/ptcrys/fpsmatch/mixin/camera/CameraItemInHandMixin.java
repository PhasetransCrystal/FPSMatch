package net.ptcrys.fpsmatch.mixin.camera;

import net.ptcrys.fpsmatch.common.client.camera.CameraDirector;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class CameraItemInHandMixin {

    @Inject(method = "renderHandsWithItems", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$hands(float partialTick, PoseStack stack, MultiBufferSource.BufferSource buffer,
                                LocalPlayer player, int light, CallbackInfo ci) {
        if (CameraDirector.policy().hideHands()) ci.cancel();
    }
}
