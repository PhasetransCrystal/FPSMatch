package com.ptcrys.fpsmatch.compat.spectate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Resolves the current spectating target from the client camera.
 */
public final class SpectatorView {

    private SpectatorView() {}

    public static boolean isSpectatingOther(LocalPlayer local) {
        if (local == null || !local.isSpectator()) {
            return false;
        }
        Entity cam = Minecraft.getInstance().getCameraEntity();
        return cam instanceof Player && cam != local;
    }

    public static Player getSpectatedPlayer(LocalPlayer local) {
        if (!isSpectatingOther(local)) {
            return null;
        }
        Entity cam = Minecraft.getInstance().getCameraEntity();
        return cam instanceof Player ? (Player) cam : null;
    }

    public static LivingEntity getSpectatedLiving(LocalPlayer local) {
        if (!isSpectatingOther(local)) {
            return null;
        }
        Entity cam = Minecraft.getInstance().getCameraEntity();
        return cam instanceof LivingEntity ? (LivingEntity) cam : null;
    }

    public static boolean shouldRenderHands() {
        Minecraft mc = Minecraft.getInstance();
        return isSpectatingOther(mc.player);
    }
}
