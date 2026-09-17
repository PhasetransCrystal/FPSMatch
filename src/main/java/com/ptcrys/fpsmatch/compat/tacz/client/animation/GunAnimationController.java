package com.ptcrys.fpsmatch.compat.tacz.client.animation;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.compat.tacz.client.test.TaczSpecScreenShake;
import com.ptcrys.fpsmatch.compat.tacz.client.util.GunSpecUtils;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.AnimationStateMachine;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;

import java.util.Optional;

public class GunAnimationController {

    /**
     * 处理换弹动画
     */
    public static void handleReload(LivingEntity entity, boolean playSound) {
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(entity);
        if (stack.isEmpty()) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            Minecraft.getInstance().submitAsync(() -> {
                AnimationStateMachine<?> asm = display.getAnimationStateMachine();
                if (asm == null) return;

                // 计算无弹药状态
                boolean noAmmo = calculateNoAmmo(stack, display);

                SoundPlayManager.stopPlayGunSound();
                if (playSound) {
                    SoundPlayManager.playReloadSound(Minecraft.getInstance().player, display, noAmmo);
                }
                GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_RELOAD);
            });
        });
    }

    /**
     * 取消换弹动画
     */
    public static void cancelReload(LivingEntity entity) {
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(entity);
        if (stack.isEmpty()) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            Minecraft.getInstance().submitAsync(() -> {
                AnimationStateMachine<?> asm = display.getAnimationStateMachine();
                if (asm == null) return;

                GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_CANCEL_RELOAD);
                GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_RELOAD_END);
                GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_STOP_RELOAD);
                SoundPlayManager.stopPlayGunSound();
            });
        });
    }

    /**
     * 处理射击动画
     */
    public static void handleShoot(LivingEntity entity) {
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(entity);
        if (stack.isEmpty()) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            Minecraft.getInstance().submitAsync(() -> {
                AnimationStateMachine<?> asm = display.getAnimationStateMachine();
                if (asm != null) {
                    GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_SHOOT);
                }
                MuzzleFlashRender.onShoot();
                TaczSpecScreenShake.kick(0.25f);
            });
        });
    }

    /**
     * 处理检视动画
     */
    public static void handleInspect(LivingEntity entity) {
        if (!(entity instanceof LivingEntity)) return;
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(entity);
        if (!(stack.getItem() instanceof IGun)) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            cancelReload(entity); // 先取消换弹
            AnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm == null) return;

            GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_CANCEL_INSPECT);
            GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_STOP_INSPECT);
            GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_INSPECT);

            SoundPlayManager.stopPlayGunSound();
            SoundPlayManager.playInspectSound(entity, display, false);
        });
    }

    /**
     * 取消检视动画
     */
    public static void cancelInspect(LivingEntity entity) {
        ItemStack stack = GunSpecUtils.getCurrentRenderStack(entity);
        if (!(stack.getItem() instanceof IGun)) return;

        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            AnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm == null) return;

            boolean anyTriggered = GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_CANCEL_INSPECT) || GunSpecUtils.safeTrigger(asm, GunSpecUtils.TRIGGER_STOP_INSPECT);

            if (!anyTriggered) {
                try {
                    asm.exit();
                    asm.initialize();
                } catch (Throwable ignored) {}
            }
            SoundPlayManager.stopPlayGunSound();
        });
    }

    // 计算无弹药状态
    private static boolean calculateNoAmmo(ItemStack stack, GunDisplayInstance display) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) return true;

        Optional<GunData> gunData = TimelessAPI.getClientGunIndex(iGun.getGunId(stack))
                .map(ClientGunIndex::getGunData);

        if (gunData.isEmpty()) return true;

        Bolt bolt = gunData.get().getBolt();
        int ammo = iGun.getCurrentAmmoCount(stack);
        boolean hasInBarrel = iGun.hasBulletInBarrel(stack);

        return (bolt == Bolt.OPEN_BOLT) ? (ammo <= 0) : (!hasInBarrel && ammo <= 0);
    }
}
