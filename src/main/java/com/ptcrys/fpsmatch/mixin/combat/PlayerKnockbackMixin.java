package com.ptcrys.fpsmatch.mixin.combat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.core.entity.BaseProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlayerKnockbackMixin extends Entity {

    public PlayerKnockbackMixin(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    @Unique
    private DamageSource fpsmatch$lastDamageSource;
    @Unique
    private long fpsmatch$lastDamageStamp = 0;

    @Inject(
            method = "hurt",
            at = @At("HEAD"))
    private void beforeKnockback(DamageSource pSource, float pAmount, CallbackInfoReturnable<Boolean> cir) {
        fpsmatch$lastDamageSource = pSource;
        fpsmatch$lastDamageStamp = this.level().getGameTime();
    }

    @Unique
    private DamageSource fpsmatch$getLastDamageSource() {
        if (level().getGameTime() - this.fpsmatch$lastDamageStamp > 40L) {
            this.fpsmatch$lastDamageSource = null;
        }
        return fpsmatch$lastDamageSource;
    }

    @Inject(
            method = "knockback",
            at = @At("HEAD"),
            cancellable = true)
    private void injectKnockback(double pStrength, double pX, double pZ, CallbackInfo ci) {
        DamageSource ds = fpsmatch$getLastDamageSource();
        if (ds != null && ds.getDirectEntity() instanceof BaseProjectileEntity) {
            ci.cancel();
        }
    }
}
