package com.example.elimination;

import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMapEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EliminationMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EliminationEvents {
    @SubscribeEvent
    public static void registerMaps(RegisterFPSMapEvent event) {
        event.registerGameType(EliminationMap.GAME_TYPE, EliminationMap::new);
    }

    @SubscribeEvent
    public static void protectWaitingPlayers(net.ptcrys.fpsmatch.common.event.FPSMapEvent.PlayerEvent.HurtEvent event) {
        if (event.getMap() instanceof EliminationMap map && !map.isCombatActive()) {
            event.setCanceled(true);
        }
    }
}
