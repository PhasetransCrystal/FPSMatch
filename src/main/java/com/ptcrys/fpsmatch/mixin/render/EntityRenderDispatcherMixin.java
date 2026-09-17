package com.ptcrys.fpsmatch.mixin.render;

import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {

    @Inject(
            method = "renderHitbox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/entity/Entity;F)V",
            at = @At("HEAD"),
            cancellable = true)
    private static void disableHitboxRender(
                                            PoseStack poseStack,
                                            VertexConsumer vertexConsumer,
                                            Entity entity,
                                            float partialTick,
                                            CallbackInfo ci) {
        if (FPSMConfig.Server.disableRenderHitBox.get()) {
            ci.cancel();
        }
    }
}
