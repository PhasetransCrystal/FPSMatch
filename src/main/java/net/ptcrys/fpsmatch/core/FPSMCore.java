package net.ptcrys.fpsmatch.core;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.FPSMReloadEvent;
import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMSaveDataEvent;
import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMapEvent;
import net.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager;
import net.ptcrys.fpsmatch.core.capability.CapabilityMap;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.persistence.FPSMDataManager;
import net.ptcrys.fpsmatch.core.shop.functional.LMManager;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeInstallations;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import com.mojang.datafixers.util.Function3;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

@SuppressWarnings("unchecked")
@Mod.EventBusSubscriber(modid = FPSMatch.MODID)
public class FPSMCore {

    private static volatile FPSMCore INSTANCE;
    public final String archiveName;
    private final Map<String, List<BaseMap>> GAMES = new HashMap<>();
    private final Map<String, Function3<ServerLevel, String, AreaData, BaseMap>> REGISTRY = new HashMap<>();
    private final Map<UUID, BaseMap> playerToMapCache = new HashMap<>();
    private final FPSMDataManager fpsmDataManager;
    private final LMManager listenerModuleManager;
    private final ServerLifetime<MinecraftServer> lifetime;
    private final MinecraftWorldBindings worldBindings;
    private final MinecraftRuntimeInstallations runtimeInstallations;
    private final IdentityHashMap<BaseMap, RuntimeMapLease> runtimeMapLeases = new IdentityHashMap<>();
    private final Map<BaseMap, RuntimeMapLease> publishedRuntimeMaps = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Map<RuntimeMapName, RuntimeMapLease> runtimeNameLeases = new HashMap<>();

    private record RuntimeMapName(String type, String name) {}

    private static final int MAX_RUNTIME_MAP_LEASES = 256;
    private boolean runtimeCleanupRunning;

    private FPSMCore(MinecraftServer server, String archiveName) {
        this.lifetime = new ServerLifetime<>(server, server::isSameThread);
        this.archiveName = archiveName;
        this.listenerModuleManager = new LMManager();
        this.fpsmDataManager = new FPSMDataManager(archiveName);
        this.worldBindings = new MinecraftWorldBindings(server, this::isActive);
        this.runtimeInstallations = new MinecraftRuntimeInstallations(server);
    }

    public static FPSMCore getInstance() {
        FPSMCore core = INSTANCE;
        if (core == null) throw new RuntimeException("FPSMatch is not install.");
        return core;
    }

    public static boolean initialized() {
        return INSTANCE != null;
    }

    /**
     * Observes this core's lifetime only; this does not grant world installation authority.
     */
    public boolean isActive() {
        return INSTANCE == this && lifetime.active();
    }

    public MinecraftRuntimeInstallations runtimeInstallations() {
        return runtimeInstallations;
    }

    /** Shared, callback-free gate before BaseMap/MapTeams can allocate native consumers. */
    public static FPSMCore requireMapConstructionAvailable(ServerLevel level) {
        if (level == null) throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        if (!level.getServer().isSameThread()) throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.WRONG_THREAD);
        FPSMCore core = INSTANCE;
        if (core == null || !core.isActive() || core.getServer() != level.getServer() || core.runtimeCleanupRunning) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        core.runtimeInstallations.requireConstructionAvailable();
        return core;
    }

    public boolean isRegistered(BaseMap map) {
        return isRegistered(map.getGameType(), map.getMapName());
    }

    /** Refuse construction before a same-name constructor can replace scoreboard objects. */
    public final void requireUnoccupiedMapName(String type, String name) {
        requireRuntimeMapNameInput(type, name);
        runtimeInstallations.requireConstruction(type, name);
        requireRuntimeMapNameUnoccupied(type, name);
    }

    private void requireRuntimeMapNameInput(String type, String name) {
        if (!getServer().isSameThread()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.WRONG_THREAD);
        }
        if (!isActive() || runtimeCleanupRunning) throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        if (type == null || type.isEmpty() || name == null || name.isEmpty()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
    }

    /** Pure in-memory occupancy check. Does not grant construction or bypass persistent ownership. */
    public final void requireRuntimeMapNameUnoccupied(String type, String name) {
        requireRuntimeMapNameInput(type, name);
        if (runtimeNameLeases.containsKey(new RuntimeMapName(type, name))) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        for (BaseMap current : GAMES.getOrDefault(type, List.of())) {
            if (name.equals(current.mapName)) {
                throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
            }
        }
    }

    public boolean isRegistered(String type) {
        return REGISTRY.containsKey(type);
    }

    public boolean isRegistered(String type, String name) {
        return REGISTRY.containsKey(type) && GAMES.containsKey(type) && getMapNames(type).contains(name);
    }

    public boolean isInGame(Player player) {
        return getMapByPlayer(player).isPresent();
    }

    public Optional<BaseMap> getMapByPlayer(Player player) {
        return getMapByPlayer(player.getUUID());
    }

    public Optional<BaseMap> getMapByPlayer(UUID player) {
        BaseMap cached = playerToMapCache.get(player);
        if (cached != null && cached.checkGameHasPlayer(player)) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    public Optional<BaseMap> getMapByPlayerWithSpec(Player player) {
        BaseMap cached = playerToMapCache.get(player.getUUID());
        if (cached != null) return Optional.of(cached);
        for (List<BaseMap> list : GAMES.values()) {
            for (BaseMap map : list) {
                if (map.checkSpecHasPlayer(player)) return Optional.of(map);
            }
        }
        return Optional.empty();
    }

    /**
     * 将玩家绑定到指定地图（仅应在玩家加入普通队伍时调用）。
     */
    public void bindPlayerToMap(UUID player, BaseMap map) {
        playerToMapCache.put(player, map);
    }

    /**
     * 解除玩家与指定地图的绑定（仅应在玩家离开地图时调用）。
     */
    public void unbindPlayerFromMap(UUID player, BaseMap map) {
        playerToMapCache.remove(player, map);
    }

    public boolean registerMap(String type, BaseMap map) {
        try (BaseMap.LegacyPersistence legacyPersistence = map.openLegacyPersistence()) {
            runtimeInstallations.requireLegacyConstruction(type, map.mapName);
            if (REGISTRY.containsKey(type)) {
                if (getMapNames(type).contains(map.getMapName())) {
                    FPSMatch.LOGGER.error("FPSMatch Core : has same map name -> {}", map.getMapName());
                    return false;
                }
                List<BaseMap> maps = GAMES.getOrDefault(type, new ArrayList<>());
                maps.add(map);
                GAMES.put(type, maps);
                return true;
            } else {
                FPSMatch.LOGGER.error("FPSMatch Core : unregister game type {}", type);
                return false;
            }
        }
    }

    public Optional<BaseMap> getMapByTypeWithName(String type, String name) {
        if (!checkGameType(type)) return Optional.empty();
        if (!GAMES.containsKey(type)) return Optional.empty();
        List<BaseMap> maps = GAMES.get(type);
        for (BaseMap map : maps) {
            if (map.getMapName().equals(name)) return Optional.of(map);
        }
        return Optional.empty();
    }

    public Optional<BaseMap> getMapByName(String name) {
        for (List<BaseMap> list : GAMES.values()) {
            for (BaseMap map : list) {
                if (map.getMapName().equals(name)) return Optional.of(map);
            }
        }
        return Optional.empty();
    }

    public <T> List<T> getMapByClass(Class<T> clazz) {
        ArrayList<T> list = new ArrayList<>();
        for (List<BaseMap> maps : GAMES.values()) {
            for (BaseMap map : maps) {
                if (clazz.isInstance(map)) {
                    list.add((T) map);
                }
            }
        }
        return list;
    }

    /** Only dedicated installed identities are saved by the installation index instead of old writers. */
    public <T> List<T> getLegacyMapsByClass(Class<T> clazz) {
        List<T> result = new ArrayList<>();
        for (List<BaseMap> maps : GAMES.values()) for (BaseMap map : maps) {
            if (clazz.isInstance(map) && !publishedRuntimeMaps.containsKey(map)) result.add(clazz.cast(map));
        }
        return result;
    }

    /** Dedicated installation publication; registerMap remains a legacy-only path. */
    public void publishInstalledRuntimeMap(BaseMap map, RuntimeMapLease lease) {
        lifetime.requireOwner();
        if (!isActive() || runtimeCleanupRunning || runtimeMapLeases.get(map) != lease) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        var key = runtimeInstallations.requirePublication(map, lease);
        String type = key.type();
        if (runtimeNameLeases.get(new RuntimeMapName(type, key.name())) != lease) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        if (!REGISTRY.containsKey(type)) throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        List<BaseMap> existingList = GAMES.get(type);
        List<BaseMap> next = existingList != null && existingList.getClass() == ArrayList.class ? existingList : new ArrayList<>(GAMES.getOrDefault(type, List.of()));
        for (BaseMap existing : next) if (existing.mapName.equals(map.mapName)) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        lease.requireOpen();
        if (publishedRuntimeMaps.putIfAbsent(map, lease) != null) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        try {
            next.add(map);
            GAMES.put(type, next);
        } catch (RuntimeException | Error failure) {
            next.removeIf(value -> value == map);
            publishedRuntimeMaps.remove(map, lease);
            throw failure;
        }
    }

    /** Exact installed identity required for online replacement; no overridable type getter is used. */
    public void requireInstalledRuntimeMap(BaseMap map, RuntimeMapLease lease,
                                           net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan.Key key) {
        lifetime.requireOwner();
        if (!isActive() || runtimeCleanupRunning) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        if (map == null || lease == null || key == null || !key.name().equals(map.mapName) || publishedRuntimeMaps.get(map) != lease || runtimeMapLeases.get(map) != lease || runtimeNameLeases.get(new RuntimeMapName(key.type(), key.name())) != lease) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        int occurrences = 0;
        for (var entry : GAMES.entrySet()) for (BaseMap current : entry.getValue()) {
            if (current == map) {
                if (!key.type().equals(entry.getKey())) {
                    throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
                }
                occurrences++;
            } else if (key.type().equals(entry.getKey()) && key.name().equals(current.mapName)) {
                throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
            }
        }
        if (occurrences != 1) throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        lease.requireOpen();
    }

    /** Withdrawal does not invoke readiness callbacks and leaves the lease available for cleanup retry. */
    public void withdrawInstalledRuntimeMap(BaseMap map, RuntimeMapLease lease) {
        lifetime.requireOwner();
        if (publishedRuntimeMaps.get(map) != lease) return;
        for (var entry : GAMES.entrySet()) {
            List<BaseMap> current = entry.getValue();
            if (current.stream().noneMatch(value -> value == map)) continue;
            List<BaseMap> remaining = current.getClass() == ArrayList.class ? current : new ArrayList<>(current);
            remaining.removeIf(value -> value == map);
            if (remaining != current) entry.setValue(remaining);
        }
        publishedRuntimeMaps.remove(map, lease);
    }

    public List<String> getMapNames() {
        List<String> names = new ArrayList<>();
        GAMES.forEach((type, mapList) -> mapList.forEach((map -> names.add(map.getMapName()))));
        return names;
    }

    public List<String> getMapNamesWithType(String type) {
        List<String> names = new ArrayList<>();
        if (GAMES.containsKey(type)) {
            GAMES.get(type).forEach(map -> names.add(map.getMapName()));
        }
        return names;
    }

    public Optional<BaseMap> getMapByPosition(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return Optional.empty();
        }

        for (List<BaseMap> maps : GAMES.values()) {
            for (BaseMap map : maps) {
                if (map.getServerLevel().dimension().equals(level.dimension()) && map.getMapArea().isBlockPosInArea(pos)) {
                    return Optional.of(map);
                }
            }
        }
        return Optional.empty();
    }

    public List<String> getMapNames(String type) {
        List<String> names = new ArrayList<>();
        List<BaseMap> maps = GAMES.getOrDefault(type, new ArrayList<>());
        maps.forEach((map -> names.add(map.getMapName())));
        return names;
    }

    public boolean checkGameType(String mapType) {
        return REGISTRY.containsKey(mapType);
    }

    @Nullable
    public Function3<ServerLevel, String, AreaData, BaseMap> getPreBuildGame(String mapType) {
        if (checkGameType(mapType)) return REGISTRY.get(mapType);
        return null;
    }

    public void registerGameType(String typeName, Function3<ServerLevel, String, AreaData, BaseMap> map) {
        ResourceLocation.isValidResourceLocation(typeName);
        REGISTRY.put(typeName, map);
    }

    public List<String> getGameTypes() {
        return REGISTRY.keySet().stream().toList();
    }

    public Map<String, List<BaseMap>> getAllMaps() {
        return GAMES;
    }

    public void onServerTick() {
        if (!isActive()) return;
        lifetime.requireOwner();
        this.GAMES.forEach((type, mapList) -> mapList.forEach((map) -> {
            try {
                map.mapTick();
            } catch (Exception e) {
                // 记录错误并继续，不要调用 map.reset() 丢弃整局对局/积分数据
                FPSMatch.LOGGER.error("FPSMatch Core -> {} map error: ", map.getMapName(), e);
            }
        }));
        MapRoomSyncManager.tick(getServer());
        runtimeInstallations.tests().tick();
    }

    protected void clearData() {
        lifetime.requireOwner();
        if (runtimeCleanupRunning) return;
        List<Runnable> cleanup = new ArrayList<>();
        for (RuntimeMapLease lease : List.copyOf(runtimeMapLeases.values())) cleanup.add(lease::close);
        runtimeCleanupRunning = true;
        try {
            ShutdownCleanup.run(() -> {}, cleanup);
        } finally {
            try {
                if (runtimeMapLeases.isEmpty()) {
                    GAMES.clear();
                    REGISTRY.clear();
                    playerToMapCache.clear();
                    MapRoomSyncManager.clear();
                }
            } finally {
                runtimeCleanupRunning = false;
            }
        }
    }

    /** Adopts retirement only; callers must separately coordinate installation and visibility. */
    public RuntimeMapLease ownRuntimeMap(BaseMap map, Runnable afterConsumers) {
        return ownRuntimeMap(map, afterConsumers, () -> {});
    }

    /** Readiness failures leave retirement open and perform no consumer cleanup. */
    public RuntimeMapLease ownRuntimeMap(BaseMap map, Runnable afterConsumers, Runnable beforeRetirement) {
        if (!getServer().isSameThread()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.WRONG_THREAD);
        }
        if (!isActive() || runtimeCleanupRunning) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        if (map == null || afterConsumers == null || beforeRetirement == null) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        ServerLevel level = map.getServerLevel();
        if (level == null || level.getServer() != getServer()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        if (runtimeMapLeases.containsKey(map) || map.hasRuntimeOwnership()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        if (runtimeMapLeases.size() >= MAX_RUNTIME_MAP_LEASES) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.BUDGET);
        }
        var capabilities = map.getCapabilityMap();
        if (capabilities.isClosed()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        var teams = map.getMapTeams();
        RuntimeMapName runtimeName = new RuntimeMapName(map.getGameType(), map.mapName);
        if (runtimeName.type() == null || runtimeName.type().isEmpty() || runtimeName.name() == null || runtimeName.name().isEmpty()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INVALID_INPUT);
        }
        runtimeInstallations.requireConstruction(runtimeName.type(), runtimeName.name());
        List<CapabilityMap<?, ?>> capturedCapabilities = new ArrayList<>();
        capturedCapabilities.add(capabilities);
        for (var team : teams.getTeamsWithSpectator()) {
            capturedCapabilities.add(team.getCapabilityMap());
        }
        List<CapabilityMap<?, ?>> retirementCapabilities = List.copyOf(capturedCapabilities);
        Scoreboard scoreboard = getServer().getScoreboard();
        if (!isActive() || runtimeCleanupRunning || capabilities.isClosed()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.INACTIVE);
        }
        if (runtimeMapLeases.containsKey(map) || map.hasRuntimeOwnership()) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        if (runtimeMapLeases.size() >= MAX_RUNTIME_MAP_LEASES) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.BUDGET);
        }
        if (runtimeNameLeases.containsKey(runtimeName)) {
            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.DUPLICATE);
        }
        RuntimeMapLease[] holder = new RuntimeMapLease[1];
        RuntimeMapLease lease = new RuntimeMapLease(List.of(capabilities::close,
                () -> teams.shutdown(scoreboard)), () -> {
                    try {
                        afterConsumers.run();
                    } finally {
                        runtimeNameLeases.remove(runtimeName, holder[0]);
                    }
                },
                () -> {
                    try {
                        withdrawInstalledRuntimeMap(map, holder[0]);
                    } finally {
                        runtimeMapLeases.remove(map, holder[0]);
                    }
                }, () -> {
                    for (CapabilityMap<?, ?> captured : retirementCapabilities) {
                        if (captured.isInLifecycleCallback()) {
                            throw RuntimeMapLeaseException.error(RuntimeMapLeaseException.Reason.BUSY);
                        }
                    }
                    beforeRetirement.run();
                });
        holder[0] = lease;
        // Allocate both private index entries before the irreversible map claim.
        // No extension callback runs in this handoff window.
        try {
            runtimeMapLeases.put(map, lease);
            runtimeNameLeases.put(runtimeName, lease);
            map.claimRuntimeOwnership(lease);
        } catch (RuntimeException | Error failure) {
            runtimeMapLeases.remove(map, lease);
            runtimeNameLeases.remove(runtimeName, lease);
            throw failure;
        }
        return lease;
    }

    public static void checkAndLeaveTeam(ServerPlayer player) {
        Optional<BaseMap> map = FPSMCore.getInstance().getMapByPlayerWithSpec(player);
        map.ifPresent(baseMap -> baseMap.leave(player));
    }

    public void shutdown() {
        lifetime.requireOwner();
        try {
            lifetime.stopping(() -> {
                Scoreboard scoreboard = getServer().getScoreboard();
                List<Runnable> cleanup = new ArrayList<>();
                Set<BaseMap> captured = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var entry : runtimeMapLeases.entrySet()) {
                    captured.add(entry.getKey());
                    cleanup.add(entry.getValue()::close);
                }
                for (List<BaseMap> maps : GAMES.values()) {
                    for (BaseMap map : maps) {
                        if (!captured.add(map)) continue;
                        var capabilities = map.getCapabilityMap();
                        var teams = map.getMapTeams();
                        cleanup.add(capabilities::close);
                        cleanup.add(() -> teams.shutdown(scoreboard));
                    }
                }
                ShutdownCleanup.run(() -> {
                    worldBindings.close();
                    if (fpsmDataManager.drainAsyncOperations()) {
                        fpsmDataManager.saveAllData();
                    } else {
                        FPSMatch.LOGGER.error("Final save skipped: an earlier asynchronous save has not terminated; writing a newer snapshot now would risk a later stale overwrite");
                    }
                }, cleanup);
            }, this::closeServices);
        } finally {
            clearStoppedInstance();
        }
    }

    private void clearStoppedInstance() {
        if (lifetime.terminalCleanupFinished() && INSTANCE == this) INSTANCE = null;
    }

    private void closeServices() {
        ShutdownCleanup.run(() -> {}, List.of(worldBindings::close, runtimeInstallations::close, fpsmDataManager::close));
    }

    @SubscribeEvent
    public static void onServerStoppingEvent(ServerStoppingEvent event) {
        FPSMCore core = INSTANCE;
        if (core == null || !core.lifetime.matches(event.getServer())) return;
        core.shutdown();
    }

    @SubscribeEvent
    public static void onServerStoppedEvent(ServerStoppedEvent event) {
        FPSMCore core = INSTANCE;
        if (core == null || !core.lifetime.matches(event.getServer())) return;
        core.lifetime.requireOwner();
        try {
            core.lifetime.stopped(core::closeServices);
        } finally {
            core.clearStoppedInstance();
        }
    }

    @SubscribeEvent
    public static void onServerStartedEvent(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("Server startup requires the server owner thread");
        }
        if (server.isStopped()) {
            throw new IllegalStateException("Cannot start FPSMatch core for a stopped server");
        }
        FPSMCore current = INSTANCE;
        if (current != null) {
            if (current.lifetime.matches(server) && current.lifetime.active()) return;
            throw new IllegalStateException("Another FPSMatch core is still present or stopping");
        }

        FPSMCore core = new FPSMCore(server, server.getWorldData().getLevelName());
        INSTANCE = core;
        try {
            core.runtimeInstallations.restoreOwnership();
            // 注册地图
            MinecraftForge.EVENT_BUS.post(new RegisterFPSMapEvent(core));
            // 注册数据
            MinecraftForge.EVENT_BUS.post(new RegisterFPSMSaveDataEvent(core.fpsmDataManager));
            // 读取数据
            core.fpsmDataManager.readAllData();
        } catch (RuntimeException | Error failure) {
            try {
                core.lifetime.stopped(core::closeServices);
            } catch (RuntimeException | Error closeFailure) {
                if (failure != closeFailure) failure.addSuppressed(closeFailure);
            } finally {
                if (INSTANCE == core) INSTANCE = null;
            }
            throw failure;
        }
    }

    @SubscribeEvent
    public static void onReloadEvent(FPSMReloadEvent event) {
        FPSMCore core = INSTANCE;
        if (core == null || event.getCore() != core || !core.isActive()) return;
        core.lifetime.requireOwner();
        for (List<BaseMap> maps : core.GAMES.values()) {
            for (BaseMap map : maps) {
                try {
                    map.reload();
                } catch (Exception e) {
                    FPSMatch.LOGGER.error("FPSMatch Core -> {} map reload error: ", map.getMapName(), e);
                }
            }
        }
    }

    public MinecraftServer getServer() {
        return lifetime.server();
    }

    /** Local binding checks only; this does not install or publish a runtime map. */
    public LocalWorldBindings getWorldBindings() {
        return worldBindings.bindings();
    }

    /** Explicit current-player registration only; callers must select and verify a fresh binding afterwards. */
    public UUID initializeEditorWorldIdentity(UUID actorId, java.util.function.BooleanSupplier cancelled) throws java.io.IOException {
        return runtimeInstallations.initializeIdentity(this, worldBindings, actorId, cancelled);
    }

    /** Synchronous handoff on this exact core; no installation or resource ownership is granted. */
    public <R> R withConfirmedWorld(WorldBinding binding, WorldBindingCheckResult result,
                                    Set<EditorId> requiredDimensions,
                                    Function<MinecraftWorldBindings.ConfirmedTarget, R> operation) {
        return worldBindings.withConfirmedTarget(binding, result, requiredDimensions, operation);
    }

    @SubscribeEvent
    public static void onBindingPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        FPSMCore core = INSTANCE;
        if (core == null || !(event.getEntity() instanceof ServerPlayer player) || !core.lifetime.matches(player.server)) return;
        core.worldBindings.disconnected(player);
    }

    public Optional<ServerPlayer> getPlayerByUUID(UUID uuid) {
        return Optional.ofNullable(this.getServer().getPlayerList().getPlayer(uuid));
    }

    public FPSMDataManager getFPSMDataManager() {
        return fpsmDataManager;
    }

    public LMManager getListenerModuleManager() {
        return listenerModuleManager;
    }

    /*
     * 获取当前FPSMatch运行在何种环境
     */
    public static FPSMDist getCurrentEnvironment() {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            return FPSMDist.SERVER;
        } else {
            Minecraft mc = Minecraft.getInstance();
            IntegratedServer localServer = mc.getSingleplayerServer();
            if (localServer != null) {
                return localServer.isPublished() ? FPSMDist.LAN : FPSMDist.LOCAL;
            }
        }
        return FPSMDist.LOCAL;
    }
}
