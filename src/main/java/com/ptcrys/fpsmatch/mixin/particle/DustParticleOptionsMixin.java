package com.ptcrys.fpsmatch.mixin.particle;

import net.minecraft.core.particles.DustParticleOptionsBase;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DustParticleOptionsBase.class)
public class DustParticleOptionsMixin {

    @Mutable
    @Final
    @Shadow
    protected Vector3f color;

    @Mutable
    @Final
    @Shadow
    protected float scale;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(Vector3f vector3f, float f, CallbackInfo ci) {
        this.color = vector3f;
        this.scale = f;
    }
}
