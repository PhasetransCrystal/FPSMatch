package net.ptcrys.fpsmatch.mixin.camera;

import net.ptcrys.fpsmatch.common.client.camera.*;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class CameraGuiMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$sceneOverlay(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (!CameraDirector.policy().hideHud()) return;
        CameraEvents.renderOverlay(graphics, partialTick);
        ci.cancel();
    }
}
