---
title: 商店监听模块
description: 注册 ListenerModule 并理解槽位组事件、费用检查和重置。
---

# 商店监听模块

`ListenerModule` 为商店槽位增加可复用逻辑，例如护甲升级或组内装备变化处理。模块先注册到 `LMManager`，再通过名称挂到需要它的槽位上。只有注册并不意味着所有商品都会调用它。

## 定义与注册

模块实现 `core.shop.functional.ListenerModule`，至少提供唯一名称和优先级。下面是观察组内变更的完整示例：

```java title="com/example/elimination/shop/ShopModules.java"
package com.example.elimination.shop;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.register.RegisterListenerModuleEvent;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;

@Mod.EventBusSubscriber(modid = "elimination_addon",
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopModules {
    @SubscribeEvent
    public static void register(RegisterListenerModuleEvent event) {
        event.register(new ListenerModule() {
            @Override
            public String getName() { return "elimination_addon:group_log"; }

            @Override
            public int getPriority() { return 0; }

            @Override
            public void onChange(ShopSlotChangeEvent change) {
                FPSMatch.LOGGER.debug("Shop group change: player={}, flag={}",
                        change.player.getUUID(), change.flag);
            }
        });
    }
}
```

注册事件在 `LMManager` 构造时发布到 Forge 总线，可能随管理器创建多次发生。事件回调中向该管理器注册即可，不要再加一个阻止后续管理器注册的全局 once 标志。

## 挂到需要的槽位

`ShopCapability.addListenerModule(moduleName, shopType, slotNum)` 按模块名、商品分类名称和槽位索引挂接。这里的 `shopType` 是枚举值名称，例如 `EQUIPMENT`，与槽位互斥逻辑使用的数值 `groupId` 不同。

```java
shopCapability.addListenerModule("elimination_addon:group_log", "EQUIPMENT", 0);
```

片段中的 `shopCapability` 是已经初始化商店的能力实例。商店模板也提供 `addDefaultShopDataListenerModule(type, index, module)`。要触发组内变更逻辑，还需要按照购买流程为相关槽位设置共同的 `groupId`；仅挂模块不等于建立互斥组。

槽位会按 `getPriority()` 从高到低调用模块；相同优先级保持槽位内的加入顺序。注册表按名称保存实例，同名注册会覆盖。实例可能被多个玩家和槽位复用，因此玩家独有计数不能直接放在模块字段里。

## 三个回调的实际语义

`onChange(ShopSlotChangeEvent)` 来自槽位内部的组变更流程，**不是发布到 Forge 总线的通用购买事件**。`flag > 0` 表示购入，`flag < 0` 表示退款，绝对值对应数量。不要把上面的日志示例当作保证每笔交易只调用一次的结算监听器。

事件的 `addMoney()`、`removeMoney()` 改变本次处理携带的金额；`setCancelLogic()` 影响组内默认逻辑。它不是 Forge 的 `setCanceled()`，也不等于撤销已经完成的整笔交易。默认组处理还可能在模块回调之前执行，涉及原子购买规则时应结合 `ShopData` 的购买入口处理。

`onCostCheck(CheckCostEvent, ShopSlot)` 参与带组 ID 的费用检查。`addCost()` 累加检查贡献值，`success()` 判断累计值是否达到所需费用；不能仅凭方法名将它当成直接扣玩家余额的 API。

`onReset(ShopSlot)` 在槽位恢复默认价格、购买次数归零和解除锁定后执行，适合恢复模块管理的槽位状态。内置的带头盔和不带头盔护甲监听模块可作为实际组合逻辑的源码参考。

继续阅读：[商店购买流程](shop.md) · [事件系统](../events.md)。

源码入口：[core/shop/functional](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/core/shop/functional)。
