package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
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

/**
 * Simulates TACZ shooting locally while spectating another player.
 */
public final class SpectatorGunShootSimulator {

    private SpectatorGunShootSimulator() {}

    public static ShootResult shootAsSpectator(LocalPlayerDataHolder data, LocalPlayer self) {
        if (System.currentTimeMillis() - LocalPlayerDataHolder.clientClickButtonTimestamp < 50L) {
            return ShootResult.COOL_DOWN;
        }
        if (!data.isShootRecorded) {
            return ShootResult.COOL_DOWN;
        }
        LivingEntity spectated = SpectatorView.getSpectatedLiving(self);
        if (spectated == null) {
            return ShootResult.FORGE_EVENT_CANCEL;
        }
        ItemStack usedStack = SpectatorGunStacks.current(spectated);
        IGun iGun = IGun.getIGunOrNull(usedStack);
        if (iGun == null) {
            return ShootResult.NOT_GUN;
        }
        ResourceLocation gunId = iGun.getGunId(usedStack);
        Optional<ClientGunIndex> optIdx = TimelessAPI.getClientGunIndex(gunId);
        GunDisplayInstance display = TimelessAPI.getGunDisplay(usedStack).orElse(null);
        if (optIdx.isEmpty() || display == null) {
            return ShootResult.ID_NOT_EXIST;
        }
        GunData gunData = optIdx.get().getGunData();
        long cd = getVisualCooldown(spectated, iGun, usedStack, gunData, data);
        if (cd >= 50L) {
            return ShootResult.COOL_DOWN;
        }
        IGunOperator op = IGunOperator.fromLivingEntity(spectated);
        if (op.getSynReloadState().getStateType().isReloading()) {
            return ShootResult.IS_RELOADING;
        }
        if (op.getSynDrawCoolDown() != 0L) {
            return ShootResult.IS_DRAWING;
        }
        if (op.getSynIsBolting()) {
            return ShootResult.IS_BOLTING;
        }
        if (op.getSynMeleeCoolDown() != 0L) {
            return ShootResult.IS_MELEE;
        }
        if (op.getSynSprintTime() > 0.0f) {
            return ShootResult.IS_SPRINTING;
        }
        Bolt bolt = gunData.getBolt();
        boolean hasAmmoInBarrel = iGun.hasBulletInBarrel(usedStack) && bolt != Bolt.OPEN_BOLT;
        int ammoCount = iGun.getCurrentAmmoCount(usedStack) + (hasAmmoInBarrel ? 1 : 0);
        if (ammoCount < 1) {
            trigger(display, "shoot");
            SpectatorGunFireMirror.applySpectatorShootFx(spectated, 0.15f);
            return ShootResult.NO_AMMO;
        }
        if (bolt == Bolt.MANUAL_ACTION && !hasAmmoInBarrel) {
            trigger(display, "blot");
            return ShootResult.NEED_BOLT;
        }
        data.isShootRecorded = false;
        FireMode mode = iGun.getFireMode(usedStack);
        long period = mode == FireMode.BURST ? gunData.getBurstShootInterval() : 1L;
        int maxCount = Math.min(ammoCount, mode == FireMode.BURST ? gunData.getBurstData().getCount() : 1);
        AtomicInteger count = new AtomicInteger(0);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        Runnable task = () -> {
            if (!isStillSpectating(self, spectated)) {
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) {
                    f.cancel(false);
                }
                return;
            }
            if (count.get() == 0) {
                data.isShootRecorded = true;
                data.clientLastShootTimestamp = data.clientShootTimestamp;
                data.clientShootTimestamp = System.currentTimeMillis();
            }
            if (count.get() >= maxCount || self.isDeadOrDying()) {
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) {
                    f.cancel(false);
                }
                return;
            }
            Minecraft.getInstance().execute(() -> {
                if (!isStillSpectating(self, spectated)) {
                    return;
                }
                trigger(display, "shoot");
                SpectatorGunFireMirror.applySpectatorShootFx(spectated, 0.35f);
            });
            count.incrementAndGet();
        };
        ScheduledFuture<?> fut = LocalPlayerDataHolder.SCHEDULED_EXECUTOR_SERVICE
                .scheduleAtFixedRate(task, cd, period, TimeUnit.MILLISECONDS);
        futureRef.set(fut);
        return ShootResult.SUCCESS;
    }

    private static long getVisualCooldown(LivingEntity shooter, IGun iGun, ItemStack stack, GunData gunData, LocalPlayerDataHolder data) {
        FireMode mode = iGun.getFireMode(stack);
        long elapsed = System.currentTimeMillis() - data.clientShootTimestamp;
        long cd = mode == FireMode.BURST ? (long) (gunData.getBurstData().getMinInterval() * 1000.0) - elapsed : gunData.getShootInterval(shooter, mode, stack) - elapsed;
        return Math.max(cd, 0L);
    }

    private static boolean isStillSpectating(LocalPlayer self, LivingEntity spectated) {
        if (!SpectatorView.isSpectatingOther(self)) {
            return false;
        }
        Entity cam = Minecraft.getInstance().getCameraEntity();
        return cam == spectated;
    }

    private static void trigger(GunDisplayInstance display, String input) {
        LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
        if (asm != null) {
            asm.trigger(input);
        }
    }
}
