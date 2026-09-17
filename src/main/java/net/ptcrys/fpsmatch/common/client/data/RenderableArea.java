package net.ptcrys.fpsmatch.common.client.data;

import net.ptcrys.fpsmatch.core.data.AreaData;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.vertex.PoseStack;

public record RenderableArea(String key, Component name, int color, AreaData area) {

    public void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        area.renderArea(poseStack, bufferSource, color);
    }
}
