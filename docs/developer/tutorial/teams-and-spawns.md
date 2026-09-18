---
title: 3. 添加队伍与出生流程
description: 添加红蓝队伍、出生点与比赛开始逻辑。
---

# 3. 添加队伍与出生流程

本章继续修改 `EliminationMap`，添加红蓝两队，并让比赛开始时的玩家进入各自出生点。保留上一章的模组入口和地图注册类。

## 创建队伍

在地图类中添加两个字段，在构造器中初始化：

```java title="EliminationMap.java"
private final ServerTeam red;
private final ServerTeam blue;

public EliminationMap(ServerLevel level, String name, AreaData area) {
    super(level, name, area);
    red = addTeam(TeamData.of("red", 8));
    blue = addTeam(TeamData.of("blue", 8));
    allowJoinInProgress.set(false);
    readyStartEnabled.set(false);
}
```

`ServerTeam` 与 `TeamData` 位于 `net.ptcrys.fpsmatch.core.team`。前者保存运行时队伍，后者描述名称、容量和能力。`TeamData.of(name, limit)` 会包含出生点能力。

教程暂时由管理员启动比赛，因此关闭全员准备开始，并禁止比赛中新增参赛者。大厅开始策略见[大厅与加入策略](../gameplay/lobby.md)。

## 配置出生位置

站在地图区域内、脚下有支撑且头部无遮挡的位置，分别为红蓝队执行：

```text
/fpsm map modify elimination training team teams red capability spawnpoints add
/fpsm map modify elimination training team teams blue capability spawnpoints add
```

命令读取执行者当时的位置与朝向，因此应在两个不同位置执行。多人队伍可以重复添加多个出生点。

将玩家加入指定队伍使用 `team teams <队名> players <玩家> join`：

```text
/fpsm map modify elimination training team teams red players Alice join
/fpsm map modify elimination training team teams blue players Bob join
```

替换为实际玩家名。加入队伍建立比赛身份，传送到本轮出生点由下面的开始流程负责。

## 决定比赛何时可以开始

添加 `SpawnPointCapability` 的导入（`net.ptcrys.fpsmatch.common.capability.team`），以及 `Component` 和 `GameType`。在类中添加以下辅助方法：

```java title="EliminationMap.java"
private boolean hasSpawns(ServerTeam team) {
    return team.getCapabilityMap().get(SpawnPointCapability.class)
            .map(cap -> !cap.getSpawnPointsData().isEmpty()).orElse(false);
}
```

```java title="EliminationMap.java"
private boolean canBegin() {
    if (red.getOnline().isEmpty() || blue.getOnline().isEmpty()) {
        announce(Component.translatable("message.elimination.players"));
        return false;
    }
    if (!hasSpawns(red) || !hasSpawns(blue)) {
        announce(Component.translatable("message.elimination.spawns"));
        return false;
    }
    return true;
}
```

这里检查在线人数，而不是包括断线预留位的队伍总人数。提示使用语言键；配套的 `resources/assets/elimination_addon/lang/zh_cn.json` 提供文案。

## 准备玩家


```java title="EliminationMap.java"
private void preparePlayers() {
    getMapTeams().startNewRound();
    getMapTeams().getOnline().forEach(player -> {
        player.setCamera(player);
        player.setGameMode(GameType.ADVENTURE);
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5);
        player.clearFire();
        player.removeAllEffects();
        player.fallDistance = 0;
        teleportPlayerToReSpawnPoint(player);
    });
}
```

```java title="EliminationMap.java"
private void announce(Component text) {
    getMapTeams().getOnlineWithSpec().forEach(player -> player.sendSystemMessage(text));
}
```

`MapTeams.startNewRound()` 更新存活标记并分配出生点，但不负责实际传送。`preparePlayers()` 将玩家恢复为冒险模式、恢复基本状态，再调用传送方法。这里保留物品栏，允许先使用已有的原版装备；后续可用[装备系统](../gameplay/equipment-and-shop.md)接管发放。

## 接入开始与重置


```java title="EliminationMap.java"
public boolean start() {
    if (isStart() || !canBegin() || !super.start()) return false;
    isStart = true;
    preparePlayers();
    announce(Component.translatable("message.elimination.started"));
    return true;
}
```

```java title="EliminationMap.java"
public void reset() {
    isStart = false;
    red.setScores(0);
    blue.setScores(0);
    getMapTeams().getOnline().forEach(player -> {
        player.setCamera(player);
        player.setGameMode(GameType.ADVENTURE);
    });
    super.reset();
}
```

```java title="EliminationMap.java"
public boolean cleanupMap() {
    if (!super.cleanupMap()) return false;
    reset();
    return true;
}
```

这些方法均覆盖父类方法。`super.start()` 发布可取消的开始事件，返回成功后才将 `isStart` 设为 `true`。`reset()` 保留队伍成员和出生配置，只清理本例的比赛状态。这里不调用会清空名单的 `MapTeams.reset()`。

`cleanupMap()` 先让父类发布可取消的清理事件，得到允许后再重置。它本身不等同于从核心删除地图实例。

开始与重置使用框架的地图操作：

```text
/fpsm map modify elimination training debug start
/fpsm map modify elimination training debug reset
```

此时 `victoryGoal()` 仍返回 `false`。下一章会根据玩家出局状态结束比赛。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/03) · 上一章：[地图注册](first-map.md) · 下一章：[一局胜负](first-match.md)
