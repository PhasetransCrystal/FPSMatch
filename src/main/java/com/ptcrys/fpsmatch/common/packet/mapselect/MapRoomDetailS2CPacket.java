package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.function.Supplier;

public record MapRoomDetailS2CPacket(MapRoomDetail detail, boolean passive) {

    /**
     * 主动(active)详情包：会在客户端未打开界面时强制打开详情界面（原有行为）。
     */
    public MapRoomDetailS2CPacket(MapRoomDetail detail) {
        this(detail, false);
    }

    public static void encode(MapRoomDetailS2CPacket packet, FriendlyByteBuf buf) {
        MapRoomDetail.encode(packet.detail(), buf);
        buf.writeBoolean(packet.passive());
    }

    public static MapRoomDetailS2CPacket decode(FriendlyByteBuf buf) {
        return new MapRoomDetailS2CPacket(MapRoomDetail.decode(buf), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
