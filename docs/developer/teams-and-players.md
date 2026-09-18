---
title: 队伍、玩家与统计
description: 使用 MapTeams、ServerTeam、TeamData 和 PlayerData 管理参赛状态。
---

# 队伍、玩家与统计

地图持有一个 `MapTeams`。它总是包含观察者队伍，普通队伍由模式构造器添加。

```java
ServerTeam red = addTeam(TeamData.of("red", 8));
ServerTeam blue = addTeam(TeamData.of("blue", 8));
```

需要指定队伍能力时：

```java
addTeam(TeamData.of("red", 8,
        List.of(SpawnPointCapability.class, ShopCapability.class)));
```

相关能力必须先在 `FPSMCapabilityManager` 注册，否则队伍创建时无法实例化。

## MapTeams

| 用途 | API |
| --- | --- |
| 查询队伍 | `getNormalTeams()`、`getTeamsWithSpectator()`、`getTeamByName()` |
| 查询玩家 | `getTeamByPlayer()`、`getPlayerData()`、`getJoinedPlayers()` |
| 加入离开 | `joinTeam()`、`leaveTeam()` |
| 在线与存活 | `getOnline()`、`getOnlineWithSpec()`、`getTeamsLiving()` |
| 新回合 | `resetLivingPlayers()`、`randomSpawnPoints()`、`startNewRound()` |
| 同步 | `sync()`、`broadcast()`、`syncCapabilities()` |

`MapTeams.startNewRound()` 重置存活和临时回合数据、重新分配出生点并同步能力，但实际传送应由模式回合开始逻辑完成。

## ServerTeam

`ServerTeam` 提供名单、在线玩家、存活玩家、比分和队伍能力：

```java
red.getOnline();
red.getLivingPlayers();
red.getPlayerCount();
red.setScores(red.getScores() + 1);
red.getCapabilityMap();
red.sendMessage(Component.translatable("message.my_mode.round_win"), false);
```

修改 roster 后使用已有 `join/leave` API，让记分板、核心绑定和客户端同步保持一致。不要直接修改内部玩家映射。

## PlayerData

`PlayerData` 保存 UUID、显示名、存活状态、出生点以及比赛和回合统计。常用字段包括：

- `kills`、`deaths`、`assists`、`scores`
- `damage`、`utilityDamage`、`flashedEnemies`
- `headshotKills`、`KD`、`healthPercent`
- 对应的临时回合统计

通过 `addKill`、`addDeath`、`addAssist`、`addDamage` 等方法修改，保留 dirty 与 combat revision 语义。`isLivingOnServer()` 同时考虑存活标记与玩家在线状态。

## 断线与重连

`handlePlayerDisconnect` 会把预留玩家标记为离线/非存活，但保留重连恢复所需的数据。不要把临时掉线等同于主动 `leave`。开局前框架会清理真正离线的预留位；进行中的策略由模式和 `allowJoinInProgress` 决定。

`MapTeams.shutdown(Scoreboard)` 会反注册队伍能力并清理记分板，只能在地图或服务器彻底销毁时调用，不能用于每回合重置。

## 队伍功能专题

[出生点](gameplay/spawn-points.md)说明分配和传送；[套件](gameplay/kits.md)说明发放行为；[商店与经济](gameplay/shop.md)区分商品配置和玩家余额；[旁观与重连](gameplay/spectating-and-reconnect.md)说明不同身份的恢复路径。
