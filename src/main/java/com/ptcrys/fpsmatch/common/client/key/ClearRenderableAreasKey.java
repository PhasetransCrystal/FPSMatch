package com.ptcrys.fpsmatch.common.client.key;

import com.mojang.blaze3d.platform.InputConstants;
import com.ptcrys.fpsmatch.common.client.FPSMClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class ClearRenderableAreasKey {
    public static final KeyMapping KEY = new KeyMapping("key.fpsm.clear_renderable_areas.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F12,
            "key.category.fpsm");

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        while (KEY.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                var debugData = FPSMClient.getGlobalData().getDebugData();
                debugData.toggleVisibility();
                minecraft.player.displayClientMessage(Component.translatable(
                        debugData.isVisible() ? "message.fpsm.preview.shown" : "message.fpsm.preview.hidden"), true);
            }
        }
    }
}
