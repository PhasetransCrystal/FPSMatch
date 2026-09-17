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
import com.ptcrys.fpsmatch.common.entity.throwable.IncendiaryGrenadeEntity;
import org.jetbrains.annotations.NotNull;

public class IncendiaryGrenadeRenderer implements EntityRendererProvider<IncendiaryGrenadeEntity> {

    @Override
    public @NotNull EntityRenderer<IncendiaryGrenadeEntity> create(Context pContext) {
        return new EntityRenderer<>(pContext) {

            ItemEntity item = null;
            final ItemEntityRenderer itemRender = new ItemEntityRenderer(pContext);

            @Override
            public @NotNull ResourceLocation getTextureLocation(@NotNull IncendiaryGrenadeEntity pEntity) {
                return TextureAtlas.LOCATION_BLOCKS;
            }

            @Override
            public void render(@NotNull IncendiaryGrenadeEntity pEntity, float pEntityYaw, float pPartialTicks, @NotNull PoseStack pPoseStack, @NotNull MultiBufferSource pBuffer, int pPackedLight) {
                super.render(pEntity, pEntityYaw, pPartialTicks, pPoseStack, pBuffer, pPackedLight);
                if (pEntity.getState() == 2) return;
                pPoseStack.pushPose();
                pPoseStack.translate(0.0F, -0.25F, 0.0F);
                if (item == null || item.getItem().getItem() != pEntity.getHoldItem()) {
                    item = new ItemEntity(pEntity.level(), pEntity.getX(), pEntity.getY(), pEntity.getZ(), new ItemStack(pEntity.getHoldItem()));
                }
                itemRender.render(item, pEntityYaw, 0, pPoseStack, pBuffer, pPackedLight);
                pPoseStack.popPose();
            }
        };
    }
}
