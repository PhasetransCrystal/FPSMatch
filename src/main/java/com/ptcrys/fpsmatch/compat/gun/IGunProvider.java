package com.ptcrys.fpsmatch.compat.gun;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 枪械提供者接口 —— 所有枪械模组兼容层的顶层抽象。
 * <p>
 * 实现类通过 {@link GunCompatManager#register} 注册，核心代码通过
 * {@link GunCompatManager#getProvider()} 获取当前活跃的提供者。
 * </p>
 */
public interface IGunProvider {

    /** 模组标识 ID */
    String getModId();

    /** 模组是否已加载 */
    boolean isAvailable();

    // ========== 枪械识别 ==========

    /** 物品栈是否为枪械 */
    boolean isGun(ItemStack stack);

    /** 获取枪械 ID */
    ResourceLocation getGunId(ItemStack stack);

    /** 获取枪械类别 */
    GunTabTypeEnum getGunTabType(ItemStack stack);

    /** 设置枪械 ID（NBT 写入） */
    default void setGunId(ItemStack stack, ResourceLocation gunId) {}

    /** 设置枪械显示 ID（NBT 写入） */
    default void setGunDisplayId(ItemStack stack, ResourceLocation gunId) {}

    // ========== 弹药操作 ==========

    /** 获取备弹量 */
    int getDummyAmmo(ItemStack stack);

    /** 设置备弹量 */
    void setDummyAmmo(ItemStack stack, int amount);

    /** 获取最大备弹量 */
    int getMaxDummyAmmo(ItemStack stack);

    /** 设置最大备弹量 */
    void setMaxDummyAmmo(ItemStack stack, int amount);

    /** 获取当前弹匣弹药 */
    int getCurrentAmmo(ItemStack stack);

    /** 设置当前弹匣弹药 */
    void setCurrentAmmo(ItemStack stack, int count);

    /** 启用虚拟弹药模式 */
    void useDummyAmmo(ItemStack stack);

    // ========== 枪械数据 ==========

    /** 获取枪械数据 */
    Optional<GunDataDTO> getGunData(ItemStack stack);

    /** 通过 gunId 获取枪械数据 */
    Optional<GunDataDTO> getGunData(ResourceLocation gunId);

    // ========== 渲染（客户端） ==========

    /** 获取枪械 HUD 纹理（客户端专用） */
    default ResourceLocation getGunHUDTexture(ItemStack stack) {
        return null;
    }

    // ========== 音效 ==========

    /** 获取拾取音效 */
    default SoundEvent getGunPickupSound(ItemStack stack) {
        return SoundEvents.ITEM_PICKUP;
    }

    /** 获取丢弃音效 */
    default SoundEvent getGunDropSound(ItemStack stack) {
        return SoundEvents.STONE_BUTTON_CLICK_ON;
    }

    // ========== 输入检查 ==========

    /** 检查玩家是否在游戏中（非 GUI 界面），用于快捷键判断 */
    default boolean isInGame() {
        return true;
    }
}
