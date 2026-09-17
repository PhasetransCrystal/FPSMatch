package net.ptcrys.fpsmatch.common.client.screen.mapselect;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import com.mojang.blaze3d.systems.RenderSystem;

/**
 * 地图缩略图/预览图渲染器。
 * <p>
 * 渲染优先级：
 * <ol>
 * <li>若提供贴图路径且资源已注册 → 绘制贴图（等比拉伸填充）</li>
 * <li>否则 → 绘制柔和渐变色块 + 模式标识</li>
 * </ol>
 * <p>
 * 实际贴图保持清晰；没有贴图时显示模式标识，不伪造地图布局。
 */
public final class MapThumbnailRenderer {

    private MapThumbnailRenderer() {}

    /**
     * 8 组低饱和战术底色对（顶部偏钢蓝 -> 底部偏炭黑）。
     */
    private static final int[][] GRADIENT_PAIRS = {
            { 0xFF344553, 0xFF151D25 },
            { 0xFF3D4A55, 0xFF182027 },
            { 0xFF2F4A4D, 0xFF152327 },
            { 0xFF4A463A, 0xFF211E1A },
            { 0xFF4C3F32, 0xFF201A16 },
            { 0xFF49383D, 0xFF21191D },
            { 0xFF3C454D, 0xFF171D23 },
            { 0xFF3E4250, 0xFF191B24 },
    };

    /**
     * 渲染地图缩略图。
     *
     * @param graphics    GuiGraphics
     * @param x           左上 x
     * @param y           左上 y
     * @param width       宽
     * @param height      高
     * @param texturePath 贴图资源路径，空串表示无贴图
     * @param mapName     地图内部名（用于色块哈希）
     * @param gameType    游戏类型（用于色块哈希 + 模式标识）
     * @param displayName 显示名（色块底部叠加）
     * @param showLabel   是否在色块底部叠加地图名标签
     */
    public static void render(GuiGraphics graphics, int x, int y, int width, int height,
                              String texturePath, String mapName, String gameType,
                              String displayName, boolean showLabel) {
        if (width <= 0 || height <= 0) return;
        if (texturePath != null && !texturePath.isEmpty() && tryRenderTexture(graphics, x, y, width, height, texturePath)) {
            if (showLabel) {
                drawBottomLabel(graphics, x, y, width, height, displayName);
            }
            return;
        }
        // Tactical block fallback.
        renderGradientBlock(graphics, x, y, width, height, mapName, gameType, displayName, showLabel);
    }

    /**
     * 尝试渲染贴图。返回 false 表示资源未注册或加载失败。
     */
    private static boolean tryRenderTexture(GuiGraphics graphics, int x, int y, int width, int height, String texturePath) {
        try {
            ResourceLocation rl = ResourceLocation.parse(texturePath);
            // 检查资源是否已注册（避免日志报错刷屏）
            Minecraft mc = Minecraft.getInstance();
            if (mc.getResourceManager().getResource(rl).isEmpty()) {
                return false;
            }
            RenderSystem.enableBlend();
            graphics.blit(rl, x, y, 0, 0, width, height, width, height);
            RenderSystem.disableBlend();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Renders a compact tactical map block with route marks and mode label. */
    private static void renderGradientBlock(GuiGraphics graphics, int x, int y, int width, int height,
                                            String mapName, String gameType, String displayName, boolean showLabel) {
        int[] colors = getGradientColors(mapName, gameType);
        int topColor = colors[0];
        int bottomColor = colors[1];

        // Discrete bands keep the thumbnail cheap and stable on the first frame.
        int segments = 8;
        int segHeight = height / segments;
        int remainder = height % segments;
        int curY = y;
        for (int i = 0; i < segments; i++) {
            int h = segHeight + (i < remainder ? 1 : 0);
            float ratio = segments == 1 ? 0f : (float) i / (segments - 1);
            int color = lerpColor(topColor, bottomColor, ratio);
            graphics.fill(x, curY, x + width, curY + h, color);
            curY += h;
        }

        // ③ 模式标识（中央文字，半透明）
        String modeLabel = modeLabel(gameType);
        if (modeLabel != null && !modeLabel.isEmpty()) {
            Minecraft mc = Minecraft.getInstance();
            modeLabel = mc.font.plainSubstrByWidth(modeLabel, Math.max(0, width - 12));
            int labelWidth = mc.font.width(modeLabel);
            int labelX = x + (width - labelWidth) / 2;
            int labelY = y + (height - mc.font.lineHeight) / 2;
            graphics.fill(labelX - 5, labelY - 3, labelX + labelWidth + 5,
                    labelY + mc.font.lineHeight + 3, 0xB31A232C);
            graphics.fill(labelX - 5, labelY - 3, labelX - 3,
                    labelY + mc.font.lineHeight + 3, 0xFFE6A23C);
            graphics.drawString(mc.font, modeLabel, labelX, labelY, 0xFFE7EDF2, false);
        }

        // ④ 底部地图名标签
        if (showLabel) {
            drawBottomLabel(graphics, x, y, width, height, displayName);
        }
    }

    /**
     * Draw a compact match-client label strip.
     */
    private static void drawBottomLabel(GuiGraphics graphics, int x, int y, int width, int height, String displayName) {
        if (displayName == null || displayName.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        displayName = mc.font.plainSubstrByWidth(displayName, Math.max(0, width - 10));
        int labelHeight = Math.min(14, mc.font.lineHeight + 4);
        int labelY = y + height - labelHeight;
        graphics.fill(x, labelY, x + width, y + height, 0xE61A232C);
        graphics.fill(x, labelY, x + 3, y + height, 0xFFE6A23C);
        graphics.fill(Math.max(x + 4, x + width - 18), labelY, x + width, labelY + 1, 0xFF667583);
        // 居中文字
        int textWidth = mc.font.width(displayName);
        int textX = x + (width - textWidth) / 2;
        int textY = labelY + (labelHeight - mc.font.lineHeight) / 2;
        graphics.drawString(mc.font, displayName, textX, textY, 0xFFE7EDF2, false);
    }

    /**
     * 基于 mapName + gameType 哈希确定性选取色对索引。
     */
    public static int[] getGradientColors(String mapName, String gameType) {
        int hash = (mapName == null ? 0 : mapName.hashCode()) ^ (gameType == null ? 0 : gameType.hashCode());
        int index = Math.floorMod(hash, GRADIENT_PAIRS.length);
        return GRADIENT_PAIRS[index];
    }

    /**
     * 模式标识文字。无贴图时叠加在色块中央。
     */
    private static String modeLabel(String gameType) {
        if (gameType == null) return "";
        return switch (gameType) {
            case "cs" -> "CS";
            case "csdm" -> "DM";
            default -> gameType.length() >= 2 ? gameType.substring(0, 2).toUpperCase() : gameType.toUpperCase();
        };
    }

    /**
     * ARGB 颜色线性插值。
     */
    private static int lerpColor(int c1, int c2, float t) {
        int a1 = (c1 >> 24) & 0xFF;
        int r1 = (c1 >> 16) & 0xFF;
        int g1 = (c1 >> 8) & 0xFF;
        int b1 = c1 & 0xFF;
        int a2 = (c2 >> 24) & 0xFF;
        int r2 = (c2 >> 16) & 0xFF;
        int g2 = (c2 >> 8) & 0xFF;
        int b2 = c2 & 0xFF;
        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
