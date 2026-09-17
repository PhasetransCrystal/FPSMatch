package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomActionService;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.mapselect.MapSelectionAccessSync;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public record MapRoomActionC2SPacket(Action action, String gameType, String mapName, UUID targetPlayer, String data) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int DATA_MAX_LENGTH = 128;

    public MapRoomActionC2SPacket {
        targetPlayer = Objects.requireNonNullElse(targetPlayer, new UUID(0L, 0L));
        data = Objects.requireNonNullElse(data, "");
    }

    public MapRoomActionC2SPacket(Action action, String gameType, String mapName, UUID targetPlayer) {
        this(action, gameType, mapName, targetPlayer, "");
    }

    public enum Action {
        JOIN,
        LEAVE,
        REQUEST_DETAIL,
        INVITE,
        ACCEPT_INVITE,
        KICK,
        READY,
        SWITCH_TEAM,
        DEBUG_START,
        DEBUG_RESET,
        DEBUG_NEW_ROUND,
        DEBUG_CLEANUP,
        DEBUG_SWITCH
    }

    public static void encode(MapRoomActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.gameType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.mapName(), ID_MAX_LENGTH);
        buf.writeUUID(packet.targetPlayer());
        buf.writeUtf(packet.data(), DATA_MAX_LENGTH);
    }

    public static MapRoomActionC2SPacket decode(FriendlyByteBuf buf) {
        return new MapRoomActionC2SPacket(buf.readEnum(Action.class), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUUID(), buf.readUtf(DATA_MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (!MapSelectionAccessSync.canUseMapSelection(player) && action != Action.ACCEPT_INVITE) {
                FPSMatch.sendToPlayer(player, new MapSelectionAccessS2CPacket(false));
                FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(Component.translatable("gui.fpsm.map_select.action.no_permission"), true));
                return;
            }
            if (action == Action.REQUEST_DETAIL) {
                sendDetail(player);
                return;
            }
            MapRoomActionService.Result result = switch (action) {
                case JOIN -> MapRoomActionService.join(player, gameType, mapName);
                case LEAVE -> MapRoomActionService.leave(player, gameType, mapName);
                case INVITE -> MapRoomActionService.invite(player, gameType, mapName, targetPlayer);
                case ACCEPT_INVITE -> MapRoomActionService.acceptInvite(player, gameType, mapName);
                case KICK -> MapRoomActionService.kick(player, gameType, mapName, targetPlayer);
                case READY -> MapRoomActionService.ready(player, gameType, mapName);
                case SWITCH_TEAM -> MapRoomActionService.switchTeam(player, gameType, mapName, targetPlayer, data);
                case DEBUG_START -> MapRoomActionService.debug(player, gameType, mapName, MapRoomActionService.DebugAction.START);
                case DEBUG_RESET -> MapRoomActionService.debug(player, gameType, mapName, MapRoomActionService.DebugAction.RESET);
                case DEBUG_NEW_ROUND -> MapRoomActionService.debug(player, gameType, mapName, MapRoomActionService.DebugAction.NEW_ROUND);
                case DEBUG_CLEANUP -> MapRoomActionService.debug(player, gameType, mapName, MapRoomActionService.DebugAction.CLEANUP);
                case DEBUG_SWITCH -> MapRoomActionService.debug(player, gameType, mapName, MapRoomActionService.DebugAction.SWITCH_DEBUG);
                case REQUEST_DETAIL -> throw new IllegalStateException("REQUEST_DETAIL handled before action dispatch");
            };
            MapRoomActionService.sendMessage(player, result);
            if (result.success() && result.detail().isPresent() && action != Action.LEAVE) {
                com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager.watchDetail(player.getUUID(), gameType, mapName);
            }
            result.detail().ifPresentOrElse(
                    detail -> FPSMatch.sendToPlayer(player, new MapRoomDetailS2CPacket(detail)),
                    () -> FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(result.message(), !result.success())));
        });
        ctx.get().setPacketHandled(true);
    }

    private void sendDetail(ServerPlayer player) {
        MapRoomQueryService.findMap(gameType, mapName).ifPresentOrElse(
                map -> {
                    FPSMatch.sendToPlayer(player, new MapRoomDetailS2CPacket(MapRoomQueryService.detail(player, map)));
                    com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager.watchDetail(player.getUUID(), gameType, mapName);
                },
                () -> FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(Component.translatable("gui.fpsm.map_select.action.map_not_found"), true)));
    }
}
