package com.ptcrys.fpsmatch.compat.gun;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * 枪械兼容管理器 —— 多 Provider 注册制。
 * <p>
 * 支持多个枪械模组同时加载（如 TACZ + 其他枪械模组），
 * 通过 {@link #findProvider(ItemStack)} 按 ItemStack 路由到正确的 Provider。
 * </p>
 *
 * <pre>{@code
 * // 注册（各模组兼容层在启动时调用）
 * GunCompatManager.register(new TACZGunProvider());
 *
 * // 判断是否为枪械（任意 Provider 识别即可）
 * if (GunCompatManager.isGun(stack)) { ... }
 *
 * // 获取特定 Provider 进行后续操作
 * IGunProvider provider = GunCompatManager.findProvider(stack);
 * provider.setDummyAmmo(stack, amount);
 * }</pre>
 */
public class GunCompatManager {

    private static final IGunProvider DEFAULT = NoGunProvider.INSTANCE;
    /** 按注册顺序保留所有 Provider */
    private static final Map<String, IGunProvider> providers = new LinkedHashMap<>();

    /**
     * 注册枪械提供者。如果提供者已加载（isAvailable 返回 true），则加入注册表。
     */
    public static void register(IGunProvider provider) {
        if (provider.isAvailable()) {
            providers.put(provider.getModId(), provider);
        }
    }

    // ========== 查询 API ==========

    /**
     * 找到能识别该 ItemStack 的 Provider。
     * 按注册顺序遍历，返回第一个匹配的；无匹配返回 NoGunProvider。
     */
    public static IGunProvider findProvider(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return DEFAULT;
        for (IGunProvider p : providers.values()) {
            if (p.isGun(stack)) return p;
        }
        return DEFAULT;
    }

    /**
     * 物品是否为枪械（任意已注册 Provider 识别即可）。
     */
    public static boolean isGun(ItemStack stack) {
        return findProvider(stack).isAvailable();
    }

    /**
     * 按 gunId 查询枪械数据，遍历所有 Provider。
     */
    public static Optional<GunDataDTO> getGunData(ResourceLocation gunId) {
        for (IGunProvider p : providers.values()) {
            Optional<GunDataDTO> data = p.getGunData(gunId);
            if (data.isPresent()) return data;
        }
        return Optional.empty();
    }

    // ========== 管理 API ==========

    /** 获取所有已注册的 Provider。 */
    public static Collection<IGunProvider> getProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    /** 按 modId 获取指定 Provider。 */
    public static Optional<IGunProvider> getProvider(String modId) {
        return Optional.ofNullable(providers.get(modId));
    }

    /** 是否有任何枪械模组已加载。 */
    public static boolean isGunModLoaded() {
        return !providers.isEmpty();
    }

    /** 检查是否在游戏中（非 GUI 界面），用于快捷键判断。任一 Provider 返回 false 则整体为 false。 */
    public static boolean isInGame() {
        for (IGunProvider p : providers.values()) {
            if (!p.isInGame()) return false;
        }
        return true;
    }
}
