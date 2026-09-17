package com.ptcrys.fpsmatch.common.client.screen.mapselect;

/**
 * FPSMatch 地图选择界面统一颜色规范。
 * <p>
 * 集中管理所有次级界面（选择/详情/设置/管理/商店/邀请/被邀请）的颜色常量，
 * 消除原先散落在各 Screen 中的硬编码颜色值，保证视觉一致性。
 * <p>
 * 颜色格式为 ARGB int（0xAARRGGBB）。
 */
public final class FPSMGuiTheme {

    private FPSMGuiTheme() {}

    // ===== 背景层级 =====
    /** 大厅/界面底色（深蓝灰，替代原 0xFF444444 平铺灰） */
    public static final int BG_BASE = 0xFF1A1D24;
    /** 面板/卡片底色 */
    public static final int BG_PANEL = 0xFF252932;
    /** 卡片悬停底色 */
    public static final int BG_PANEL_HOVER = 0xFF2E333D;
    /** 卡片选中底色 */
    public static final int BG_PANEL_SELECTED = 0xFF3A4150;
    /** 列表区底色（半透明深色） */
    public static final int BG_LIST = 0x991F2229;
    /** 裁剪区底色 */
    public static final int BG_SCISSOR = 0xFF1A1D24;
    /** 阴影半透明黑（多层背景外层） */
    public static final int BG_SHADOW = 0x80000000;

    // ===== 边框 =====
    /** 外边框（深） */
    public static final int BORDER_OUTER = 0xFF0D0F14;
    /** 内边框（浅） */
    public static final int BORDER_INNER = 0xFF3A3F4A;
    /** 内边距 */
    public static final int PADDING = 4;

    // ===== 文字层级 =====
    /** 标题白 */
    public static final int TEXT_TITLE = 0xFFF1F5F9;
    /** 正文 */
    public static final int TEXT_BODY = 0xFFE2E8F0;
    /** 副标题灰 */
    public static final int TEXT_SUB = 0xFFB8D4E3;
    /** 弱化灰 */
    public static final int TEXT_MUTED = 0xFFAAAAAA;
    /** 不可编辑/离线灰 */
    public static final int TEXT_DISABLED = 0xFF8F9AA3;
    /** 主要信息浅蓝白 */
    public static final int TEXT_HIGHLIGHT = 0xFFE6F2FF;

    // ===== 状态色（点 + 文字同色） =====
    /** 等待中绿 */
    public static final int ST_WAITING = 0xFF74E084;
    /** 进行中可加入青 */
    public static final int ST_RUNNING_JOINABLE = 0xFF66D9E8;
    /** 进行中不可加入红 */
    public static final int ST_RUNNING_CLOSED = 0xFFFF6B6B;
    /** 调试模式黄 */
    public static final int ST_DEBUG = 0xFFFFC857;
    /** 观战紫 */
    public static final int ST_SPECTATOR = 0xFFBBA7FF;
    /** 在线绿（与等待中同色系） */
    public static final int ST_ONLINE = 0xFF74E084;

    // ===== 队伍色 =====
    /** 队伍色：CT 蓝 */
    public static final int TEAM_CT = 0xFF4080FF;
    /** 队伍色：T 金 */
    public static final int TEAM_T = 0xFFFFC040;

    // ===== 强调色 =====
    /** 品牌蓝（选中条、主按钮） */
    public static final int ACCENT_PRIMARY = 0xFF4A9EFF;
    /** 品牌紫（OP 标识） */
    public static final int ACCENT_SECONDARY = 0xFF7B61FF;
    /** 危险红（踢人、离开） */
    public static final int ACCENT_DANGER = 0xFFFF6B6B;

    // ===== 列表行底色 =====
    /** 列表行常态 */
    public static final int ROW_NORMAL = 0x88212A33;
    /** 列表行悬停 */
    public static final int ROW_HOVER = 0x8834485A;
    /** 列表行选中 */
    public static final int ROW_SELECTED = 0xAA2D5F7D;
    public static final int ROW_DISABLED = 0x66323A42;
    public static final int ROW_WARNING = 0x665B3F24;
    public static final int ROW_DANGER = 0x665A2B31;
    public static final int CHIP_BG = 0xAA111827;
    public static final int CHIP_BORDER = 0x884A5568;
    public static final int PANEL_HIGHLIGHT = 0x332D5F7D;
    public static final int TEXT_DANGER = 0xFFFFA0A0;
    public static final int TEXT_WARNING = 0xFFFFD580;

    // ===== 滚动条 =====
    /** 滚动条槽背景 */
    public static final int SCROLL_TRACK = 0x33FFFFFF;
    /** 滚动条 thumb */
    public static final int SCROLL_THUMB = 0x88FFFFFF;
    /** 滚动条宽度 */
    public static final int SCROLL_BAR_WIDTH = 4;

    // ===== 按钮宽度规范 =====
    /** 小按钮：行内操作（apply/kick/invite/edit） */
    public static final int BUTTON_SMALL_WIDTH = 70;
    /** 中按钮：主操作（join/leave/detail/refresh/debug） */
    public static final int BUTTON_MEDIUM_WIDTH = 90;
    /** 大按钮：返回、确认（back/done/accept） */
    public static final int BUTTON_LARGE_WIDTH = 110;
    /** 按钮高度统一 */
    public static final int BUTTON_HEIGHT = 20;
    /** 按钮间距统一 */
    public static final int BUTTON_GAP = 8;

    // ===== 布局规范 =====
    /** 列表项行高（设置/管理/邀请/商店统一） */
    public static final int ROW_HEIGHT_LIST = 24;
    /** 主选择界面卡片行高 */
    public static final int ROW_HEIGHT_CARD = 46;
    /** 列表面板宽度 */
    public static final int PANEL_WIDTH_LIST = 380;
    /** 详情双栏单侧宽度 */
    public static final int PANEL_WIDTH_DETAIL_HALF = 210;
    /** 列表顶部 y 坐标 */
    public static final int LIST_TOP = 72;
    /** 有顶部按钮行时的列表顶部 y */
    public static final int LIST_TOP_WITH_HEADER = 118;
    /** 列表底部留白 */
    public static final int LIST_BOTTOM_PADDING = 88;
    /** 行间距 */
    public static final int ROW_GAP = 4;
    /** 状态色条宽度（左侧） */
    public static final int STATUS_BAR_WIDTH = 4;

    /**
     * 将 ARGB 颜色按系数提亮（factor > 0）或压暗（factor < 0）。
     *
     * @param color  原始 ARGB 颜色
     * @param factor 调整系数，0.2f 表示提亮 20%
     */
    public static int lighten(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        int r = (color >>> 16) & 0xFF;
        int g = (color >>> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.min(255, (int) (r + (255 - r) * factor));
        g = Math.min(255, (int) (g + (255 - g) * factor));
        b = Math.min(255, (int) (b + (255 - b) * factor));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
