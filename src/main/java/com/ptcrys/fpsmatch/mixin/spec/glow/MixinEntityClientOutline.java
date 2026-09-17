package com.ptcrys.fpsmatch.mixin.spec.glow;

import net.minecraft.world.entity.Entity;

import com.ptcrys.fpsmatch.common.client.spec.PlayerOutlineRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class MixinEntityClientOutline {

    @Inject(method = "isCurrentlyGlowing", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useClientOutlineDecision(CallbackInfoReturnable<Boolean> cir) {
        Entity entity = (Entity) (Object) this;
        if (PlayerOutlineRenderer.controlsOutline(entity)) {
            cir.setReturnValue(PlayerOutlineRenderer.shouldOutline(entity));
        }
    }

    @Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true)
    private void fpsmatch$useClientOutlineColor(CallbackInfoReturnable<Integer> cir) {
        int color = PlayerOutlineRenderer.getOutlineColor((Entity) (Object) this);
        if (color != PlayerOutlineRenderer.NO_OUTLINE_COLOR) {
            cir.setReturnValue(color);
        }
    }
}
