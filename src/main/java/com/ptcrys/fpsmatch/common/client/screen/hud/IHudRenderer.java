package com.ptcrys.fpsmatch.common.client.screen.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;

public interface IHudRenderer {

    void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event);

    void onSpectatorRender(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight);

    void onPlayerRender(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight);

    default void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight, boolean isSpectator) {
        if (isSpectator) {
            onSpectatorRender(gui, guiGraphics, partialTick, screenWidth, screenHeight);
        } else {
            onPlayerRender(gui, guiGraphics, partialTick, screenWidth, screenHeight);
        }
    }
}
