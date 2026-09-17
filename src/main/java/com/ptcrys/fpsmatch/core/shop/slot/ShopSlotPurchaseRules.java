package com.ptcrys.fpsmatch.core.shop.slot;

public final class ShopSlotPurchaseRules {

    private ShopSlotPurchaseRules() {}

    public static boolean canBuy(int money, int cost, int boughtCount, int maxBuyCount, boolean locked) {
        return !locked && (money == -1 || (money >= cost && boughtCount < maxBuyCount));
    }
}
