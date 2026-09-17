package com.ptcrys.fpsmatch.common.event;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.common.item.BaseThrowAbleItem;

public class FPSMThrowGrenadeEvent extends Event {

    private final LivingEntity entity;
    private final ItemStack itemStack;
    private final BaseThrowAbleItem.ThrowType type;

    public FPSMThrowGrenadeEvent(LivingEntity entity, ItemStack itemStack, BaseThrowAbleItem.ThrowType type) {
        this.entity = entity;
        this.itemStack = itemStack;
        this.type = type;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public ItemStack getItemStack() {
        return itemStack;
    }

    public BaseThrowAbleItem.ThrowType getType() {
        return type;
    }
}
