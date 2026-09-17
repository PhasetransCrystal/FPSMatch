package com.ptcrys.fpsmatch.bukkit.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.bukkit.FPSMBukkit;
import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Optional;

public class FPSMBukkitEventBirge {

    protected FPSMBukkitEventBirge() {}

    public static void register() {
        if (FPSMBukkit.isBukkitEnvironment()) {
            MinecraftForge.EVENT_BUS.register(new FPSMBukkitEventBirge());
            FPSMatch.LOGGER.info("FPSMatch : Bukkit API checked, successfully registered event bridge!");
        }
    }

    @SubscribeEvent
    public void onForgeKillEvent(FPSMapEvent.PlayerEvent.DeathEvent event) {
        if (!FPSMBukkit.isBukkitEnvironment()) return;
        Player forgeDead = event.getPlayer();
        Optional<ServerPlayer> forgeKiller = event.getAttacker();
        BukkitPlayerKillOnMapEvent bukkitEvent = new BukkitPlayerKillOnMapEvent(
                event.getMap(), forgeDead.getUUID(), forgeKiller.map(Entity::getUUID).orElse(null));
        Bukkit.getPluginManager().callEvent(bukkitEvent);
    }

    @SubscribeEvent
    public void onForgeGameWinnerEvent(FPSMapEvent.VictoryEvent event) {
        if (!FPSMBukkit.isBukkitEnvironment()) return;

        ServerLevel forgeLevel = event.getMap().getServerLevel();
        World bukkitWorld = Bukkit.getWorld(FPSMBukkit.getLevelName(forgeLevel));

        BukkitGameWinnerEvent bukkitEvent = new BukkitGameWinnerEvent(
                event.getMap(),
                bukkitWorld);
        Bukkit.getPluginManager().callEvent(bukkitEvent);
    }
}
