package com.ptcrys.fpsmatch.common.packet.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.core.team.TeamData;

import java.util.function.Supplier;

public record FPSMAddTeamS2CPacket(String gameType, String mapName, int color, TeamData teamData) {

    public static FPSMAddTeamS2CPacket of(ServerTeam team) {
        return new FPSMAddTeamS2CPacket(team.gameType, team.mapName, team.getColor(), TeamData.of(team));
    }

    public static void encode(FPSMAddTeamS2CPacket packet, FriendlyByteBuf packetBuffer) {
        packetBuffer.writeUtf(packet.gameType);
        packetBuffer.writeUtf(packet.mapName);
        packetBuffer.writeInt(packet.color);
        packetBuffer.writeJsonWithCodec(TeamData.CODEC, packet.teamData);
    }

    public static FPSMAddTeamS2CPacket decode(FriendlyByteBuf packetBuffer) {
        return new FPSMAddTeamS2CPacket(
                packetBuffer.readUtf(),
                packetBuffer.readUtf(),
                packetBuffer.readInt(),
                packetBuffer.readJsonWithCodec(TeamData.CODEC));
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        ClientPacketExecutor.execute(supplier, this);
    }
}
