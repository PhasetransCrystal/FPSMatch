package com.ptcrys.fpsmatch.mixin.compat.spectate.lrt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorMotion;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.client.animation.statemachine.LuaAnimationStateMachine;
import me.xjqsh.lrtactical.client.renderer.item.FlashShieldItemRenderer;
import me.xjqsh.lrtactical.client.renderer.item.MeleeItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors spectated player movement into LRTactical item animations.
 * Only loaded when LRTactical is present.
 */
@Mixin(Minecraft.class)
public class MixinMinecraftLrtSpectator {

    @Inject(method = "tick", at = @At("HEAD"))
    private void fpsmatch$onClientTickStart(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null) return;

        Player target = SpectatorView.getSpectatedPlayer(self);
        if (target == null) {
            return;
        }
        ItemStack stack = target.getMainHandItem();
        if (stack.isEmpty()) {
            return;
        }
        BlockEntityWithoutLevelRenderer renderer = net.minecraftforge.client.extensions.common.IClientItemExtensions.of(stack).getCustomRenderer();
        if (renderer instanceof MeleeItemRenderer meleeRenderer) {
            LuaAnimationStateMachine<?> asm = meleeRenderer.getStateMachine(stack);
            if (asm != null) {
                fpsmatch$tickMove(target, asm);
            }
            return;
        }
        if (renderer instanceof FlashShieldItemRenderer shieldRenderer) {
            LuaAnimationStateMachine<?> asm = shieldRenderer.getStateMachine(stack);
            if (asm != null) {
                fpsmatch$tickMove(target, asm);
            }
        }
    }

    @Unique
    private void fpsmatch$tickMove(Player target, LuaAnimationStateMachine<?> asm) {
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
    }
}
