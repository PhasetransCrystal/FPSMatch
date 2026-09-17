package com.ptcrys.fpsmatch.core.shop.event;

import net.minecraft.world.entity.player.Player;

public class CheckCostEvent {

    private final int cost;
    private final Player player;
    private int aCost = 0;

    public CheckCostEvent(Player player, int cost) {
        this.player = player;
        this.cost = cost;
    }

    public void addCost(int cost) {
        this.aCost += cost;
    }

    public boolean success() {
        return this.aCost >= cost;
    }

    public Player player() {
        return player;
    }
}
