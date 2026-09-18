package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.ptcrys.fpsmatch.common.client.FPSMGameHudManager;
import net.ptcrys.fpsmatch.common.client.screen.hud.IHudRenderer;

@Mod.EventBusSubscriber(modid = "elimination_addon", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EliminationHud implements IHudRenderer {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> FPSMGameHudManager.INSTANCE
                .registerHud("elimination", new EliminationHud()));
    }

    @Override
    public void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        // 需要调整原版覆盖层时，再按覆盖层 ID 选择性处理。
    }

    @Override
    public void onPlayerRender(ForgeGui gui, GuiGraphics graphics,
            float partialTick, int width, int height) {
        draw(graphics, width, "淘汰赛");
    }

    @Override
    public void onSpectatorRender(ForgeGui gui, GuiGraphics graphics,
            float partialTick, int width, int height) {
        draw(graphics, width, "淘汰赛 · 旁观中");
    }

    private void draw(GuiGraphics graphics, int width, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        graphics.drawCenteredString(mc.font, text, width / 2, 12, 0xFFFFFF);
    }
}
