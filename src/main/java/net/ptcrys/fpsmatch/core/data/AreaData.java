package net.ptcrys.fpsmatch.core.data;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
        // MutableBlockPos 会被调用方复用/重定位，构造时固定为 immutable 快照，避免 AABB 与角点随外部漂移。
        this.pos1 = pos1.immutable();
        this.pos2 = pos2.immutable();
        this.aabb = new AABB(this.pos1, this.pos2);
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
}
