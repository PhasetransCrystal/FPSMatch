package com.ptcrys.fpsmatch.mixin.compat.lrt;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.ptcrys.fpsmatch.compat.LrtUtilityAttribution;
import me.xjqsh.lrtactical.entity.sp.SpEffectCloudEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SpEffectCloudEntity.class, remap = false)
public abstract class LrtCloudFireStatsMixin {
    @WrapOperation(method = "tick", remap = true, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setSecondsOnFire(I)V", remap = true))
    private void fpsmatch$rememberThrower(Entity target, int seconds, Operation<Void> original) {
        original.call(target, seconds);
        Entity cloud = (Entity) (Object) this;
        LrtUtilityAttribution.ignited(target, cloud, LrtUtilityAttribution.owner(cloud), seconds);
    }
}
