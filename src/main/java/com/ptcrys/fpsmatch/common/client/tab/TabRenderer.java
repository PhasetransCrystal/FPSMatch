package com.ptcrys.fpsmatch.common.client.tab;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;

import java.util.List;

public interface TabRenderer {

    void render(GuiGraphics graphics, int windowWidth, List<PlayerInfo> players, Scoreboard scoreboard, Objective objective);

    String getGameType();
}
