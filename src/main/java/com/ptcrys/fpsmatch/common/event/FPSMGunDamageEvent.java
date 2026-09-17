package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;

/**
 * FPSMatch 枪械伤害事件（替代 TACZ EntityHurtByGunEvent）。
 * 由 TACZ 兼容层（TACZGunEventBridge）桥接触发。
 */
public class FPSMGunDamageEvent extends Event {

    private final LivingEntity attacker;
    private final LivingEntity hurtEntity;
    private float baseAmount;
    private final boolean isHeadShot;
    @Nullable
    private final Entity bullet;
    private float headshotMultiplier = 1.0F;

    public FPSMGunDamageEvent(LivingEntity attacker, LivingEntity hurtEntity, float baseAmount, boolean isHeadShot) {
        this(attacker, hurtEntity, baseAmount, isHeadShot, null);
    }

    public FPSMGunDamageEvent(LivingEntity attacker, LivingEntity hurtEntity, float baseAmount, boolean isHeadShot,
                              @Nullable Entity bullet) {
        this.attacker = attacker;
        this.hurtEntity = hurtEntity;
        this.baseAmount = baseAmount;
        this.isHeadShot = isHeadShot;
        this.bullet = bullet;
    }

    public LivingEntity getAttacker() {
        return attacker;
    }

    public LivingEntity getHurtEntity() {
        return hurtEntity;
    }

    public float getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(float baseAmount) {
        this.baseAmount = baseAmount;
    }

    public boolean isHeadShot() {
        return isHeadShot;
    }

    @Nullable
    public Entity getBullet() {
        return bullet;
    }

    public float getHeadshotMultiplier() {
        return headshotMultiplier;
    }

    public void setHeadshotMultiplier(float headshotMultiplier) {
        this.headshotMultiplier = headshotMultiplier;
    }
}
