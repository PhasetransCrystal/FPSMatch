package com.ptcrys.fpsmatch.mixin.camera;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraSetupMixin {
    @Inject(method = "setup", at = @At("RETURN"))
    private void fpsmatch$applyCamera(net.minecraft.world.level.BlockGetter level, net.minecraft.world.entity.Entity entity, boolean detached, boolean thirdPerson, float partialTick, CallbackInfo ci) {
        com.ptcrys.fpsmatch.common.client.camera.CameraDirector.apply((Camera) (Object) this);
    }
}
