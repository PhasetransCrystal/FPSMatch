package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorMotion;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import com.tacz.guns.client.animation.statemachine.GunAnimationStateContext;

/**
 * Mirrors spectated player movement into TACZ gun animation states.
 * Registered by {@link com.ptcrys.fpsmatch.compat.tacz.TACZBootstrap} when TACZ is loaded.
 */
public final class SpectatorGunMovementMirror {

    private SpectatorGunMovementMirror() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        Player target = SpectatorView.getSpectatedPlayer(self);
        if (target == null) {
            return;
        }
        ItemStack stack = SpectatorGunStacks.current(target);
        if (stack.isEmpty()) {
            return;
        }
        TimelessAPI.getGunDisplay(stack).ifPresent(display -> {
            LuaAnimationStateMachine<GunAnimationStateContext> asm = display.getAnimationStateMachine();
            if (asm == null) {
                return;
            }
            if (target.isCrouching()) {
                asm.trigger("idle");
                return;
            }
            if (target.isSprinting()) {
                asm.trigger("run");
                return;
            }
            if (SpectatorMotion.isMoving(target)) {
                asm.trigger("walk");
            } else {
                asm.trigger("idle");
            }
        });
    }
}
