package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;

/**
 * Keeps the local held item mirrored to the spectated player's main hand.
 * Registered by {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class SpectatorGunItemMirrorTicker {

    private SpectatorGunItemMirrorTicker() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null) {
            return;
        }
        Player target = SpectatorView.getSpectatedPlayer(self);
        if (target != null) {
            ItemStack targetStack = target.getMainHandItem();
            SpectatorGunItemMirror.equip(self, targetStack);
            SpectatorGunItemMirror.tick(self);
            return;
        }
        SpectatorGunItemMirror.revert(self);
    }
}
