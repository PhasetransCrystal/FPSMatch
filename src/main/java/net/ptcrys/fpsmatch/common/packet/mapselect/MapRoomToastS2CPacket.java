package net.ptcrys.fpsmatch.common.packet.mapselect;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MapRoomToastS2CPacket(Component message, boolean error, long requestId) {

    public MapRoomToastS2CPacket(Component message, boolean error) {
        this(message, error, -1L);
    }

    public static void encode(MapRoomToastS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeComponent(packet.message());
        buf.writeBoolean(packet.error());
        buf.writeLong(packet.requestId());
    }

    public static MapRoomToastS2CPacket decode(FriendlyByteBuf buf) {
        return new MapRoomToastS2CPacket(buf.readComponent(), buf.readBoolean(), buf.readLong());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
