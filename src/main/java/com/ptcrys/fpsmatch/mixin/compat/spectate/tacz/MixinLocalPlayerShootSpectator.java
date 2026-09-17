package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.player.LocalPlayer;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorGunShootSimulator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import com.tacz.guns.client.gameplay.LocalPlayerShoot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Redirects local shooting to the spectator simulator when spectating another player.
 */
@Mixin(value = LocalPlayerShoot.class, remap = false)
public abstract class MixinLocalPlayerShootSpectator {

    @Shadow
    @Final
    private LocalPlayerDataHolder data;

    @Shadow
    @Final
    private LocalPlayer player;

    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$redirectShootWhenSpectating(CallbackInfoReturnable<ShootResult> cir) {
        if (this.player == null) {
            return;
        }
        if (!SpectatorView.isSpectatingOther(this.player)) {
            return;
        }
        ShootResult r = SpectatorGunShootSimulator.shootAsSpectator(this.data, this.player);
        cir.setReturnValue(r);
        cir.cancel();
    }
}
