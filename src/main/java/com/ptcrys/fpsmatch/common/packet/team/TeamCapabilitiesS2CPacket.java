package com.ptcrys.fpsmatch.common.packet.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record TeamCapabilitiesS2CPacket(
                                        String teamName,
                                        String capName,
                                        FriendlyByteBuf capabilityData) {

    public static <T extends TeamCapability & FPSMCapability.CapabilitySynchronizable> TeamCapabilitiesS2CPacket of(ServerTeam team, Class<T> clazz) {
        FriendlyByteBuf dataBuf = new FriendlyByteBuf(Unpooled.buffer());
        team.getCapabilityMap().serializeCapability(clazz, dataBuf);

        if (dataBuf.writerIndex() > 1048576) {
            throw new IllegalArgumentException("Packet may not be larger than 1048576 bytes");
        }

        return new TeamCapabilitiesS2CPacket(
                team.name,
                clazz.getSimpleName(),
                dataBuf);
    }

    public static <T extends TeamCapability & FPSMCapability.CapabilitySynchronizable> List<TeamCapabilitiesS2CPacket> toList(ServerTeam team, List<Class<T>> classes) {
        List<TeamCapabilitiesS2CPacket> packets = new ArrayList<>();
        for (Class<T> clazz : classes) {
            packets.add(of(team, clazz));
        }
        return packets;
    }

    public static void encode(TeamCapabilitiesS2CPacket packet, FriendlyByteBuf packetBuffer) {
        packetBuffer.writeUtf(packet.teamName);
        packetBuffer.writeUtf(packet.capName);
        packetBuffer.writeBytes(packet.capabilityData.slice());
    }

    public static TeamCapabilitiesS2CPacket decode(FriendlyByteBuf packetBuffer) {
        String teamName = packetBuffer.readUtf();
        String capName = packetBuffer.readUtf();
        int i = packetBuffer.readableBytes();
        if (i >= 0 && i <= 1048576) {
            return new TeamCapabilitiesS2CPacket(teamName, capName, new FriendlyByteBuf(packetBuffer.readBytes(i)));
        } else {
            throw new IllegalArgumentException("Packet may not be larger than 1048576 bytes");
        }
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        ClientPacketExecutor.execute(supplier, this);
    }
}
