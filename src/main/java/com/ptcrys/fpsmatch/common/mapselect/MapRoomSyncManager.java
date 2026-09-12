package com.ptcrys.fpsmatch.common.mapselect;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetailS2CPacket;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapSelectionSnapshotS2CPacket;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.data.Setting;
import com.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图选择界面的“订阅式事件驱动”同步管理器（服务端）。
 * 取代原先“打开/手动刷新才拉取一次”的纯 PULL 模型：
 * 玩家打开房间列表 → 订阅 LIST；打开某房间详情 → 订阅该房间 DETAIL。
 * 每 {@code RoomSyncIntervalTicks} tick 计算一次房间/列表的轻量签名，只有在
 * 数据实际发生变化时，才向“正在观看该数据”的订阅者广播一次被动(passive)快照。
 * 通过“订阅者集合 + 签名 dirty 检测”双重约束，既保证准实时，又避免对局内玩家收到
 * 无关 GUI 弹出、并把带宽/CPU 开销压到最低（无人观看时直接短路返回）。
 * 本机制受 {@link FPSMConfig.Server#roomSyncPushEnabled} 控制，关闭后完全回退旧行为。
 */
public final class MapRoomSyncManager {
    /** 观看列表的哨兵目标值（区别于具体房间 key）。 */
    private static final String LIST_TARGET = "\u0000list";
    private static final String KEY_SEPARATOR = "\u001f";

    /** 玩家 UUID -> 观看目标（LIST_TARGET 或 房间 key）。同一玩家同一时刻只观看一个目标。 */
    private static final Map<UUID, String> WATCHERS = new ConcurrentHashMap<>();
    /** 房间 key -> 上次广播时的签名，用于 dirty 检测。 */
    private static final Map<String, Long> LAST_ROOM_SIGNATURE = new ConcurrentHashMap<>();

    private static long lastListSignature = Long.MIN_VALUE;
    private static int tickCounter = 0;

    private MapRoomSyncManager() {
    }

    public static String roomKey(String gameType, String mapName) {
        return gameType + KEY_SEPARATOR + mapName;
    }

    public static String roomKey(BaseMap map) {
        return roomKey(map.getGameType(), map.getMapName());
    }

    /** 订阅房间列表视图。 */
    public static void watchList(UUID player) {
        if (player != null) {
            WATCHERS.put(player, LIST_TARGET);
        }
    }

    /** 订阅指定房间详情视图。 */
    public static void watchDetail(UUID player, String gameType, String mapName) {
        if (player != null) {
            WATCHERS.put(player, roomKey(gameType, mapName));
        }
    }

    /** 退订（玩家关闭界面/登出）。 */
    public static void unwatch(UUID player) {
        if (player != null) {
            WATCHERS.remove(player);
        }
    }

    /** 全局 tick 入口，由 {@link FPSMCore#onServerTick()} 在所有地图 tick 之后调用。 */
    public static void tick(MinecraftServer server) {
        if (server == null || !FPSMConfig.Server.roomSyncPushEnabled.get()) {
            return;
        }
        if (WATCHERS.isEmpty() || !FPSMCore.initialized()) {
            return;
        }
        int interval = Math.max(1, FPSMConfig.Server.roomSyncIntervalTicks.get());
        if (++tickCounter < interval) {
            return;
        }
        tickCounter = 0;

        flushList(server);
        flushDetails(server);
    }

    private static void flushList(MinecraftServer server) {
        boolean anyListWatcher = WATCHERS.containsValue(LIST_TARGET);
        if (!anyListWatcher) {
            return;
        }
        long signature = computeListSignature();
        if (signature == lastListSignature) {
            return;
        }
        lastListSignature = signature;

        boolean nonOpButtonEnabled = FPSMConfig.Server.enableMapSelectionButtonForNonOps.get();
        for (Map.Entry<UUID, String> entry : WATCHERS.entrySet()) {
            if (!LIST_TARGET.equals(entry.getValue())) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            boolean viewerOp = MapRoomQueryService.isMapOperator(player);
            FPSMatch.sendToPlayer(player, new MapSelectionSnapshotS2CPacket(
                    MapRoomQueryService.summaries(player), viewerOp, nonOpButtonEnabled, true));
        }
    }

    private static void flushDetails(MinecraftServer server) {
        Set<String> watchedRooms = new HashSet<>(WATCHERS.values());
        watchedRooms.remove(LIST_TARGET);
        if (watchedRooms.isEmpty()) {
            return;
        }
        int maxWatchers = Math.max(1, FPSMConfig.Server.roomSyncMaxWatchersPerRoom.get());

        for (String key : watchedRooms) {
            BaseMap map = findMapByKey(key);
            if (map == null) {
                LAST_ROOM_SIGNATURE.remove(key);
                continue;
            }
            long signature = computeRoomSignature(map);
            Long last = LAST_ROOM_SIGNATURE.get(key);
            if (last != null && last == signature) {
                continue;
            }
            LAST_ROOM_SIGNATURE.put(key, signature);

            int sent = 0;
            for (Map.Entry<UUID, String> entry : WATCHERS.entrySet()) {
                if (!key.equals(entry.getValue())) {
                    continue;
                }
                if (sent >= maxWatchers) {
                    break;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player == null) {
                    continue;
                }
                FPSMatch.sendToPlayer(player, new MapRoomDetailS2CPacket(MapRoomQueryService.detail(player, map), true));
                sent++;
            }
        }
    }

    private static BaseMap findMapByKey(String key) {
        int idx = key.indexOf(KEY_SEPARATOR);
        if (idx < 0) {
            return null;
        }
        String gameType = key.substring(0, idx);
        String mapName = key.substring(idx + KEY_SEPARATOR.length());
        return MapRoomQueryService.findMap(gameType, mapName).orElse(null);
    }

    /** 轻量列表签名：与房间数变化、人数、开始/调试/可加入状态相关，顺序无关累加。 */
    private static long computeListSignature() {
        long acc = 0L;
        for (Map.Entry<String, java.util.List<BaseMap>> entry : FPSMCore.getInstance().getAllMaps().entrySet()) {
            for (BaseMap map : entry.getValue()) {
                long h = map.getGameType().hashCode();
                h = h * 31 + map.getMapName().hashCode();
                h = h * 31 + map.getMapTeams().getJoinedUUID().size();
                h = h * 31 + (map.isStart() ? 1 : 0);
                h = h * 31 + (map.isDebug() ? 1 : 0);
                h = h * 31 + (map.allowJoinInProgress() ? 1 : 0);
                acc += h;
            }
        }
        return acc;
    }

    /** 轻量房间签名：玩家/队伍/在线/准备/设置/开始/调试/倒计时变化即变化，玩家顺序无关。 */
    private static long computeRoomSignature(BaseMap map) {
        long sig = 1469598103934665603L;
        sig = mix(sig, map.isStart() ? 1 : 0);
        sig = mix(sig, map.isDebug() ? 1 : 0);
        sig = mix(sig, map.getReadyCountdownSeconds());

        long playersAcc = 0L;
        for (ServerTeam team : map.getMapTeams().getTeamsWithSpectator()) {
            int teamHash = team.getName().hashCode();
            for (PlayerData data : team.getPlayersData()) {
                long ph = data.getOwner().hashCode();
                ph = ph * 31 + teamHash;
                ph = ph * 31 + (data.getPlayer().isPresent() ? 1 : 0);
                ph = ph * 31 + (map.isReady(data.getOwner()) ? 1 : 0);
                playersAcc += ph;
            }
        }
        sig = mix(sig, playersAcc);

        for (Setting<?> setting : map.settings()) {
            sig = mix(sig, setting.getConfigName().hashCode());
            Object value = setting.get();
            sig = mix(sig, value == null ? 0 : value.hashCode());
        }
        sig = mix(sig, map.getMapArea().pos1().hashCode());
        sig = mix(sig, map.getMapArea().pos2().hashCode());
        for (var area : map.getCapabilityMap().get(DemolitionModeCapability.class)
                .map(DemolitionModeCapability::getBombAreaData).orElseGet(java.util.List::of)) {
            sig = mix(sig, area.pos1().hashCode());
            sig = mix(sig, area.pos2().hashCode());
        }
        sig = mix(sig, MapRoomQueryService.computeInviteTargetSignature(map));
        return sig;
    }

    private static long mix(long sig, long value) {
        sig ^= value;
        sig *= 1099511628211L;
        return sig;
    }

    /** 服务器停止/重载时清理，避免跨会话脏状态。 */
    public static void clear() {
        WATCHERS.clear();
        LAST_ROOM_SIGNATURE.clear();
        lastListSignature = Long.MIN_VALUE;
        tickCounter = 0;
    }
}
