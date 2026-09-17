package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.client.event.TickAnimationEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables TACZ movement tick animation while spectating to avoid local input overrides.
 */
@Mixin(value = TickAnimationEvent.class, remap = false)
public abstract class MixinTaczTickAnimationEvent {

    @Inject(method = "tickAnimation(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V", at = @At("HEAD"), cancellable = true)
    private static void fpsmatch$skipWhenSpectating(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        if (SpectatorView.isSpectatingOther(Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }
}
