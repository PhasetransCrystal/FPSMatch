package com.ptcrys.fpsmatch.common.client.screen.ldlib2;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

/** Shared CS2-inspired competition HUD tokens and clipped keyboard focus indicators. */
public final class FPSMLdlib2Theme {
    public static final int BG = 0xFF181C20;
    public static final int BORDER = 0xFF3A4A57;
    public static final int ACCENT = 0xFFE6A23C;
    public static final int SUCCESS = 0xFF66B77B;
    public static final int WARNING = 0xFFE6A23C;
    public static final int DANGER = 0xFFD65C5C;
    public static final int MUTED = 0xFF8B99A6;
    public static final int DISABLED = 0xFF596875;
    public static final int GRID_LINE = 0x302B3947;
    private FPSMLdlib2Theme() { }

    /** Container screens retain a local cue; standalone screens draw it above all siblings. */
    public static void drawFocusRing(UIElement element, GUIContext context) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof AccessibleModularUIScreen) return;
        if (element.isFocused()) drawIndicator(element, context.graphics, 0, 0);
    }

    public static void drawIndicator(UIElement element, net.minecraft.client.gui.GuiGraphics graphics,
                                     int offsetX, int offsetY) {
        if (element == null) return;
        float left = element.getPositionX() + offsetX;
        float top = element.getPositionY() + offsetY;
        float right = left + element.getSizeWidth();
        float bottom = top + element.getSizeHeight();
        for (UIElement current = element; current != null; current = current.getParent()) {
            if (!current.isVisible() || !current.isDisplayed() || !current.isActive()) return;
            if (current != element && !current.getStyle().overflowVisible()) {
                left = Math.max(left, current.getContentX() + offsetX);
                top = Math.max(top, current.getContentY() + offsetY);
                right = Math.min(right, current.getContentX() + offsetX + current.getContentWidth());
                bottom = Math.min(bottom, current.getContentY() + offsetY + current.getContentHeight());
            }
        }
        if (right - left < 5 || bottom - top < 5) return;
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 1000);
        graphics.enableScissor((int) left, (int) top, (int) right, (int) bottom);
        int x = (int) element.getPositionX() + offsetX + 2;
        int y = (int) element.getPositionY() + offsetY + 2;
        int r = (int) (element.getPositionX() + element.getSizeWidth()) + offsetX - 2;
        int b = (int) (element.getPositionY() + element.getSizeHeight()) + offsetY - 2;
        int color = 0xFFD7DFE7;
        if (element.hasClass("room-row")) {
            // Keyboard location is an inset corner mark, never a selected-map frame.
            int markRight = Math.min(r - 4, x + 112);
            graphics.fill(x, y, x + 2, Math.min(b, y + 7), color);
            graphics.fill(x, y, Math.min(markRight, x + 8), y + 2, color);
            graphics.fill(x, b - 2, Math.min(markRight, x + 8), b, color);
            graphics.fill(x, Math.max(y, b - 7), x + 2, b, color);
        } else {
            int length = Math.min(6, Math.max(2, (r - x) / 5));
            graphics.fill(x, y, x + length, y + 1, color);
            graphics.fill(x, y, x + 1, Math.min(b, y + 4), color);
            graphics.fill(r - length, b - 1, r, b, color);
            graphics.fill(r - 1, Math.max(y, b - 4), r, b, color);
        }
        graphics.disableScissor();
        graphics.pose().popPose();
    }
}
