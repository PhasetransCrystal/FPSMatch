package com.ptcrys.fpsmatch.common.packet.mapselect;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.mapselect.MapImportService;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RequestMapImportSourcesC2SPacket(String gameType, String mapName) {
    private static final int MAX_LENGTH = 128;

    public static void encode(RequestMapImportSourcesC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.gameType, MAX_LENGTH);
        buf.writeUtf(packet.mapName, MAX_LENGTH);
    }

    public static RequestMapImportSourcesC2SPacket decode(FriendlyByteBuf buf) {
        return new RequestMapImportSourcesC2SPacket(buf.readUtf(MAX_LENGTH), buf.readUtf(MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> {
            ServerPlayer player = contextSupplier.get().getSender();
            if (player == null) return;
            if (!MapRoomQueryService.isMapOperator(player)) {
                FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(Component.translatable(
                        "gui.fpsm.map_select.action.no_permission"), true));
                return;
            }
            FPSMatch.sendToPlayer(player, new MapImportSourcesS2CPacket(
                    gameType, mapName, MapImportService.listSources(player, gameType, mapName)));
        });
        contextSupplier.get().setPacketHandled(true);
    }
}
