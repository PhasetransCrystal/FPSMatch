package com.ptcrys.fpsmatch.common.client.data;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ptcrys.fpsmatch.core.data.AreaData;

public record RenderableArea(String key, Component name, int color, AreaData area) {

    public void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        area.renderArea(poseStack, bufferSource, color);
    }
}
