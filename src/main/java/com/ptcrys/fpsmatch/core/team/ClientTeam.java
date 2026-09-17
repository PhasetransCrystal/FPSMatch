package com.ptcrys.fpsmatch.core.team;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.team.TeamChatMessageC2SPacket;
import com.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import com.ptcrys.fpsmatch.core.data.PlayerData;

import java.util.*;

public final class ClientTeam extends BaseTeam {

    public final Map<UUID, PlayerData> players;

    public ClientTeam(String gameType, String mapName, TeamData data) {
        super(gameType, mapName, data.name(), -1, null);
        players = new HashMap<>();
        for (String cap : data.capabilities()) {
            FPSMCapabilityManager.getRegisteredCapabilityClassByFormated(cap, TeamCapability.class).ifPresent(this.getCapabilityMap()::add);
        }
    }

    @Override
    public boolean join(Player player) {
        if (!super.join(player)) return false;
        this.players.put(player.getUUID(), new PlayerData(player.getUUID(), player.getDisplayName()));
        return true;
    }

    public void join(UUID uuid, PlayerData data) {
        this.players.put(uuid, data);
    }

    public void join(UUID uuid, Component displayName) {
        this.players.put(uuid, new PlayerData(uuid, displayName));
    }

    @Override
    public boolean leave(Player player) {
        if (!super.leave(player)) return false;
        this.delPlayer(player.getUUID());
        return true;
    }

    @Override
    public void delPlayer(UUID player) {
        this.players.remove(player);
    }

    @Override
    public void resetLiving() {}

    @Override
    public Optional<PlayerData> getPlayerData(UUID player) {
        return players.containsKey(player) ? Optional.of(players.get(player)) : Optional.empty();
    }

    @Override
    public List<PlayerData> getPlayersData() {
        return new ArrayList<>(players.values());
    }

    @Override
    public List<UUID> getPlayerList() {
        return new ArrayList<>(players.keySet());
    }

    @Override
    public boolean hasPlayer(UUID uuid) {
        return players.containsKey(uuid);
    }

    @Override
    public int getPlayerCount() {
        return players.size();
    }

    @Override
    public boolean isEmpty() {
        return players.isEmpty();
    }

    @Override
    public Map<UUID, PlayerData> getPlayers() {
        return players;
    }

    public void setPlayerData(UUID player, PlayerData data) {
        players.put(player, data);
    }

    @Override
    public void clearAndPutPlayers(Map<UUID, PlayerData> players) {
        this.players.clear();
        this.players.putAll(players);
    }

    public void sendMessage(Component message) {
        this.sendMessage(message, false);
    }

    @Override
    public void sendMessage(Component message, boolean onlyLiving) {
        FPSMatch.sendToServer(new TeamChatMessageC2SPacket(message));
    }

    @Override
    public boolean isClientSide() {
        return true;
    }
}
