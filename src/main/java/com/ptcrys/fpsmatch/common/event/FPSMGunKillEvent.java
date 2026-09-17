package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;

/**
 * FPSMatch 枪械击杀事件（替代 TACZ EntityKillByGunEvent）。
 * 由 TACZ 兼容层（TACZGunEventBridge）桥接触发。
 */
public class FPSMGunKillEvent extends Event {

    private final LivingEntity attacker;
    private final LivingEntity killedEntity;
    private final boolean isHeadShot;
    @Nullable
    private final Entity bullet;
    private final ItemStack gunItemStack;

    public FPSMGunKillEvent(LivingEntity attacker, LivingEntity killedEntity, boolean isHeadShot,
                            @Nullable Entity bullet, ItemStack gunItemStack) {
        this.attacker = attacker;
        this.killedEntity = killedEntity;
        this.isHeadShot = isHeadShot;
        this.bullet = bullet;
        this.gunItemStack = gunItemStack;
    }

    public LivingEntity getAttacker() {
        return attacker;
    }

    public LivingEntity getKilledEntity() {
        return killedEntity;
    }

    public boolean isHeadShot() {
        return isHeadShot;
    }

    @Nullable
    public Entity getBullet() {
        return bullet;
    }

    public ItemStack getGunItemStack() {
        return gunItemStack;
    }
}
