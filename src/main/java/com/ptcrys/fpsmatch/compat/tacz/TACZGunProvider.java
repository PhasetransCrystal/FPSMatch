package com.ptcrys.fpsmatch.compat.tacz;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import com.ptcrys.fpsmatch.compat.gun.*;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.resource.index.CommonGunIndex;

import java.util.Optional;

/**
 * TACZ（Timeless and Classics Zero）的 IGunProvider 实现。
 * 仅在 TACZ 模组加载时注册。
 */
public class TACZGunProvider implements IGunProvider {

    private static final String MOD_ID = "tacz";

    @Override
    public String getModId() {
        return MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        return net.minecraftforge.fml.ModList.get().isLoaded(MOD_ID);
    }

    // ========== 枪械识别 ==========

    @Override
    public boolean isGun(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof IGun;
    }

    @Override
    public ResourceLocation getGunId(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        return iGun != null ? iGun.getGunId(stack) : ResourceLocation.tryBuild("empty", "empty");
    }

    @Override
    public GunTabTypeEnum getGunTabType(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) return GunTabTypeEnum.RIFLE;
        return getGunTypeByGunId(iGun.getGunId(stack)).orElse(GunTabTypeEnum.RIFLE);
    }

    private Optional<GunTabTypeEnum> getGunTypeByGunId(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId)
                .map(CommonGunIndex::getType)
                .map(type -> {
                    try {
                        return GunTabTypeEnum.valueOf(type.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        return GunTabTypeEnum.RIFLE;
                    }
                });
    }

    // ========== 弹药操作 ==========

    @Override
    public int getDummyAmmo(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        return iGun != null ? iGun.getDummyAmmoAmount(stack) : 0;
    }

    @Override
    public void setDummyAmmo(ItemStack stack, int amount) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.setDummyAmmoAmount(stack, amount);
        }
    }

    @Override
    public int getMaxDummyAmmo(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        return iGun != null ? iGun.getMaxDummyAmmoAmount(stack) : 0;
    }

    @Override
    public void setMaxDummyAmmo(ItemStack stack, int amount) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.setMaxDummyAmmoAmount(stack, amount);
        }
    }

    @Override
    public int getCurrentAmmo(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        return iGun != null ? iGun.getCurrentAmmoCount(stack) : 0;
    }

    @Override
    public void setCurrentAmmo(ItemStack stack, int count) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.setCurrentAmmoCount(stack, count);
        }
    }

    @Override
    public void useDummyAmmo(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.useDummyAmmo(stack);
        }
    }

    // ========== 枪械数据 ==========

    @Override
    public Optional<GunDataDTO> getGunData(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) return Optional.empty();
        return getGunData(iGun.getGunId(stack));
    }

    @Override
    public Optional<GunDataDTO> getGunData(ResourceLocation gunId) {
        return TimelessAPI.getCommonGunIndex(gunId).map(index -> {
            int ammoAmount = index.getGunData().getAmmoAmount();
            String type = index.getType();
            GunTabTypeEnum tabType;
            try {
                tabType = GunTabTypeEnum.valueOf(type.toUpperCase());
            } catch (IllegalArgumentException e) {
                tabType = GunTabTypeEnum.RIFLE;
            }
            return new GunDataDTO(gunId, ammoAmount, 0, tabType, type);
        });
    }

    // ========== 渲染（客户端） ==========

    @Override
    public ResourceLocation getGunHUDTexture(ItemStack stack) {
        if (!isGun(stack)) return null;
        // 客户端专用逻辑通过 DistExecutor 分发，避免在专用服务器上加载 client-only 的 TACZ 渲染类
        // Client-only texture lookup; avoid DistExecutor safe referent validation here.
        if (FMLEnvironment.dist != Dist.CLIENT) return null;
        return com.ptcrys.fpsmatch.compat.tacz.client.util.GunSpecUtils.getGunHUDTexture(stack);
    }

    // ========== 枪械 ID 设置 ==========

    @Override
    public void setGunId(ItemStack stack, ResourceLocation gunId) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.setGunId(stack, gunId);
        }
    }

    @Override
    public void setGunDisplayId(ItemStack stack, ResourceLocation gunId) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun != null) {
            iGun.setGunDisplayId(stack, gunId);
        }
    }
}
