---
title: 内置能力与爆破目标
description: 选择已经注册的地图、队伍能力，并理解爆破和结束位置的职责。
---

# 内置能力与爆破目标

FPSMatch 在 `FPSMCapabilityRegister` 中注册一组内置能力。注册表示能力工厂可供使用，地图和队伍仍需将需要的能力加入自己的容器。能力取得方式和工厂流程见[能力系统](../capabilities.md)。

## 队伍能力

| 能力 | 提供的功能 | 进一步阅读 |
| --- | --- | --- |
| `SpawnPointCapability` | 队伍出生点管理 | [出生点](../gameplay/spawn-points.md) |
| `StartKitsCapability` | 开局装备配置和发放 | [装备套件](../gameplay/kits.md) |
| `ShopCapability` | 队伍商店和玩家购买状态 | [商店](../gameplay/shop.md) |
| `CompensationCapability` | 经济补偿相关能力 | [装备与经济入口](../gameplay/equipment-and-shop.md) |
| `PauseCapability` | 队伍暂停相关状态 | [暂停与阶段](../round/phases-and-pause.md) |
| `TeamSwitchRestrictionCapability` | 限制名单与登录后的记分板队伍恢复 | [旁观与重连](../gameplay/spectating-and-reconnect.md) |

`TeamSwitchRestrictionCapability` 提供 `addUnableToSwitchPlayer(UUID)`、`removeUnableToSwitchPlayer(UUID)` 和 `isUnableToSwitch(UUID)`。自己的换队入口需要结合这些状态处理规则，不能假定加入名单就会拦截任意第三方换队操作。其销毁流程会清空名单。

## 经济补偿与暂停额度

`CompensationCapability` 保存一个同步的补偿因子。`setFactor()`、`add()` 和 `reduce()` 修改它，默认将因子限制在 0 到 4，`reset()` 归零。`withSetter()` 可以改变取值策略。它本身不计算获胜奖励或给玩家加钱；玩法在回合结算时根据因子计算金额，再写入玩家商店数据。

`PauseCapability` 保存已用暂停次数与待处理暂停请求。`canPause()` 判断次数小于 2 且没有待处理请求，`addPause()` 在允许时增加次数并标记请求。`setNeedPause()`、`setPauseTime()` 修改同步状态，`resetPauseIfNeed()` 则清除待处理请求并退回这一次额度。

这个能力不会自行调用回合时钟的 `setPaused()`。玩法需要消费暂停请求、切换回合阶段并在恢复时更新能力状态。注意当前 `addPause()` 不会直接置脏；需要即时同步时，应通过会置脏的 setter 更新状态或安排明确的能力同步。

## 爆破区域能力

`common.capability.map.DemolitionModeCapability` 保存爆破区域、进攻方队伍身份，并关联当前 `BlastBombEntity`。创建爆破玩法时先给地图加入能力，再通过容器取得它。

下面是已有 `BaseMap map`、`ServerTeam attackers` 和 `AreaData bombArea` 的服务端方法片段：

```java
map.getCapabilityMap().get(DemolitionModeCapability.class).ifPresent(cap -> {
    cap.setDemolitionTeam(attackers);
    boolean added = cap.addBombArea(bombArea);
    if (!added) {
        // 将失败结果反馈给当前配置操作的调用者。
    }
});
```

`addBombArea()`、`updateBombArea()` 和 `removeBombArea()` 会判断地图是否已开始，并检查区域边界或索引；成功后同步区域。使用这些修改方法可以保留检查与同步行为，不要直接改 `getBombAreaData()` 返回的列表来绕开它们。

`setDemolitionTeam()` 保存队伍的固定名称。`checkCanPlacingBombs(team)` 判断传入名称是否属于进攻方，`checkPlayerIsInBombArea(player)` 判断玩家是否位于放置区域。

## 炸弹实体与回合结果

`setBombEntity(bomb)` 关联本轮实体，`blastState()` 返回实体状态，没有实体时为 `BlastBombState.NONE`。`setBombEntity(null)` 还会丢弃原来未移除的实体，因此它同时承担清理操作。

`BlastBombEntity` 抽象要求提供`getOwner()`、`getDemolisher()`、`getState()` 和 `isDeleting()` 等接口。具体炸弹实体仍需处理安放、计时、拆除、数据同步和渲染；能力中的区域数据不能代替这些逻辑。把爆炸或拆除结果接入[回合结束规则](../round/rules.md)，并在新回合或地图清理时释放实体引用。

区域和进攻队伍通过能力的 `Savable` 数据保存，运行中的炸弹实体不是静态地图配置。能力同步入口包括向单个玩家或全部玩家发送爆破区域。

## 结束位置配置

`GameEndTeleportCapability` 保存一个 `SpawnPointData`，提供 `setPoint()`、`getPoint()`、Codec 和配置命令。初始点位可能为空。

当前类没有比赛结束监听或自动传送实现。因此仅挂载它不会让玩家在结算后自动回大厅；玩法需要在自己的结束流程取得点位并调用传送逻辑。这个能力提供的是可保存的目的地，传送时机由玩法决定。

源码入口：[common/capability](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/capability)。
