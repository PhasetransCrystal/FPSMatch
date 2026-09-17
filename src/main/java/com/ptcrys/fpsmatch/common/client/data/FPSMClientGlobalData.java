package com.ptcrys.fpsmatch.common.client.data;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.shop.ClientShopSlot;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.ClientTeam;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
public class FPSMClientGlobalData {

    // 常量定义
    public static final String NONE_VALUE = "none";
    public static final String SPECTATOR_TEAM = "spectator";
    public static final String DEFAULT_MAP = "fpsm_none";

    private volatile String currentMap = NONE_VALUE;
    private volatile String currentGameType = NONE_VALUE;
    private volatile String currentTeam = NONE_VALUE;
    private volatile boolean teamGlow = false;
    private volatile boolean enemyGlow = false;

    private final Map<String, List<ClientShopSlot>> clientShopData = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> playersMoney = new ConcurrentHashMap<>();
    private final Map<String, ClientTeam> clientTeamData = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerTeamData> playerDataCache = new ConcurrentHashMap<>();

    private final DebugData debugData = new DebugData();
    private volatile boolean mapSelectionButtonVisible;
    private volatile MapSelectionSnapshotS2CPacket mapSelectionSnapshot;
    private volatile MapRoomDetail mapRoomDetail;
    private volatile MapRoomToastS2CPacket mapRoomToast;
    private volatile MapRoomInvitation mapRoomInvitation;

    public DebugData getDebugData() {
        return debugData;
    }

    public record MapRoomInvitation(String gameType, String mapName, Component message, long receivedAtMillis) {}

    // 记录类用于简化数据传递
    public record PlayerTeamData(@Nullable String teamName, @Nullable PlayerData playerData) {

        public boolean isValid() {
            return teamName != null && playerData != null;
        }
    }

    // === 商店数据相关方法 ===

    /**
     * 获取指定类型和索引的商店槽位数据，如果索引无效或不存在则返回空槽位
     */
    @NotNull
    public ClientShopSlot getSlotData(@NotNull String type, int index) {
        if (index < 0) {
            return ClientShopSlot.empty();
        }

        List<ClientShopSlot> shopSlots = clientShopData.computeIfAbsent(type,
                k -> Collections.synchronizedList(new ArrayList<>()));

        synchronized (shopSlots) {
            while (shopSlots.size() <= index) {
                shopSlots.add(ClientShopSlot.empty());
            }
            return shopSlots.get(index);
        }
    }

    /**
     * 安全获取槽位数据，不会自动填充空槽位
     */
    @NotNull
    public Optional<ClientShopSlot> getSlotDataIfPresent(@NotNull String type, int index) {
        if (index < 0) {
            return Optional.empty();
        }

        List<ClientShopSlot> slots = clientShopData.get(type);
        if (slots == null || index >= slots.size()) {
            return Optional.empty();
        }

        synchronized (slots) {
            return Optional.ofNullable(slots.get(index));
        }
    }

    // === 队伍数据相关方法 ===

    public List<ClientTeam> getTeams() {
        return new ArrayList<>(clientTeamData.values());
    }

    @NotNull
    public Optional<ClientTeam> getTeamByName(@NotNull String teamName) {
        return Optional.ofNullable(clientTeamData.get(teamName));
    }

    @NotNull
    public Optional<ClientTeam> getTeamByUUID(@NotNull UUID uuid) {
        return clientTeamData.values().stream()
                .filter(team -> team.hasPlayer(uuid))
                .findFirst();
    }

    public void addTeam(@NotNull ClientTeam team) {
        clientTeamData.put(team.name, team);
    }

    /**
     * 更新玩家队伍数据，如果玩家在其他队伍则会自动移除
     */
    public void updatePlayerTeamData(@NotNull String teamName, @NotNull UUID uuid,
                                     @NotNull PlayerData data) {
        ClientTeam targetTeam = clientTeamData.get(teamName);
        if (targetTeam == null) {
            if ("csdm".equals(teamName)) {
                removePlayerFromAllTeams(uuid);
                playerDataCache.put(uuid, new PlayerTeamData(teamName, data));
                return;
            }
            FPSMatch.LOGGER.error("ClientGlobalData: Team {} does not exist", teamName);
            return;
        }

        // 从原队伍移除
        removePlayerFromAllTeams(uuid);

        // 添加到新队伍
        playerDataCache.remove(uuid);
        targetTeam.setPlayerData(uuid, data);
    }

    /**
     * 从所有队伍中移除玩家
     */
    private void removePlayerFromAllTeams(@NotNull UUID uuid) {
        clientTeamData.values().forEach(team -> team.delPlayer(uuid));
    }

    @NotNull
    public Optional<PlayerTeamData> getPlayerTeamData(@NotNull UUID uuid) {
        for (Map.Entry<String, ClientTeam> entry : clientTeamData.entrySet()) {
            Optional<PlayerData> playerData = entry.getValue().getPlayerData(uuid);
            if (playerData.isPresent()) {
                return Optional.of(new PlayerTeamData(entry.getKey(), playerData.get()));
            }
        }
        return Optional.ofNullable(playerDataCache.get(uuid));
    }

    @NotNull
    public Optional<String> getPlayerTeamName(@NotNull UUID uuid) {
        return getPlayerTeamData(uuid).map(PlayerTeamData::teamName);
    }

    public void leaveTeam(@NotNull UUID uuid) {
        playerDataCache.remove(uuid);
        getTeamByUUID(uuid).ifPresent(team -> team.delPlayer(uuid));
    }

    @NotNull
    public Optional<PlayerData> getPlayerData(@NotNull UUID uuid) {
        return getPlayerTeamData(uuid).map(PlayerTeamData::playerData);
    }

    public Optional<PlayerData> getLocalData() {
        return getPlayerTeamData(Minecraft.getInstance().player.getUUID()).map(PlayerTeamData::playerData);
    }

    @NotNull
    public Optional<PlayerData> getPlayerData(@NotNull String teamName, @NotNull UUID uuid) {
        return getTeamByName(teamName)
                .flatMap(team -> team.getPlayerData(uuid));
    }

    // === 金钱相关方法 ===

    public void setPlayerMoney(@NotNull UUID uuid, int money) {
        playersMoney.put(uuid, money);
    }

    public int getPlayerMoney(@NotNull UUID uuid) {
        return playersMoney.getOrDefault(uuid, 0);
    }

    // === 状态判断方法 ===

    public boolean isSpectator() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return isSpectatorTeam() || player.isSpectator();
    }

    public boolean isSpectatorTeam() {
        return SPECTATOR_TEAM.equals(currentTeam);
    }

    public boolean isInTeam() {
        return !NONE_VALUE.equals(currentTeam);
    }

    public boolean isInMap() {
        return !NONE_VALUE.equals(currentMap);
    }

    public boolean isInGame() {
        return !NONE_VALUE.equals(currentGameType);
    }

    public boolean isInNormalTeam() {
        return getTeamByName(currentTeam)
                .map(BaseTeam::isNormal)
                .orElse(false);
    }

    public boolean isSameTeam(@NotNull Player p1, @NotNull Player p2) {
        Optional<String> team1 = getPlayerTeamName(p1.getUUID());
        Optional<String> team2 = getPlayerTeamName(p2.getUUID());
        return team1.isPresent() && team2.isPresent() &&
                team1.get().equals(team2.get());
    }

    // === 玩家统计数据的便捷方法 ===

    public int getKills(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getKills)
                .orElse(0);
    }

    public int getHeadshots(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getHeadshotKills)
                .orElse(0);
    }

    public boolean isLiving(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::isLiving)
                .orElse(false);
    }

    public int getMvpCount(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getMvpCount)
                .orElse(0);
    }

    public int getDeaths(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getDeaths)
                .orElse(0);
    }

    public float getDamage(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getDamage)
                .orElse(0F);
    }

    public int getAssists(@NotNull UUID uuid) {
        return getPlayerData(uuid)
                .map(PlayerData::getAssists)
                .orElse(0);
    }

    public int getLivingWithTeam(String team) {
        int living = 0;
        for (var clientTeam : clientTeamData.values()) {
            if (clientTeam.name.equals(team)) {
                for (var data : clientTeam.players.values()) {
                    if (data.isLiving()) living++;
                }
            }
        }
        return living;
    }

    // === 状态管理方法 ===

    public void removePlayer(@NotNull UUID uuid) {
        removePlayerFromAllTeams(uuid);
        playerDataCache.remove(uuid);
        playersMoney.remove(uuid);
    }

    public void reset() {
        this.currentMap = NONE_VALUE;
        this.currentGameType = NONE_VALUE;
        this.currentTeam = NONE_VALUE;
        this.teamGlow = false;
        this.enemyGlow = false;
        this.playersMoney.clear();
        this.clientShopData.clear();
        this.clientTeamData.clear();
        this.playerDataCache.clear();
        clearMapSelectData();
    }

    public Optional<MapSelectionSnapshotS2CPacket> getMapSelectionSnapshot() {
        return Optional.ofNullable(mapSelectionSnapshot);
    }

    public void setMapSelectionSnapshot(MapSelectionSnapshotS2CPacket mapSelectionSnapshot) {
        this.mapSelectionSnapshot = mapSelectionSnapshot;
    }

    public boolean isMapSelectionButtonVisible() {
        return mapSelectionButtonVisible;
    }

    public void setMapSelectionButtonVisible(boolean mapSelectionButtonVisible) {
        this.mapSelectionButtonVisible = mapSelectionButtonVisible;
    }

    public Optional<MapRoomDetail> getMapRoomDetail() {
        return Optional.ofNullable(mapRoomDetail);
    }

    public void setMapRoomDetail(MapRoomDetail mapRoomDetail) {
        this.mapRoomDetail = mapRoomDetail;
    }

    public Optional<MapRoomToastS2CPacket> getMapRoomToast() {
        return Optional.ofNullable(mapRoomToast);
    }

    public void setMapRoomToast(MapRoomToastS2CPacket mapRoomToast) {
        this.mapRoomToast = mapRoomToast;
    }

    public Optional<MapRoomInvitation> getMapRoomInvitation() {
        return Optional.ofNullable(mapRoomInvitation);
    }

    public void setMapRoomInvitation(String gameType, String mapName, Component message) {
        this.mapRoomInvitation = new MapRoomInvitation(gameType, mapName, message, System.currentTimeMillis());
    }

    public void clearMapRoomInvitation() {
        this.mapRoomInvitation = null;
    }

    public void clearMapSelectData() {
        this.mapSelectionSnapshot = null;
        this.mapRoomDetail = null;
        this.mapRoomToast = null;
        this.mapRoomInvitation = null;
    }

    // === Getter/Setter ===

    public String getCurrentMap() {
        return currentMap;
    }

    public void setCurrentMap(String currentMap) {
        this.currentMap = Objects.requireNonNullElse(currentMap, NONE_VALUE);
    }

    public String getCurrentGameType() {
        return currentGameType;
    }

    public void setCurrentGameType(String currentGameType) {
        this.currentGameType = Objects.requireNonNullElse(currentGameType, NONE_VALUE);
    }

    public String getCurrentTeam() {
        return currentTeam;
    }

    @NotNull
    public Optional<ClientTeam> getCurrentClientTeam() {
        return getTeamByName(currentTeam);
    }

    public void setCurrentTeam(String currentTeam) {
        this.currentTeam = Objects.requireNonNullElse(currentTeam, NONE_VALUE);
    }

    public boolean isTeamGlow() {
        return teamGlow;
    }

    public void setTeamGlow(boolean teamGlow) {
        this.teamGlow = teamGlow;
    }

    public boolean isEnemyGlow() {
        return enemyGlow;
    }

    public void setEnemyGlow(boolean enemyGlow) {
        this.enemyGlow = enemyGlow;
    }

    // === 相等性检查 ===

    public boolean isCurrentMap(String map) {
        return currentMap.equals(map);
    }

    public boolean isCurrentGameType(String gameType) {
        return currentGameType.equals(gameType);
    }

    public boolean isCurrentTeam(String team) {
        return currentTeam.equals(team);
    }

    public Map<UUID, Integer> getAllPlayersMoney() {
        return Collections.unmodifiableMap(playersMoney);
    }
}
