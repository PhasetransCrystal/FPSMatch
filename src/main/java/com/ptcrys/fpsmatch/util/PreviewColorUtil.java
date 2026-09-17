package com.ptcrys.fpsmatch.util;

public final class PreviewColorUtil {

    private static final int[] PALETTE = {
            0xFFE85D5D,
            0xFF2FD3D9,
            0xFF4BB56C,
            0xFFF08A24,
            0xFF3A7BFF,
            0xFFF1C94B,
            0xFFE05DB1
    };

    private PreviewColorUtil() {}

    public static int getMapPreviewColor(String gameType) {
        return mix(getBaseColor(gameType), 0xFFFFFFFF, 0.35F);
    }

    public static int getPointPreviewColor(String gameType) {
        return mix(getBaseColor(gameType), 0xFFFFFFFF, 0.12F);
    }

    public static int getPointPreviewColor(String gameType, int variantIndex) {
        return mix(getIndexedBaseColor(gameType, variantIndex), 0xFFFFFFFF, 0.12F);
    }

    public static int getBaseColor(String key) {
        return PALETTE[Math.floorMod(key == null ? 0 : key.hashCode(), PALETTE.length)];
    }

    private static int getIndexedBaseColor(String key, int variantIndex) {
        int baseIndex = Math.floorMod(key == null ? 0 : key.hashCode(), PALETTE.length);
        return PALETTE[Math.floorMod(baseIndex + variantIndex, PALETTE.length)];
    }

    private static int mix(int source, int target, float ratio) {
        ratio = Math.max(0.0F, Math.min(1.0F, ratio));
        int sr = (source >> 16) & 0xFF;
        int sg = (source >> 8) & 0xFF;
        int sb = source & 0xFF;
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;

        int red = sr + Math.round((tr - sr) * ratio);
        int green = sg + Math.round((tg - sg) * ratio);
        int blue = sb + Math.round((tb - sb) * ratio);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }
}
