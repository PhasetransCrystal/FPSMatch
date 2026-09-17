package com.ptcrys.fpsmatch.mixin.nametag;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ptcrys.fpsmatch.common.client.event.PlayerNameTagRenderEvent;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class NameTagRenderMixin {

    @Inject(
            method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                     value = "HEAD"),
            cancellable = true)
    private void onPreRenderNameTag(AbstractClientPlayer player,
                                    Component pDisplayName,
                                    PoseStack poseStack,
                                    MultiBufferSource bufferSource,
                                    int packedLight,
                                    CallbackInfo ci) {
        if (FPSMConfig.Server.disableRenderNameTag.get()) ci.cancel();

        PlayerNameTagRenderEvent.Pre event = new PlayerNameTagRenderEvent.Pre(
                player, poseStack, bufferSource, packedLight, 0.0f);

        if (MinecraftForge.EVENT_BUS.post(event)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderNameTag(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/network/chat/Component;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL"))
    private void onPostRenderNameTag(AbstractClientPlayer player,
                                     Component pDisplayName,
                                     PoseStack poseStack,
                                     MultiBufferSource bufferSource,
                                     int packedLight,
                                     CallbackInfo ci) {
        PlayerNameTagRenderEvent.Post event = new PlayerNameTagRenderEvent.Post(
                player, poseStack, bufferSource, packedLight, 0.0f);

        MinecraftForge.EVENT_BUS.post(event);
    }
}
