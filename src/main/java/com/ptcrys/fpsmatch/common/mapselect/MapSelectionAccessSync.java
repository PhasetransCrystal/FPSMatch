package com.ptcrys.fpsmatch.common.mapselect;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionAccessS2CPacket;
import com.ptcrys.fpsmatch.config.FPSMConfig;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MapSelectionAccessSync {

    private MapSelectionAccessSync() {}

    public static boolean canUseMapSelection(ServerPlayer player) {
        return MapRoomQueryService.isMapOperator(player) || FPSMConfig.Server.enableMapSelectionButtonForNonOps.get();
    }

    public static void sync(ServerPlayer player) {
        FPSMatch.sendToPlayer(player, new MapSelectionAccessS2CPacket(canUseMapSelection(player)));
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }
}
