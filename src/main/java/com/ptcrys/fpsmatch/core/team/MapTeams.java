package com.ptcrys.fpsmatch.core.team;

import com.mojang.datafixers.util.Pair;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.common.capability.team.TeamSwitchRestrictionCapability;
import com.ptcrys.fpsmatch.common.packet.team.FPSMAddTeamS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamPlayerLeaveS2CPacket;
import com.ptcrys.fpsmatch.common.packet.team.TeamPlayerStatsS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.CapabilityMap;
import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.data.SpawnPointData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import com.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

public class MapTeams {
    public record JoinTeamResult(Status status, @Nullable String teamName) {
        public enum Status {
            JOINED,
            TEAM_NOT_FOUND,
            TEAM_FULL,
            NO_AVAILABLE_TEAM,
            MID_MATCH_JOIN_DISABLED,
            CANCELLED
        }

        public boolean isSuccess() {
            return status == Status.JOINED;
        }

        public static JoinTeamResult joined(String teamName) {
            return new JoinTeamResult(Status.JOINED, teamName);
        }

        public static JoinTeamResult of(Status status) {
            return new JoinTeamResult(status, null);
        }
    }

    protected final ServerLevel level;
    protected final BaseMap map;
    private final Map<String, ServerTeam> teams = new HashMap<>();
    public final Map<UUID,Component> playerName = new HashMap<>();

    /**
     * 构造函数，用于创建 MapTeams 对象
     * @param level 服务器级别。
     * @param map 地图对象。
     */
    public MapTeams(ServerLevel level, BaseMap map){
        this.level = level;
        this.map = map;
        this.addTeam(TeamData.of("spectator",-1),true)
                .getCapabilityMap()
                .ifPresent(SpawnPointCapability.class,cap->{
                    Vec3 vec3 = map.getMapArea().aabb().getCenter();
                    cap.addSpawnPointData(new SpawnPointData(map.getServerLevel().dimension(),vec3,0,0));
                });
    }

    /**
     * 构造函数，用于创建 MapTeams 对象
     * @param level 服务器级别。
     * @param teams 包含团队名称,能力,玩家限制的映射。
     * @param map 地图对象。
     */
    public MapTeams(ServerLevel level, List<TeamData> teams, BaseMap map) {
        this(level,map);
        teams.forEach(this::addTeam);
    }


    public void tick(){
        List<ServerPlayer> online = getOnlineWithSpec();
        for (ServerTeam team : teams.values()) {
            team.tick();
            team.syncCapabilities(online);
        }
        sync(online);
    }

    public void addSpawnPoint(ServerTeam team, SpawnPointData spawnPointData) {
        team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(cap -> cap.addSpawnPointData(spawnPointData));
    }

    /**
     * 根据队伍名称获取指定队伍的出生点数据。
     * <p>
     * 如果指定的队伍不存在，则返回 null。
     *
     * @param team 队伍名称
     * @return 指定队伍的出生点数据列表，如果队伍不存在则返回 null
     */
    public Optional<List<SpawnPointData>> getSpawnPointsByTeam(String team){
        ServerTeam t = this.teams.getOrDefault(team,null);
        if(t == null) return Optional.empty();
        return t.getCapabilityMap().get(SpawnPointCapability.class).map(SpawnPointCapability::getSpawnPointsData);
    }

    /**
     * 交换两个队伍的攻击方和防守方状态。
     * <p>
     * 该方法会交换两个队伍的玩家数据、得分、连败次数、补偿因子以及暂停次数和状态。
     * 如果指定的攻击方或防守方队伍不存在，则不会执行任何操作。
     *
     * @param attackTeam 攻击方队伍
     * @param defendTeam 防守方队伍
     */
    public void switchAttackAndDefend(BaseMap map , ServerTeam attackTeam, ServerTeam defendTeam) {
        if(map == null || attackTeam == null || defendTeam == null) return;

        //交换玩家
        Map<UUID, PlayerData> tempPlayers = new HashMap<>(attackTeam.getPlayers());
        attackTeam.clearAndPutPlayers(defendTeam.getPlayers(),this::addToUnableSwitch);
        defendTeam.clearAndPutPlayers(tempPlayers,this::addToUnableSwitch);

        // 交换得分
        int tempScore = attackTeam.getScores();
        attackTeam.setScores(defendTeam.getScores());
        defendTeam.setScores(tempScore);

        attackTeam.getCapabilityMap().get(ShopCapability.class).flatMap(ShopCapability::getShopSafe).ifPresent(shop-> shop.resetPlayerData(attackTeam.getPlayerList()));
        defendTeam.getCapabilityMap().get(ShopCapability.class).flatMap(ShopCapability::getShopSafe).ifPresent(shop-> shop.resetPlayerData(defendTeam.getPlayerList()));

        randomSpawnPoints();
        syncCapabilities();
        broadcast();
    }

    public void addToUnableSwitch(ServerTeam team, PlayerData data) {
        team.getCapabilityMap().get(TeamSwitchRestrictionCapability.class).ifPresent(cap->{
            cap.addUnableToSwitchPlayer(data.getOwner());
        });
    }

    /**
     * 将提供的出生点数据批量添加到对应队伍中。
     * <p>
     * 如果队伍存在，则将提供的出生点数据列表添加到队伍的出生点数据中。
     *
     * @param data 包含队伍名称和出生点数据列表的 Map
     */
    public void putAllSpawnPoints(Map<String,List<SpawnPointData>> data){
        data.forEach((n,list)->{
            if (teams.containsKey(n)){
                teams.get(n).getCapabilityMap().get(SpawnPointCapability.class).ifPresent(cap->cap.addAllSpawnPointData(list));
            }
        });
    }

    /**
     * 为所有队伍随机分配出生点。
     * <p>
     * 遍历所有队伍，并调用队伍的随机出生点分配方法。
     */
    public boolean randomSpawnPoints(){
        AtomicBoolean flag = new AtomicBoolean(true);
        this.teams.forEach(((s, t) -> {
            if(t.isNormal() && flag.get()) flag.set(t.getCapabilityMap().get(SpawnPointCapability.class).map(SpawnPointCapability::assignNextSpawnPoints).orElse(false));
        }));
        return flag.get();
    }

    /**
     * 为指定队伍添加出生点数据。
     * <p>
     * 如果指定的队伍不存在，则不会执行任何操作。
     *
     * @param teamName 队伍名称
     * @param data 出生点数据
     */
    public void defineSpawnPoint(String teamName, SpawnPointData data) {
        ServerTeam team = this.teams.getOrDefault(teamName, null);
        if (team == null) return;
        team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(cap->cap.addSpawnPointData(data));
    }

    /**
     * 重置指定队伍的出生点数据。
     * <p>
     * 如果指定的队伍不存在，则不会执行任何操作。
     *
     * @param teamName 队伍名称
     */
    public void resetSpawnPoints(String teamName){
        ServerTeam team = this.teams.getOrDefault(teamName, null);
        if (team == null) return;
        team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(SpawnPointCapability::clearSpawnPointsData);
    }

    /**
     * 重置所有队伍的出生点数据。
     * <p>
     * 遍历所有队伍，并调用队伍的出生点数据重置方法。
     */
    public void resetAllSpawnPoints(){
        this.teams.forEach((s,t)-> t.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(SpawnPointCapability::clearSpawnPointsData));
    }

    public ServerTeam addTeam(TeamData data, boolean isSpectator){
        String teamName = data.name();
        int limit = data.limit();
        String fixedName = map.getGameType()+"_"+map.getMapName()+"_"+teamName;

        Scoreboard scoreboard = this.level.getScoreboard();
        PlayerTeam invalid = scoreboard.getPlayerTeam(fixedName);
        if(invalid != null) scoreboard.removePlayerTeam(invalid);

        PlayerTeam playerteam = scoreboard.addPlayerTeam(fixedName);
        ServerTeam team = new ServerTeam(map,teamName,limit,playerteam);
        for (Class<? extends TeamCapability> capClazz : data.getCapabilities()){
            if(!team.getCapabilityMap().add(capClazz)){
                FPSMatch.LOGGER.error("{} Team Capability is not registered : {}",fixedName,capClazz.getName());
            }
        }
        if(isSpectator) team.setSpectator(true);
        this.teams.put(teamName, team);
        return team;
    }

    /**
     * 添加一个新队伍。
     * <p>
     * 创建一个新的队伍，并设置队伍的名称、颜色、是否允许友军伤害等属性。
     * 队伍名称会根据游戏类型、地图名称和队伍名称进行固定格式化。
     *
     * @param data 队伍数据
     */
    public ServerTeam addTeam(TeamData data){
        return addTeam(data,false);
    }

    /**
     * 设置队伍的名称颜色。
     * <p>
     * 根据游戏类型、地图名称和队伍名称获取或创建队伍，并设置其颜色。
     *
     * @param map 地图信息
     * @param teamName 队伍名称
     * @param color 队伍名称颜色
     */
    public void setTeamNameColor(BaseMap map, String teamName, ChatFormatting color){
        String fixedName = map.getGameType()+"_"+map.getMapName()+"_"+teamName;
        PlayerTeam playerteam = Objects.requireNonNullElseGet(this.level.getScoreboard().getPlayersTeam(fixedName), () -> this.level.getScoreboard().addPlayerTeam(fixedName));
        playerteam.setColor(color);
    }

    /**
     * 删除一个队伍。
     * <p>
     * 如果指定的队伍不存在，则不会执行任何操作。
     *
     * @param team 要删除的队伍
     */
    public void delTeam(PlayerTeam team){
        String teamName = null;
        for (Map.Entry<String, ServerTeam> entry : this.teams.entrySet()) {
            if (entry.getValue().getPlayerTeam().equals(team)) {
                teamName = entry.getKey();
                break;
            }
        }
        if (teamName == null) return;
        ServerTeam removedTeam = this.teams.remove(teamName);
        // 队伍被彻底删除时，反注册其各能力在全局事件总线上的监听器，防止泄漏
        if (removedTeam != null) {
            removedTeam.getCapabilityMap().unregisterAllFromBus();
        }
        this.level.getScoreboard().removePlayerTeam(team);
    }

    /**
     * 根据玩家对象获取其所属的队伍。
     * <p>
     * 遍历所有队伍，检查是否有队伍包含该玩家的 UUID，返回对应的队伍。
     * 如果玩家未加入任何队伍，则返回 null。
     *
     * @param player 玩家对象
     * @return 玩家所属的队伍，如果未找到则返回 null
     */
    public Optional<ServerTeam> getTeamByPlayer(Player player) {
        ServerTeam team = null;
        for (ServerTeam baseTeam : this.teams.values()) {
            if(baseTeam.hasPlayer(player.getUUID())){
                team = baseTeam;
                break;
            }
        }
        return Optional.ofNullable(team);
    }

    public ServerTeam getSpectatorTeam(){
        return this.teams.get("spectator");
    }

    /**
     * 根据玩家 UUID 获取其所属的队伍。
     * <p>
     * 遍历所有队伍，检查是否有队伍包含该玩家的 UUID，返回对应的队伍。
     * 如果玩家未加入任何队伍，则返回 null。
     *
     * @param player 玩家 UUID
     * @return 玩家所属的队伍，如果未找到则返回 null
     */
    public Optional<ServerTeam> getTeamByPlayer(UUID player) {
        for (ServerTeam team : this.teams.values()) {
            if (team.isNormal() && team.hasPlayer(player)) {
                return Optional.of(team);
            }
        }
        ServerTeam spec = getSpectatorTeam();
        if (spec != null && spec.hasPlayer(player)) {
            return Optional.of(spec);
        }
        return Optional.empty();
    }
    public Optional<Pair<ServerTeam,PlayerData>> getPlayerTeamAndData(ServerPlayer player){
        return getPlayerTeamAndData(player.getUUID());
    }

    public Optional<Pair<ServerTeam,PlayerData>> getPlayerTeamAndData(UUID player){
        for (ServerTeam team : this.teams.values()) {
            if(!team.isNormal()) continue;
            Optional<Pair<ServerTeam,PlayerData>> opt = team.getPlayerData(player).map(data -> Pair.of(team, data));
            if(opt.isPresent()) return opt;
        }
        return Optional.empty();
    }

    public Optional<PlayerData> getPlayerData(ServerPlayer player){
        return getPlayerData(player.getUUID());
    }

    public Optional<PlayerData> getPlayerData(UUID uuid){
        for (ServerTeam team : this.teams.values()) {
            Optional<PlayerData> data = team.getPlayerData(uuid);
            if (data.isPresent()) return data;
        }
        return Optional.empty();
    }

    /**
    * 获取除removal以外的队伍
    * */
    public List<ServerTeam> getNormalTeams(ServerTeam removal){
        return this.getNormalTeams().stream().filter(t->!removal.equals(t)).toList();
    }

    public List<ServerTeam> getNormalTeams(){
        return this.teams.values().stream().filter(BaseTeam::isNormal).collect(Collectors.toList());
    }

    public List<ServerTeam> getTeamsWithSpectator(){
        return new ArrayList<>(this.teams.values());
    }

    public Map<ServerTeam,List<PlayerData>> getJoinedPlayersMap(){
        return this.teams.values().stream().collect(Collectors.toMap(Function.identity(), ServerTeam::getPlayersData));
    }

    /**
     * 获取所有可进行游戏的玩家 UUID 列表。
     *
     * @return 包含所有可进行游戏的玩家 UUID 的列表
     */
    public List<PlayerData> getJoinedPlayers() {
        List<PlayerData> data = new ArrayList<>();
        getNormalTeams().forEach((t) -> data.addAll(t.getPlayersData()));
        return data;
    }

    public List<UUID> getJoinedUUID() {
        List<UUID> data = new ArrayList<>();
        getNormalTeams().forEach((t) -> data.addAll(t.getPlayerList()));
        return data;
    }

    /**
     * 获取所有已加入队伍的玩家 UUID 列表。
     * <p>
     * 遍历所有队伍，收集所有队伍中的玩家 UUID。
     *
     * @return 包含所有已加入队伍的玩家 UUID 的列表
     */
    public List<UUID> getJoinedPlayersWithSpec() {
        List<UUID> uuids = new ArrayList<>();
        this.teams.values().forEach((t) -> uuids.addAll(t.getPlayerList()));
        return uuids;
    }



    public List<UUID> getSpecPlayers(){
        return this.getSpectatorTeam().getPlayerList();
    }

    /**
     * 重置所有队伍的“存活状态”。
     * <p>
     * 遍历所有队伍，调用队伍的重置存活状态方法。
     */
    public void resetLivingPlayers() {
        this.teams.values().forEach(BaseTeam::resetLiving);
    }

    /**
     * 让玩家加入指定的队伍。
     * <p>
     * 如果指定的队伍不存在，则不会执行任何操作。
     *
     * @param player 玩家对象
     * @param teamName 队伍名称
     */
    private boolean playerJoin(ServerPlayer player, String teamName) {
        // 获取队伍
        ServerTeam team = this.teams.get(teamName);
        if (!team.join(player)) {
            return false;
        }
        if (team.isNormal()) {
            FPSMCore.getInstance().bindPlayerToMap(player.getUUID(), this.map);
            team.getCapabilityMap().get(SpawnPointCapability.class).ifPresent(cap -> cap.assignNextSpawnPoint(player.getUUID()));
        }

        // The ServerTeam hook only sends the newly joined team's capability snapshot.
        // Send the complete roster to the joining client so a client that reset its
        // match state while leaving can recover every team without broadcasting that
        // same O(players) payload to every existing client.
        this.sync(player);
        // Existing clients only need the changed team definition and the new player's
        // stats below. A forced full broadcast here is O(players^2) and duplicates both
        // packets, which can stall the client while switching teams.
        this.syncToAll(FPSMAddTeamS2CPacket.of(team));
        team.getPlayerData(player.getUUID()).ifPresent(playerData ->
                this.syncToAll(TeamPlayerStatsS2CPacket.of(team, playerData))
        );
        return true;
    }

    /**
     * 将脏数据同步给指定玩家。
     * 该方法会将所有队伍中标记为脏数据的玩家信息同步给指定的单个玩家。
     * 同步后不会清除脏数据标记，适用于单个玩家加入游戏时的数据初始化。
     *
     * @param player 要同步数据的目标玩家
     */
    public void sync(ServerPlayer player){
        this.sync(List.of(player),true,false);
    }

    public void broadcast(){
        this.sync(getOnlineWithSpec(),true,false);
    }

    public void syncCapabilities(){
        for (ServerTeam team : this.teams.values()) {
            CapabilityMap<BaseTeam, TeamCapability> map = team.getCapabilityMap();
            map.stream()
                    .filter(cap-> cap instanceof FPSMCapability.DataSynchronizable)
                    .map(FPSMCapability.DataSynchronizable.class::cast)
                    .forEach(FPSMCapability.DataSynchronizable::sync);
        }
    }

    /**
     * 将脏数据同步给指定的玩家集合。
     * 该方法会将所有队伍中标记为脏数据的玩家信息同步给指定的玩家集合。
     * 可以根据参数决定是否在同步后清除脏数据标记。
     *
     * @param players 要同步数据的目标玩家集合
     * @param force 是否无视标记
     * @param setMarked 是否在同步后清除脏数据标记：
     *                  true - 同步后清除标记，表示数据已同步；
     *                  false - 同步后保留标记，数据仍视为脏数据
     */
    public void sync(Collection<ServerPlayer> players, boolean force, boolean setMarked){
        Collection<ServerTeam> teams = this.teams.values();

        for (ServerTeam team : teams) {
            // Team definitions are sent on forced/full syncs (initial join, explicit
            // recovery). The regular dirty sync runs every map tick and must not resend
            // the same definition to every online player.
            if (force) {
                FPSMAddTeamS2CPacket addTeamPacket = FPSMAddTeamS2CPacket.of(team);
                for (ServerPlayer player : players) {
                    FPSMatch.sendToPlayer(player, addTeamPacket);
                    team.syncCapabilities(player);
                }
            }
            for (PlayerData playerData : team.getPlayersData()) {
                if (force || playerData.isDirty()) {
                    TeamPlayerStatsS2CPacket packet = TeamPlayerStatsS2CPacket.of(team, playerData);
                    for (ServerPlayer player : players) {
                        FPSMatch.sendToPlayer(player, packet);
                    }
                    if(setMarked) playerData.setDirty(false);
                }
            }
        }
    }

    /**
     * 将脏数据同步给所有在线玩家。
     * 该方法会收集所有在线玩家，并将所有队伍中标记为脏数据的玩家信息同步给他们。
     * 同步后会清除脏数据标记，表示数据已经完成全量同步。
     * 适用于游戏状态更新时的全局数据同步。
     */
    public void sync(Collection<ServerPlayer> players) {
        sync(players,false, true);
    }

    public List<ServerPlayer> getOnlineWithSpec(){
        List<ServerPlayer> allOnlinePlayers = new ArrayList<>();
        for (ServerTeam team : teams.values()) {
            for (PlayerData playerData : team.getPlayersData()) {
                playerData.getPlayer().ifPresent(allOnlinePlayers::add);
            }
        }
        return allOnlinePlayers;
    }

    public List<ServerPlayer> getOnline(){
        List<ServerPlayer> allOnlinePlayers = new ArrayList<>();
        for (ServerTeam team : getNormalTeams()) {
            for (PlayerData playerData : team.getPlayersData()) {
                playerData.getPlayer().ifPresent(allOnlinePlayers::add);
            }
        }
        return allOnlinePlayers;
    }

    public <M> void syncToAll(M msg){
        for (ServerPlayer player : getOnlineWithSpec()) {
            FPSMatch.sendToPlayer(player, msg);
        }
    }

    /**
     * 让玩家加入指定的队伍，并离开当前队伍。
     * <p>
     * 如果指定的队伍不存在或队伍已满，则发送提示信息并让玩家离开当前队伍。
     *
     * @param teamName 队伍名称
     * @param player   玩家对象
     */
    public JoinTeamResult joinTeam(String teamName, ServerPlayer player) {
        if (!checkTeam(teamName)) {
            return JoinTeamResult.of(JoinTeamResult.Status.TEAM_NOT_FOUND);
        } else if (teamIsFull(teamName)) {
            return JoinTeamResult.of(JoinTeamResult.Status.TEAM_FULL);
        } else {
            if (!this.playerJoin(player, teamName)) {
                return JoinTeamResult.of(JoinTeamResult.Status.CANCELLED);
            }
            this.playerName.put(player.getUUID(), player.getDisplayName());
            return JoinTeamResult.joined(teamName);
        }
    }

    public static JoinTeamResult joinTeam(MapTeams mapTeams, String teamName, ServerPlayer player){
        return mapTeams.joinTeam(teamName,player);
    }

    /**
     * 检查队伍能否被加入。
     * <p>
     * 只有队伍存在且未满员时，允许玩家加入。
     *
     * @param teamName 队伍名称
     * @return 如果队伍可以被加入返回 true，否则返回 false
     */
    public boolean canJoinTeam(String teamName){
        return checkTeam(teamName) && !teamIsFull(teamName);
    }

    public static boolean canJoinTeam(MapTeams mapTeams,String teamName){
        return mapTeams.canJoinTeam(teamName);
    }

    /**
     * 检查指定的队伍是否存在。
     * <p>
     * 如果队伍不存在，则发送提示信息。
     *
     * @param teamName 队伍名称
     * @return 如果队伍存在返回 true，否则返回 false
     */
    public boolean checkTeam(String teamName) {
        if(teamName.equals("spectator")) return true;

        return this.teams.containsKey(teamName);
    }

    /**
     * 检查指定的队伍是否已满。
     * <p>
     * 如果队伍不存在，则返回 false。
     *
     * @param teamName 队伍名称
     * @return 如果队伍已满返回 true，否则返回 false
     */
    public boolean teamIsFull(String teamName) {
        ServerTeam team = teams.get(teamName);
        if (team == null) return false;
        if (team.getPlayerLimit() == -1) {
            return false;
        }
        return team.getPlayerLimit() <= team.getPlayerList().size();
    }

    /**
     * 获取所有不是旁观者队伍的名称列表。
     * <p>
     *
     * @return 名称列表
     */
    public List<String> getNormalTeamsName() {
        return teams.keySet().stream().filter(n->!n.equals("spectator")).toList();
    }

    /**
     * 获取所有队伍的名称列表。
     * <p>
     * 返回一个包含所有队伍名称的列表。
     *
     * @return 所有队伍的名称列表
     */
    public List<String> getAllTeamsName() {
        return teams.keySet().stream().toList();
    }

    /**
     * 根据队伍名称获取队伍对象。
     * <p>

     *
     * @param teamName 队伍名称
     * @return 队伍对象
     */
    public Optional<ServerTeam> getTeamByName(String teamName) {
        return Optional.ofNullable(teams.getOrDefault(teamName,null));
    }

    /**
     * 根据队伍的完整名称（固定格式名称）获取队伍对象。
     * <p>
     * 遍历所有队伍，检查是否有队伍的固定名称与指定名称匹配。
     * 如果未找到，则返回 null。
     *
     * @param teamName 队伍的完整名称
     * @return 队伍对象的 Optional 包装类
     */
    public Optional<ServerTeam> getTeamByFixedName(String teamName) {
        AtomicReference<ServerTeam> team = new AtomicReference<>();
        teams.forEach((s, t) -> {
            if (t.getFixedName().equals(teamName)) {
                team.set(t);
            }
        });
        return Optional.ofNullable(team.get());
    }

    /**
     * 重置所有队伍的状态。
     * <p>
     * 包括重置所有队伍的伤害数据、存活状态、得分、玩家列表、连败次数、补偿因子和暂停时间。
     */
    public void reset() {
        this.teams.forEach((name, team) -> {
            team.clean();
        });
        this.playerName.clear();
    }

    /**
     * 让玩家离开当前所在的队伍。
     * <p>
     * 遍历所有队伍，调用队伍的离开方法移除玩家，并从玩家名称映射中移除该玩家的 UUID。
     *
     * @param player 玩家对象
     */
    public void leaveTeam(ServerPlayer player) {
        if (player.getScoreboard().getPlayersTeam(player.getScoreboardName()) != null) {
            player.getScoreboard().removePlayerFromTeam(player.getScoreboardName());
        }

        for (ServerTeam team : teams.values()) {
            if(team.hasPlayer(player.getUUID())) {
                team.leave(player);
            }
        }
        this.playerName.remove(player.getUUID());
        FPSMCore.getInstance().unbindPlayerFromMap(player.getUUID(), this.map);

        syncToAll(new TeamPlayerLeaveS2CPacket(player.getUUID()));
    }
    /**
     * 让玩家离开当前所在的队伍。
     * <p>
     * 遍历所有队伍，调用队伍的离开方法移除玩家，并从玩家名称映射中移除该玩家的 UUID。
     *
     * @param player 玩家UUID
     */
    public void leaveTeam(UUID player) {
        boolean removed = false;
        for (ServerTeam team : this.teams.values()) {
            if (team.hasPlayer(player)) {
                team.delPlayer(player);
                removed = true;
            }
        }
        this.playerName.remove(player);
        FPSMCore.getInstance().unbindPlayerFromMap(player, this.map);
        if (removed) {
            syncToAll(new TeamPlayerLeaveS2CPacket(player));
        }
    }

    /**
     * 获取所有队伍的存活玩家数据。
     * <p>
     * 返回一个 Map，键为队伍名称，值为该队伍存活玩家的 UUID 列表。
     * 如果某个队伍没有存活玩家，则不会将其加入返回的 Map 中。
     *
     * @return 包含所有普通队伍存活玩家数据的 Map
     */
    public Map<ServerTeam, List<UUID>> getTeamsLiving() {
        Map<ServerTeam, List<UUID>> teamsLiving = new HashMap<>();
        teams.forEach((s, t) -> {
            if(t.isNormal()){
                List<UUID> list = t.getLivingPlayers();
                if (!list.isEmpty()) {
                    teamsLiving.put(t, list);
                }
            }
        });
        return teamsLiving;
    }


    /**
     * 获取与玩家同队的所有玩家 UUID 列表。
     * <p>
     * 如果玩家未加入任何队伍，则返回空列表。
     *
     * @param player 玩家对象
     * @return 同队玩家的 UUID 列表
     */
    public List<UUID> getSameTeamPlayerUUIDs(Player player) {
        List<UUID> uuids = new ArrayList<>();
        getTeamByPlayer(player).ifPresent(t->{
            uuids.addAll(t.getPlayerList());
            uuids.remove(player.getUUID());
        });
        return uuids;
    }

    /**
     * 添加玩家的伤害数据。
     * <p>
     * 根据攻击者和目标玩家的 UUID，记录伤害值。
     * 如果攻击者未加入任何队伍，则不会记录任何数据。
     *
     * @param attacker 攻击者玩家对象
     * @param target 目标玩家的 UUID
     * @param damage 伤害值
     */
    public void addHurtData(ServerPlayer attacker, ServerPlayer target, float damage) {
        getTeamByPlayer(attacker)
                .flatMap(t -> t.getPlayerData(attacker.getUUID()))
                .ifPresent(p -> p.addDamageData(target.getUUID(), Math.min(target.getHealth(), damage)));
    }

    /**
     * 获取当前游戏中伤害输出最高的玩家 UUID。
     * <p>
     * 遍历所有队伍的伤害数据，计算每个玩家的总伤害输出，返回最高伤害输出的玩家 UUID。
     * 如果没有玩家造成伤害，则返回 null。
     *
     * @return 伤害输出最高的玩家 UUID，如果没有则返回 null
     */
    @Nullable
    public UUID getDamageMvp() {
        Map<UUID, Float> damageMap = this.getLivingHurtData();

        UUID mvpId = null;
        float highestDamage = 0;

        for (Map.Entry<UUID, Float> entry : damageMap.entrySet()) {
            if (mvpId == null || entry.getValue() > highestDamage) {
                mvpId = entry.getKey();
                highestDamage = entry.getValue();
            }
        }
        return mvpId;
    }

    @Nullable
    public UUID getRoundDamageMvp() {
        UUID mvpId = null;
        float highestDamage = 0;

        for (ServerTeam team : getNormalTeams()) {
            for (PlayerData data : team.getPlayersData()) {
                float damage = data.getTempDamage();
                if (mvpId == null || damage > highestDamage) {
                    mvpId = data.getOwner();
                    highestDamage = damage;
                }
            }
        }
        return highestDamage > 0 ? mvpId : null;
    }

    /**
     * 获取游戏的 MVP 玩家数据。
     * <p>
     * 根据队伍的得分、击杀数、助攻数和伤害输出计算 MVP 玩家。
     * 如果没有玩家符合条件，则返回 null。
     *
     * @param winnerTeam 获胜队伍
     * @return MVP 玩家数据，如果没有则返回 null
     */
    public RawMVPData getGameMvp(BaseTeam winnerTeam) {
        UUID mvpId = null;
        int highestScore = 0;
        UUID damageMvpId = this.getDamageMvp();

        for (PlayerData data : winnerTeam.getPlayersData()) {
            data.saveRoundData();
            int kills = data.getKills() * 2;
            int assists = data.getAssists();

            int score = kills + assists;
            if (data.getOwner().equals(damageMvpId)) {
                score += 2;
            }

            if (mvpId == null || score > highestScore) {
                mvpId = data.getOwner();
                highestScore = score;
            }
        }

        return mvpId == null ? null : new RawMVPData(mvpId, "MVP");
    }

    /**
     * 开始新一轮游戏。
     * <p>
     * 重置所有玩家的伤害数据和存活状态，并保存队伍的临时数据。
     */
    public void startNewRound() {
        this.resetLivingPlayers();
        this.randomSpawnPoints();
        this.syncCapabilities();
    }

    /**
     * 检查当前是否是第一轮游戏。
     * <p>
     * 如果所有队伍的得分总和为 0，则认为是第一轮。
     *
     * @return 如果是第一轮返回 true，否则返回 false
     */
    public boolean isFirstRound() {
        AtomicInteger flag = new AtomicInteger();
        teams.values().forEach((team) -> flag.addAndGet(team.getScores()));
        return flag.get() == 0;
    }

    public boolean isSameTeam(Player p1, Player p2){
        if (p1.getUUID().equals(p2.getUUID())) {
            return true;
        }
        Optional<ServerTeam> t1 = getTeamByPlayer(p1);
        Optional<ServerTeam> t2 = getTeamByPlayer(p2);
        if (t1.isEmpty() || t2.isEmpty()) {
            return false;
        }
        // Spectator / observer teams never count as combat teammates.
        if ("spectator".equals(t1.get().getName()) || "spectator".equals(t2.get().getName())) {
            return false;
        }
        return map.areCombatTeammates(p1, p2, t1.get(), t2.get());
    }

    /**
     * 获取当前轮次的 MVP 玩家数据。
     * <p>
     * 根据击杀数、助攻数和伤害输出计算 MVP 玩家。
     * 如果是第一轮，则直接调用 {@link #getGameMvp(BaseTeam)} 方法。
     *
     * @param winnerTeam 获胜队伍
     * @return MVP 玩家数据
     */
    public RawMVPData getRoundMvpPlayer(ServerTeam winnerTeam) {
        if (!teams.containsValue(winnerTeam)) return null;
        if (isFirstRound()) {
            return getGameMvp(winnerTeam);
        }

        RawMVPData mvpId = null;
        int highestScore = 0;
        UUID damageMvpId = this.getRoundDamageMvp();

        for (PlayerData data : winnerTeam.getPlayersData()) {
            int kills = data.getTempKills() * 3;
            int assists = data.getTempAssists();
            int damageBonus = data.getOwner().equals(damageMvpId) && data.getTempDamage() > 0 ? 2 : 0;
            int score = kills + assists + damageBonus;

            if (score > highestScore) {
                mvpId = new RawMVPData(data.getOwner(), "MVP");
                highestScore = score;
            }
        }

        if (mvpId != null) {
            winnerTeam.getPlayerData(mvpId.uuid()).ifPresent(data -> {
                data.addMvpCount(1);
            });
        }

        return mvpId;
    }

    /**
     * 获取所有存活玩家的伤害数据。
     * <p>
     * 返回一个 Map，键为玩家 UUID，值为该玩家对其他玩家造成的伤害数据。
     *
     * @return 包含所有存活玩家伤害数据的 Map
     */
    public Map<UUID,  Float> getLivingHurtData() {
        Map<UUID,Float> hurtData = new HashMap<>();
        teams.values().forEach((t)-> t.getPlayersData().forEach((data)-> hurtData.put(data.getOwner(),data.getDamage())));
        return hurtData;
    }

    public Map<UUID, Map<UUID, Float>> getDamageMap() {
        Map<UUID, Map<UUID, Float>> hurtData = new HashMap<>();
        teams.values().forEach((t) -> t.getPlayersData().forEach((data) -> hurtData.put(data.getOwner(), data.getDamages())));
        return hurtData;
    }

    public Map<String, CapabilityMap.Wrapper> getData(){
        Map<String, CapabilityMap.Wrapper> capabilityMap = new HashMap<>();
        teams.values().forEach((t) -> capabilityMap.put(t.getName(), t.getCapabilityMap().getData()));
        return capabilityMap;
    }

    public void writeData(Map<String, CapabilityMap.Wrapper> capabilityMap){
        capabilityMap.forEach((name, wrapper) -> {
            if(teams.containsKey(name)){
                teams.get(name).getCapabilityMap().write(wrapper);
            }else{
                FPSMatch.LOGGER.error("Team {} not found : capability is not instantiated", name);
            }
        });
    }
    public Map<UUID, PlayerData.Damage> getDamageReceivedByPlayer(){
        Map<UUID, PlayerData.Damage> damageMap = new HashMap<>();
        for (ServerTeam team : getNormalTeams()) {
            for (PlayerData data : team.getPlayersData()) {
                Map<UUID, PlayerData.Damage> map = data.getDamageData();
                for (Map.Entry<UUID, PlayerData.Damage> entry : map.entrySet()) {
                    damageMap.computeIfAbsent(entry.getKey(),k-> new PlayerData.Damage()).merge(entry.getValue());
                }
            }
        }

        return damageMap;
    }


    public Map<UUID,Float> getRemainHealth(){
        Map<UUID,Float> remainHealth = new HashMap<>();

        for (ServerTeam team : getNormalTeams()) {
            for (PlayerData data : team.getPlayersData()) {
                remainHealth.put(data.getOwner(),data.getHpServer());
            }
        }

        return remainHealth;
    }

    public Component getPlayerName(UUID uuid){
        return playerName.getOrDefault(uuid, Component.literal(String.valueOf(uuid)));
    }

    // 只有在服务器关闭时调用 否则会出现空指针异常！
    // 删除队伍使用
    public void shutdown(Scoreboard scoreboard) {
        for (ServerTeam team : teams.values()) {
            // 关停时反注册能力在全局事件总线上的监听器，避免事件总线累积持有对象
            team.getCapabilityMap().unregisterAllFromBus();
            scoreboard.removePlayerTeam(team.getPlayerTeam());
        }
    }

    /**
     * 用于表示 MVP 数据的记录类。
     * <p>
     * 包含玩家的 UUID 和获得 MVP 的原因。
     *
     * @param uuid 玩家的 UUID
     * @param reason 获得 MVP 的原因
     */
    public record RawMVPData(UUID uuid, String reason) {
    }

}
