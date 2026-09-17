package com.ptcrys.fpsmatch.common.client.renderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ptcrys.fpsmatch.common.entity.throwable.FlashBombEntity;
import com.ptcrys.fpsmatch.common.item.FPSMItemRegister;
import org.jetbrains.annotations.NotNull;

public class FlashBombRenderer implements EntityRendererProvider<FlashBombEntity> {

    @Override
    public @NotNull EntityRenderer<FlashBombEntity> create(@NotNull Context context) {
        return new EntityRenderer<>(context) {

            ItemEntity item = null;
            ItemEntityRenderer itemRender = null;

            @Override
            public @NotNull ResourceLocation getTextureLocation(@NotNull FlashBombEntity entity) {
                return TextureAtlas.LOCATION_BLOCKS;
            }

            @Override
            public void render(@NotNull FlashBombEntity entity, float pEntityYaw, float pPartialTicks, @NotNull PoseStack pPoseStack, MultiBufferSource pBuffer, int pPackedLight) {
                super.render(entity, pEntityYaw, pPartialTicks, pPoseStack, pBuffer, pPackedLight);
                if (entity.getState() == 2) return;
                pPoseStack.pushPose();
                pPoseStack.translate(0.0F, -0.25F, 0.0F);
                if (item == null) {
                    item = new ItemEntity(entity.level(), entity.getX(), entity.getY(), entity.getZ(), new ItemStack(FPSMItemRegister.FLASH_BOMB.get()));
                    itemRender = new ItemEntityRenderer(context);
                }
                item.setXRot(entity.getXRot());
                item.setYRot(entity.getYRot());
                itemRender.render(item, pEntityYaw, 0, pPoseStack, pBuffer, pPackedLight);
                pPoseStack.popPose();
            }
        };
    }
}
