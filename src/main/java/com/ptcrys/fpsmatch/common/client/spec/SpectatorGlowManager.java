package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.data.FPSMClientGlobalData;

@OnlyIn(Dist.CLIENT)
public class SpectatorGlowManager {

    public static boolean shouldGlow(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        boolean isSpecTeam = PlayerOutlineRenderer.getCurrentMatchClientTeam(data)
                .map(team -> PlayerOutlineRenderer.isCurrentMatchSpectatorTeam(data, team))
                .orElse(false);

        if (mc.player == null || !mc.player.isSpectator()) return false;

        return entity instanceof Player target && isSpecTeam && PlayerOutlineRenderer.isSameCurrentMatchClientTeam(data, mc.player, target).orElse(false);
    }

    public static void clearFakeGlowFromAll() {}
}
