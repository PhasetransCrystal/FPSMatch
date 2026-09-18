---
title: 地图与比赛生命周期
description: 注册 BaseMap，管理开始、tick、胜利、重置、清理以及玩家加入和出生。
---

# 地图与比赛生命周期

`BaseMap` 是一个地图实例和整场比赛的宿主。游戏类型注册表保存三参数工厂：

```java
event.registerGameType("my_mode", MyModeMap::new);

public MyModeMap(ServerLevel level, String mapName, AreaData area) {
    super(level, mapName, area);
}
```

`getGameType()` 必须返回稳定 ID；`victoryGoal()` 是每 tick 调用的整局胜利条件。

## 生命周期

| API | 作用 |
| --- | --- |
| `load()` | 加载地图配置并发布 `LoadEvent` |
| `start()` | 发布可取消的 `StartEvent`，重置时钟与准备状态 |
| `mapTick()` | 框架统一 tick 入口，不要手动重复调用 |
| `tick()` | 子类地图逻辑扩展点 |
| `victory()` | 发布 `VictoryEvent` |
| `reset()` | 重置时钟并发布 `ResetEvent` |
| `reload()` | 可取消的重载入口 |
| `cleanupMap()` | 可取消的清理入口 |

:::warning 必须自行维护 `isStart`
当前版本的 `BaseMap.start()` 不会把 `isStart` 设为 `true`，`reset()` 也不会设为 `false`。模式必须显式维护，否则 `BaseRoundMap` 不会推进，或胜利会在后续 tick 重复发布。
:::

```java
@Override
public boolean start() {
    if (isStart() || !super.start()) return false;
    this.isStart = true;
    return true;
}

@Override
public boolean victoryGoal() {
    return isStart() && score >= targetScore.get();
}

@Override
public void victory() {
    if (!isStart()) return;
    this.isStart = false;
    super.victory();
}

@Override
public void reset() {
    this.isStart = false;
    super.reset();
}
```

## 玩家与大厅

- `join(ServerPlayer)` 自动选择人数最少的普通队伍。
- `join(String, ServerPlayer)` 加入指定队伍并返回 `JoinTeamResult`。
- `leave(ServerPlayer)` 触发离开事件并清理绑定。
- `toggleReady`、`setReady`、`isReady` 和 `getReadyPlayers` 管理准备状态。
- `canAutoStart()` 与 `canReadyStart()` 定义大厅开始条件。
- `allowJoinInProgress` 设置决定是否允许中途加入。

加入与离开事件可以被取消。调用后必须检查 `JoinTeamResult.isSuccess()`，不要假设玩家一定加入成功。

## 出生与物品

`TeamData.of(name, limit)` 默认为队伍声明 `SpawnPointCapability`，但地图制作者仍需配置实际出生点。

```java
getMapTeams().startNewRound();
getMapTeams().getOnline().forEach(player -> {
    handleRespawn(player); // BaseRoundMap
    clearInventory(player);
    syncInventory(player);
});
```

可用 `teleportPlayerToReSpawnPoint`、`teleportToPoint`、`clearInventory` 与 `syncInventory` 组合回合开始流程。没有出生点时应保留错误提示，不要传送到任意默认坐标。

## 战斗扩展点

模式可覆盖 `handleDeath(DeathContext)`、`resolveDeathItem(...)`、`isValidAttack(...)`、`areCombatTeammates(...)` 和 `creditAssist(...)`。覆盖 `handleDeath()` 时先调用父实现保留死亡统计；其他方法按各自的返回值契约组合。`isValidAttack()` 参与攻击来源判断，不等于取消伤害。回合胜负统一交给规则处理。

继续阅读[回合系统](rounds.md)、[队伍与玩家](teams-and-players.md)和[事件系统](events.md)。

## 深入阅读

- [大厅与加入策略](gameplay/lobby.md)：准备、自动开始、换队和比赛中加入。
- [出生点与恢复](gameplay/spawn-points.md)：数据分配、传送和玩家状态。
- [伤害与出局](gameplay/combat.md)：死亡管线和统计职责。
- [旁观与重连](gameplay/spectating-and-reconnect.md)：身份、预留数据和离场。
