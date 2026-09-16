package com.ptcrys.fpsmatch.mixin.compat.spectate.tacz;

import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.client.event.CameraSetupEvent;
import com.ptcrys.fpsmatch.compat.spectate.tacz.SpectatorCameraRecoil;
import net.minecraftforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CameraSetupEvent.class, remap = false)
public abstract class CameraSetupEventMixin {
    @Inject(method = "initialCameraRecoil(Lcom/tacz/guns/api/event/common/GunFireEvent;)V", at = @At("HEAD"))
    private static void fpsmatch$initSpectatorCameraRecoil(GunFireEvent event, CallbackInfo ci) {
        if (!event.getLogicalSide().isClient()) {
            return;
        }
        SpectatorCameraRecoil.trigger(event.getShooter());
    }

    @Inject(method = "applyCameraRecoil(Lnet/minecraftforge/client/event/ViewportEvent$ComputeCameraAngles;)V", at = @At("HEAD"), cancellable = true)
    private static void fpsmatch$applySpectatorCameraRecoil(ViewportEvent.ComputeCameraAngles event, CallbackInfo ci) {
        if (com.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy().suppressViewEffects()) {
            ci.cancel();
            return;
        }
        SpectatorCameraRecoil.apply(event);
    }
}
