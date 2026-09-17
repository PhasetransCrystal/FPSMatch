package com.ptcrys.fpsmatch.core.music;

import net.minecraft.resources.ResourceLocation;

/**
 * FPSMatch 通用 MVP 音乐管理器。
 * <p>
 * 承载默认 MVP 音乐的设置职责，供所有 FPS 对战玩法复用。
 * 子模组（如 BlockOffensive）可通过 {@link #setDefaultMvpMusic(ResourceLocation)} 自定义默认音乐，
 * 也可直接使用 FPSMatch 注册的 {@code fpsmatch:mvp.default} 声音事件。
 */
public final class MvpMusicManager {

    private static final ResourceLocation BUILTIN_DEFAULT = ResourceLocation.tryBuild("fpsmatch", "mvp.default");

    private static ResourceLocation defaultMvpMusic = BUILTIN_DEFAULT;

    private MvpMusicManager() {}

    /**
     * 获取当前默认 MVP 音乐的 ResourceLocation。
     *
     * @return 默认 MVP 音乐，永不为 null
     */
    public static ResourceLocation getDefaultMvpMusic() {
        return defaultMvpMusic;
    }

    /**
     * 设置默认 MVP 音乐。
     *
     * @param music 新的默认 MVP 音乐，为 null 时回退到内置默认值
     */
    public static void setDefaultMvpMusic(ResourceLocation music) {
        defaultMvpMusic = music != null ? music : BUILTIN_DEFAULT;
    }

    /**
     * 获取默认 MVP 音乐的字符串名称，用于 HUD 显示。
     *
     * @return 默认 MVP 音乐名称
     */
    public static String getDefaultMvpMusicName() {
        return defaultMvpMusic.toString();
    }

    /**
     * 重置为内置默认 MVP 音乐。
     */
    public static void resetToBuiltinDefault() {
        defaultMvpMusic = BUILTIN_DEFAULT;
    }
}
