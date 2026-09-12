package com.ptcrys.fpsmatch.common.packet.mapselect;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.mapselect.MapImportService;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomActionService;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ImportMapConfigC2SPacket(
        String targetGameType,
        String targetMapName,
        String sourceId,
        boolean settings,
        boolean shop,
        boolean startKits
) {
    private static final int MAX_LENGTH = 128;

    public static void encode(ImportMapConfigC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.targetGameType, MAX_LENGTH);
        buf.writeUtf(packet.targetMapName, MAX_LENGTH);
        buf.writeUtf(packet.sourceId, MAX_LENGTH);
        buf.writeBoolean(packet.settings);
        buf.writeBoolean(packet.shop);
        buf.writeBoolean(packet.startKits);
    }

    public static ImportMapConfigC2SPacket decode(FriendlyByteBuf buf) {
        return new ImportMapConfigC2SPacket(buf.readUtf(MAX_LENGTH), buf.readUtf(MAX_LENGTH),
                buf.readUtf(MAX_LENGTH), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player == null) return;
            MapRoomActionService.Result result = MapImportService.importInto(
                    player, targetGameType, targetMapName, sourceId, settings, shop, startKits);
            FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(result.message(), !result.success()));
            result.detail().ifPresent(detail -> FPSMatch.sendToPlayer(player,
                    new MapRoomDetailS2CPacket(detail, true)));
            if (result.success()) {
                MapRoomSyncManager.watchDetail(player.getUUID(), targetGameType, targetMapName);
            }
        });
        contextSupplier.get().setPacketHandled(true);
    }
}
