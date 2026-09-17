package com.ptcrys.fpsmatch.core.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.level.Level;

import com.ptcrys.fpsmatch.core.map.BlastBombState;

public abstract class BlastBombEntity extends Entity implements TraceableEntity {

    public BlastBombEntity(EntityType<?> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    public abstract boolean isDeleting();

    public abstract LivingEntity getOwner();

    public abstract LivingEntity getDemolisher();

    public abstract BlastBombState getState();
}
