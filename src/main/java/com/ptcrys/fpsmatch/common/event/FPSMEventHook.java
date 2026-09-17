package com.ptcrys.fpsmatch.common.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.map.BaseRoundMap;

import java.util.Optional;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FPSMEventHook {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerPickupItem(PlayerEvent.ItemPickupEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayer(player);
            if (opt.isPresent()) {
                BaseMap map = opt.get();
                FPSMapEvent.PlayerEvent.PickupItemEvent pickupItemEvent = new FPSMapEvent.PlayerEvent.PickupItemEvent(map, player, event.getOriginalEntity(), event.getStack());
                if (MinecraftForge.EVENT_BUS.post(pickupItemEvent)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTossItemEvent(ItemTossEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayer(player);
            if (opt.isPresent()) {
                BaseMap map = opt.get();
                FPSMapEvent.PlayerEvent.TossItemEvent tossItemEvent = new FPSMapEvent.PlayerEvent.TossItemEvent(map, player, event.getEntity());
                if (MinecraftForge.EVENT_BUS.post(tossItemEvent)) {
                    event.setCanceled(true);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerChatEvent(ServerChatEvent event) {
        Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayer(event.getPlayer());
        if (opt.isPresent()) {
            BaseMap map = opt.get();
            FPSMapEvent.PlayerEvent.ChatEvent chatEvent = new FPSMapEvent.PlayerEvent.ChatEvent(map, event.getPlayer(), event.getMessage().getString());
            if (MinecraftForge.EVENT_BUS.post(chatEvent)) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * 玩家登录事件处理
     *
     * @param event 玩家登录事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayerWithSpec(player);
            opt.ifPresentOrElse(map -> {
                FPSMapEvent.PlayerEvent.LoggedInEvent loggedInEvent = new FPSMapEvent.PlayerEvent.LoggedInEvent(map, player);
                MinecraftForge.EVENT_BUS.post(loggedInEvent);
            }, () -> {
                if (FPSMConfig.common.autoAdventureMode.get()) {
                    if (!player.isCreative()) {
                        player.heal(player.getMaxHealth());
                        player.setGameMode(GameType.ADVENTURE);
                    }
                }
            });
        }
    }

    /**
     * 玩家登出事件处理
     *
     * @param event 玩家登出事件
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager.unwatch(player.getUUID());
            Optional<BaseMap> opt = FPSMCore.getInstance().getMapByPlayerWithSpec(player);
            boolean leave = true;
            if (opt.isPresent()) {
                BaseMap map = opt.get();
                FPSMapEvent.PlayerEvent.LoggedOutEvent loggedOutEvent = new FPSMapEvent.PlayerEvent.LoggedOutEvent(map, player);
                if (MinecraftForge.EVENT_BUS.post(loggedOutEvent)) {
                    leave = false;
                } else if (map.isStart()) {
                    // Keep the PlayerData reservation and name for a reconnect, while
                    // removing the disconnecting entity from the active scoreboard team.
                    map.handlePlayerDisconnect(player);
                    leave = false;
                }
            }

            if (leave) {
                FPSMCore.checkAndLeaveTeam(player);
            }
        }
    }

    @SubscribeEvent
    public static void onMapPlayerLoggedInEvent(FPSMapEvent.PlayerEvent.LoggedInEvent event) {
        BaseMap map = event.getMap();
        ServerPlayer player = event.getPlayer();
        map.getMapTeams().getTeamByPlayer(player)
                .ifPresent(team -> team.getPlayerData(player.getUUID()).ifPresent(playerData -> {
                    player.getScoreboard().addPlayerToTeam(player.getScoreboardName(), team.getPlayerTeam());
                    playerData.setLiving(false);
                    player.setGameMode(GameType.SPECTATOR);
                    map.pullGameInfo(player);
                    map.getMapTeams().sync(player);
                    team.syncCapabilities(player);
                    // The reconnecting player already received a full snapshot
                    // above. Existing clients only need the dirty state change.
                    map.getMapTeams().sync(map.getMapTeams().getOnlineWithSpec());
                }));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerRespawnEvent(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        FPSMCore.getInstance().getMapByPlayer(player).ifPresent(map -> {
            if (!map.isStart()) {
                return;
            }
            if (map instanceof BaseRoundMap<?, ?> roundMap) {
                roundMap.handleRespawn(player);
            }
        });
    }
}
