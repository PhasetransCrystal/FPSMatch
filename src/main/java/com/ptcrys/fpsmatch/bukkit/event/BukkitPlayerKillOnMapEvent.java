package com.ptcrys.fpsmatch.bukkit.event;

import com.ptcrys.fpsmatch.core.map.BaseMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class BukkitPlayerKillOnMapEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final BaseMap map;
    private final UUID dead;
    @Nullable
    private final UUID killer;

    public BukkitPlayerKillOnMapEvent(BaseMap map, UUID dead, @Nullable UUID killer) {
        this.map = map;
        this.dead = dead;
        this.killer = killer;
    }

    public BaseMap getMap() {
        return map;
    }

    public UUID getDead() {
        return dead;
    }

    public @Nullable Player getDeadPlayer() {
        return Bukkit.getPlayer(dead);
    }

    public @Nullable UUID getKiller() {
        return killer;
    }

    public @Nullable Player getKillerPlayer() {
        return killer == null ? null : Bukkit.getPlayer(killer);
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
