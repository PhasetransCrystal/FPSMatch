package com.ptcrys.fpsmatch.common.client.key;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

import static com.tacz.guns.util.InputExtraCheck.isInGame;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class SwitchPreviousItemKey {

    private static int previous = -1;
    private static int current = 0;

    public static final KeyMapping KEY = new KeyMapping("key.fpsm.switch_previous_item.desc",
            KeyConflictContext.IN_GAME,
            KeyModifier.NONE,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "key.category.fpsm");

    @SubscribeEvent
    public static void onInspectPress(InputEvent.Key event) {
        boolean isInGame = isInGame();
        if (isInGame && KEY.isDown()) {
            if (event.getAction() == GLFW.GLFW_PRESS) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player == null) {
                    return;
                }

                if (previous != -1) {
                    player.getInventory().selected = previous;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null) return;
            if (previous == -1) {
                previous = player.getInventory().selected;
                current = player.getInventory().selected;
            } else {
                if (current != player.getInventory().selected) {
                    previous = current;
                    current = player.getInventory().selected;
                }
            }
        }
    }
}
