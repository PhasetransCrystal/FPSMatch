package com.ptcrys.fpsmatch.common.client.renderer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.ptcrys.fpsmatch.common.entity.MatchDropEntity;
import com.ptcrys.fpsmatch.compat.LrtacticalCompat;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.ptcrys.fpsmatch.compat.impl.FPSMImpl;
import org.jetbrains.annotations.NotNull;

import java.util.Random;

public class MatchDropRenderer implements EntityRendererProvider<MatchDropEntity> {

    public static final Random RANDOM = new Random();

    @Override
    public @NotNull EntityRenderer<MatchDropEntity> create(@NotNull Context context) {
        return new EntityRenderer<>(context) {

            private final ItemRenderer itemRenderer = context.getItemRenderer();
            private final float yRotation = RANDOM.nextFloat(0f, 360f);

            @Override
            public @NotNull ResourceLocation getTextureLocation(@NotNull MatchDropEntity entity) {
                return TextureAtlas.LOCATION_BLOCKS;
            }

            @Override
            public void render(@NotNull MatchDropEntity entity, float pEntityYaw, float pPartialTicks, @NotNull PoseStack poseStack, @NotNull MultiBufferSource bufferSource, int pPackedLight) {
                poseStack.pushPose();
                ItemStack itemStack = entity.getItem();
                BakedModel bakedModel = itemRenderer.getModel(itemStack, entity.level(), null, entity.getId());
                boolean isSpecialItem = GunCompatManager.isGun(itemStack) || (FPSMImpl.findLrtacticalMod() && LrtacticalCompat.isKnife(itemStack));
                poseStack.translate(0, 0.25F, 0);
                if (isSpecialItem) {
                    poseStack.mulPose(Axis.XP.rotationDegrees(90));
                    poseStack.mulPose(Axis.ZP.rotationDegrees(entity.getRotation()));
                    poseStack.mulPose(Axis.YP.rotationDegrees(90));
                }
                itemRenderer.render(itemStack, ItemDisplayContext.GROUND, false, poseStack, bufferSource, pPackedLight, OverlayTexture.NO_OVERLAY, bakedModel);
                poseStack.popPose();
            }
        };
    }
}
