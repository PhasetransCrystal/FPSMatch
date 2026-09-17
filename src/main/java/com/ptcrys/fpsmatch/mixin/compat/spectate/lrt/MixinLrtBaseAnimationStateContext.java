package com.ptcrys.fpsmatch.mixin.compat.spectate.lrt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorMotion;
import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import me.xjqsh.lrtactical.api.animation.BaseAnimationStateContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirrors spectated movement input into LRTactical animation state context.
 */
@Mixin(value = BaseAnimationStateContext.class, remap = false)
public abstract class MixinLrtBaseAnimationStateContext {

    @Inject(method = "isInputUp", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useSpectatedInputUp(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = getSpectated();
        if (target != null) {
            cir.setReturnValue(SpectatorMotion.isMovingForward(target));
        }
    }

    @Inject(method = "isInputDown", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useSpectatedInputDown(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = getSpectated();
        if (target != null) {
            cir.setReturnValue(SpectatorMotion.isMovingBackward(target));
        }
    }

    @Inject(method = "isInputLeft", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useSpectatedInputLeft(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = getSpectated();
        if (target != null) {
            cir.setReturnValue(SpectatorMotion.isMovingLeft(target));
        }
    }

    @Inject(method = "isInputRight", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useSpectatedInputRight(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = getSpectated();
        if (target != null) {
            cir.setReturnValue(SpectatorMotion.isMovingRight(target));
        }
    }

    @Inject(method = "isInputJumping", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useSpectatedInputJumping(CallbackInfoReturnable<Boolean> cir) {
        LivingEntity target = getSpectated();
        if (target != null) {
            cir.setReturnValue(SpectatorMotion.isJumping(target));
        }
    }

    private static LivingEntity getSpectated() {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        return SpectatorView.getSpectatedLiving(localPlayer);
    }
}
