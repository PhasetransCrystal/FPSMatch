package com.ptcrys.fpsmatch.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.scores.Scoreboard;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.core.data.PlayerData;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;

import static com.ptcrys.fpsmatch.common.client.FPSMClient.PLAYER_COMPARATOR;

public class RenderUtil {

    public static int WHITE = 0xFFFFFFFF;

    // Player-list ordering and team projection are shared by several HUDs in the same frame.
    // Recompute them once per client tick instead of sorting and allocating for every overlay.
    private static Minecraft cachedMinecraft;
    private static net.minecraft.client.player.LocalPlayer cachedPlayer;
    private static long cachedPlayerListTick = Long.MIN_VALUE;
    private static List<PlayerInfo> cachedPlayerInfos = List.of();
    private static long cachedTeamListTick = Long.MIN_VALUE;
    private static Map<String, List<PlayerInfo>> cachedTeamPlayers = Map.of();
    private static List<PlayerInfo> cachedProjectedInput = List.of();

    public static Vector3f color(int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        return new Vector3f(r, g, b);
    }

    public static int color(Vector3f color) {
        int r = (int) (color.x * 255);
        int g = (int) (color.y * 255);
        int b = (int) (color.z * 255);
        return color(r, g, b);
    }

    public static int color(int r, int g, int b) {
        return (((0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | ((b & 0xFF)));
    }

    public static int color(int r, int g, int b, int a) {
        return (((a) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | ((b & 0xFF)));
    }

    public static Map<String, List<PlayerInfo>> getTeamsPlayerInfo() {
        Minecraft mc = Minecraft.getInstance();
        long tick = clientTick(mc);
        if (mc.player != null) {
            if (cachedMinecraft == mc && cachedPlayer == mc.player && cachedTeamListTick == tick) {
                return cachedTeamPlayers;
            }
            Map<String, List<PlayerInfo>> teams = getTeamsPlayerInfo(getPlayerInfos());
            cachedMinecraft = mc;
            cachedPlayer = mc.player;
            cachedTeamListTick = tick;
            cachedTeamPlayers = teams;
            return teams;
        }
        return new HashMap<>();
    }

    public static List<PlayerInfo> getPlayerInfos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            long tick = clientTick(mc);
            if (cachedMinecraft == mc && cachedPlayer == mc.player && cachedPlayerListTick == tick) {
                return cachedPlayerInfos;
            }
            cachedMinecraft = mc;
            cachedPlayer = mc.player;
            cachedPlayerListTick = tick;
            cachedPlayerInfos = mc.player.connection.getListedOnlinePlayers().stream()
                    .sorted(PLAYER_COMPARATOR)
                    .limit(80L)
                    .toList();
            // The team projection depends on this exact list and must be rebuilt with it.
            cachedTeamListTick = Long.MIN_VALUE;
            return cachedPlayerInfos;
        }
        return new ArrayList<>();
    }

    public static void invalidatePlayerInfoCache() {
        cachedMinecraft = null;
        cachedPlayer = null;
        cachedPlayerListTick = Long.MIN_VALUE;
        cachedTeamListTick = Long.MIN_VALUE;
        cachedPlayerInfos = List.of();
        cachedProjectedInput = List.of();
        cachedTeamPlayers = Map.of();
    }

    private static long clientTick(Minecraft mc) {
        return mc.level == null ? Long.MIN_VALUE : mc.level.getGameTime();
    }

    public static Optional<PlayerData> getPlayerData(PlayerInfo player) {
        return FPSMClient.getGlobalData().getPlayerData(player.getProfile().getId());
    }

    public static Scoreboard getScoreboard() {
        return Minecraft.getInstance().level.getScoreboard();
    }

    public static Map<String, List<PlayerInfo>> getTeamsPlayerInfo(List<PlayerInfo> playerInfoList) {
        if (playerInfoList == cachedProjectedInput) {
            return cachedTeamPlayers;
        }
        Map<String, List<PlayerInfo>> teamPlayers = new HashMap<>();

        for (PlayerInfo info : playerInfoList) {
            UUID uuid = info.getProfile().getId();
            FPSMClient.getGlobalData().getTeamByUUID(uuid).ifPresent(team -> {
                team.getPlayerData(uuid).ifPresent(tabData -> {
                    teamPlayers.computeIfAbsent(team.name, k -> new ArrayList<>()).add(info);
                });
            });
        }
        if (playerInfoList == cachedPlayerInfos) {
            cachedProjectedInput = playerInfoList;
            cachedTeamPlayers = teamPlayers;
        }
        return teamPlayers;
    }

    public static void renderReverseTexture(GuiGraphics guiGraphics, ResourceLocation icon,
                                            int x, int y, int width, int height) {
        renderTexture(guiGraphics, icon, x, y, width, height, true, false);
    }

    public static void renderTexture(GuiGraphics guiGraphics, ResourceLocation texture,
                                     int x, int y, int width, int height,
                                     boolean flipHorizontal, boolean flipVertical) {
        if (!flipHorizontal && !flipVertical) {
            guiGraphics.blit(texture, x, y, 0, 0, width, height, width, height);
            return;
        }

        // Calculate UV coordinates
        float minU = 0;
        float maxU = 1;
        float minV = 0;
        float maxV = 1;

        if (flipHorizontal) {
            float temp = minU;
            minU = maxU;
            maxU = temp;
        }

        if (flipVertical) {
            float temp = minV;
            minV = maxV;
            maxV = temp;
        }

        PoseStack poseStack = guiGraphics.pose();
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);

        Matrix4f matrix = poseStack.last().pose();
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();

        // 构建顶点
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buffer.vertex(matrix, x, y, 0).uv(minU, minV).endVertex();
        buffer.vertex(matrix, x, y + height, 0).uv(minU, maxV).endVertex();
        buffer.vertex(matrix, x + width, y + height, 0).uv(maxU, maxV).endVertex();
        buffer.vertex(matrix, x + width, y, 0).uv(maxU, minV).endVertex();

        BufferUploader.drawWithShader(buffer.end());
    }

    public static ResourceLocation fetchSkin(UUID id, String name) {
        return Minecraft.getInstance().getSkinManager()
                .getInsecureSkinLocation(new GameProfile(id, name));
    }

    /**
     * 指数平滑算法（用于动画过渡）
     *
     * @param cur         当前值
     * @param target      目标值
     * @param dt          时间差（秒）
     * @param halfLifeSec 半衰期（过渡速度）
     * @return 平滑后的值
     */
    public static float expSmooth(float cur, float target, float dt, float halfLifeSec) {
        if (halfLifeSec <= 0f) return target;
        double f = Math.pow(0.5d, dt / Math.max(1e-6, halfLifeSec));
        return (float) (target + (cur - target) * f);
    }

    /**
     * 调整颜色的透明度
     *
     * @param argb 原始ARGB颜色
     * @param mul  透明度乘数（0-1）
     * @return 调整后的颜色
     */
    public static int mulAlpha(int argb, float mul) {
        mul = Mth.clamp(mul, 0, 1f);
        int a = (int) (((argb >>> 24) & 0xFF) * mul);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 颜色插值（从c1过渡到c2）
     *
     * @param c1 起始颜色
     * @param c2 目标颜色
     * @param t  插值因子（0-1）
     * @return 插值后的颜色
     */
    public static int lerpColor(int c1, int c2, float t) {
        t = Mth.clamp(t, 0, 1f);
        int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int a = a1 + Math.round((a2 - a1) * t);
        int r = r1 + Math.round((r2 - r1) * t);
        int g = g1 + Math.round((g2 - g1) * t);
        int b = b1 + Math.round((b2 - b1) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 居中缩放绘制字符串
     *
     * @param g     绘图工具
     * @param mc    Minecraft实例
     * @param text  要绘制的文本
     * @param x     绘制区域X坐标
     * @param y     绘制区域Y坐标
     * @param w     绘制区域宽度
     * @param h     绘制区域高度
     * @param color 文本颜色
     */
    public static void drawCenteredScaledString(GuiGraphics g, Minecraft mc, String text,
                                                int x, int y, int w, int h, int color) {
        int tw = mc.font.width(text);
        int th = mc.font.lineHeight;
        float maxW = Math.max(1f, w - 6f);
        float sx = Math.min(1f, maxW / Math.max(1f, tw));
        float sy = Math.min(1f, (h - 2f) / th);
        float scale = Math.min(sx, sy);

        int drawW = Math.round(tw * scale);
        int drawH = Math.round(th * scale);
        int dx = x + (w - drawW) / 2;
        int dy = y + (h - drawH) / 2;

        g.pose().pushPose();
        g.pose().translate(dx, dy, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(mc.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    public static int lighten(int rgb, float p) {
        return mixRGB(rgb, 0xFFFFFF, p);
    }

    public static int darken(int rgb, float p) {
        return mixRGB(rgb, 0x000000, p);
    }

    public static float snapToPixel(float v, float scale) {
        return Math.round(v * scale) / scale;
    }

    public static int lerpARGB(int a, int b, float t) {
        t = Mth.clamp(t, 0f, 1f);
        int aA = (a >>> 24) & 0xFF, aR = (a >>> 16) & 0xFF, aG = (a >>> 8) & 0xFF, aB = a & 0xFF;
        int bA = (b >>> 24) & 0xFF, bR = (b >>> 16) & 0xFF, bG = (b >>> 8) & 0xFF, bB = b & 0xFF;
        int rA = aA + Math.round((bA - aA) * t);
        int rR = aR + Math.round((bR - aR) * t);
        int rG = aG + Math.round((bG - aG) * t);
        int rB = aB + Math.round((bB - aB) * t);
        return (rA << 24) | (rR << 16) | (rG << 8) | rB;
    }

    public static int mixRGB(int c1, int c2, float p) {
        p = Mth.clamp(p, 0f, 1f);
        int r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
        int r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
        int r = (int) (r1 + (r2 - r1) * p), g = (int) (g1 + (g2 - g1) * p), b = (int) (b1 + (b2 - b1) * p);
        return (r << 16) | (g << 8) | b;
    }

    public static float easeOutBack(float t) {
        t = Mth.clamp(t, 0f, 1f);
        float c1 = 1.70158f, c3 = c1 + 1f;
        float u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }
}
