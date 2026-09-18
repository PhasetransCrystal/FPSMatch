package com.example.elimination.shop;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import java.util.ArrayList;

public enum ArenaShopType implements INamedType {
    EQUIPMENT, SUPPLIES;

    @Override
    public int slotCount() {
        return 1;
    }

    @Override
    public boolean dorpUnlock() {
        return false;
    }

    @Override
    public ArrayList<ShopSlot> defaultSlots() {
        ArrayList<ShopSlot> slots = new ArrayList<>();
        if (this == EQUIPMENT) {
            slots.add(new ShopSlot(new ItemStack(Items.IRON_HELMET), 650, 1));
        } else {
            slots.add(new ShopSlot(new ItemStack(Items.BREAD), 100, 2));
        }
        return slots;
    }
}
