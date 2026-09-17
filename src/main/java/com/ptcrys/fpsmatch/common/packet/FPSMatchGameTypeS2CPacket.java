package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FPSMatchGameTypeS2CPacket {

    private final String mapName;
    private final String gameType;
    private final boolean teamGlow;
    private final boolean enemyGlow;

    public FPSMatchGameTypeS2CPacket(String mapName, String gameType, boolean teamGlow, boolean enemyGlow) {
        this.mapName = mapName;
        this.gameType = gameType;
        this.teamGlow = teamGlow;
        this.enemyGlow = enemyGlow;
    }

    public static void encode(FPSMatchGameTypeS2CPacket packet, FriendlyByteBuf packetBuffer) {
        packetBuffer.writeUtf(packet.mapName);
        packetBuffer.writeUtf(packet.gameType);
        packetBuffer.writeBoolean(packet.teamGlow);
        packetBuffer.writeBoolean(packet.enemyGlow);
    }

    public static FPSMatchGameTypeS2CPacket decode(FriendlyByteBuf packetBuffer) {
        String mapName = packetBuffer.readUtf();
        String gameType = packetBuffer.readUtf();
        boolean teamGlow = packetBuffer.readBoolean();
        boolean enemyGlow = packetBuffer.readBoolean();
        return new FPSMatchGameTypeS2CPacket(mapName, gameType, teamGlow, enemyGlow);
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        ClientPacketExecutor.execute(supplier, this);
    }

    public String getMapName() {
        return mapName;
    }

    public String getGameType() {
        return gameType;
    }

    public boolean isTeamGlow() {
        return teamGlow;
    }

    public boolean isEnemyGlow() {
        return enemyGlow;
    }
}
