package com.ptcrys.fpsmatch.common.drop;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.util.FPSMUtil;

import java.util.function.Predicate;

public enum DropType {

    MAIN_WEAPON((stack -> {
        for (Predicate<ItemStack> predicate : FPSMUtil.MAIN_WEAPON_PREDICATE) {
            if (predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }), 0),
    SECONDARY_WEAPON((stack -> {
        for (Predicate<ItemStack> predicate : FPSMUtil.SECONDARY_WEAPON_PREDICATE) {
            if (predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }), 1),
    THIRD_WEAPON((stack -> {
        for (Predicate<ItemStack> predicate : FPSMUtil.THIRD_WEAPON_PREDICATE) {
            if (predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }), 2),
    THROW((stack -> {
        for (Predicate<ItemStack> predicate : FPSMUtil.THROW_PREDICATE) {
            if (predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }), 4, 5, 6, 7),
    MISC((stack -> true), 8);

    private final Predicate<ItemStack> predicate;
    private final int[] slotIndex;

    DropType(Predicate<ItemStack> predicate, int... slotIndex) {
        this.predicate = predicate;
        this.slotIndex = slotIndex;
    }

    public Predicate<Player> inventoryMatch() {
        return (player) -> {
            if (this == THROW) {
                // 对于投掷物，使用更严格的检查
                return canPickupThrowable(player, null);
            }
            int i = player.getInventory().clearOrCountMatchingItems(predicate, 0, player.inventoryMenu.getCraftSlots());
            return i < getLimit(this);
        };
    }

    /**
     * 检查玩家是否可以拾取特定的投掷物
     */
    public boolean canPickupThrowable(Player player, ItemStack throwableStack) {
        if (this != THROW) {
            return false;
        }

        // 检查总投掷物数量限制
        int total = player.getInventory().clearOrCountMatchingItems(predicate, 0, player.inventoryMenu.getCraftSlots());
        if (total >= getLimit(this)) {
            return false;
        }

        // 如果提供了具体的物品堆，进行子类型限制检查
        if (throwableStack != null && !throwableStack.isEmpty()) {
            Item item = throwableStack.getItem();
            ThrowableSubType subType = ThrowableRegistry.getThrowableSubType(item);

            // 如果物品已注册到投掷物子类型，检查子类型限制
            if (subType != null) {
                int subTypeCount = countThrowablesOfSubType(player, subType);
                int subTypeLimit = ThrowableRegistry.getLimitForSubType(subType);

                return subTypeCount < subTypeLimit;
            }
        }

        return true;
    }

    /**
     * 计算玩家拥有的特定子类型投掷物数量
     */
    private int countThrowablesOfSubType(Player player, ThrowableSubType subType) {
        int count = 0;
        for (Item item : ThrowableRegistry.getItemsForSubType(subType)) {
            count += player.getInventory().clearOrCountMatchingItems(
                    stack -> stack.getItem() == item,
                    0,
                    player.inventoryMenu.getCraftSlots());
        }
        return count;
    }

    public Predicate<ItemStack> itemMatch() {
        return predicate;
    }

    public static int getLimit(DropType type) {
        return switch (type) {
            case MAIN_WEAPON -> FPSMConfig.common.mainWeaponCount.get();
            case SECONDARY_WEAPON -> FPSMConfig.common.secondaryWeaponCount.get();
            case THIRD_WEAPON -> FPSMConfig.common.thirdWeaponCount.get();
            case THROW -> FPSMConfig.common.throwableCount.get();
            case MISC -> Integer.MAX_VALUE;
        };
    }

    public static DropType getItemDropType(ItemStack itemStack) {
        for (DropType type : DropType.values()) {
            if (type.itemMatch().test(itemStack)) {
                return type;
            }
        }
        return DropType.MISC;
    }

    public int[] getSlotIndex() {
        return slotIndex;
    }
}
