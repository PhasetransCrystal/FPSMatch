package com.ptcrys.fpsmatch.common.event;

import net.minecraftforge.eventbus.api.Event;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ptcrys.fpsmatch.core.shop.FPSMShop;
import com.ptcrys.fpsmatch.core.shop.INamedType;
import com.ptcrys.fpsmatch.core.shop.slot.ShopSlot;

import java.util.UUID;

public class FPSMShopEvent extends Event {

    private final FPSMShop<?> shop;
    private final UUID playerUUID;

    public FPSMShopEvent(FPSMShop<?> shop, UUID playerUUID) {
        this.shop = shop;
        this.playerUUID = playerUUID;
    }

    public FPSMShop<?> getShop() {
        return shop;
    }

    public UUID getPlayerUUID() {
        return playerUUID;
    }

    /**
     * 玩家商店数据即将初始化事件
     * 在创建新的玩家商店数据前触发，允许修改即将用于创建的参数
     */
    public static class DataInit<T extends Enum<T> & INamedType> extends FPSMShopEvent {

        private int money;
        private final ImmutableMap<T, ImmutableList<ShopSlot>> data;

        public DataInit(FPSMShop<T> shop, UUID uuid, ImmutableMap<T, ImmutableList<ShopSlot>> data, int money) {
            super(shop, uuid);
            this.data = data;
            this.money = money;
        }

        /**
         * 获取将用于初始化的金钱
         */
        public int getMoney() {
            return money;
        }

        /**
         * 设置初始化金钱
         */
        public void setMoney(int money) {
            this.money = money;
        }

        public ImmutableMap<T, ImmutableList<ShopSlot>> getData() {
            return data;
        }
    }
}
