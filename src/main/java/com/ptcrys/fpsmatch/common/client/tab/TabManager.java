package com.ptcrys.fpsmatch.common.client.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import com.ptcrys.fpsmatch.common.client.FPSMClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TabManager 类
 * 用于管理CustomTab渲染器
 */
public class TabManager {

    private static final TabManager INSTANCE = new TabManager();
    private final Map<String, TabRenderer> renderers = new HashMap<>();

    /**
     * 获取TabManager实例
     */
    public static TabManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册CustomTab渲染器
     */
    public void registerRenderer(TabRenderer renderer) {
        renderers.put(renderer.getGameType(), renderer);
    }

    /**
     * 渲染CustomTab
     */
    public void render(GuiGraphics graphics, int windowWidth, List<PlayerInfo> players, Scoreboard scoreboard, Objective objective) {
        TabRenderer renderer = renderers.getOrDefault(FPSMClient.getGlobalData().getCurrentGameType(), null);
        if (renderer != null) {
            renderer.render(graphics, windowWidth, players, scoreboard, objective);
        }
    }
}
