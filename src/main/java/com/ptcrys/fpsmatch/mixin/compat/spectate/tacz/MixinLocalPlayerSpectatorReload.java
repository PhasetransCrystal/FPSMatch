package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorGunReloadMirror;
import com.tacz.guns.api.entity.IGunOperator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors reload state changes from the spectated player to the local view.
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerSpectatorReload {

    private static boolean lastTargetReloading = false;
    private static int lastTargetId = -1;

    @Inject(method = "tick", at = @At("TAIL"))
    private void fpsmatch$mirrorReload(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        Player target = SpectatorView.getSpectatedPlayer(self);
        if (target == null) {
            reset();
            return;
        }
        int targetId = target.getId();
        if (targetId != lastTargetId) {
            lastTargetReloading = false;
            SpectatorGunReloadMirror.cancel(target);
        }
        boolean targetReloading = IGunOperator.fromLivingEntity(target)
                .getSynReloadState().getStateType().isReloading();
        if (targetReloading && !lastTargetReloading) {
            SpectatorGunReloadMirror.start(target, true);
        } else if (!targetReloading && lastTargetReloading) {
            SpectatorGunReloadMirror.cancel(target);
        }
        lastTargetReloading = targetReloading;
        lastTargetId = targetId;
    }

    private static void reset() {
        lastTargetReloading = false;
        lastTargetId = -1;
    }
}
