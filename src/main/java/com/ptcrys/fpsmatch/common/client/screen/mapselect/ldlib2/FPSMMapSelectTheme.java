package com.ptcrys.fpsmatch.common.client.screen.mapselect.ldlib2;

/**
 * Status color data scoped to the map-selection product flow, aligned with the competitive
 * HUD tokens in {@code assets/fpsmatch/lss/rhodes.lss}. Panels, buttons, inputs and
 * scrollers are styled by that stylesheet; only data-driven colors live here.
 * The keyboard focus ring is drawn by {@code FPSMLdlib2Theme.drawFocusRing}.
 */
public final class FPSMMapSelectTheme {
    public static final int BG = 0xFF181C20;
    public static final int BORDER = 0xFF3A4A57;
    public static final int ACCENT = 0xFFE6A23C;
    public static final int SUCCESS = 0xFF66B77B;
    public static final int WARNING = 0xFFE6A23C;
    public static final int DANGER = 0xFFD65C5C;
    public static final int MUTED = 0xFF8B99A6;
    public static final int GRID_LINE = 0x302B3947;
    /** Hold-to-confirm progress arc color (HoldActionProgress). */
    public static final int HOLD_ACTION_PROGRESS = DANGER;

    private FPSMMapSelectTheme() {
    }
}
