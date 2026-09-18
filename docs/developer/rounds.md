---
title: 回合系统
description: 使用 BaseRoundMap、RoundLifecycle、RoundRule 与 RoundContext 构建阶段式玩法。
---

# 回合系统

固定回合玩法继承 `BaseRoundMap<W, R>`。`W` 是回合胜者类型，`R` 是结束原因类型；常见组合为 `ServerTeam` 与枚举。

```java
public final class ArenaMap
        extends BaseRoundMap<ServerTeam, ArenaMap.EndReason> {
    public enum EndReason { ELIMINATION, TIMEOUT, OBJECTIVE }
}
```

## 阶段模型

```text
WAITING -> ACTIVE_ROUND -> ROUND_END_WAITING -> 请求下一轮
                  |
                PAUSED
```

`waitingTicks` 是准备阶段（冻结玩家需由模式另行实现），`roundTicks` 是活动回合上限，`roundEndTicks` 是结算展示时间。20 tick 通常等于 1 秒。

```java
@Override
protected RoundLifecycle<ServerTeam, EndReason> buildRoundLifecycle() {
    return lifecycleBuilder()
            .waitingTicks(5 * 20)
            .roundTicks(90 * 20)
            .roundEndTicks(5 * 20)
            .addRule(this::evaluateElimination)
            .timeoutResult(() ->
                    new RoundResult<>(null, EndReason.TIMEOUT))
            .build();
}
```

优先使用 `lifecycleBuilder()`，它已经绑定地图的 `onRoundStart`、`onRoundEnd`、`onNextRoundRequested`、`onWaitingTick` 和 `onRoundTick`。直接在 Builder 再设置同一个回调会替换绑定，而不是追加。

## 规则

无上下文规则实现 `RoundRule<W,R>`；需要地图快照时实现 `RoundRuleWithContext<W,R>`。

```java
private Optional<RoundResult<ServerTeam, EndReason>> evaluateElimination(
        RoundLifecycle<ServerTeam, EndReason> lifecycle) {
    boolean redAlive = !red.getLivingPlayers().isEmpty();
    boolean blueAlive = !blue.getLivingPlayers().isEmpty();
    if (redAlive && blueAlive) return Optional.empty();
    return Optional.of(new RoundResult<>(
            redAlive == blueAlive ? null : redAlive ? red : blue,
            EndReason.ELIMINATION));
}
```

规则按注册顺序求值，第一个返回结果的规则结束本轮。因此目标物引爆、拆除、全灭和超时的优先级应通过顺序明确表达。

## 上下文

`RoundContext` 是标记接口，适合每 tick 构造只读快照：

```java
private record ArenaContext(
        int redLiving,
        int blueLiving,
        boolean objectiveComplete) implements RoundContext {}

@Override
protected RoundContext createRoundContext() {
    return new ArenaContext(
            red.getLivingPlayers().size(),
            blue.getLivingPlayers().size(),
            objectiveComplete);
}
```

不要把需要持久化的唯一状态只放在临时 Context 中。

## 生命周期回调

- `onRoundStart()`：传送、恢复生命、发放装备。
- `onRoundEnd(result)`：增加比分并广播结果。
- `onNextRoundRequested()`：在结算展示结束后，达到整局目标则 `victory()`，否则 `startNewRound()`。整局胜利条件应与此时机一致，避免每 tick 的胜利检查提前结束最后一轮。
- `shouldAdvanceRoundLifecycle()`：实现全局暂停或热身拦截。
- `rebuildRoundLifecycle()`：用当前设置重新建立下一轮计时器与规则。

```java
@Override
public void startNewRound() {
    getMapTeams().startNewRound();
    rebuildRoundLifecycle();
}
```

运行时可查询 `phase()`、`phaseElapsedTicks()`、`roundElapsedTicks()`、`lastResult()` 和 `isPaused()`。完整组合见[团队淘汰赛教程](game-mode-tutorial.md)。

## 进一步组织回合

[结束规则与上下文](round/rules.md)介绍多个结束条件的优先级；[阶段回调与暂停](round/phases-and-pause.md)说明暂停影响哪些时钟，以及哪些玩家行为仍需由模式控制。
