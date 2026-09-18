---
title: 套件与发放时机
description: 配置 StartKitsCapability 并在合适的比赛阶段发放装备。
---

# 套件与发放时机

`StartKitsCapability` 保存一支队伍的初始装备。它适合两队拥有不同套件、并允许地图制作者修改内容的玩法。

## 添加套件能力

创建队伍时声明需要的能力：

```java
ServerTeam red = addTeam(TeamData.of("red", 8,
        List.of(SpawnPointCapability.class, StartKitsCapability.class)));
```

两种能力都位于 `common.capability.team`。FPSMatch 已注册内置能力工厂，附属模组只需为自己的队伍安装。

## 设置装备内容

下面在服务端为该队伍设置一把铁剑：

```java
red.getCapabilityMap().get(StartKitsCapability.class).ifPresent(kits -> {
    kits.clearTeamKits();
    kits.addKit(new ItemStack(Items.IRON_SWORD));
});
```

`addKit()` 复制传入物品栈，避免之后修改手中物品影响套件。`getTeamKits()` 返回列表副本；修改返回的列表不会替代 `addKit`、`removeItem` 或 `clearTeamKits`。

## 发放给参赛者

在地图的回合准备流程中，对普通队伍调用：

```java
getMapTeams().getNormalTeams().forEach(team ->
        team.getCapabilityMap().get(StartKitsCapability.class)
                .ifPresent(StartKitsCapability::giveAllPlayersKits));
```

需要只处理一个玩家时调用 `givePlayerKits(player)`。方法会检查队伍归属、清空玩家物品栏、复制套件内容，将护甲放入对应槽位，并触发获得物品事件和物品栏同步。

因为发放会清空背包，通常每轮只调用一次。不要在每 tick 执行，否则玩家购买或拾取的物品会不断被替换。

## 保存套件

能力实现了 `Savable<List<ItemStack>>`，通过 `ItemStack.CODEC.listOf()` 保存套件。运行时修改能力与写盘是两件事；需要持久保存修改时，使用框架的保存流程。普通比赛重置不应销毁套件配置。

继续阅读：[商店与经济](shop.md) · [能力持久化](../capability/persistence.md)。
