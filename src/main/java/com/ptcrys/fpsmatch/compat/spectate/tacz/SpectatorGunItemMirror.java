package com.ptcrys.fpsmatch.compat.spectate.tacz;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.tacz.guns.api.item.IGun;

/**
 * Mirrors the spectated player's main-hand item into the local inventory slot
 * so TACZ renders the correct gun model in first person.
 */
public final class SpectatorGunItemMirror {

    private static ItemStack oldStack = ItemStack.EMPTY;
    private static ItemStack mirrorStack = ItemStack.EMPTY;
    private static boolean isHoldingFake = false;
    private static int fakeSlotIndex = -1;

    private SpectatorGunItemMirror() {}

    public static void equip(LocalPlayer localPlayer, ItemStack targetStack) {
        if (localPlayer == null) {
            return;
        }
        if (!isHoldingFake) {
            fakeSlotIndex = localPlayer.getInventory().selected;
            oldStack = localPlayer.getInventory().getItem(fakeSlotIndex);
            mirrorStack = targetStack == null ? ItemStack.EMPTY : targetStack.copy();
            localPlayer.getInventory().setItem(fakeSlotIndex, mirrorStack);
            isHoldingFake = true;
            return;
        }
        if (targetStack == null || targetStack.isEmpty()) {
            mirrorStack = ItemStack.EMPTY;
            localPlayer.getInventory().setItem(fakeSlotIndex, ItemStack.EMPTY);
            return;
        }
        if (isSameItemType(mirrorStack, targetStack)) {
            syncMirrorStackData(mirrorStack, targetStack);
        } else {
            mirrorStack = targetStack.copy();
            localPlayer.getInventory().setItem(fakeSlotIndex, mirrorStack);
        }
    }

    public static void tick(LocalPlayer localPlayer) {
        if (!isHoldingFake) {
            return;
        }
        if (fakeSlotIndex < 0 || fakeSlotIndex >= localPlayer.getInventory().items.size()) {
            return;
        }
        if (localPlayer.getInventory().items.get(fakeSlotIndex) != mirrorStack) {
            localPlayer.getInventory().setItem(fakeSlotIndex, mirrorStack);
        }
    }

    public static void revert(LocalPlayer localPlayer) {
        if (!isHoldingFake) {
            return;
        }
        if (fakeSlotIndex >= 0 && fakeSlotIndex < localPlayer.getInventory().items.size()) {
            localPlayer.getInventory().setItem(fakeSlotIndex, oldStack);
        }
        oldStack = ItemStack.EMPTY;
        mirrorStack = ItemStack.EMPTY;
        fakeSlotIndex = -1;
        isHoldingFake = false;
    }

    public static boolean isActive() {
        return isHoldingFake;
    }

    public static int getFakeSlotIndex() {
        return fakeSlotIndex;
    }

    public static ItemStack getMirroredStack() {
        return mirrorStack;
    }

    public static boolean isFakeGun() {
        return isHoldingFake && mirrorStack.getItem() instanceof IGun;
    }

    private static boolean isSameItemType(ItemStack a, ItemStack b) {
        Item ia = a == null || a.isEmpty() ? null : a.getItem();
        Item ib = b == null || b.isEmpty() ? null : b.getItem();
        return ia != null && ia == ib;
    }

    private static void syncMirrorStackData(ItemStack mirror, ItemStack target) {
        if (mirror == null || target == null) {
            return;
        }
        CompoundTag tag = target.getTag();
        mirror.setTag(tag == null ? null : tag.copy());
        mirror.setCount(target.getCount());
        mirror.setDamageValue(target.getDamageValue());
    }
}
