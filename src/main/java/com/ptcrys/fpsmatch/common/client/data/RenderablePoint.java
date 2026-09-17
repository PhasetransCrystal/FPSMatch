package com.ptcrys.fpsmatch.common.client.data;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

public record RenderablePoint(String key, Component name, int color, Vec3 position) {

    public void render(PoseStack poseStack, MultiBufferSource bufferSource) {
        VertexConsumer vertexconsumer = bufferSource.getBuffer(RenderType.lines());
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        AABB markerBox = new AABB(
                position.x - 0.18D, position.y - 0.18D, position.z - 0.18D,
                position.x + 0.18D, position.y + 0.18D, position.z + 0.18D);
        LevelRenderer.renderLineBox(
                poseStack,
                vertexconsumer,
                markerBox.minX, markerBox.minY, markerBox.minZ,
                markerBox.maxX, markerBox.maxY, markerBox.maxZ,
                red, green, blue, 1.0F,
                Math.max(red * 0.55F, 0.1F),
                Math.max(green * 0.55F, 0.1F),
                Math.max(blue * 0.55F, 0.1F));

        AABB verticalLine = new AABB(
                position.x - 0.02D, position.y, position.z - 0.02D,
                position.x + 0.02D, position.y + 1.1D, position.z + 0.02D);
        LevelRenderer.renderLineBox(
                poseStack,
                vertexconsumer,
                verticalLine.minX, verticalLine.minY, verticalLine.minZ,
                verticalLine.maxX, verticalLine.maxY, verticalLine.maxZ,
                red, green, blue, 1.0F,
                Math.max(red * 0.55F, 0.1F),
                Math.max(green * 0.55F, 0.1F),
                Math.max(blue * 0.55F, 0.1F));
    }
}
