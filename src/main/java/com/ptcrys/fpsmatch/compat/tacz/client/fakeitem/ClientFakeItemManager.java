package com.ptcrys.fpsmatch.compat.tacz.client.fakeitem;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class ClientFakeItemManager {

    private static ItemStack oldStack = ItemStack.EMPTY;
    private static ItemStack mirrorStack = ItemStack.EMPTY;
    private static boolean isHoldingFake = false;
    private static int fakeSlotIndex = -1;

    // 装备/更新旁观者假物品
    public static void equipOrUpdateForSpectator(LocalPlayer player, ItemStack targetStack) {
        if (player == null) return;

        if (!isHoldingFake) {
            fakeSlotIndex = player.getInventory().selected;
            oldStack = player.getInventory().items.get(fakeSlotIndex).copy();
            mirrorStack = targetStack == null ? ItemStack.EMPTY : targetStack.copy();
            updatePlayerInventory(player);
            isHoldingFake = true;
            return;
        }

        if (targetStack == null || targetStack.isEmpty()) {
            mirrorStack = ItemStack.EMPTY;
            updatePlayerInventory(player);
            return;
        }

        if (isSameItemType(mirrorStack, targetStack)) {
            syncMirrorStackData(mirrorStack, targetStack);
        } else {
            mirrorStack = targetStack.copy();
            updatePlayerInventory(player);
        }
    }

    // 还原假物品
    public static void revertFakeItem(LocalPlayer player) {
        if (!isHoldingFake) return;
        if (fakeSlotIndex >= 0 && fakeSlotIndex < player.getInventory().items.size()) {
            player.getInventory().items.set(fakeSlotIndex, oldStack);
        }
        resetState();
    }

    // tick更新确保假物品状态正确
    public static void tickUpdate(LocalPlayer player) {
        if (!isHoldingFake || player == null) {
            return;
        }
        Inventory inv = player.getInventory();
        int selected = inv.selected;
        if (selected == fakeSlotIndex) {
            return;
        }
        if (selected < 0 || selected >= inv.items.size()) {
            return;
        }
        if (fakeSlotIndex >= 0 && fakeSlotIndex < inv.items.size()) {
            inv.items.set(fakeSlotIndex, oldStack);
        }
        fakeSlotIndex = selected;
        oldStack = inv.items.get(fakeSlotIndex).copy();
        inv.items.set(fakeSlotIndex, mirrorStack.copy());

        if (!isHoldingFake || fakeSlotIndex < 0 || fakeSlotIndex >= player.getInventory().items.size()) return;
        if (!player.getInventory().items.get(fakeSlotIndex).equals(mirrorStack)) {
            updatePlayerInventory(player);
        }
    }

    private static void updatePlayerInventory(LocalPlayer player) {
        player.getInventory().items.set(fakeSlotIndex, mirrorStack.copy());
    }

    private static void resetState() {
        oldStack = ItemStack.EMPTY;
        mirrorStack = ItemStack.EMPTY;
        fakeSlotIndex = -1;
        isHoldingFake = false;
    }

    private static boolean isSameItemType(ItemStack a, ItemStack b) {
        Item ia = a.isEmpty() ? null : a.getItem();
        Item ib = b.isEmpty() ? null : b.getItem();
        return ia != null && ia == ib;
    }

    private static void syncMirrorStackData(ItemStack mirror, ItemStack target) {
        if (mirror.isEmpty() || target.isEmpty()) return;
        CompoundTag tag = target.getTag();
        mirror.setTag(tag == null ? null : tag.copy());
        mirror.setDamageValue(target.getDamageValue());
        mirror.setCount(target.getCount());
    }

    // Getter方法
    public static boolean isHoldingFakeItem() {
        return isHoldingFake;
    }

    public static int getFakeSlotIndex() {
        return fakeSlotIndex;
    }

    public static ItemStack getCurrentFakeStack() {
        return mirrorStack;
    }

    public static boolean isFakeGun() {
        return isHoldingFake && mirrorStack.getItem() instanceof com.tacz.guns.api.item.IGun;
    }
}
