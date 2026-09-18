---
title: 商店与经济
description: 初始化队伍商店，配置商品并控制购买条件。
---

# 商店与经济

`ShopCapability` 把队伍、商店配置和玩家商店数据连接起来。组件存在时，商店类型仍可能尚未初始化，因此查询时优先使用 `getShopSafe()`。

## 初始化商店类型

能力的 `initialize(shopTypeId, startMoney)` 接收已注册类型 ID 和初始金钱。类型决定商店的数据结构及分组；不要假设框架中一定有名为 `default` 的类型。玩法扩展应先提供或选择实际注册的商店类型，再为队伍初始化。

```java
team.getCapabilityMap().get(ShopCapability.class).ifPresent(cap -> {
    if (!cap.initialize(shopTypeId, 800)) {
        throw new IllegalStateException("Unknown shop type: " + shopTypeId);
    }
});
```

这是服务端初始化片段，`team` 为目标队伍，`shopTypeId` 来自玩法注册。它说明安装能力与建立商店内容之间的关系。

## 配置商品槽位

商店初始化之后，通过能力修改已有分组中的槽位：

```java
cap.setShopItem(new ItemStack(Items.IRON_SWORD), group, slot);
cap.setShopCost(500, group, slot);
cap.syncShopData();
```

`group` 和 `slot` 必须来自该商店类型的有效分组与槽位，不能任意添加一个字符串作为新分类。枪械商品还可以设置虚拟弹药数量，槽位也可以附加监听模块。

## 控制购买时机

模式覆盖 `BaseMap.canUseShop(ShopCapability, ServerPlayer)`，将购买条件与比赛阶段、区域和玩家状态关联。客户端的按钮禁用只是展示，真正允许购买仍由服务端决定。

购买处理应使用服务器当前商品、价格与余额。客户端只提交购买目标，不能提交“扣多少钱”或“给多少件”作为可信结算数据。

## 初始金钱与当前余额

`setStartMoney()` 改变商店配置中的开局金额。修改正在比赛的玩家余额，使用 `ShopCapability.setPlayerMoney(map, playerUuid, amount)`；同步余额使用能力提供的金钱同步接口。配置起始金额与一次奖励应分开处理。

`resetPlayerData()` 用于重置商店玩家数据，`syncShopData()` 与 `syncShopMoneyData()` 分别面向商店内容和余额。整局重置、下一轮补偿和胜利奖励应根据玩法分别调用，避免每轮都覆盖累计经济。

继续阅读：[出生装备](equipment-and-shop.md) · [网络请求](../network/c2s.md)。

分类枚举与默认商品见[商店分类](shop-types.md)，组内装备扩展见[监听模块](shop-listeners.md)。
