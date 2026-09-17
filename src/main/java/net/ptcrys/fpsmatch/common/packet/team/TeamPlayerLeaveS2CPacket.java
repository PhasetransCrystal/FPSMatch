package net.ptcrys.fpsmatch.common.packet.team;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record TeamPlayerLeaveS2CPacket(UUID player) {

    public static void encode(TeamPlayerLeaveS2CPacket packet, FriendlyByteBuf packetBuffer) {
        packetBuffer.writeUUID(packet.player);
    }

    public static TeamPlayerLeaveS2CPacket decode(FriendlyByteBuf packetBuffer) {
        return new TeamPlayerLeaveS2CPacket(
                packetBuffer.readUUID());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        ClientPacketExecutor.execute(supplier, this);
    }
}
