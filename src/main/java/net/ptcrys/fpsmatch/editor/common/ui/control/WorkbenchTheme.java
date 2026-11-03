package net.ptcrys.fpsmatch.editor.common.ui.control;

/** Semantic theme/density tokens. Panels never use Minecraft button textures. */
public record WorkbenchTheme(int background, int panel, int raised, int border, int text, int muted,
                             int accent, int selected, int warning, int disabled, int row, int gap) {

    public boolean isLight() {
        return ((background >>> 16) & 255) > 128;
    }

    public int canvas() {
        return isLight() ? 0xffdfe6eb : 0xff171d26;
    }

    public int hover() {
        return isLight() ? 0xffe4ebf4 : 0xff323d4c;
    }

    public int onAccent() {
        return isLight() ? 0xffffffff : 0xff102342;
    }

    public int danger() {
        return isLight() ? 0xffb83246 : 0xffff96a5;
    }

    public int positive() {
        return isLight() ? 0xff276846 : 0xff87ceaa;
    }

    public int shadow() {
        return isLight() ? 0x24172e4b : 0x77000000;
    }

    public static WorkbenchTheme dark() {
        return new WorkbenchTheme(0xff181c23, 0xff222731, 0xff2a303c, 0xff363f4d,
                0xffe0e7f1, 0xff9daabe, 0xff7db1ff, 0xff2a4264, 0xffdeb77b, 0xff8190a4, 20, 4);
    }

    public static WorkbenchTheme light() {
        return new WorkbenchTheme(0xffe9edf2, 0xfff9fafc, 0xffffffff, 0xffd1d9e3,
                0xff253246, 0xff647187, 0xff2666c2, 0xffdceaff, 0xff916323, 0xff748093, 20, 4);
    }
}
