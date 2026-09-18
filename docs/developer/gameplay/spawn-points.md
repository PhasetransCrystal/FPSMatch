---
title: 出生点与玩家恢复
description: 配置出生位置，理解分配、传送和恢复状态的职责。
---

# 出生点与玩家恢复

出生点同时包含维度、位置和朝向。`SpawnPointCapability` 属于队伍，`PlayerData` 保存分配给玩家的出生点，地图负责执行传送。

## 为队伍安装出生能力

`TeamData.of("red", 8)` 默认包含 `SpawnPointCapability`。若使用显式能力列表，应把它也放入列表：

```java
addTeam(TeamData.of("red", 8, List.of(SpawnPointCapability.class)));
```

显式列表用于描述队伍需要的组件。出生能力中的坐标通常由地图制作者配置，使同一种模式可用于不同场地。

## 创建出生数据

需要从编辑工具或自己的配置导入位置时，构造 `SpawnPointData`：

```java
SpawnPointData point = new SpawnPointData(
        player.serverLevel().dimension(), player.position(),
        player.getYRot(), player.getXRot());
team.getCapabilityMap().get(SpawnPointCapability.class)
        .ifPresent(cap -> cap.addSpawnPointDataIfAbsent(point));
```

`player` 是位置提供者，`team` 是目标队伍。直接使用能力方法不会替代命令层的安全检查；自己的编辑入口需要保证同维度、位于地图内、脚下有支撑且玩家占用空间无碰撞或流体。现成的出生点工具和命令会处理这些限制。

## 分配与传送

`MapTeams.startNewRound()` 重置比赛存活信息、分配出生点并同步相关能力。它不执行玩家传送。地图中可以接着调用：

```java
getMapTeams().startNewRound();
getMapTeams().getOnline().forEach(this::teleportPlayerToReSpawnPoint);
```

`teleportPlayerToReSpawnPoint()` 优先读取玩家已有分配，缺少时尝试从队伍能力取得一个点。没有可用点时会显示提示；它不会凭空生成安全位置。

## 恢复比赛状态

传送不会自动恢复所有模式状态。原版旁观模式、生命、饥饿、药水效果、物品栏以及自定义经济状态应按玩法处理。`BaseRoundMap.handleRespawn()` 默认标记存活并传送，不会替你把旁观者改回冒险模式。

团队淘汰赛的完整恢复代码见[教程第三章](../tutorial/teams-and-spawns.md)。如果模式会清空玩家物品，先明确离场时是否需要恢复原物品，再设计存储与恢复逻辑。
