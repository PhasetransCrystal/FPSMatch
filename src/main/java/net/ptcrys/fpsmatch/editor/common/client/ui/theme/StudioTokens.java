package net.ptcrys.fpsmatch.editor.common.client.ui.theme;

/**
 * R17.0 固定视觉 token：颜色为不透明 ARGB，尺寸为未缩放 logical 整数。
 *
 * <p>
 * 本类只提供常量，不含任何设计推断、不读资源、不访问线程或控件。token 名字与取值由任务卡固定，
 * 后续 R17 控件只能消费本表，不得新增“设计”颜色或在此处改写数值。
 */
public final class StudioTokens {

    private StudioTokens() {}

    /* ---------- 背景 ---------- */

    public static final int BG_CANVAS = 0xFF171B22;
    public static final int BG_PANEL = 0xFF20252E;
    public static final int BG_RAISED = 0xFF282F3A;
    public static final int BG_INPUT = 0xFF171C24;
    public static final int BG_HOVER = 0xFF303846;
    public static final int BG_SELECTED = 0xFF263B51;

    /* ---------- 描边 ---------- */

    public static final int STROKE = 0xFF353E4C;
    public static final int STROKE_STRONG = 0xFF576578;

    /* ---------- 文本 ---------- */

    public static final int TEXT_PRIMARY = 0xFFE5EAF2;
    public static final int TEXT_SECONDARY = 0xFFA9B5C7;
    public static final int TEXT_DISABLED = 0xFF8190A4;

    /* ---------- 强调 ---------- */

    public static final int ACCENT = 0xFF83B7F4;
    public static final int ACCENT_TEXT = 0xFF141C29;

    /* ---------- 语义 ---------- */

    public static final int POSITIVE = 0xFF88D3B0;
    public static final int WARNING = 0xFFF2C87C;
    public static final int DANGER = 0xFFF19BA1;
    public static final int READONLY = 0xFFB7A6E3;

    /* ---------- 间距（logical） ---------- */

    public static final int SPACE_4 = 4;
    public static final int SPACE_8 = 8;
    public static final int SPACE_12 = 12;
    public static final int SPACE_16 = 16;
    public static final int SPACE_24 = 24;

    /* ---------- 字号（logical） ---------- */

    public static final int FONT_BODY = 13;
    public static final int FONT_SECONDARY = 12;
    public static final int FONT_PANEL = 13;
    public static final int FONT_DIALOG = 16;
    public static final int FONT_CODE = 12;

    /* ---------- 控件/行高/图标（logical） ---------- */

    public static final int CONTROL_HEIGHT = 28;
    public static final int TREE_ROW_HEIGHT = 28;
    public static final int MENU_ROW_HEIGHT = 30;
    public static final int ICON_SIZE = 16;

    /* ---------- 半径与描边（logical） ---------- */

    public static final int CONTROL_RADIUS = 4;
    public static final int DIALOG_RADIUS = 8;
    public static final int STROKE_WIDTH = 1;
    public static final int FOCUS_WIDTH = 2;

    /* ---------- 动画时长（毫秒） ---------- */

    public static final int TOOLTIP_DELAY_MS = 400;
    public static final int PANEL_MOTION_MS = 120;
    public static final int FEEDBACK_MOTION_MS = 150;
}
