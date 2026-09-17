package com.ptcrys.fpsmatch.common.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;

/**
 * 玩家获得物品事件，在物品进入玩家背包后触发。
 * 适用于商店购买和回合开始装备发放等场景。
 */
public class PlayerObtainItemEvent extends Event {

    private final ServerPlayer player;
    private final ItemStack itemStack;
    private final BaseMap map;

    public PlayerObtainItemEvent(ServerPlayer player, ItemStack itemStack) {
        this.player = player;
        this.itemStack = itemStack;
        this.map = FPSMCore.getInstance().getMapByPlayer(player).orElse(null);
    }

    public ServerPlayer getPlayer() {
        return player;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public BaseMap getMap() {
        return map;
    }
}
