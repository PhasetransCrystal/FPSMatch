package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import net.minecraft.client.gui.GuiGraphics;

/** Shared low-contrast competitive HUD backdrop for every FPSM map surface. */
public final class FPSMLdlib2Backdrop {
    private FPSMLdlib2Backdrop() {
    }

    public static void draw(GuiGraphics graphics, int width, int height) {
        graphics.fill(0, 0, width, height, FPSMLdlib2Theme.BG);
    }

    public static void drawMapIndex(GuiGraphics graphics, int width, int height) {
        draw(graphics, width, height);
    }
}
