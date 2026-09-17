package com.ptcrys.fpsmatch.compat.tacz.client.shoot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.compat.tacz.client.animation.GunAnimationController;
import com.ptcrys.fpsmatch.compat.tacz.client.test.TaczSpecScreenShake;
import com.ptcrys.fpsmatch.compat.tacz.client.util.GunSpecUtils;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;

import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class SpecLocalShoot {

    private SpecLocalShoot() {}

    public static ShootResult shootAsSpectator(LocalPlayerDataHolder data, LocalPlayer self) {
        // 检查冷却
        if (System.currentTimeMillis() - LocalPlayerDataHolder.clientClickButtonTimestamp < 50) {
            return ShootResult.COOL_DOWN;
        }
        if (!data.isShootRecorded) return ShootResult.COOL_DOWN;

        // 获取目标实体
        LivingEntity spectated = getSpectatedEntity(self);
        if (spectated == null) return ShootResult.FORGE_EVENT_CANCEL;

        // 检查枪械状态
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(spectated);
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) return ShootResult.NOT_GUN;

        // 检查枪械数据
        ResourceLocation gunId = iGun.getGunId(stack);
        Optional<ClientGunIndex> gunIndex = TimelessAPI.getClientGunIndex(gunId);
        GunDisplayInstance display = TimelessAPI.getGunDisplay(stack).orElse(null);
        if (gunIndex.isEmpty() || display == null) return ShootResult.ID_NOT_EXIST;
        GunData gunData = gunIndex.get().getGunData();

        // 检查冷却时间
        long cd = getCoolDownForVisual(spectated, iGun, stack, gunData, data);
        if (cd >= 50) return ShootResult.COOL_DOWN;

        // 检查操作状态
        IGunOperator op = IGunOperator.fromLivingEntity(spectated);
        if (op.getSynReloadState().getStateType().isReloading()) return ShootResult.IS_RELOADING;
        if (op.getSynDrawCoolDown() != 0) return ShootResult.IS_DRAWING;
        if (op.getSynIsBolting()) return ShootResult.IS_BOLTING;
        if (op.getSynMeleeCoolDown() != 0) return ShootResult.IS_MELEE;
        if (op.getSynSprintTime() > 0) return ShootResult.IS_SPRINTING;

        // 检查弹药
        Bolt bolt = gunData.getBolt();
        boolean hasAmmoInBarrel = iGun.hasBulletInBarrel(stack) && bolt != Bolt.OPEN_BOLT;
        int ammoCount = iGun.getCurrentAmmoCount(stack) + (hasAmmoInBarrel ? 1 : 0);
        if (ammoCount < 1) {
            GunAnimationController.handleShoot(spectated);
            TaczSpecScreenShake.kick(0.15f);
            return ShootResult.NO_AMMO;
        }
        if (bolt == Bolt.MANUAL_ACTION && !hasAmmoInBarrel) {
            GunSpecUtils.safeTrigger(display.getAnimationStateMachine(), GunSpecUtils.TRIGGER_BOLT);
            return ShootResult.NEED_BOLT;
        }

        // 处理连射/点射逻辑
        data.isShootRecorded = false;
        FireMode mode = iGun.getFireMode(stack);
        long period = (mode == FireMode.BURST) ? gunData.getBurstShootInterval() : 1L;
        int maxCount = Math.min(ammoCount, (mode == FireMode.BURST) ? gunData.getBurstData().getCount() : 1);

        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        Runnable task = () -> {
            if (!GunSpecUtils.isStillSpectating(self, spectated)) {
                cancelTask(futureRef);
                return;
            }

            if (count.get() == 0) {
                data.isShootRecorded = true;
                data.clientLastShootTimestamp = data.clientShootTimestamp;
                data.clientShootTimestamp = System.currentTimeMillis();
            }
            if (count.get() >= maxCount || self.isDeadOrDying()) {
                cancelTask(futureRef);
                return;
            }

            Minecraft.getInstance().submitAsync(() -> {
                if (GunSpecUtils.isStillSpectating(self, spectated)) {
                    GunAnimationController.handleShoot(spectated);
                    TaczSpecScreenShake.kick(0.35f);
                }
            });
            count.incrementAndGet();
        };

        ScheduledFuture<?> future = LocalPlayerDataHolder.SCHEDULED_EXECUTOR_SERVICE
                .scheduleAtFixedRate(task, cd, period, TimeUnit.MILLISECONDS);
        futureRef.set(future);

        return ShootResult.SUCCESS;
    }

    private static LivingEntity getSpectatedEntity(LocalPlayer self) {
        if (!self.isSpectator()) return null;
        var cam = Minecraft.getInstance().getCameraEntity();
        return (cam instanceof LivingEntity le && le != self) ? le : null;
    }

    private static long getCoolDownForVisual(LivingEntity shooter, IGun iGun, ItemStack stack,
                                             GunData gunData, LocalPlayerDataHolder data) {
        FireMode mode = iGun.getFireMode(stack);
        long elapsed = System.currentTimeMillis() - data.clientShootTimestamp;
        return Math.max((mode == FireMode.BURST) ? (long) (gunData.getBurstData().getMinInterval() * 1000f) - elapsed : gunData.getShootInterval(shooter, mode, stack) - elapsed, 0);
    }

    private static void cancelTask(AtomicReference<ScheduledFuture<?>> futureRef) {
        ScheduledFuture<?> future = futureRef.get();
        if (future != null) future.cancel(false);
    }
}
