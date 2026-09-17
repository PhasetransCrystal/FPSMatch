package com.ptcrys.fpsmatch.common.item.tool;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.NotNull;

public abstract class CreatorToolItem extends FPSMToolItem {

    public CreatorToolItem(Properties pProperties) {
        super(pProperties);
    }

    @Override
    public boolean canAttackBlock(@NotNull BlockState state, @NotNull Level level,
                                  @NotNull BlockPos pos, @NotNull Player player) {
        return false;
    }
}
