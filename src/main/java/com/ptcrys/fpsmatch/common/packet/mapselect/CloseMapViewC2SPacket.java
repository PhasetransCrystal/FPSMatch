package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager;

import java.util.function.Supplier;

/**
 * 客户端关闭地图选择/房间界面时发送，用于从 {@link MapRoomSyncManager} 退订，
 * 避免服务端向已离开界面的玩家继续计算/广播被动同步包。
 */
public record CloseMapViewC2SPacket() {

    public static void encode(CloseMapViewC2SPacket packet, FriendlyByteBuf buf) {}

    public static CloseMapViewC2SPacket decode(FriendlyByteBuf buf) {
        return new CloseMapViewC2SPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                MapRoomSyncManager.unwatch(player.getUUID());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
