package com.ptcrys.fpsmatch.compat.gun;

import net.minecraft.resources.ResourceLocation;

/**
 * 枪械数据 DTO，纯 POJO，不依赖 TACZ 或其他枪械模组。
 * 由 IGunProvider 实现负责填充。
 */
public class GunDataDTO {

    private final ResourceLocation gunId;
    private final int ammoAmount;       // 弹匣容量
    private final int maxDummyAmmo;     // 最大备弹
    private final GunTabTypeEnum gunTabType;
    private final String gunType;       // 原始 gun type 字符串

    public GunDataDTO(ResourceLocation gunId, int ammoAmount, int maxDummyAmmo, GunTabTypeEnum gunTabType, String gunType) {
        this.gunId = gunId;
        this.ammoAmount = ammoAmount;
        this.maxDummyAmmo = maxDummyAmmo;
        this.gunTabType = gunTabType;
        this.gunType = gunType;
    }

    public ResourceLocation getGunId() {
        return gunId;
    }

    public int getAmmoAmount() {
        return ammoAmount;
    }

    public int getMaxDummyAmmo() {
        return maxDummyAmmo;
    }

    public GunTabTypeEnum getGunTabType() {
        return gunTabType;
    }

    public String getGunType() {
        return gunType;
    }
}
