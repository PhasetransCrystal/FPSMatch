package com.ptcrys.fpsmatch.compat.tacz.client.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

public final class SpecRenderStacks {

    private SpecRenderStacks() {}

    public static ItemStack current(LivingEntity spectated) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack local = mc.player.getMainHandItem();
            if (!local.isEmpty()) return local;
        }
        return spectated.getMainHandItem();
    }
}
