package com.ptcrys.fpsmatch.compat.gun;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * 默认空实现 —— 无枪械模组加载时使用。
 * 所有方法返回安全默认值，不会抛出异常。
 */
public enum NoGunProvider implements IGunProvider {

    INSTANCE;

    @Override
    public String getModId() {
        return "none";
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public boolean isGun(ItemStack stack) {
        return false;
    }

    @Override
    public ResourceLocation getGunId(ItemStack stack) {
        return ResourceLocation.tryBuild("empty", "empty");
    }

    @Override
    public GunTabTypeEnum getGunTabType(ItemStack stack) {
        return GunTabTypeEnum.RIFLE;
    }

    @Override
    public int getDummyAmmo(ItemStack stack) {
        return 0;
    }

    @Override
    public void setDummyAmmo(ItemStack stack, int amount) {}

    @Override
    public int getMaxDummyAmmo(ItemStack stack) {
        return 0;
    }

    @Override
    public void setMaxDummyAmmo(ItemStack stack, int amount) {}

    @Override
    public int getCurrentAmmo(ItemStack stack) {
        return 0;
    }

    @Override
    public void setCurrentAmmo(ItemStack stack, int count) {}

    @Override
    public void useDummyAmmo(ItemStack stack) {}

    @Override
    public Optional<GunDataDTO> getGunData(ItemStack stack) {
        return Optional.empty();
    }

    @Override
    public Optional<GunDataDTO> getGunData(ResourceLocation gunId) {
        return Optional.empty();
    }
}
