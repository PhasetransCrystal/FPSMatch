package net.ptcrys.fpsmatch.core.shop;

import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import java.util.ArrayList;

public interface INamedType {

    String name();

    int slotCount();

    boolean dorpUnlock();

    ArrayList<ShopSlot> defaultSlots();
}
