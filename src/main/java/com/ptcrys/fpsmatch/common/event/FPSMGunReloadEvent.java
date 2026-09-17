package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

/**
 * FPSMatch 枪械换弹事件（替代 TACZ GunReloadEvent）。
 * 由 TACZ 兼容层（TACZGunEventBridge）桥接触发。
 */
public class FPSMGunReloadEvent extends Event {

    private final LivingEntity entity;
    private final ItemStack gunItemStack;

    public FPSMGunReloadEvent(LivingEntity entity, ItemStack gunItemStack) {
        this.entity = entity;
        this.gunItemStack = gunItemStack;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public ItemStack getGunItemStack() {
        return gunItemStack;
    }
}
