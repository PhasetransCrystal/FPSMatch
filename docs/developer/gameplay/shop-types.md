---
title: 商店分类与默认槽位
description: 实现 INamedType，为玩法定义细分商品页和默认商品。
---

# 商店分类与默认槽位

`FPSMShop` 用一个实现 `INamedType` 的枚举描述商品分类，每个枚举值有自己的默认槽位列表。`ShopData` 则保存某个玩家的购买状态。定义分类时先决定商品如何分组，再配置各组的槽位、价格和监听模块。

## 创建分类枚举

下面的完整文件为示例玩法提供两个商品页。这里用原版物品展示 API，实际枪械物品可以由[枪械提供器](../integration/gun-provider.md)生成。

```java title="com/example/elimination/shop/ArenaShopType.java"
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
```

枚举已有的 `name()` 满足 `INamedType.name()`，不需要覆盖。当前接口中的方法拼写是 `dorpUnlock()`；它控制相关丢弃处理是否解锁这一分类中的槽位，应按这个真实拼写实现。

`defaultSlots()` 每次返回新的可变槽位对象，避免不同商店共享价格、锁定或购买次数。`slotCount()` 与默认列表长度保持一致。示例的三参数构造器依次是物品栈、价格和最大购买次数。

## 注册并创建商店

在公共初始化的合适时机、创建商店之前注册类型：

```java
FPSMShop.registerShopType("elimination_addon:arena", ArenaShopType.class);
FPSMShop<ArenaShopType> shop = FPSMShop.create(
        ArenaShopType.class, "training_shop", 800);
```

`FPSMShop` 位于 `net.ptcrys.fpsmatch.core.shop`。若配置中只保存字符串类型 ID，可以使用 `createWithTypeId(typeId, name, startMoney)` 查注册表创建。相同类型 ID 再次注册会覆盖旧类型，因此使用自己的命名空间。

创建的商店仍需接入玩法的 [ShopCapability](shop.md)。枚举类本身不负责限制购买阶段、扣款同步或为所有在线玩家创建个人状态。

## 配置默认值和玩家状态

`getDefaultShopSlotListByType()` 与 `setDefaultShopDataItemStack()`、`setDefaultShopDataCost()` 面向商店模板；`getPlayerShopData()` 和 `getPlayerShopDataSafe()` 面向玩家数据。初始化模板和修改某个玩家的余额、槽位属于不同操作，不能用修改模板代替比赛中的玩家状态更新。

需要同组装备互斥、护甲升级或特殊价格逻辑时，为槽位设置组关系并挂接[商店监听模块](shop-listeners.md)。

源码入口：[core/shop/FPSMShop.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/core/shop/FPSMShop.java)。
