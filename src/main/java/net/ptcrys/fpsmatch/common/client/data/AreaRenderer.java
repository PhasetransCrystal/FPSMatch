package net.ptcrys.fpsmatch.common.client.data;

import net.ptcrys.fpsmatch.core.data.AreaData;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 区域线框绘制。
 * <p>
 * 原实现是 core 中 {@code AreaData.renderArea(PoseStack, MultiBufferSource[, int])} 两个重载，但那会让
 * core 数据类直接引用 client/Blaze3D 类型；该方法已随迁移删除，这里只保留纯文本引用。
 * 这里仅搬运绘制逻辑：颜色（默认 0xFFFFFF00，含亮度下限 0.1F 的副色）与线框几何范围保持不变，
 * 业务判定（{@code AreaData.isInArea} 等）仍全部留在 {@code AreaData} 中。
 */
public final class AreaRenderer {

    /** 与迁移前 {@code AreaData.renderArea(poseStack, bufferSource)} 的默认颜色一致。 */
    private static final int DEFAULT_COLOR = 0xFFFFFF00;

    private AreaRenderer() {}

    public static void renderArea(PoseStack poseStack, MultiBufferSource bufferSource, AreaData area) {
        renderArea(poseStack, bufferSource, area, DEFAULT_COLOR);
    }

    public static void renderArea(PoseStack poseStack, MultiBufferSource bufferSource, AreaData area, int color) {
        VertexConsumer vertexconsumer = bufferSource.getBuffer(RenderType.lines());
        AABB aabb = area.aabb();
        float red = ((color >> 16) & 0xFF) / 255.0F;
        float green = ((color >> 8) & 0xFF) / 255.0F;
        float blue = (color & 0xFF) / 255.0F;

        LevelRenderer.renderLineBox(poseStack, vertexconsumer,
                aabb.minX, aabb.minY, aabb.minZ,
                aabb.maxX, aabb.maxY, aabb.maxZ,
                red, green, blue, 1.0F,
                Math.max(red * 0.55F, 0.1F),
                Math.max(green * 0.55F, 0.1F),
                Math.max(blue * 0.55F, 0.1F));
    }
}
