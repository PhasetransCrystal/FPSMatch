package com.ptcrys.fpsmatch.common.item.tool.handler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public interface EditToolClickHandler {

    void handleClick(ItemStack stack, ServerPlayer player,
                     boolean isDoubleClicked, boolean isShiftKeyDown, ClickAction action);
}
