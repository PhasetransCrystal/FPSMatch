package com.ptcrys.fpsmatch.common.packet.team;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 玩家队伍数据同步包（服务端→客户端）
 * 核心逻辑：
 * 1. 根据enableRounds决定是否序列化回合字段
 * 2. 客户端仅解析聚合后的基础字段，不处理_开头字段
 */
public class TeamPlayerStatsS2CPacket {
    private final UUID uuid;
    private final String teamName;
    private final Component playerName;
    private final int scores;
    private final int Kills;
    private final int Deaths;
    private final int Assists;
    private final float Damage;
    private final int mvpCount;
    private final boolean isLiving;
    private final int headshotKills;
    private final float healthPercent;
    private final float utilityDamage;
    private final int flashedEnemies;
    private final int roundKills;

    //  构建方法
    public static TeamPlayerStatsS2CPacket of(ServerTeam team, PlayerData data) {
        return new TeamPlayerStatsS2CPacket(
                data.getOwner(),
                team.name,
                data.name(),
                data.getScores(),
                data.getKills(),
                data.getDeaths(),
                data.getAssists(),
                data.getDamage(),
                data.getMvpCount(),
                data.isLivingOnServer(),
                data.getHeadshotKills(),
                data.healthPercentServer(),
                data.getUtilityDamage(), data.getFlashedEnemies(), data.getTempKills()
        );
    }

    public TeamPlayerStatsS2CPacket(UUID playerUuid, String teamName, Component playerName,
                                    int scores, int Kills, int Deaths, int Assists,
                                    float Damage, int mvpCount, boolean isLiving, int headshotKills,
                                    float healthPercent, float utilityDamage, int flashedEnemies, int roundKills) {
        this.uuid = playerUuid;
        this.teamName = teamName;
        this.playerName = playerName;
        this.scores = scores;
        this.Kills = Kills;
        this.Deaths = Deaths;
        this.Assists = Assists;
        this.Damage = Damage;
        this.mvpCount = mvpCount;
        this.isLiving = isLiving;
        this.headshotKills = headshotKills;
        this.healthPercent = healthPercent;
        this.utilityDamage = utilityDamage;
        this.flashedEnemies = flashedEnemies;
        this.roundKills = roundKills;
    }

    public TeamPlayerStatsS2CPacket(FriendlyByteBuf buf) {
        this.uuid = buf.readUUID();
        this.teamName = buf.readUtf();
        this.playerName = buf.readComponent();

        this.scores = buf.readInt();
        this.Kills = buf.readInt();
        this.Deaths = buf.readInt();
        this.Assists = buf.readInt();
        this.Damage = buf.readFloat();
        this.mvpCount = buf.readInt();
        this.isLiving = buf.readBoolean();
        this.headshotKills = buf.readInt();
        this.healthPercent = buf.readFloat();
        this.utilityDamage = buf.readFloat();
        this.flashedEnemies = buf.readVarInt();
        this.roundKills = buf.readVarInt();
    }

    public static void encode(TeamPlayerStatsS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.uuid);
        buf.writeUtf(packet.teamName);
        buf.writeComponent(packet.playerName);

        buf.writeInt(packet.scores);
        buf.writeInt(packet.Kills);
        buf.writeInt(packet.Deaths);
        buf.writeInt(packet.Assists);
        buf.writeFloat(packet.Damage);
        buf.writeInt(packet.mvpCount);
        buf.writeBoolean(packet.isLiving);
        buf.writeInt(packet.headshotKills);
        buf.writeFloat(packet.healthPercent);
        buf.writeFloat(packet.utilityDamage);
        buf.writeVarInt(packet.flashedEnemies);
        buf.writeVarInt(packet.roundKills);
    }

    public static TeamPlayerStatsS2CPacket decode(FriendlyByteBuf buf) {
        return new TeamPlayerStatsS2CPacket(buf);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }

    public UUID getUuid() {
        return uuid;
    }

    public String getTeamName() {
        return teamName;
    }

    public Component getPlayerName() {
        return playerName;
    }

    public int getScores() {
        return scores;
    }

    public int getKills() {
        return Kills;
    }

    public int getDeaths() {
        return Deaths;
    }

    public int getAssists() {
        return Assists;
    }

    public float getDamage() {
        return Damage;
    }

    public int getMvpCount() {
        return mvpCount;
    }

    public boolean isLiving() {
        return isLiving;
    }

    public int getHeadshotKills() {
        return headshotKills;
    }

    public float getUtilityDamage() { return utilityDamage; }
    public int getFlashedEnemies() { return flashedEnemies; }
    public int getRoundKills() { return roundKills; }

    public float getHealthPercent() {
        return healthPercent;
    }

}
