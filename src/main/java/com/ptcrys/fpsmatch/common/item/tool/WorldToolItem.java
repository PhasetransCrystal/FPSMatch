package com.ptcrys.fpsmatch.common.item.tool;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;

public interface WorldToolItem {

    void handleWorldInteraction(ServerPlayer player, ItemStack stack, ToolInteractionAction action, @Nullable BlockPos clickedPos);
}
