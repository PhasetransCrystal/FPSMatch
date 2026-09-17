package com.ptcrys.fpsmatch.common.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.google.common.collect.Maps;
import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.data.FPSMClientGlobalData;
import com.ptcrys.fpsmatch.common.client.screen.hud.IHudRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class FPSMGameHudManager implements IGuiOverlay {

    public static boolean enable = true;
    public static final FPSMGameHudManager INSTANCE = new FPSMGameHudManager();
    private final Map<String, List<IHudRenderer>> gameHudMap = Maps.newHashMap();

    @SubscribeEvent
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        String gameType = data.getCurrentGameType();
        if (enable && INSTANCE.gameHudMap.containsKey(gameType) && !data.isSpectator()) {
            INSTANCE.gameHudMap.get(gameType).forEach(overlay -> overlay.onRenderGuiOverlayPre(event));
        }
    }

    public static boolean shouldRender() {
        return enable && FPSMClient.getGlobalData().isInGame();
    }

    public void registerHud(String gameType, IHudRenderer overlay) {
        gameHudMap.computeIfAbsent(gameType, k -> new ArrayList<>()).add(overlay);
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int screenWidth, int screenHeight) {
        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        String gameType = data.getCurrentGameType();
        if (enable && gameHudMap.containsKey(gameType)) {
            gameHudMap.get(gameType).forEach(overlay -> overlay.render(gui, guiGraphics, partialTick, screenWidth, screenHeight, data.isSpectator()));
        }
    }
}
