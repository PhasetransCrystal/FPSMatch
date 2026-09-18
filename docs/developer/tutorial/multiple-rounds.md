---
title: 5. 改为多回合比赛
description: 使用 BaseRoundMap 与 RoundLifecycle 组织准备、战斗和结算。
---

# 5. 改为多回合比赛

本章把一局比赛改为多回合比赛：准备 5 秒，战斗最多 90 秒，结算展示 5 秒；每轮胜者加 1 分，先到 5 分的一方赢得比赛。

## 切换地图基类

将 `BaseMap` 导入替换为 `net.ptcrys.fpsmatch.core.map.BaseRoundMap`，把类声明改为：

```java title="EliminationMap.java（类声明与新增字段）"
public final class EliminationMap
        extends BaseRoundMap<ServerTeam, EliminationMap.EndReason> {
    public enum EndReason { ELIMINATION, TIMEOUT }
    private boolean matchComplete;
}
```

这是类声明片段，原有队伍字段、构造器、出生辅助方法和死亡处理均保留。`ServerTeam` 表示回合胜者，`EndReason` 表示结束原因。`RoundLifecycle`、`RoundResult` 与 `RoundPhase` 位于 `core.match`。

## 定义每轮规则


```java title="EliminationMap.java"
private Optional<RoundResult<ServerTeam, EndReason>> eliminationResult() {
    boolean redAlive = !red.getLivingPlayers().isEmpty();
    boolean blueAlive = !blue.getLivingPlayers().isEmpty();
    if (redAlive && blueAlive) return Optional.empty();
    ServerTeam winner = redAlive == blueAlive ? null : redAlive ? red : blue;
    return Optional.of(new RoundResult<>(winner, EndReason.ELIMINATION));
}
```

```java title="EliminationMap.java"
protected RoundLifecycle<ServerTeam, EndReason> buildRoundLifecycle() {
    return lifecycleBuilder()
            .waitingTicks(5 * 20)
            .roundTicks(90 * 20)
            .roundEndTicks(5 * 20)
            .addRule(lifecycle -> eliminationResult())
            .timeoutResult(() -> new RoundResult<>(null, EndReason.TIMEOUT))
            .build();
}
```

导入 `java.util.Optional`。规则返回空值表示继续本轮；返回 `RoundResult` 表示结束本轮，`winner == null` 表示平局。

`lifecycleBuilder()` 已把回调绑定到地图方法。等待和结算阶段不会评估淘汰规则，只有活动回合执行规则检查。

## 准备下一轮

替换 `start()`，新增 `startNewRound()`：

```java title="EliminationMap.java"
public boolean start() {
    if (isStart() || !canBegin() || !super.start()) return false;
    isStart = true;
    red.setScores(0);
    blue.setScores(0);
    matchComplete = false;
    startNewRound();
    announce(Component.translatable("message.elimination.started"));
    return true;
}
```

```java title="EliminationMap.java"
public void startNewRound() {
    if (!isStart()) return;
    preparePlayers();
    rebuildRoundLifecycle();
}
```

玩家在准备阶段开始时就恢复状态并回到出生点。`rebuildRoundLifecycle()` 创建新的计时器，进入 `WAITING` 阶段。阶段由 `BaseRoundMap.tick()` 推进，不需要自己重复调用。

## 限制准备阶段的伤害

阶段只决定何时运行规则，不会自动禁止伤害。地图提供一个查询方法：

```java title="EliminationMap.java"
public boolean isCombatActive() {
    return isStart() && roundLifecycle != null
            && roundLifecycle.phase() == RoundPhase.ACTIVE_ROUND;
}
```

在已有 `EliminationEvents` 类中添加：

```java title="EliminationEvents.java"
@SubscribeEvent
public static void protectWaitingPlayers(net.ptcrys.fpsmatch.common.event.FPSMapEvent.PlayerEvent.HurtEvent event) {
    if (event.getMap() instanceof EliminationMap map && !map.isCombatActive()) {
        event.setCanceled(true);
    }
}
```

本例允许准备阶段移动，取消准备与结算期间的地图伤害事件。需要锁定位置时，应另外实现移动限制，而不是仅修改等待时长。

## 计分与整局结束


```java title="EliminationMap.java"
protected void onRoundEnd(RoundResult<ServerTeam, EndReason> result) {
    if (result.winner() == null) {
        announce(Component.translatable("message.elimination.draw"));
    } else {
        ServerTeam winner = result.winner();
        winner.setScores(winner.getScores() + 1);
        announce(Component.translatable("message.elimination.round_win", winner.getName()));
    }
}
```

```java title="EliminationMap.java"
protected void onNextRoundRequested() {
    if (red.getScores() >= 5 || blue.getScores() >= 5) {
        matchComplete = true;
        victory();
    } else {
        startNewRound();
    }
}
```

```java title="EliminationMap.java"
public boolean victoryGoal() { return isStart() && matchComplete; }
```

```java title="EliminationMap.java"
public void victory() {
    if (!isStart()) return;
    isStart = false;
    matchComplete = false;
    announce(Component.translatable("message.elimination.result", red.getScores(), blue.getScores()));
    super.victory();
}
```

替换上一章的 `victoryGoal()` 和 `victory()`。回合结束时只加分，结算展示时间结束后才判断是否结束整场比赛。如果 `victoryGoal()` 直接检查比分，框架会在下一 tick 提前结束比赛，跳过最后一轮的结算等待。

在现有 `reset()` 开头加上 `matchComplete = false;`，其余玩家恢复代码保持不变。`super.reset()` 还会清空回合生命周期。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/05) · 上一章：[一局胜负](first-match.md) · 下一章：[地图设置](configurable-rules.md)
