package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.client.model.functional.MuzzleFlashRender;

import java.util.HashMap;
import java.util.Map;

/**
 * Mirrors gun firing animation and muzzle flash for the spectated player.
 * Registered by {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class SpectatorGunFireMirror {

    private static final Map<Integer, Integer> LAST_SYN_CD = new HashMap<>();

    private SpectatorGunFireMirror() {}

    @SubscribeEvent
    public static void onGunFire(GunFireEvent e) {
        if (!e.getLogicalSide().isClient()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!SpectatorView.isSpectatingOther(mc.player)) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (!(cam instanceof LivingEntity le)) {
            return;
        }
        if (e.getShooter() != le) {
            return;
        }
        mirrorShootOnce(le);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!SpectatorView.isSpectatingOther(mc.player)) {
            return;
        }
        Entity cam = mc.getCameraEntity();
        if (!(cam instanceof LivingEntity le)) {
            return;
        }
        IGunOperator op = IGunOperator.fromLivingEntity(le);
        int curr = (int) op.getSynShootCoolDown();
        int id = le.getId();
        int prev = LAST_SYN_CD.getOrDefault(id, 0);
        LAST_SYN_CD.put(id, curr);
        if (prev <= 0 && curr > 0) {
            mirrorShootOnce(le);
        }
    }

    private static void mirrorShootOnce(LivingEntity spectated) {
        Minecraft mc = Minecraft.getInstance();
        if (!SpectatorView.isSpectatingOther(mc.player)) {
            return;
        }
        ItemStack stack = SpectatorGunStacks.current(spectated);
        if (stack.isEmpty()) {
            return;
        }
        TimelessAPI.getGunDisplay(stack).ifPresent(display -> Minecraft.getInstance().execute(() -> {
            LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm != null) {
                asm.trigger("shoot");
            }
            MuzzleFlashRender.onShoot();
            applySpectatorShootFx(spectated, 0.25f);
        }));
    }

    public static void applySpectatorShootFx(LivingEntity spectated, float fallbackKick) {
        if (spectated == null) {
            return;
        }
        SpectatorShootSway.markShoot();
        boolean recoilApplied = SpectatorCameraRecoil.trigger(spectated);
        if (!recoilApplied && fallbackKick > 0.0f) {
            SpectatorGunRecoil.kick(fallbackKick);
        }
    }
}
