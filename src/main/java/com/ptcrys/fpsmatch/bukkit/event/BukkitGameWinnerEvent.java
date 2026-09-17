package com.ptcrys.fpsmatch.bukkit.event;

import com.ptcrys.fpsmatch.core.map.BaseMap;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class BukkitGameWinnerEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final BaseMap map;
    private final World world;

    public BukkitGameWinnerEvent(BaseMap map, World world) {
        this.map = map;
        this.world = world;
    }

    public BaseMap getMap() {
        return map;
    }

    public World getWorld() {
        return world;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
