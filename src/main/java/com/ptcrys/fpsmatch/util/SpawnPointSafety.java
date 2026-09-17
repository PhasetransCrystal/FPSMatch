package com.ptcrys.fpsmatch.util;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Checks the minimum physical requirements for a player spawn location. */
public final class SpawnPointSafety {

    private SpawnPointSafety() {}

    public static boolean isSafe(ServerLevel level, BlockPos feet) {
        BlockPos ground = feet.below();
        return !level.getBlockState(ground).getCollisionShape(level, ground).isEmpty() && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.above()).isEmpty() && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
}
