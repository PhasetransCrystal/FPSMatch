package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.config.FPSMConfig;

import java.util.function.Supplier;

public record OpenMapSelectionC2SPacket() {

    public static void encode(OpenMapSelectionC2SPacket packet, FriendlyByteBuf buf) {}

    public static OpenMapSelectionC2SPacket decode(FriendlyByteBuf buf) {
        return new OpenMapSelectionC2SPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            boolean viewerOp = MapRoomQueryService.isMapOperator(player);
            boolean nonOpButtonEnabled = FPSMConfig.Server.enableMapSelectionButtonForNonOps.get();
            if (!viewerOp && !nonOpButtonEnabled) {
                FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(Component.translatable("gui.fpsm.map_select.action.no_permission"), true));
                return;
            }
            FPSMatch.sendToPlayer(player, new MapSelectionSnapshotS2CPacket(MapRoomQueryService.summaries(player), viewerOp, nonOpButtonEnabled));
            com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager.watchList(player.getUUID());
        });
        ctx.get().setPacketHandled(true);
    }
}
