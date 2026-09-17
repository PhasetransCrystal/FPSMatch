package net.ptcrys.fpsmatch.mixin.compat.grenades;

import net.ptcrys.fpsmatch.core.FPSMCore;

import net.minecraft.server.level.ServerPlayer;

import club.pisquad.minecraft.csgrenades.entity.FlashBangEntity;
import club.pisquad.minecraft.csgrenades.network.message.AffectedPlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Consume the grenade's actual server result, including its occlusion and duration checks. */
@Mixin(value = FlashBangEntity.class, remap = false)
public abstract class FlashBangStatsMixin {

    @Unique
    private final Set<UUID> fpsmatch$countedTargets = new HashSet<>();

    @Inject(method = "calculateAffectedPlayers", at = @At("RETURN"))
    private void fpsmatch$countFlashedEnemies(CallbackInfoReturnable<List<AffectedPlayerInfo>> cir) {
        FlashBangEntity grenade = (FlashBangEntity) (Object) this;
        if (!(grenade.getOwner() instanceof ServerPlayer thrower)) return;
        FPSMCore.getInstance().getMapByPlayer(thrower).ifPresent(map -> {
            for (AffectedPlayerInfo affected : cir.getReturnValue()) {
                if (affected.getEffectData().getEffectSustain() <= 0) continue;
                ServerPlayer target = thrower.server.getPlayerList().getPlayer(affected.getUuid());
                if (target != null && fpsmatch$countedTargets.add(target.getUUID())) {
                    map.recordFlashedEnemy(thrower, target);
                }
            }
        });
    }
}
