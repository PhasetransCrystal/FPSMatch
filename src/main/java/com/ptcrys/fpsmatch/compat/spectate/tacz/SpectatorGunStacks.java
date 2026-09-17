package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Resolves the item stack used for gun animations when spectating.
 */
public final class SpectatorGunStacks {

    private SpectatorGunStacks() {}

    public static ItemStack current(LivingEntity spectated) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack local = mc.player.getMainHandItem();
            if (!local.isEmpty()) {
                return local;
            }
        }
        return spectated.getMainHandItem();
    }
}
