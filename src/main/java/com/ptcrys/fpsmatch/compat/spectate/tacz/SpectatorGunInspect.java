package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.renderer.item.AnimateGeoItemRenderer;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.sound.SoundPlayManager;

import java.lang.reflect.Method;

/**
 * Plays and cancels TACZ inspect animations for the local player or spectated view.
 * Registered by {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class SpectatorGunInspect {

    private SpectatorGunInspect() {}

    public static void playInspectAnimationFor(LivingEntity entity) {
        if (!(entity instanceof Player player)) {
            return;
        }
        ItemStack mainhand = player.getMainHandItem();
        if (mainhand.getItem() instanceof IGun) {
            TimelessAPI.getGunDisplay(mainhand).ifPresent(display -> {
                cancelReload(display);
                LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
                if (asm != null) {
                    safeTrigger(asm, "cancel_inspect");
                    safeTrigger(asm, "stop_inspect");
                    asm.trigger("inspect");
                }
                SoundPlayManager.stopPlayGunSound();
                SoundPlayManager.playInspectSound(player, display, false);
            });
            return;
        }
        BlockEntityWithoutLevelRenderer renderer = IClientItemExtensions.of(mainhand).getCustomRenderer();
        if (renderer instanceof AnimateGeoItemRenderer animRenderer) {
            animRenderer.triggerAnimation(mainhand, "inspect");
        }
    }

    public static void cancelInspectFor(Player player) {
        ItemStack mainhand = player.getMainHandItem();
        if (!(mainhand.getItem() instanceof IGun)) {
            return;
        }
        TimelessAPI.getGunDisplay(mainhand).ifPresent(display -> {
            LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm != null) {
                boolean any = false;
                any |= safeTrigger(asm, "cancel_inspect");
                if (!(any |= safeTrigger(asm, "stop_inspect"))) {
                    try {
                        asm.exit();
                        asm.initialize();
                    } catch (Throwable ignored) {}
                }
            }
            SoundPlayManager.stopPlayGunSound();
        });
    }

    @SubscribeEvent
    public static void onLocalFireInterruptInspect(GunFireEvent e) {
        if (!e.getLogicalSide().isClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (e.getShooter() != mc.player) {
            return;
        }
        cancelInspectFor(mc.player);
    }

    @SubscribeEvent
    public static void onLocalReloadInterruptInspect(GunReloadEvent e) {
        if (!e.getLogicalSide().isClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (e.getEntity() != mc.player) {
            return;
        }
        cancelInspectFor(mc.player);
    }

    private static void cancelReload(GunDisplayInstance display) {
        LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
        if (asm == null) {
            return;
        }
        safeTrigger(asm, "cancel_reload");
        safeTrigger(asm, "reload_end");
        safeTrigger(asm, "stop_reload");
    }

    private static boolean safeTrigger(Object asm, String trigger) {
        try {
            Method m = asm.getClass().getMethod("trigger", String.class);
            m.invoke(asm, trigger);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
