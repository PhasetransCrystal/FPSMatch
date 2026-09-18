---
title: 伤害、出局与击杀统计
description: 区分伤害拦截、死亡处理、统计写入和击杀通知。
---

# 伤害、出局与击杀统计

比赛中的“出局”是框架状态，原版实体死亡只是其中一种触发来源。FPSMatch 会把伤害来源、枪械附加信息和地图玩家数据集中到死亡管线中处理。

## 在伤害阶段修改规则

需要禁止准备期伤害、实现友伤或伤害倍率时，监听 `FPSMapEvent.PlayerEvent.HurtEvent`。先筛选目标地图，然后调整 `amount` 或取消事件：

```java
@SubscribeEvent
public static void onHurt(FPSMapEvent.PlayerEvent.HurtEvent event) {
    if (!(event.getMap() instanceof EliminationMap map)) return;
    if (!map.isCombatActive()) event.setCanceled(true);
}
```

放在 Forge 事件订阅类中。`isCombatActive()` 来自[回合教程](../tutorial/multiple-rounds.md)。不要用 `isValidAttack()` 代替伤害取消：这个方法参与有效攻击来源判定，其返回值不等同于取消原版伤害。

## 在死亡阶段改变玩家表现

`BaseMap.handleDeath(DeathContext)` 的基础实现标记非存活并增加死亡次数。地图可覆盖它切换旁观、清理装备或安排复活：

```java
@Override
public void handleDeath(DeathContext context) {
    super.handleDeath(context);
    context.getDeadPlayer().setGameMode(GameType.SPECTATOR);
}
```

`DeathContext` 位于 `core.map`。调用父实现后，框架会继续处理击杀和助攻；不要再次手动增加这些计数。

## 区分通知时机

| 扩展点 | 适合做什么 |
| --- | --- |
| `HurtEvent` | 改变或阻止伤害 |
| `DeathEvent` | 拦截地图死亡流程；取消方需要自行维持一致状态 |
| `handleDeath` | 实现模式的出局行为并保留基础统计 |
| `KillRecordEvent` | 有条件地阻止击杀统计写入 |
| `KillEvent` | 在击杀确定后播报或奖励 |

底层枪械事件可能补充爆头、穿透等信息，死亡管线会合并这些信息。不要同时监听原版死亡、枪械击杀和地图击杀来重复发放同一种奖励。

## 从出局状态判断胜负

淘汰规则读取 `ServerTeam.getLivingPlayers()`。它返回 UUID 集合，并考虑服务器端的在线与存活状态。不要因为 FPSMatch 恢复了实体生命值，就认为玩家仍然在本轮存活。

胜负规则放在地图或 `RoundRule` 中，击杀事件负责结果消费。这样同时发生的死亡、环境死亡和枪械死亡仍然使用同一套胜负逻辑。

自定义投掷物及伤害来源分类见[投掷物与伤害分类](projectiles.md)。
