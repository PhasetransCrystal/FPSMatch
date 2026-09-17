package com.ptcrys.fpsmatch.common.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Scoreboard;

import com.ptcrys.fpsmatch.common.client.tab.TabRenderer;
import com.ptcrys.fpsmatch.util.RenderUtil;
import org.jetbrains.annotations.NotNull;

public class TabScreen extends Screen {

    public final TabRenderer tab;
    public final boolean shouldCloseOnEsc;

    public TabScreen(TabRenderer tab, boolean shouldCloseOnEsc) {
        super(Component.empty());
        this.tab = tab;
        this.shouldCloseOnEsc = shouldCloseOnEsc;
    }

    public TabScreen(TabRenderer tab) {
        super(Component.empty());
        this.tab = tab;
        this.shouldCloseOnEsc = false;
    }

    @Override
    public void render(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        Scoreboard scoreboard = RenderUtil.getScoreboard();
        tab.render(guiGraphics, this.width, RenderUtil.getPlayerInfos(), scoreboard, scoreboard.getDisplayObjective(0));
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return this.shouldCloseOnEsc;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
