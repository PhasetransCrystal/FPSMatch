package com.ptcrys.fpsmatch.common.client.key;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import com.ptcrys.fpsmatch.common.client.FPSMGameHudManager;
import org.lwjgl.glfw.GLFW;

import static com.tacz.guns.util.InputExtraCheck.isInGame;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class CustomHudKey {

    public static final KeyMapping KEY = new KeyMapping("key.fpsm.hud.custom.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.UNKNOWN,
            "key.category.fpsm");

    @SubscribeEvent
    public static void onInspectPress(InputEvent.Key event) {
        if (isInGame() && event.getAction() == GLFW.GLFW_PRESS && KEY.matches(event.getKey(), event.getScanCode())) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isSpectator()) {
                return;
            }
            // 切换自定义Tab
            FPSMGameHudManager.enable = !FPSMGameHudManager.enable;
            if (FPSMGameHudManager.enable) {
                player.displayClientMessage(Component.translatable("key.fpsm.hud.custom.on").withStyle(ChatFormatting.GREEN), true);
            } else {
                player.displayClientMessage(Component.translatable("key.fpsm.hud.custom.off").withStyle(ChatFormatting.RED), true);
            }
        }
    }
}
