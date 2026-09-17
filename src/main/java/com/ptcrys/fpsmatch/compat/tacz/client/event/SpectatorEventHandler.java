package com.ptcrys.fpsmatch.compat.tacz.client.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.ptcrys.fpsmatch.compat.tacz.client.animation.GunAnimationController;
import com.ptcrys.fpsmatch.compat.tacz.client.fakeitem.ClientFakeItemManager;
import com.ptcrys.fpsmatch.compat.tacz.client.test.TaczSpecScreenShake;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;

/**
 * TACZ 旁观者事件处理。
 * 由 {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} 在确认 TACZ 加载后手动注册。
 */
public class SpectatorEventHandler {

    // 处理假物品tick更新
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (shouldSkipSpecHandlers()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        // 旁观者模式处理
        if (player.isSpectator()) {
            Entity cam = Minecraft.getInstance().getCameraEntity();
            if (cam instanceof Player target && target != player) {
                ClientFakeItemManager.equipOrUpdateForSpectator(player, target.getMainHandItem());
                ClientFakeItemManager.tickUpdate(player);
                return;
            }
        }

        // 非旁观模式还原假物品
        ClientFakeItemManager.revertFakeItem(player);
    }

    // 处理射击事件
    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (!event.getLogicalSide().isClient()) return;
        if (shouldSkipSpecHandlers()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isSpectator()) return;

        Entity cam = Minecraft.getInstance().getCameraEntity();
        if (cam instanceof LivingEntity target && event.getShooter() == target) {
            GunAnimationController.handleShoot(target);
        }
    }

    // 处理换弹事件
    @SubscribeEvent
    public static void onGunReload(GunReloadEvent event) {
        if (!event.getLogicalSide().isClient()) return;
        if (shouldSkipSpecHandlers()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || !player.isSpectator()) return;

        Entity cam = Minecraft.getInstance().getCameraEntity();
        if (cam instanceof LivingEntity target && event.getEntity() == target) {
            GunAnimationController.handleReload(target, true);
        }
    }

    // 射击中断检视动画
    @SubscribeEvent
    public static void onFireInterruptInspect(GunFireEvent event) {
        if (!event.getLogicalSide().isClient()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getShooter() != player) return;
        GunAnimationController.cancelInspect(player);
    }

    // 换弹中断检视动画
    @SubscribeEvent
    public static void onReloadInterruptInspect(GunReloadEvent event) {
        if (!event.getLogicalSide().isClient()) return;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || event.getEntity() != player) return;
        GunAnimationController.cancelInspect(player);
    }

    // 处理震屏逻辑
    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (shouldSkipSpecHandlers()) return;
        TaczSpecScreenShake.handleCameraAngles(event);
    }

    private static boolean shouldSkipSpecHandlers() {
        LocalPlayer player = Minecraft.getInstance().player;
        return SpectatorView.isSpectatingOther(player);
    }
}
