package com.ptcrys.fpsmatch.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;

import com.mojang.datafixers.util.Function3;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.event.FPSMReloadEvent;
import com.ptcrys.fpsmatch.common.event.register.RegisterFPSMSaveDataEvent;
import com.ptcrys.fpsmatch.common.event.register.RegisterFPSMapEvent;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomSyncManager;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.persistence.FPSMDataManager;
import com.ptcrys.fpsmatch.core.shop.functional.LMManager;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@SuppressWarnings("unchecked")
@Mod.EventBusSubscriber(modid = FPSMatch.MODID)
public class FPSMCore {

    private static FPSMCore INSTANCE;
    public final String archiveName;
    private final Map<String, List<BaseMap>> GAMES = new HashMap<>();
    private final Map<String, Function3<ServerLevel, String, AreaData, BaseMap>> REGISTRY = new HashMap<>();
    private final Map<UUID, BaseMap> playerToMapCache = new HashMap<>();
    private final FPSMDataManager fpsmDataManager;
    private final LMManager listenerModuleManager;

    private FPSMCore(String archiveName) {
        this.archiveName = archiveName;
        this.listenerModuleManager = new LMManager();
        this.fpsmDataManager = new FPSMDataManager(archiveName);
    }

    public static FPSMCore getInstance() {
        if (INSTANCE == null) throw new RuntimeException("FPSMatch is not install.");
        return INSTANCE;
    }

    public static boolean initialized() {
        return INSTANCE != null;
    }

    public boolean isRegistered(BaseMap map) {
        return isRegistered(map.getGameType(), map.getMapName());
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
        this.GAMES.forEach((type, mapList) -> mapList.forEach((map) -> {
            try {
                map.mapTick();
            } catch (Exception e) {
                // 记录错误并继续，不要调用 map.reset() 丢弃整局对局/积分数据
                FPSMatch.LOGGER.error("FPSMatch Core -> {} map error: ", map.getMapName(), e);
            }
        }));
        MapRoomSyncManager.tick(getServer());
    }

    protected void clearData() {
        GAMES.clear();
        REGISTRY.clear();
        playerToMapCache.clear();
        MapRoomSyncManager.clear();
    }

    public static void checkAndLeaveTeam(ServerPlayer player) {
        Optional<BaseMap> map = FPSMCore.getInstance().getMapByPlayerWithSpec(player);
        map.ifPresent(baseMap -> baseMap.leave(player));
    }

    public void shutdown() {
        FPSMCore.getInstance().fpsmDataManager.saveAllData();

        for (List<BaseMap> list : GAMES.values()) {
            for (BaseMap map : list) {
                map.getMapTeams().shutdown(getServer().getScoreboard());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStoppingEvent(ServerStoppingEvent event) {
        FPSMCore.getInstance().shutdown();
    }

    @SubscribeEvent
    public static void onServerStartedEvent(ServerStartedEvent event) {
        // 设置实例
        INSTANCE = new FPSMCore(event.getServer().getWorldData().getLevelName());
        // 注册地图
        MinecraftForge.EVENT_BUS.post(new RegisterFPSMapEvent(INSTANCE));
        // 注册数据
        MinecraftForge.EVENT_BUS.post(new RegisterFPSMSaveDataEvent(INSTANCE.fpsmDataManager));
        // 读取数据
        INSTANCE.fpsmDataManager.readAllData();
    }

    @SubscribeEvent
    public static void onReloadEvent(FPSMReloadEvent event) {
        for (List<BaseMap> maps : INSTANCE.GAMES.values()) {
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
        return ServerLifecycleHooks.getCurrentServer();
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
