package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;

/**
 * Mirrors reload start and cancel animations while spectating.
 */
public final class SpectatorGunReloadMirror {

    private SpectatorGunReloadMirror() {}

    public static void start(LivingEntity spectated, boolean playSound) {
        ItemStack stack = SpectatorGunStacks.current(spectated);
        if (stack.isEmpty()) {
            return;
        }
        TimelessAPI.getGunDisplay(stack).ifPresent(display -> Minecraft.getInstance().execute(() -> {
            LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm == null) {
                return;
            }
            boolean noAmmo = false;
            IGun iGun = IGun.getIGunOrNull(stack);
            if (iGun != null) {
                ResourceLocation gunId = iGun.getGunId(stack);
                GunData gunData = TimelessAPI.getClientGunIndex(gunId).map(ClientGunIndex::getGunData).orElse(null);
                if (gunData != null) {
                    Bolt bolt = gunData.getBolt();
                    int ammo = iGun.getCurrentAmmoCount(stack);
                    boolean hasInBarrel = iGun.hasBulletInBarrel(stack);
                    noAmmo = bolt == Bolt.OPEN_BOLT ? ammo <= 0 : !hasInBarrel && ammo <= 0;
                }
            }
            SoundPlayManager.stopPlayGunSound();
            if (playSound) {
                SoundPlayManager.playReloadSound(Minecraft.getInstance().player, display, noAmmo);
            }
            asm.trigger("reload");
        }));
    }

    public static void cancel(LivingEntity spectated) {
        ItemStack stack = SpectatorGunStacks.current(spectated);
        if (stack.isEmpty()) {
            return;
        }
        TimelessAPI.getGunDisplay(stack).ifPresent(display -> Minecraft.getInstance().execute(() -> {
            LuaAnimationStateMachine<?> asm = display.getAnimationStateMachine();
            if (asm != null) {
                asm.trigger("cancel_reload");
                asm.trigger("reload_end");
                asm.trigger("stop_reload");
            }
            SoundPlayManager.stopPlayGunSound();
        }));
    }
}
