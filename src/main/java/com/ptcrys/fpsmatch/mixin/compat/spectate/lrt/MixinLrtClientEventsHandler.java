package com.ptcrys.fpsmatch.mixin.compat.spectate.lrt;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.TickEvent;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import me.xjqsh.lrtactical.client.ClientEventsHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disables LRTactical movement tick animation while spectating.
 */
@Mixin(value = ClientEventsHandler.class, remap = false)
public abstract class MixinLrtClientEventsHandler {

    @Inject(method = "tickAnimation(Lnet/minecraftforge/event/TickEvent$ClientTickEvent;)V", at = @At("HEAD"), cancellable = true)
    private static void fpsmatch$skipWhenSpectating(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        if (SpectatorView.isSpectatingOther(Minecraft.getInstance().player)) {
            ci.cancel();
        }
    }
}
