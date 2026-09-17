package com.ptcrys.fpsmatch.core.team;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.common.MinecraftForge;

import com.ptcrys.fpsmatch.common.event.FPSMTeamEvent;
import com.ptcrys.fpsmatch.core.capability.CapabilityMap;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.util.RenderUtil;
import org.joml.Vector3f;

import java.util.*;

public abstract class BaseTeam {

    public final String name;
    public final String gameType;
    public final String mapName;
    private final int playerLimit;
    private final PlayerTeam playerTeam;
    private int scores = 0;
    private Vector3f color = new Vector3f(1, 1, 1);
    private boolean isSpectator = false;
    private final CapabilityMap<BaseTeam, TeamCapability> capabilities;

    public BaseTeam(String gameType, String mapName, String name, int playerLimit, PlayerTeam playerTeam) {
        this.gameType = gameType;
        this.mapName = mapName;
        this.name = name;
        this.playerLimit = playerLimit;
        this.playerTeam = playerTeam;
        this.capabilities = CapabilityMap.ofTeamCapability(this);
    }

    public CapabilityMap<BaseTeam, TeamCapability> getCapabilityMap() {
        return capabilities;
    }

    public boolean join(Player player) {
        return !MinecraftForge.EVENT_BUS.post(new FPSMTeamEvent.JoinEvent(this, player));
    };

    public boolean leave(Player player) {
        return !MinecraftForge.EVENT_BUS.post(new FPSMTeamEvent.LeaveEvent(this, player));
    };

    public abstract void delPlayer(UUID player);

    public abstract void resetLiving();

    public abstract Optional<PlayerData> getPlayerData(UUID player);

    public abstract List<PlayerData> getPlayersData();

    public abstract List<UUID> getPlayerList();

    public abstract boolean hasPlayer(UUID uuid);

    public abstract int getPlayerCount();

    public abstract boolean isEmpty();

    public abstract Map<UUID, PlayerData> getPlayers();

    public abstract void clearAndPutPlayers(Map<UUID, PlayerData> players);

    public abstract void sendMessage(Component message, boolean onlyLiving);

    public abstract boolean isClientSide();

    // 公共方法实现
    public int getPlayerLimit() {
        return playerLimit;
    }

    public int getRemainingLimit() {
        return playerLimit - getPlayerCount();
    }

    /**
     * @apiNote 只在服务端返回不为null
     */
    public PlayerTeam getPlayerTeam() {
        return playerTeam;
    }

    public int getScores() {
        return scores;
    }

    public void setScores(int scores) {
        this.scores = scores;
    }

    public String getFixedName() {
        return this.gameType + "_" + this.mapName + "_" + this.name;
    }

    public String getName() {
        return this.name;
    }

    public Vector3f getColorVec3f() {
        return color;
    }

    public int getColor() {
        return RenderUtil.color(color);
    }

    public void setColor(Vector3f color) {
        this.color = color;
    }

    public void resetCapabilities() {
        this.capabilities.resetAll();
    }

    public void clean() {
        this.resetCapabilities();
        this.setScores(0);
        this.getPlayers().clear();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof BaseTeam team) {
            return this.gameType.equals(team.gameType) && this.mapName.equals(team.mapName) && this.name.equals(team.name);
        }
        return false;
    }

    public void setSpectator(boolean isSpectator) {
        this.isSpectator = isSpectator;
    }

    public boolean isSpectator() {
        return isSpectator;
    }

    public boolean isNormal() {
        return !isSpectator;
    }
}
