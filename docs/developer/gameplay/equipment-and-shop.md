---
title: 出生装备与商店
description: 选择直接发放装备、队伍套件或商店能力。
---

# 出生装备与商店

装备涉及两个不同问题：玩家开始一轮时得到什么，以及比赛中可以购买什么。前者适合回合准备逻辑或 `StartKitsCapability`，后者由 `ShopCapability` 和商店类型处理。

## 在模式中直接发放

固定装备的玩法可以在地图的玩家准备方法中操作物品栏。下面是发放原版装备的局部示例：

```java
clearInventory(player);
player.getInventory().add(new ItemStack(Items.IRON_SWORD));
player.getInventory().add(new ItemStack(Items.BOW));
player.getInventory().add(new ItemStack(Items.ARROW, 32));
syncInventory(player);
```

`player` 是本轮参赛的 `ServerPlayer`。这段代码会清除物品，适用于由玩法管理装备的场景；若要保留玩家进入比赛前的背包，需要另外设计离场恢复。

## 将装备变成队伍配置

两队开局装备不同、并允许地图制作者调整时，使用 `StartKitsCapability`。它与 `SpawnPointCapability`、`ShopCapability` 都位于 `common.capability.team`，可以通过 `TeamData` 声明。

选择能力前先决定发放时机：整场开始、每轮开始、复活，还是换边之后。把同一套发放代码同时放进这些路径会造成重复物品。具体套件接口见[套件与发放时机](kits.md)。

## 添加商店能力

```java
addTeam(TeamData.of("red", 8,
        List.of(SpawnPointCapability.class, ShopCapability.class)));
```

这只为队伍添加商店组件，不代表已经有可购买的商品。能力还需要一个已注册的商店类型，并配置商品分组、槽位、价格和初始金钱。调用 `initialize(shopTypeId, startMoney)` 后应处理返回值；不存在的类型无法完成初始化。

比赛阶段、购买区域和玩家状态共同决定能否购买。服务端的 `BaseMap.canUseShop()` 是玩法可覆盖的购买判定点。详见[商店与经济](shop.md)。
