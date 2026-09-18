package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.common.client.camera.CameraOverlayEvent;

@Mod.EventBusSubscriber(modid = "elimination_addon", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ArenaCameraOverlay {
    @SubscribeEvent
    public static void render(CameraOverlayEvent event) {
        if (!ArenaCamera.isPlaying()) return;
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        event.graphics().drawCenteredString(mc.font,
                Component.literal("竞技场"), width / 2, 24, 0xFFFFFF);
    }
}
