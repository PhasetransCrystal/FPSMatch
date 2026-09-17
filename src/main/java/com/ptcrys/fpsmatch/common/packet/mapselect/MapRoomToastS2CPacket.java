package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.function.Supplier;

public record MapRoomToastS2CPacket(Component message, boolean error) {

    public static void encode(MapRoomToastS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeComponent(packet.message());
        buf.writeBoolean(packet.error());
    }

    public static MapRoomToastS2CPacket decode(FriendlyByteBuf buf) {
        return new MapRoomToastS2CPacket(buf.readComponent(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
