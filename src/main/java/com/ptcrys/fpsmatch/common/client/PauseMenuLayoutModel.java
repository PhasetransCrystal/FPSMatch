package com.ptcrys.fpsmatch.common.client;

/** Pure positioning model for the FPSMatch entry appended to the pause menu. */
public final class PauseMenuLayoutModel {

    private static final int PREFERRED_WIDTH = 204;
    private static final int PREFERRED_HEIGHT = 20;
    private static final int HORIZONTAL_MARGIN = 8;
    private static final int VERTICAL_MARGIN = 4;
    private static final int MENU_GAP = 4;

    private PauseMenuLayoutModel() {}

    public record Placement(int x, int y, int width, int height, int menuShiftUp) {}

    /**
     * Places the entry immediately after the detected pause-menu bounds. When the new row would
     * leave the screen, the existing centered menu is shifted up just enough to keep both rows in
     * bounds. The final clamp also handles pathological screen sizes where the vanilla menu itself
     * is already taller than the viewport.
     */
    public static Placement belowMenu(int screenWidth, int screenHeight, int menuTop, int menuBottom) {
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw new IllegalArgumentException("screen dimensions must be positive");
        }
        if (menuBottom < menuTop) {
            throw new IllegalArgumentException("menu bottom must not be above menu top");
        }

        int horizontalMargin = Math.min(HORIZONTAL_MARGIN, Math.max(0, (screenWidth - 1) / 2));
        int width = Math.max(1, Math.min(PREFERRED_WIDTH, screenWidth - horizontalMargin * 2));
        int x = (screenWidth - width) / 2;

        int verticalMargin = Math.min(VERTICAL_MARGIN, Math.max(0, (screenHeight - 1) / 2));
        int height = Math.max(1, Math.min(PREFERRED_HEIGHT, screenHeight - verticalMargin * 2));
        int bottomLimit = screenHeight - verticalMargin;
        int desiredY = menuBottom + MENU_GAP;
        int overflow = Math.max(0, desiredY + height - bottomLimit);
        int availableShift = Math.max(0, menuTop - verticalMargin);
        int menuShiftUp = Math.min(overflow, availableShift);
        int y = desiredY - menuShiftUp;
        y = Math.max(verticalMargin, Math.min(y, bottomLimit - height));

        return new Placement(x, y, width, height, menuShiftUp);
    }
}
