package com.ptcrys.fpsmatch.core.shop;

import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import java.util.ArrayList;

public record UnknownShopType(String name, int slotCount, boolean dorpUnlock) implements INamedType {

    public UnknownShopType(String name) {
        this(name, 0, false);
    }

    @Override
    public ArrayList<ShopSlot> defaultSlots() {
        return new ArrayList<>();
    }
}
