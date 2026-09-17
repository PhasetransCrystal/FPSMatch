package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.world.level.GameType;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders spectator first-person hands and bypasses the spectator item-in-hand gate.
 */
@Mixin(GameRenderer.class)
public abstract class MixinGameRendererSpectatorHands {

    @Final
    @Shadow
    public ItemInHandRenderer itemInHandRenderer;

    @Final
    @Shadow
    private RenderBuffers renderBuffers;

    @Redirect(
              method = "renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V",
              at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC, target = "Lnet/minecraft/world/level/GameType;SPECTATOR:Lnet/minecraft/world/level/GameType;"),
              require = 0)
    private GameType fpsmatch$allowSpectatorHands() {
        if (Minecraft.getInstance().options.getCameraType().isFirstPerson() && SpectatorView.shouldRenderHands()) {
            return GameType.SURVIVAL;
        }
        return GameType.SPECTATOR;
    }

    @Inject(method = "renderItemInHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/Camera;F)V", at = @At("TAIL"))
    private void fpsmatch$renderSpectatorHands(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (!SpectatorView.shouldRenderHands()) {
            return;
        }
        if (!mc.options.getCameraType().isFirstPerson()) {
            return;
        }
        if (mc.options.hideGui || player.isSleeping()) {
            return;
        }
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        MultiBufferSource.BufferSource buffer = this.renderBuffers.bufferSource();
        this.itemInHandRenderer.renderHandsWithItems(partialTicks, poseStack, buffer, player,
                mc.getEntityRenderDispatcher().getPackedLightCoords(player, partialTicks));
        buffer.endBatch();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }
}
