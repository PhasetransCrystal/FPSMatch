package com.ptcrys.fpsmatch.common.packet.mapselect;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomActionService;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager;
import com.ptcrys.fpsmatch.core.data.AreaData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MapRegionActionC2SPacket(
        Action action,
        String gameType,
        String mapName,
        int index,
        BlockPos pos1,
        BlockPos pos2
) {
    private static final int ID_MAX_LENGTH = 128;

    public enum Action {
        SET_MAP,
        ADD_BOMB,
        UPDATE_BOMB,
        REMOVE_BOMB,
        PREVIEW
    }

    public static void encode(MapRegionActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action);
        buf.writeUtf(packet.gameType, ID_MAX_LENGTH);
        buf.writeUtf(packet.mapName, ID_MAX_LENGTH);
        buf.writeVarInt(packet.index);
        buf.writeBlockPos(packet.pos1);
        buf.writeBlockPos(packet.pos2);
    }

    public static MapRegionActionC2SPacket decode(FriendlyByteBuf buf) {
        return new MapRegionActionC2SPacket(buf.readEnum(Action.class), buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH), buf.readVarInt(), buf.readBlockPos(), buf.readBlockPos());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player == null) {
                return;
            }
            AreaData area = new AreaData(pos1, pos2);
            MapRoomActionService.Result result = switch (action) {
                case SET_MAP -> MapRoomActionService.setMapArea(player, gameType, mapName, area);
                case ADD_BOMB -> MapRoomActionService.addBombArea(player, gameType, mapName, area);
                case UPDATE_BOMB -> MapRoomActionService.updateBombArea(player, gameType, mapName, index, area);
                case REMOVE_BOMB -> MapRoomActionService.removeBombArea(player, gameType, mapName, index);
                case PREVIEW -> MapRoomActionService.previewAreas(player, gameType, mapName);
            };
            FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(result.message(), !result.success()));
            result.detail().ifPresent(detail -> {
                MapRoomSyncManager.watchDetail(player.getUUID(), gameType, mapName);
                FPSMatch.sendToPlayer(player, new MapRoomDetailS2CPacket(detail, true));
            });
        });
        contextSupplier.get().setPacketHandled(true);
    }
}
