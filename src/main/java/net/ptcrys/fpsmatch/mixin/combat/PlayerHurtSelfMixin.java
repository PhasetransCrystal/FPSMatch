package net.ptcrys.fpsmatch.mixin.combat;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.map.BaseMap;

import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

@Mixin(Player.class)
public class PlayerHurtSelfMixin {

    @Inject(method = "canHarmPlayer(Lnet/minecraft/world/entity/player/Player;)Z", at = @At("HEAD"), cancellable = true)
    public void canHarmPlayer(Player other, CallbackInfoReturnable<Boolean> cir) {
        if (other.level().isClientSide) return;
        Player self = (Player) (Object) this;
        Optional<BaseMap> otherMap = FPSMCore.getInstance().getMapByPlayer(other);
        Optional<BaseMap> selfMap = FPSMCore.getInstance().getMapByPlayer(self);
        if (otherMap.isPresent() && otherMap.equals(selfMap) && "csdm".equals(otherMap.get().getGameType())) {
            if (!otherMap.get().getMapTeams().isSameTeam(self, other)) {
                cir.setReturnValue(true);
            }
            return;
        }
        if (otherMap.isPresent()) {
            if (other.is((Player) (Object) this)) {
                cir.setReturnValue(true);
            }
        }
    }
}
