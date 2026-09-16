package com.ptcrys.fpsmatch.mixin.camera;

import com.ptcrys.fpsmatch.common.client.camera.CameraBackend;
import com.ptcrys.fpsmatch.common.client.camera.CameraDirector;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class CameraEntityMixin {
    @Inject(method = "setCameraEntity", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$protectSession(Entity entity, CallbackInfo ci) {
        if (CameraDirector.hasSession() && !CameraBackend.isBinding()) ci.cancel();
    }
}
