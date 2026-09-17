package com.ptcrys.fpsmatch.mixin.camera;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.ToastComponent;

import com.ptcrys.fpsmatch.common.client.camera.CameraDirector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastComponent.class)
public class CameraToastMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$toasts(GuiGraphics graphics, CallbackInfo ci) {
        if (CameraDirector.policy().hideToasts()) ci.cancel();
    }
}
