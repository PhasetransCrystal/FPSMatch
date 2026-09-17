package net.ptcrys.fpsmatch.mixin.compat.lrt;

import net.ptcrys.fpsmatch.core.FPSMCore;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.xjqsh.lrtactical.entity.StunGrenadeEntity;
import me.xjqsh.lrtactical.init.ModEffects;
import me.xjqsh.lrtactical.item.FlashShieldItem;
import me.xjqsh.lrtactical.item.throwable.flash.StunThrowableData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Both stun grenades and flash shields resolve visibility before calling addEffect. */
@Mixin(value = { StunGrenadeEntity.class, FlashShieldItem.class }, remap = false)
public abstract class LrtFlashStatsMixin {

    @WrapOperation(method = "calculateAndApplyEffect",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/world/entity/LivingEntity;addEffect(Lnet/minecraft/world/effect/MobEffectInstance;)Z",
                            remap = true))
    private static boolean fpsmatch$countBlind(LivingEntity target, MobEffectInstance effect,
                                               Operation<Boolean> original, Entity source,
                                               LivingEntity affected, StunThrowableData.StunData data) {
        boolean applied = original.call(target, effect);
        if (applied && effect.getDuration() > 0 && effect.getEffect() == ModEffects.BLIND.get() && target instanceof ServerPlayer victim) {
            Entity owner = source instanceof TraceableEntity traceable ? traceable.getOwner() : source;
            if (owner instanceof ServerPlayer thrower) {
                FPSMCore.getInstance().getMapByPlayer(thrower)
                        .ifPresent(map -> map.recordFlashedEnemy(thrower, victim));
            }
        }
        return applied;
    }
}
