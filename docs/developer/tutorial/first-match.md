---
title: 4. 实现一局胜负
description: 处理玩家出局、决定胜者并保留基础统计。
---

# 4. 实现一局胜负

现在双方可以进入地图。本章让一队全部出局时结束比赛，并为没有分出胜负的情况设置 90 秒上限。

## 玩家生命值与比赛存活状态

FPSMatch 在比赛中接管死亡流程。它可以取消原版死亡并恢复玩家生命值，再通过死亡管线更新比赛数据。因此，淘汰规则应查询队伍的存活玩家，不能只读取 Minecraft 的生命值。

本章使用原版旁观模式表示“本局出局”。玩家保留队伍身份，但不会继续参加战斗。

## 覆盖死亡处理

在 `EliminationMap.java` 导入 `net.ptcrys.fpsmatch.core.map.DeathContext`，添加：

```java title="EliminationMap.java"
public void handleDeath(DeathContext context) {
    super.handleDeath(context);
    context.getDeadPlayer().setGameMode(GameType.SPECTATOR);
}
```

先调用父实现，保留死亡次数和非存活标记，然后切换游戏模式。无需在这里手动增加击杀或助攻，框架会在死亡管线中继续处理。

## 判断比赛结束

替换上一章始终为 `false` 的 `victoryGoal()`：

```java title="EliminationMap.java"
public boolean victoryGoal() {
    return isStart() && (red.getLivingPlayers().isEmpty()
            || blue.getLivingPlayers().isEmpty() || getElapsedMatchTicks() >= 90 * 20);
}
```

框架每 tick 调用这一方法。`isStart()` 避免刚创建地图、没人在线或比赛已结束时重复触发结算。这里的 90 秒以服务器 tick 计时，稍后会改成地图设置。

## 发布结算


```java title="EliminationMap.java"
public void victory() {
    if (!isStart()) return;
    boolean redAlive = !red.getLivingPlayers().isEmpty();
    boolean blueAlive = !blue.getLivingPlayers().isEmpty();
    ServerTeam winner = redAlive == blueAlive ? null : redAlive ? red : blue;
    isStart = false;
    if (winner != null) winner.setScores(1);
    announce(winner == null ? Component.translatable("message.elimination.draw")
            : Component.translatable("message.elimination.win", winner.getName()));
    super.victory();
}
```

只有一边仍有存活玩家时，该队获胜；两边都存活的超时，以及两边都出局，均为平局。先关闭开始状态，再发布 `VictoryEvent`，下一个 tick 就不会再次结算。

胜利不会立即清空比分或名单。事件监听器仍能读取结算数据，玩家也能看到比赛结果。管理员调用上一章的重置操作后，可以再次开始。

## 回到同一个地图对象

保留上一章的 `reset()` 和 `preparePlayers()`。重置清除旧比分，下一次开始将出局玩家恢复为冒险模式，再分配出生位置。一场比赛结束不需要重新创建地图。

下一章保留这些玩家操作，将单局驱动替换为连续的回合流程。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/04) · 上一章：[队伍与出生](teams-and-spawns.md) · 下一章：[多回合比赛](multiple-rounds.md)
