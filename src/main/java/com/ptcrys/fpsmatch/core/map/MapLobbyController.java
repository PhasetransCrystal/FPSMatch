package com.ptcrys.fpsmatch.core.map;

import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图开局前大厅控制器。
 * <p>
 * 负责管理玩家准备状态、准备倒计时、自动开始倒计时及相关广播。
 * 对局中状态由 {@link BaseMap} 本身和回合生命周期负责，本类只处理“开始前的等待大厅”。
 */
public class MapLobbyController {

    private final BaseMap map;

    // 已准备玩家集合（旁观者不计入）
    private final Set<UUID> readyPlayers = ConcurrentHashMap.newKeySet();
    // 准备倒计时剩余秒数（仅客户端展示）
    private int readyCountdownSeconds = 0;

    // 倒计时运行状态
    private int autoStartTimer = 0;
    private boolean autoStartAnnounced = false;
    private int readyCountdownTimer = 0;
    private boolean readyCountdownAnnounced = false;

    public MapLobbyController(BaseMap map) {
        this.map = map;
    }

    /**
     * 每 tick 调用一次，驱动准备/自动开始倒计时。
     */
    public void tick() {
        if (map.isStart) {
            resetStartCountdownState();
            return;
        }

        if (map.readyStartEnabled.get() && map.canReadyStart()) {
            handleReadyCountdown();
            return;
        }
        resetReadyCountdownState();

        if (map.autoStart.get() && map.canAutoStart()) {
            handleAutoStartCountdown();
        } else {
            resetAutoStartState();
        }
    }

    /**
     * 切换指定玩家的准备状态。
     */
    public boolean toggleReady(ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (readyPlayers.contains(uuid)) {
            readyPlayers.remove(uuid);
            return false;
        }
        readyPlayers.add(uuid);
        return true;
    }

    /**
     * 设置指定玩家的准备状态。
     */
    public void setReady(UUID uuid, boolean ready) {
        if (ready) {
            readyPlayers.add(uuid);
        } else {
            readyPlayers.remove(uuid);
        }
    }

    /**
     * 检查玩家是否已准备。
     */
    public boolean isReady(UUID uuid) {
        return readyPlayers.contains(uuid);
    }

    /**
     * 获取当前已准备玩家集合的副本。
     */
    public Set<UUID> getReadyPlayers() {
        return new HashSet<>(readyPlayers);
    }

    /**
     * 清空所有玩家的准备状态。
     */
    public void clearReadyPlayers() {
        readyPlayers.clear();
    }

    /**
     * 获取当前准备倒计时剩余秒数（用于客户端展示）。
     */
    public int getReadyCountdownSeconds() {
        return readyCountdownSeconds;
    }

    /**
     * 设置准备倒计时剩余秒数。
     */
    public void setReadyCountdownSeconds(int seconds) {
        this.readyCountdownSeconds = seconds;
    }

    /**
     * 检查所有在线普通队伍玩家是否都已准备。
     */
    public boolean allNormalOnlinePlayersReady() {
        int onlineCount = 0;
        for (var team : map.getMapTeams().getNormalTeams()) {
            for (var data : team.getPlayersData()) {
                if (data.getPlayer().isEmpty()) continue;
                onlineCount++;
                if (!readyPlayers.contains(data.getOwner())) return false;
            }
        }
        return onlineCount > 0;
    }

    private void handleReadyCountdown() {
        readyCountdownTimer++;
        int totalTicks = map.readyStartTime.get();
        int secondsLeft = (totalTicks - readyCountdownTimer) / 20;
        setReadyCountdownSeconds(Math.max(0, secondsLeft));

        if (!readyCountdownAnnounced) {
            readyCountdownAnnounced = true;
        }

        if (readyCountdownTimer % 20 == 0 || readyCountdownTimer == 1) {
            map.broadcastReadyCountdown(secondsLeft);
        }

        if (readyCountdownTimer >= totalTicks) {
            map.startGameWithAnnouncement();
            resetStartCountdownState();
        }
    }

    private void handleAutoStartCountdown() {
        autoStartTimer++;
        int totalTicks = map.autoStartTime.get();
        int secondsLeft = (totalTicks - autoStartTimer) / 20;
        setReadyCountdownSeconds(Math.max(0, secondsLeft));

        if (!autoStartAnnounced) {
            autoStartAnnounced = true;
        }

        if (autoStartTimer >= totalTicks) {
            map.startGameWithAnnouncement();
            resetStartCountdownState();
        }
    }

    private void resetStartCountdownState() {
        resetReadyCountdownState();
        resetAutoStartState();
    }

    private void resetAutoStartState() {
        if (autoStartTimer != 0 || autoStartAnnounced) {
            autoStartTimer = 0;
            autoStartAnnounced = false;
            setReadyCountdownSeconds(0);
        }
    }

    private void resetReadyCountdownState() {
        if (readyCountdownTimer != 0 || readyCountdownAnnounced) {
            readyCountdownTimer = 0;
            readyCountdownAnnounced = false;
            setReadyCountdownSeconds(0);
        }
    }
}
