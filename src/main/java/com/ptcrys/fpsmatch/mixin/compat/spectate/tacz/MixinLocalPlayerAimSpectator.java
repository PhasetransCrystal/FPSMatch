package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.player.LocalPlayer;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.client.gameplay.LocalPlayerAim;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Blocks local aiming input when the client is spectating another player.
 */
@Mixin(value = LocalPlayerAim.class, remap = false)
public abstract class MixinLocalPlayerAimSpectator {

    @Shadow
    private LocalPlayer player;

    @Inject(method = "aim(Z)V", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$blockAimInputWhileSpectating(boolean isAim, CallbackInfo ci) {
        if (SpectatorView.isSpectatingOther(this.player)) {
            ci.cancel();
        }
    }
}
