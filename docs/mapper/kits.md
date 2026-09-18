---
title: 起始装备
description: 为每个队伍配置开局物品，并理解物品数据和发放行为。
---

# 起始装备

具有 `StartKitsCapability` 的队伍可以保存一组起始物品。玩法在对应的准备流程中发放它们；FPSMatch 的能力本身负责存储、发放和保存，具体发放时点由游戏类型决定。

## 从手中物品添加

把希望保存的物品放在主手。下面的命令会复制完整物品栈，包括数量和 NBT：

```text
/fpsm map modify cs training team teams <team> capability kits add
```

这种方式适合带枪械数据、附魔或自定义组件的物品。添加后再修改手里的原物品，不会改变已保存的副本。

## 按物品 ID 添加

普通物品可以直接写 ID 和数量：

```text
/fpsm map modify cs training team teams <team> capability kits add minecraft:bread 3
```

省略数量时默认为 1。该命令使用 Minecraft 1.20.1 的物品参数语法，命令补全会列出已注册物品。

列出或清空当前队伍装备：

```text
/fpsm map modify cs training team teams <team> capability kits list
/fpsm map modify cs training team teams <team> capability kits clear
```

当前命令没有按序号删除单件装备的入口。需要替换整套时，先 `clear`，再按希望的顺序重新添加；或者从兼容地图导入整套配置。

## 发放时会发生什么

发放起始装备会先清空该玩家当前物品栏。护甲物品放入对应装备槽，其他物品加入背包；枪械物品会经过当前枪械兼容层修正，随后整理物品栏。

这意味着起始装备适用于由比赛完全管理背包的玩法。它不是在玩家原生生存物品上追加奖励，也不会在离场时自动恢复进入比赛前的背包。

起始装备按队伍分别保存。若两队使用相同配置，可以先配置一张模板地图，再通过[配置导入](importing.md)按同名队伍复制。

下一步：[配置商店](shop.md)。
