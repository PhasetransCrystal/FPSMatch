package com.example.elimination;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = EliminationMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EliminationClient {
    private static ScoreS2CPacket latest;
    private static long receivedAt;

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ScoreS2CPacket.receiver = packet -> {
                latest = packet;
                receivedAt = System.nanoTime();
            };
            MinecraftForge.EVENT_BUS.addListener(EliminationClient::logout);
        });
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { latest = null; }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("elimination_score", (gui, graphics, partialTick, width, height) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (latest == null || minecraft.player == null || minecraft.options.hideGui
                    || System.nanoTime() - receivedAt > 2_000_000_000L) return;
            Component text = Component.translatable("hud.elimination.score", latest.mapName(),
                    latest.red(), latest.blue(),
                    Component.translatable("phase.elimination." + latest.phase()));
            graphics.drawCenteredString(minecraft.font, text, width / 2, 12, 0xFFFFFF);
        });
    }
}
