package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.Event;

/**
 * FPSMatch 枪械射击事件（替代 TACZ GunShootEvent）。
 * 由 TACZ 兼容层（TACZGunEventBridge）桥接触发。
 */
public class FPSMGunShootEvent extends Event {

    private final LivingEntity shooter;

    public FPSMGunShootEvent(LivingEntity shooter) {
        this.shooter = shooter;
    }

    public LivingEntity getShooter() {
        return shooter;
    }
}
