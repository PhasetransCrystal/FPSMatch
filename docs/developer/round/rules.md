---
title: 结束规则与回合上下文
description: 组织多个结束条件，并明确规则优先级和上下文的用途。
---

# 结束规则与回合上下文

`RoundLifecycle` 按顺序检查注册的规则，使用第一个产生的结果结束本轮。规则组合的顺序就是玩法的一部分。

## 从一个条件返回结果

无上下文的规则可以只接受计时器：

```java
private Optional<RoundResult<ServerTeam, EndReason>> elimination(
        RoundLifecycle<ServerTeam, EndReason> lifecycle) {
    boolean redAlive = !red.getLivingPlayers().isEmpty();
    boolean blueAlive = !blue.getLivingPlayers().isEmpty();
    if (redAlive && blueAlive) return Optional.empty();
    ServerTeam winner = redAlive == blueAlive ? null : redAlive ? red : blue;
    return Optional.of(new RoundResult<>(winner, EndReason.ELIMINATION));
}
```

这是地图方法片段，字段和结束原因来自[回合教程](../tutorial/multiple-rounds.md)。双方都出局时返回无胜者结果；不能仅用“两队存活布尔值相等”返回空，否则同时出局会被拖到超时。

## 安排优先级

假设目标完成与全灭可能发生在同一个 tick：

```java
return lifecycleBuilder()
        .addRule(this::objectiveCompleted)
        .addRule(this::elimination)
        .timeoutResult(() -> new RoundResult<>(null, EndReason.TIMEOUT))
        .build();
```

这里的 `objectiveCompleted` 是玩法自行实现的方法，必须返回同一种结果类型。它先返回结果时，全灭规则不再执行。超时判定在规则检查之后，边界 tick 的目标达成可以优先于超时。

## 需要共享快照时使用上下文

多个规则读取同一组场上数据时，在地图中覆盖 `createRoundContext()`，每 tick 生成同一份值：

```java
private record ArenaContext(int redLiving, int blueLiving) implements RoundContext {}

@Override
protected RoundContext createRoundContext() {
    return new ArenaContext(red.getLivingPlayers().size(), blue.getLivingPlayers().size());
}
```

接受两个参数 `(lifecycle, context)` 的规则使用 `RoundRuleWithContext`。根据地图提供的上下文类型读取快照，不要把唯一的可变比赛状态放在每 tick 都会重建的对象中。

## 在结果回调中计分

规则求值最好只做判断。加分、播报和奖励放在 `onRoundEnd(result)` 中，使所有结束原因共享一次结算。下一轮由 `onNextRoundRequested()` 处理；不要在规则内部重建计时器。
