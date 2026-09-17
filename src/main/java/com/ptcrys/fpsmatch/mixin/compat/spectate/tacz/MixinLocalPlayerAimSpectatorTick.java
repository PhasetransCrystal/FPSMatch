package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

import com.ptcrys.fpsmatch.compat.spectate.SpectatorView;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.client.gameplay.LocalPlayerAim;
import com.tacz.guns.client.gameplay.LocalPlayerDataHolder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors the spectated player's aiming progress into the local aim state.
 */
@Mixin(value = LocalPlayerAim.class, remap = false)
public abstract class MixinLocalPlayerAimSpectatorTick {

    @Shadow
    @Final
    private LocalPlayerDataHolder data;

    @Shadow
    @Final
    private LocalPlayer player;

    @Inject(method = "tickAimingProgress()V", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$mirrorRemoteProgressWhenSpectating(CallbackInfo ci) {
        LivingEntity target = SpectatorView.getSpectatedLiving(this.player);
        if (target == null) {
            return;
        }
        float remote = IGunOperator.fromLivingEntity(target).getSynAimingProgress();
        LocalPlayerDataHolder.oldAimingProgress = remote;
        this.data.clientAimingProgress = remote;
        this.data.clientIsAiming = remote > 0.5f;
        ci.cancel();
    }
}
