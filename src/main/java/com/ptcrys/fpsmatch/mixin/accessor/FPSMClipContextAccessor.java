package com.ptcrys.fpsmatch.mixin.accessor;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.shapes.CollisionContext;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClipContext.class)
public interface FPSMClipContextAccessor {

    @Accessor
    CollisionContext getCollisionContext();
}
