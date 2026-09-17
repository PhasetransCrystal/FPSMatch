package net.ptcrys.fpsmatch.mixin.input;

import net.ptcrys.fpsmatch.config.FPSMConfig;

import net.minecraft.client.CameraType;
import net.minecraft.client.Options;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public class BanThirdPersonMixin {

    @Inject(method = "setCameraType", at = @At("HEAD"), cancellable = true)
    private void onHandleKeybinds(CameraType type, CallbackInfo ci) {
        if (net.ptcrys.fpsmatch.common.client.camera.CameraDirector.policy().lockPerspective() || (FPSMConfig.Server.lock3PersonCamera.get() && type != CameraType.FIRST_PERSON)) ci.cancel();
    }
}
