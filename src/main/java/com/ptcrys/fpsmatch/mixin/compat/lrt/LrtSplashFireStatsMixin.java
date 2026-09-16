package com.ptcrys.fpsmatch.mixin.compat.lrt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ptcrys.fpsmatch.compat.LrtUtilityAttribution;
import me.xjqsh.lrtactical.entity.EffectCloudGrenadeEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = EffectCloudGrenadeEntity.class, remap = false)
public abstract class LrtSplashFireStatsMixin {
    @WrapOperation(method = "applyAllEffects", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;setSecondsOnFire(I)V", remap = true))
    private void fpsmatch$rememberThrower(LivingEntity target, int seconds, Operation<Void> original) {
        original.call(target, seconds);
        Entity grenade = (Entity) (Object) this;
        LrtUtilityAttribution.ignited(target, grenade, LrtUtilityAttribution.owner(grenade), seconds);
    }
}
