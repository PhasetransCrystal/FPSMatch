package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorInspectPackets.C2SStartInspectPacket;
import com.ptcrys.fpsmatch.compat.spectate.net.SpectatorSyncNetwork;
import com.tacz.guns.client.gameplay.LocalPlayerInspect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends inspect events to the server so spectators can mirror the animation.
 */
@Mixin(value = LocalPlayerInspect.class, remap = false)
public abstract class MixinLocalPlayerInspectSync {

    @Inject(method = "inspect", at = @At("HEAD"))
    private void fpsmatch$syncInspect(CallbackInfo ci) {
        SpectatorSyncNetwork.CHANNEL.sendToServer(new C2SStartInspectPacket());
    }
}
