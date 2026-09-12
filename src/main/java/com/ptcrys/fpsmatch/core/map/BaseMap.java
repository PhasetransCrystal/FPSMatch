package com.ptcrys.fpsmatch.core.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import com.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import com.ptcrys.fpsmatch.common.packet.AddAreaDataS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.capability.CapabilityMap;
import com.ptcrys.fpsmatch.core.capability.map.MapCapability;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.data.Setting;
import com.ptcrys.fpsmatch.core.data.SpawnPointData;
import com.ptcrys.fpsmatch.common.packet.FPSMatchGameTypeS2CPacket;
import com.ptcrys.fpsmatch.common.packet.FPSMatchStatsResetS2CPacket;
import com.ptcrys.fpsmatch.common.event.FPSMapEvent;
import com.ptcrys.fpsmatch.core.persistence.ISavePort;
import com.ptcrys.fpsmatch.core.team.BaseTeam;
import com.ptcrys.fpsmatch.core.team.MapTeams;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import com.ptcrys.fpsmatch.core.team.TeamData;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import com.ptcrys.fpsmatch.util.PreviewColorUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;
import java.util.function.Predicate;

/**
 * BaseMap 抽象类，表示游戏中的基础地图。
 */
@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public abstract class BaseMap {
    // 地图名称
    public final String mapName;
    // 游戏是否开始
    protected boolean isStart = false;
    // 是否处于调试模式
    private boolean isDebug = false;
    // 服务器世界
    private final ServerLevel serverLevel;
    // 地图团队
    private final MapTeams mapTeams;

    private final List<Setting<?>> settings = new LinkedList<>();

    protected final Setting<Float> minAssistDamageRatio = this.addSetting("player", "minAssistDamageRatio", 0.25f);
    protected final Setting<Boolean> allowJoinInProgress = this.addSetting("player", "allowJoinInProgress", true);
    protected final Setting<Boolean> teammateGlow = this.addSetting("player", "teammateGlow", false);
    protected final Setting<Boolean> enemyGlow = this.addSetting("player", "enemyGlow", false);
    protected final Setting<Boolean> hideEnemyNameTag = this.addSetting("player", "hideEnemyNameTag", true);
    protected final Setting<String> displayName = this.addSetting("map", "displayName", "");
    /**
     * 地图图标贴图资源路径。
     * <p>
     * OP 可在地图设置界面填写，留空则客户端使用色块兜底渲染。
     * 例：{@code fpsmatch:textures/gui/maps/dust2_icon.png}
     * <p>
     * 该值通过 MapRoomDetail.iconTexture() 专用字段下发到客户端，
     * 不混入 settings 列表展示（避免重复），但存储走 Setting 通道以获得持久化与编辑能力。
     */
    protected final Setting<String> iconTexture = this.addSetting("map", "iconTexture", "");
    /**
     * 地图背景贴图资源路径（详情页大图）。
     * <p>
     * 用法同 {@link #iconTexture}，留空则客户端使用色块兜底渲染。
     */
    protected final Setting<String> backgroundTexture = this.addSetting("map", "backgroundTexture", "");

    private final CapabilityMap<BaseMap, MapCapability> capabilities;

    // 通用倒计时配置（大厅控制器使用）
    protected final Setting<Boolean> autoStart = this.addSetting("match", "autoStart", false);
    protected final Setting<Integer> autoStartTime = this.addSetting("match", "autoStartTime", 6000);
    protected final Setting<Boolean> readyStartEnabled = this.addSetting("match", "readyStartEnabled", true);
    protected final Setting<Integer> readyStartTime = this.addSetting("match", "readyStartTime", 200);

    private final MapLobbyController lobby = new MapLobbyController(this);

    // 地图区域数据
    public AreaData mapArea;

    /**
     * BaseMap 类的构造函数。
     *
     * @param serverLevel 地图所在世界。
     * @param mapName     地图名称。
     * @param areaData    地图的区域数据。
     */
    public BaseMap(ServerLevel serverLevel, String mapName, AreaData areaData) {
        this.serverLevel = serverLevel;
        this.mapName = mapName;
        this.mapArea = areaData;
        this.mapTeams = new MapTeams(serverLevel, this);
        this.capabilities = CapabilityMap.ofMapCapability(this);
    }

    public BaseMap(ServerLevel serverLevel, String mapName, AreaData areaData, List<Class<? extends MapCapability>> capabilities) {
        this(serverLevel, mapName, areaData);
        for (Class<? extends MapCapability> cap : capabilities) {
            if (!this.capabilities.add(cap)) {
                FPSMatch.LOGGER.error("Failed to add capability {} to map {}", cap.getSimpleName(), this.mapName);
            }
        }
    }

    /**
     * 获取当前地图的所有配置项集合。
     *
     * @return 配置项集合。
     */
    public Collection<Setting<?>> settings() {
        return settings;
    }

    ;

    /**
     * 获取大厅控制器。
     */
    public MapLobbyController getLobby() {
        return lobby;
    }

    /**
     * 切换指定玩家的准备状态。
     *
     * @param player 玩家对象
     * @return 切换后的准备状态
     */
    public boolean toggleReady(ServerPlayer player) {
        return lobby.toggleReady(player);
    }

    /**
     * 设置指定玩家的准备状态。
     */
    public void setReady(UUID uuid, boolean ready) {
        lobby.setReady(uuid, ready);
    }

    /**
     * 检查玩家是否已准备。
     */
    public boolean isReady(UUID uuid) {
        return lobby.isReady(uuid);
    }

    /**
     * 获取当前已准备玩家集合。
     */
    public Set<UUID> getReadyPlayers() {
        return lobby.getReadyPlayers();
    }

    /**
     * 清空所有玩家的准备状态。
     */
    public void clearReadyPlayers() {
        lobby.clearReadyPlayers();
    }

    /**
     * 获取当前准备倒计时剩余秒数（用于客户端展示）。
     */
    public int getReadyCountdownSeconds() {
        return lobby.getReadyCountdownSeconds();
    }

    /**
     * 设置准备倒计时剩余秒数。
     */
    public void setReadyCountdownSeconds(int seconds) {
        lobby.setReadyCountdownSeconds(seconds);
    }

    /**
     * 子类可覆盖：是否满足自动开始条件。
     */
    protected boolean canAutoStart() {
        return false;
    }

    /**
     * 子类可覆盖：是否满足全员准备开始条件。
     * 默认要求所有在线普通队伍玩家均已准备。
     */
    protected boolean canReadyStart() {
        return lobby.allNormalOnlinePlayersReady();
    }

    /**
     * 检查所有在线普通队伍玩家是否都已准备。
     */
    protected boolean allNormalOnlinePlayersReady() {
        return lobby.allNormalOnlinePlayersReady();
    }

    /**
     * 广播准备倒计时。
     */
    protected void broadcastReadyCountdown(int seconds) {
        var packet = new com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomReadyStateS2CPacket(
                getGameType(), getMapName(), seconds, getReadyPlayers());
        sendPacketToAllPlayer(packet);
    }

    /**
     * 子类可覆盖：倒计时结束时如何开始游戏。
     */
    protected void startGameWithAnnouncement() {
        start();
    }

    /**
     * 添加团队
     *
     * @param data 团队数据
     */
    public ServerTeam addTeam(TeamData data) {
        return this.mapTeams.addTeam(data);
    }

    public ServerTeam getSpectatorTeam() {
        return this.mapTeams.getSpectatorTeam();
    }

    /**
     * 地图每个 tick 的操作
     */
    public final void mapTick() {
        checkForVictory();
        tick();
        if (!isStart) {
            clearOfflinePlayersBeforeStart();
            lobby.tick();
        }
        applyTeammateGlow();
        getMapTeams().tick();
        capabilities.tick();
        syncToClient();
    }

    protected void clearOfflinePlayersBeforeStart() {
        boolean changed = false;
        for (ServerTeam team : getMapTeams().getTeamsWithSpectator()) {
            List<UUID> toRemove = new ArrayList<>();
            for (PlayerData data : team.getPlayersData()) {
                if (data.getPlayer().isEmpty()) {
                    toRemove.add(data.getOwner());
                }
            }
            for (UUID uuid : toRemove) {
                lobby.setReady(uuid, false);
                getMapTeams().leaveTeam(uuid);
                changed = true;
            }
        }
        if (changed) {
            clearReadyPlayers();
        }
    }

    /**
     * Marks a disconnected player as temporarily inactive without deleting the
     * reservation needed to restore the player on the next login.
     */
    public void handlePlayerDisconnect(ServerPlayer player) {
        if (player == null) {
            return;
        }
        // Use the player overload so the spectator reservation is handled as well as
        // normal teams; UUID lookup intentionally excludes the spectator team.
        getMapTeams().getTeamByPlayer(player)
                .flatMap(team -> team.getPlayerData(player.getUUID()))
                .ifPresent(data -> {
                    data.setLiving(false);
                    setReady(player.getUUID(), false);
                    player.getScoreboard().removePlayerFromTeam(player.getScoreboardName());
                });
        // Disconnect only changes this reservation's online/living state. Send
        // dirty player data to the remaining clients instead of forcing every
        // team definition and roster entry across the whole map.
        getMapTeams().sync(getMapTeams().getOnlineWithSpec());
    }

    /**
     * 为队友应用透视发光效果，使玩家可透过墙壁看到队友位置。
     * <p>
     * 已改为客户端按队伍判断，不再通过服务端 {@link MobEffects#GLOWING} 实现。
     * 服务端 GLOWING 效果对所有客户端可见，会导致敌方也能看到发光。
     * 队友透视发光逻辑见 {@code PlayerOutlineRenderer}。
     */
    protected void applyTeammateGlow() {
        // 队友透视发光已改为客户端按队伍判断，不再通过服务端 MobEffects.GLOWING 实现
    }

    /**
     * 同步数据到客户端
     */
    public void syncToClient() {
    }

    ;

    /**
     * 每个 tick 的操作
     */
    public void tick() {
    }

    /**
     * 检查胜利条件
     */
    public final void checkForVictory() {
        if (this.victoryGoal()) {
            this.victory();
        }
    }

    /**
     * 开始游戏
     */
    public boolean start() {
        boolean cancelled = MinecraftForge.EVENT_BUS.post(new FPSMapEvent.StartEvent(this));
        if (!cancelled) {
            this.clearReadyPlayers();
        }
        return !cancelled;
    }

    ;

    /**
     * 检查玩家是否在游戏中
     *
     * @param player 玩家对象
     * @return 是否在游戏中
     */
    public boolean checkGameHasPlayer(Player player) {
        return this.checkGameHasPlayer(player.getUUID());
    }

    /**
     * 检查玩家是否在游戏中
     *
     * @param player 玩家对象
     * @return 是否在游戏中
     */
    public boolean checkGameHasPlayer(UUID player) {
        return this.getMapTeams().getTeamByPlayer(player)
                .map(ServerTeam::isNormal)
                .orElse(false);
    }

    public boolean checkSpecHasPlayer(Player player) {
        return this.getMapTeams().getSpecPlayers().contains(player.getUUID());
    }

    /**
     * 开始新一轮游戏
     */
    public void startNewRound() {
    }


    /**
     * 当对局内玩家死亡
     *
     */
    public void handleDeath(DeathContext context) {
        ServerPlayer player = context.getDeadPlayer();
        MapTeams mapTeams = this.getMapTeams();
        mapTeams.getPlayerData(player).ifPresent(data -> {
            data.setLiving(false);
            data.addDeath();
        });
    }

    ;

    /**
     * 解析死亡武器（基础实现：主手物品）
     */
    public ItemStack resolveDeathItem(@Nullable ServerPlayer attacker, DamageSource source) {
        return attacker == null ? ItemStack.EMPTY : attacker.getMainHandItem();
    }

    /**
     * 胜利操作
     */
    public void victory() {
        MinecraftForge.EVENT_BUS.post(new FPSMapEvent.VictoryEvent(this));
    }

    ;

    /**
     * 胜利条件
     *
     * @return 是否满足胜利条件
     */
    public abstract boolean victoryGoal();

    /**
     * 清理地图
     */
    public boolean cleanupMap() {
        return !MinecraftForge.EVENT_BUS.post(new FPSMapEvent.ClearEvent(this));
    }

    /**
     * 重置游戏
     */
    public void reset() {
        MinecraftForge.EVENT_BUS.post(new FPSMapEvent.ResetEvent(this));
        this.clearReadyPlayers();
    }

    ;

    /**
     * 获取地图团队
     *
     * @return 地图团队对象
     */
    public MapTeams getMapTeams() {
        return mapTeams;
    }

    public RandomSource getRandom() {
        return getServerLevel().getRandom();
    }

    /**
     * Resolves the combat relationship between two players whose runtime teams
     * have already been found. Game modes such as free-for-all deathmatch can
     * keep normal roster teams while treating every other player as an enemy.
     */
    public boolean areCombatTeammates(Player first, Player second, ServerTeam firstTeam, ServerTeam secondTeam) {
        return firstTeam.equals(secondTeam);
    }

    /**
     * Credits an assist. Modes with assist score rewards can extend this hook
     * without duplicating the common death pipeline.
     */
    public void creditAssist(PlayerData playerData) {
        playerData.addAssist();
    }

    public void leave(ServerPlayer player) {
        if (MinecraftForge.EVENT_BUS.post(new FPSMapEvent.PlayerEvent.LeaveEvent(this, player))) return;
        this.sendPacketToJoinedPlayer(player, new FPSMatchStatsResetS2CPacket(), true);
        player.setGameMode(GameType.ADVENTURE);
        this.lobby.setReady(player.getUUID(), false);
        this.getMapTeams().leaveTeam(player);
        if (!isStart) {
            this.clearReadyPlayers();
        }
    }


    public MapTeams.JoinTeamResult join(ServerPlayer player) {
        MapTeams mapTeams = this.getMapTeams();
        List<ServerTeam> baseTeams = mapTeams.getNormalTeams();
        if (baseTeams.isEmpty()) return MapTeams.JoinTeamResult.of(MapTeams.JoinTeamResult.Status.NO_AVAILABLE_TEAM);

        List<ServerTeam> teams = new ArrayList<>();
        int minPlayerCount = 0;
        boolean firstFlag = true;
        for (ServerTeam t : baseTeams) {
            if (firstFlag || t.getPlayerCount() < minPlayerCount) {
                minPlayerCount = t.getPlayerCount();
                teams.clear();
                teams.add(t);
                firstFlag = false;
            } else if (t.getPlayerCount() == minPlayerCount) {
                teams.add(t);
            }
        }
        ServerTeam team = teams.size() == 1
                ? teams.get(0)
                : teams.get(new Random().nextInt(0, teams.size()));

        return this.join(team.name, player);
    }

    /**
     * 加入团队
     *
     * @param teamName 团队名称
     * @param player   玩家对象
     */
    public MapTeams.JoinTeamResult join(String teamName, ServerPlayer player) {
        if (this.isStart() && !this.checkGameHasPlayer(player) && !this.allowJoinInProgress.get()) {
            return MapTeams.JoinTeamResult.of(MapTeams.JoinTeamResult.Status.MID_MATCH_JOIN_DISABLED);
        }

        if (MinecraftForge.EVENT_BUS.post(new FPSMapEvent.PlayerEvent.JoinEvent(this, player))) {
            return MapTeams.JoinTeamResult.of(MapTeams.JoinTeamResult.Status.CANCELLED);
        }

        FPSMCore.checkAndLeaveTeam(player);
        this.pullGameInfo(player);
        MapTeams.JoinTeamResult result = this.getMapTeams().joinTeam(teamName, player);
        if (result.isSuccess() && !isStart) {
            this.clearReadyPlayers();
        }
        return result;
    }

    public boolean allowJoinInProgress() {
        return this.allowJoinInProgress.get();
    }

    public void teleportPlayerToReSpawnPoint(ServerPlayer player) {
        this.getMapTeams().getTeamByPlayer(player)
                .ifPresent(team -> team.getPlayerData(player.getUUID()).ifPresent(playerData -> {
                    SpawnPointData currentPoint = playerData.getSpawnPointsData();
                    if (currentPoint == null) {
                        currentPoint = team.getCapabilityMap().get(SpawnPointCapability.class)
                                .flatMap(cap -> cap.assignNextSpawnPoint(player.getUUID()))
                                .orElse(null);
                    }
                    if (currentPoint == null) {
                        player.displayClientMessage(Component.translatable("message.fpsmatch.error.no_spawn_points")
                                .withStyle(ChatFormatting.RED), false);
                        return;
                    }

                    player.setRespawnPosition(currentPoint.getDimension(), currentPoint.getBlockPos(), currentPoint.getYaw(), true, false);
                    if (teleportToPoint(player, currentPoint)) {
                        team.getCapabilityMap().get(SpawnPointCapability.class)
                                .ifPresent(cap -> cap.assignNextSpawnPoint(player.getUUID()));
                    }
                }));
    }

    public boolean teleportToPoint(ServerPlayer player, SpawnPointData data) {
        if (!Level.isInSpawnableBounds(data.getBlockPos())) return false;
        ServerLevel targetLevel = this.getServerLevel().getServer().getLevel(data.getDimension());
        if (targetLevel == null) {
            return false;
        }

        float yaw = Mth.wrapDegrees(data.getYaw());
        float pitch = Mth.clamp(data.getPitch(), -90.0F, 90.0F);
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setCamera(player);
        Set<RelativeMovement> set = EnumSet.noneOf(RelativeMovement.class);
        if (player.teleportTo(targetLevel, data.getX(), data.getY(), data.getZ(), set, yaw, pitch)) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            label23:
            {
                if (player.isFallFlying()) {
                    break label23;
                }

                player.setDeltaMovement(player.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D));
                player.setOnGround(true);
            }
            return true;
        }
        return false;
    }

    public void clearInventory(UUID uuid, Predicate<ItemStack> inventoryPredicate) {
        Player player = this.getServerLevel().getPlayerByUUID(uuid);
        if (player instanceof ServerPlayer serverPlayer) {
            this.clearInventory(serverPlayer, inventoryPredicate);
        }
    }

    public void clearInventory(ServerPlayer player, Predicate<ItemStack> predicate) {
        player.getInventory().clearOrCountMatchingItems(predicate, -1, player.inventoryMenu.getCraftSlots());
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
    }

    public void clearInventory(ServerPlayer player) {
        player.getInventory().clearOrCountMatchingItems((p_180029_) -> true, -1, player.inventoryMenu.getCraftSlots());
        player.containerMenu.broadcastChanges();
        player.inventoryMenu.slotsChanged(player.getInventory());
    }

    public void syncInventory(ServerPlayer player) {
        player.inventoryMenu.slotsChanged(player.getInventory());
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * 获取服务器世界
     *
     * @return 服务器世界对象
     */
    public ServerLevel getServerLevel() {
        return serverLevel;
    }

    /**
     * 是否处于调试模式
     *
     * @return 是否处于调试模式
     */
    public boolean isDebug() {
        return isDebug;
    }

    /**
     * 切换调试模式
     *
     * @return 切换后的调试模式状态
     */
    public boolean switchDebugMode() {
        this.isDebug = !this.isDebug;
        return this.isDebug;
    }

    /**
     * 获取地图名称
     *
     * @return 地图名称
     */
    public String getMapName() {
        return mapName;
    }

    /**
     * 获取游戏类型
     *
     * @return 游戏类型
     */
    public abstract String getGameType();

    /**
     * 重新加载地图逻辑
     *
     */
    public boolean reload() {
        boolean flag = !MinecraftForge.EVENT_BUS.post(new FPSMapEvent.ReloadEvent(this));
        if (flag) {
            loadConfig();
        }
        return flag;
    }

    public final void load() {
        if (FPSMCore.getInstance().isRegistered(this)) return;

        MinecraftForge.EVENT_BUS.post(new FPSMapEvent.LoadEvent(this));
        FPSMCore.getInstance().registerMap(this.getGameType(), this);
    }


    /**
     * 获取地图的所有能力
     *
     * @return 能力实例集合
     */
    public CapabilityMap<BaseMap, MapCapability> getCapabilityMap() {
        return capabilities;
    }

    protected MapTeams.RawMVPData getRoundMvpPlayer(@NotNull ServerTeam winnerTeam, @NotNull MapTeams mapTeams) {
        return mapTeams.getRoundMvpPlayer(winnerTeam);
    }

    protected MapTeams.RawMVPData getGameMvp(@NotNull BaseTeam winnerTeam, @NotNull MapTeams mapTeams) {
        return mapTeams.getGameMvp(winnerTeam);
    }

    public boolean canUseShop(ShopCapability cap, ServerPlayer player) {
        return cap != null && player != null && player.isAlive() && cap.isInitialized();
    }

    /**
     * 比较两张地图是否相等
     *
     * @param object 比较对象
     * @return 是否相等
     */
    public boolean equals(Object object) {
        if (object instanceof BaseMap map) {
            return map.getMapName().equals(this.getMapName()) && map.getGameType().equals(this.getGameType());
        } else {
            return false;
        }
    }


    /**
     * 获取地图区域数据
     *
     * @return 地图区域数据对象
     */
    public AreaData getMapArea() {
        return mapArea;
    }

    public void setMapArea(AreaData mapArea) {
        this.mapArea = mapArea;
    }

    /**
     * 发送数据包给所有玩家
     *
     * @param packet 数据包对象
     * @param <MSG>  数据包类型
     */
    public <MSG> void sendPacketToAllPlayer(MSG packet) {
        this.getMapTeams().getJoinedPlayersWithSpec().forEach(uuid ->
                this.getPlayerByUUID(uuid).ifPresent(player ->
                        this.sendPacketToJoinedPlayer(player, packet, true)
                )
        );
    }

    public <MSG> void sendPacketToSpecPlayer(MSG packet) {
        this.getMapTeams().getSpecPlayers().forEach(uuid ->
                this.getPlayerByUUID(uuid).ifPresent(player ->
                        this.sendPacketToJoinedPlayer(player, packet, true)
                )
        );
    }

    public <MSG> void sendPacketToTeamPlayer(ServerTeam team, MSG packet, boolean living) {
        team.getPlayersData().forEach(data ->
                data.getPlayer().ifPresent(player -> {
                    if (data.isLiving() || !living) {
                        this.sendPacketToJoinedPlayer(player, packet, true);
                    }
                })
        );
    }

    public <MSG> void sendPacketToTeamLivingPlayer(ServerTeam team, MSG packet) {
        this.sendPacketToTeamPlayer(team, packet, true);
    }

    /**
     * 发送数据包给加入游戏的玩家
     *
     * @param player  玩家对象
     * @param packet  数据包对象
     * @param noCheck 是否跳过检查
     * @param <MSG>   数据包类型
     */
    public <MSG> void sendPacketToJoinedPlayer(@NotNull ServerPlayer player, MSG packet, boolean noCheck) {
        if (noCheck || this.checkGameHasPlayer(player)) {
            if (packet instanceof Packet<?> vanilla) {
                player.connection.send(vanilla);
            } else {
                FPSMatch.sendToPlayer(player, packet);
            }
        } else {
            FPSMatch.LOGGER.error("{} is not join {}:{}", player.getDisplayName().getString(), this.getGameType(), this.getMapName());
        }
    }

    public Optional<ServerPlayer> getPlayerByUUID(UUID uuid) {
        return FPSMCore.getInstance().getPlayerByUUID(uuid);
    }

    public void pullGameInfo(ServerPlayer player) {
        this.sendPacketToJoinedPlayer(player, new FPSMatchGameTypeS2CPacket(this.getMapName(), this.getGameType(), this.teammateGlow.get(), this.enemyGlow.get()), true);
    }

    public final boolean isStart() {
        return this.isStart;
    }

    /**
     * 验证攻击是否有效
     */
    public boolean isValidAttack(ServerPlayer attacker, ServerPlayer hurt) {
        return attacker != null &&
                !attacker.isDeadOrDying() &&
                !attacker.getUUID().equals(hurt.getUUID());
    }

    /**
     * 记录有效的伤害来源，用于后续助攻计算。
     */
    public void recordHurtData(ServerPlayer hurt, DamageSource source, float amount) {
        getAttackerFromDamageSource(source).ifPresent(attacker -> {
            if (!isValidAttack(attacker, hurt)) return;
            if (!getMapTeams().isSameTeam(attacker, hurt)) {
                getMapTeams().addHurtData(attacker, hurt, amount);
            }
        });
    }

    /**
     * 从伤害源中提取服务器玩家攻击者
     */
    public Optional<ServerPlayer> getAttackerFromDamageSource(DamageSource source) {
        // 检查主要攻击者
        if (source.getEntity() instanceof ServerPlayer serverPlayer) {
            return Optional.of(serverPlayer);
        }

        // 检查直接攻击实体
        if (source.getDirectEntity() instanceof ServerPlayer serverPlayer) {
            return Optional.of(serverPlayer);
        }

        // 检查追踪实体的拥有者
        return Optional.ofNullable(FPSMUtil.getOwnerIfTraceable(source.getEntity(), source.getDirectEntity()));
    }

    /**
     * 将所有配置项序列化为 JSON 格式。
     * <p>
     * 遍历所有配置项，并调用每个配置项的 {@link Setting#toJson()} 方法，
     * 将其值编码为 JSON 元素并添加到一个 JSON 对象中。
     *
     * @return 包含所有配置项的 JSON 对象。
     */
    public JsonElement configToJson() {
        JsonElement json = new JsonObject();
        for (Setting<?> setting : settings()) {
            json.getAsJsonObject().add(setting.getConfigName(), setting.toJson());
        }
        return json;
    }

    /**
     * 从 JSON 格式反序列化配置项。
     * <p>
     * 遍历 JSON 对象中的每个键值对，并根据配置项的名称查找对应的配置项。
     * 如果找到匹配的配置项，则调用其 {@link Setting#fromJson(JsonElement)} 方法进行反序列化。
     * 如果未找到匹配的配置项，则记录警告日志。
     *
     * @param json 包含配置项的 JSON 对象。
     */
    public void configFromJson(JsonElement json) {
        JsonObject jsonObject = json.getAsJsonObject();
        for (Setting<?> setting : settings()) {
            if (jsonObject.has(setting.getConfigName())) {
                setting.fromJson(jsonObject.get(setting.getConfigName()));
            } else {
                FPSMatch.LOGGER.warn("Setting {} not found in config file.", setting.getConfigName());
            }
        }
    }

    /**
     * 获取当前地图的配置文件路径。
     * <p>
     * 如果地图实现了 {@link ISavePort} 接口，则根据地图名称生成配置文件路径。
     * 如果地图未实现该接口，则记录错误日志并返回 null。
     *
     * @return 配置文件路径，或 null（如果地图未实现 ISavedData 接口）。
     */
    public File getConfigFile() {
        File file = FPSMCore.getInstance().getFPSMDataManager().getSaveFolder(this);
        if (file == null) {
            FPSMatch.LOGGER.error("Failed to get config file for map {} because ：Map is not implement ISavedData interface.", this.getMapName());
            return null;
        } else {
            return new File(file, this.getMapName() + ".cfg");
        }

    }

    /**
     * 加载地图配置文件。
     * <p>
     * 从配置文件路径读取 JSON 数据，并调用 {@link #configFromJson(JsonElement)} 方法反序列化配置项。
     * 如果配置文件不存在或读取失败，则记录错误日志。
     */
    public void loadConfig() {
        File dataFile = getConfigFile();
        if (dataFile == null) return;
        try {
            if (dataFile.exists()) {
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                try (FileReader reader = new FileReader(dataFile, StandardCharsets.UTF_8)) {
                    this.configFromJson(gson.fromJson(reader, JsonElement.class));
                }
            }
        } catch (Exception e) {
            FPSMatch.LOGGER.error("Failed to load map config {}", dataFile.getAbsolutePath(), e);
        }
    }

    /**
     * 保存地图配置文件。
     * <p>
     * 将所有配置项序列化为 JSON 格式，并写入到配置文件路径。
     * 如果配置文件不存在，则创建新文件。
     * 如果保存失败，则记录错误日志。
     */
    public void saveConfig() {
        File dataFile = getConfigFile();
        if (dataFile == null) return;
        try {
            if (!dataFile.exists() && !dataFile.createNewFile()) {
                return;
            }
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            try (FileWriter writer = new FileWriter(dataFile, StandardCharsets.UTF_8)) {
                gson.toJson(this.configToJson(), writer);
            }
        } catch (Exception e) {
            FPSMatch.LOGGER.error("Failed to save map config {}", dataFile.getAbsolutePath(), e);
        }
    }

    public <I> Setting<I> addSetting(Setting<I> setting) {
        settings.add(setting);
        return setting;
    }

    /**
     * 添加一个整型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Integer> addSetting(String configName, int defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Integer> addSetting(String category, String configName, int defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个长整型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Long> addSetting(String configName, long defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Long> addSetting(String category, String configName, long defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个浮点型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Float> addSetting(String configName, float defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Float> addSetting(String category, String configName, float defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个双精度浮点型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Double> addSetting(String configName, double defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Double> addSetting(String category, String configName, double defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个字节型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Byte> addSetting(String configName, byte defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Byte> addSetting(String category, String configName, byte defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个布尔型配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<Boolean> addSetting(String configName, boolean defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<Boolean> addSetting(String category, String configName, boolean defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    /**
     * 添加一个字符串配置项。
     *
     * @param configName   配置项名称。
     * @param defaultValue 默认值。
     * @return 添加的配置项。
     */
    public Setting<String> addSetting(String configName, String defaultValue) {
        return addSetting(Setting.DEFAULT_CATEGORY, configName, defaultValue);
    }

    public Setting<String> addSetting(String category, String configName, String defaultValue) {
        return addSetting(Setting.of(category, configName, defaultValue));
    }

    public Optional<Setting<?>> findSetting(String settingName) {
        return settings().stream()
                .filter(setting -> setting.getConfigName().equals(settingName))
                .findFirst();
    }

    public Setting<Boolean> getEnemyGlowSetting() {
        return enemyGlow;
    }

    public Setting<Boolean> getTeammateGlowSetting() {
        return teammateGlow;
    }

    public float getMinAssistDamageRatio() {
        return minAssistDamageRatio.get();
    }

    public String getDisplayName() {
        String name = displayName.get();
        return name.isEmpty() ? mapName : name;
    }

    /**
     * 获取地图图标贴图资源路径。留空表示未配置，客户端应使用色块兜底。
     */
    public String getIconTexture() {
        return iconTexture.get();
    }

    /**
     * 获取地图背景贴图资源路径。留空表示未配置，客户端应使用色块兜底。
     */
    public String getBackgroundTexture() {
        return backgroundTexture.get();
    }

    public void displayAreas(ServerPlayer player) {
        FPSMatch.sendToPlayer(player, new AddAreaDataS2CPacket(
                "map_preview:" + this.getGameType() + ":" + this.getMapName(),
                Component.literal(this.getMapName()),
                PreviewColorUtil.getMapPreviewColor(this.getGameType()),
                this.mapArea
        ));
    }
}
