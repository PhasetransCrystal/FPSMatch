package com.ptcrys.fpsmatch.core.data;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import javax.annotation.Nonnull;

public class AreaData {

    public static final Codec<AreaData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("Position1", BlockPos.of(0L)).forGetter(AreaData::pos1),
            BlockPos.CODEC.optionalFieldOf("Position2", BlockPos.of(0L)).forGetter(AreaData::pos2)).apply(instance, AreaData::new));

    private final BlockPos pos1;
    private final BlockPos pos2;
    private final AABB aabb;

    public AreaData(@Nonnull BlockPos pos1, @Nonnull BlockPos pos2) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.aabb = new AABB(pos1, pos2);
    }

    public BlockPos pos1() {
        return pos1;
    }

    public BlockPos pos2() {
        return pos2;
    }

    public boolean isPlayerInArea(Player player) {
        return isInArea(new Vec3(player.getX(), player.getY(), player.getZ()));
    }

    public boolean isBlockPosInArea(BlockPos blockPos) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return blockPos.getX() >= minX && blockPos.getX() <= maxX && blockPos.getY() >= minY && blockPos.getY() <= maxY && blockPos.getZ() >= minZ && blockPos.getZ() <= maxZ;
    }

    public boolean isBlockPosInPlacementArea(BlockPos blockPos) {
        return isBlockPosInArea(blockPos) || isBlockPosInHorizontalArea(blockPos);
    }

    public boolean isBlockPosInHorizontalArea(BlockPos blockPos) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());
        return blockPos.getX() >= minX && blockPos.getX() <= maxX && blockPos.getZ() >= minZ && blockPos.getZ() <= maxZ;
    }

    public boolean isEntityInArea(Entity entity) {
        return isInArea(new Vec3(entity.getX(), entity.getY(), entity.getZ()));
    }

    public boolean isInArea(Vec3 pos) {
        return aabb.contains(pos);
    }

    public AABB aabb() {
        return aabb;
    }

    public void renderArea(PoseStack poseStack, MultiBufferSource bufferSource) {
        this.renderArea(poseStack, bufferSource, 0xFFFFFF00);
    }

    public void renderArea(PoseStack poseStack, MultiBufferSource bufferSource, int color) {
        VertexConsumer vertexconsumer = bufferSource.getBuffer(RenderType.lines());
        AABB aabb = aabb();
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
