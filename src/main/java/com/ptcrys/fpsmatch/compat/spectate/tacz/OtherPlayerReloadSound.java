package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.index.ClientGunIndex;
import com.tacz.guns.client.sound.SoundPlayManager;
import com.tacz.guns.config.common.GunConfig;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.sound.SoundManager;

/**
 * Plays TACZ reload sounds for other players on the client.
 * Registered by {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class OtherPlayerReloadSound {

    private OtherPlayerReloadSound() {}

    @SubscribeEvent
    public static void onGunReload(GunReloadEvent event) {
        if (!event.getLogicalSide().isClient()) {
            return;
        }
        LivingEntity shooter = event.getEntity();
        if (shooter == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer local = mc.player;
        if (local != null && shooter == local) {
            return;
        }
        if (local != null && shooter instanceof Player) {
            Player spectated = SpectatorView.getSpectatedPlayer(local);
            if (spectated != null && spectated == shooter) {
                return;
            }
        }
        ItemStack stack = event.getGunItemStack();
        if (stack == null || stack.isEmpty()) {
            return;
        }
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        GunData gunData = TimelessAPI.getClientGunIndex(gunId).map(ClientGunIndex::getGunData).orElse(null);
        if (gunData == null) {
            return;
        }
        Bolt boltType = gunData.getBolt();
        boolean noAmmo = boltType == Bolt.OPEN_BOLT ? iGun.getCurrentAmmoCount(stack) <= 0 : !iGun.hasBulletInBarrel(stack);
        TimelessAPI.getGunDisplay(stack).ifPresent(display -> playReloadSound(shooter, display, noAmmo));
    }

    private static void playReloadSound(LivingEntity shooter, GunDisplayInstance display, boolean noAmmo) {
        ResourceLocation soundId = display.getSounds(noAmmo ? SoundManager.RELOAD_EMPTY_SOUND : SoundManager.RELOAD_TACTICAL_SOUND);
        if (soundId == null) {
            return;
        }
        SoundPlayManager.playClientSound(
                shooter,
                soundId,
                1.0f,
                1.0f,
                GunConfig.DEFAULT_GUN_OTHER_SOUND_DISTANCE.get());
    }
}
