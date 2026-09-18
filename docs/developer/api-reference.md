---
title: API 索引
description: 按包和职责快速定位 FPSMatch 核心类型及对应专题文档。
---

# API 索引

按职责查找类型，再进入对应专题了解调用方式。首次接入建议从[连续教程](game-mode-tutorial.md)开始。

## 地图与回合

| 类型 | 用途 | 专题 |
| --- | --- | --- |
| `BaseMap` | 地图实例、整局状态、玩家与设置入口 | [地图生命周期](maps-and-matches.md) |
| `BaseRoundMap<W,R>` | 自动驱动回合 lifecycle | [回合系统](rounds.md) |
| `RoundLifecycle<W,R>` | 阶段、计时、规则和回调 | [回合系统](rounds.md) |
| `RoundRule` / `RoundRuleWithContext` | 回合结束规则 | [回合系统](rounds.md) |
| `RoundContext` / `RoundResult` | 规则输入与结果 | [回合系统](rounds.md) |
| `DeathContext` | 死亡管线输入 | [地图生命周期](maps-and-matches.md) |

## 队伍与玩家

| 类型 | 用途 | 专题 |
| --- | --- | --- |
| `MapTeams` | 地图队伍集合、统计与同步 | [队伍与玩家](teams-and-players.md) |
| `ServerTeam` / `ClientTeam` | 服务端/客户端队伍表示 | [队伍与玩家](teams-and-players.md) |
| `TeamData` | 队伍名称、人数和能力声明 | [队伍与玩家](teams-and-players.md) |
| `PlayerData` | 玩家状态、比赛和回合统计 | [队伍与玩家](teams-and-players.md) |
| `SpawnPointData` | 维度、坐标和朝向 | [地图生命周期](maps-and-matches.md) |

## 扩展系统

| 类型 | 用途 | 专题 |
| --- | --- | --- |
| `Setting<T>` | 每张地图的 Codec 配置 | [设置系统](settings.md) |
| `FPSMCapability<H>` | 能力生命周期与扩展接口 | [Capability](capabilities.md) |
| `MapCapability` / `TeamCapability` | 地图/队伍能力基类 | [Capability](capabilities.md) |
| `CapabilityMap` | 能力容器、保存和同步 | [Capability](capabilities.md) |
| `FPSMCapabilityManager` | 能力工厂注册表 | [Capability](capabilities.md) |
| `FPSMapEvent` / `FPSMTeamEvent` | 地图、玩家和队伍事件 | [事件系统](events.md) |
| `RegisterFPSMCommandEvent` | `/fpsm` 命令扩展 | [命令系统](commands.md) |

## 数据与通信

| 类型 | 用途 | 专题 |
| --- | --- | --- |
| `SaveHolder<T>` / `ISavePort<T>` | Codec 存档定义 | [持久化](persistence.md) |
| `FPSMDataManager` | 数据注册、读取和保存 | [持久化](persistence.md) |
| `NetworkPacketRegister` | Forge 网络包注册 | [Minecraft 网络包](networking.md) |
| `NetworkModule` | 外部 HTTP 请求 | [HTTP 客户端](http-client.md) |
| `IGunProvider` / `GunCompatManager` | 枪械兼容抽象 | [客户端与兼容层](client-and-compatibility.md) |

## 注册事件

```java
RegisterFPSMapEvent          // 游戏类型
RegisterFPSMCommandEvent     // /fpsm 命令
RegisterFPSMSaveDataEvent    // 独立存档类型
RegisterListenerModuleEvent  // 监听模块
```

## 常用 BaseMap API

```text
getGameType        getMapName          getMapArea
getServerLevel     getMapTeams         getCapabilityMap
addTeam            join                leave
start              startNewRound       tick
handleDeath        victoryGoal         victory
reset              reload              cleanupMap
addSetting         settings            saveConfig
syncToClient       sendPacketToAllPlayer
```

相关运行时边界见[线程与资源生命周期](reliability.md)。

## 按具体任务查找

| 类型或任务 | 文档 |
| --- | --- |
| `MapLobbyController`、准备条件 | [大厅与加入](gameplay/lobby.md) |
| `SpawnPointCapability`、`SpawnPointData` | [出生点](gameplay/spawn-points.md) |
| `DeathContext`、`HurtEvent`、`KillEvent` | [战斗管线](gameplay/combat.md) |
| `StartKitsCapability` | [套件发放](gameplay/kits.md) |
| `ShopCapability` | [商店与经济](gameplay/shop.md) |
| `RoundRule`、`RoundContext` | [结束规则](round/rules.md) |
| `RoundPhase`、暂停 | [阶段回调](round/phases-and-pause.md) |
| `Setting.parse`、复杂 Codec | [设置解析](configuration/validation.md) |
| `Savable`、同步接口 | [保存能力](capability/persistence.md) · [同步能力](capability/synchronization.md) |
| `DataFixer` | [版本迁移](data/migration.md) |
| C2S 与 S2C | [请求](network/c2s.md) · [快照](network/s2c.md) |
| Forge GUI 覆盖层 | [HUD](client/hud.md) |
| 脚本与插件 | [KubeJS](integration/kubejs.md) · [Bukkit](integration/bukkit.md) |

## 相机与客户端模块

| 类型 | 专题 |
| --- | --- |
| `CameraDirector`、`CameraSession` | [相机入门](camera/first-scene.md) · [控制权和生命周期](camera/lifecycle.md) |
| `CameraPose`、`CameraFrame`、`CameraRig`、内置 Rig | [镜头轨迹](camera/rigs.md) |
| `CameraSequence`、`SequenceClock` | [序列与标记](camera/sequences.md) |
| `CameraPolicy`、`CameraOverlayEvent` | [输入和场景界面](camera/policies-and-overlay.md) |
| `SpectateState`、`SpectateTarget` | [旁观视角](client/spectator.md) |
| `FPSMGameHudManager`、`IHudRenderer` | [HUD 管理器](client/hud-manager.md) |
| `TabManager`、`TabRenderer` | [Tab 列表](client/tab.md) |
| `FPSMClientGlobalData`、`FPSMClientResetEvent` | [客户端数据](client/global-data.md) |
| `FPSClientMusicManager`、`MvpMusicManager` | [音乐](client/music.md) |
| `DebugData`、`FPSMToolItem`、预览数据包 | [区域和点位预览](client/area-previews.md) |

## 其他扩展模块

| 类型 | 专题 |
| --- | --- |
| `MapRoomQueryService`、`MapRoomActionService`、`MapRoomSyncManager` | [房间查询与订阅](network/rooms.md) |
| `FPSMShop`、`INamedType` | [商店分类](gameplay/shop-types.md) |
| `ListenerModule`、`LMManager` | [监听模块](gameplay/shop-listeners.md) |
| `BaseProjectileEntity`、`BaseProjectileLifeTimeEntity`、`IThrowEntityAble` | [投掷物](gameplay/projectiles.md) |
| `ThrowableRegistry`、`DamageSourceManager` | [携带与伤害分类](gameplay/projectiles.md) |
| `DemolitionModeCapability`、`GameEndTeleportCapability`、`BlastBombEntity` | [内置能力](capability/built-ins.md) |
| `DownloadBuilder`、`Downloader`、`HashDownloadHolder` | [文件下载](network/downloads.md) |
| `GunAnimationController`、`ClientFakeItemManager` | [TaCZ 客户端适配](integration/tacz-client.md) |

## 工具类

| 类型 | 用途 | 专题 |
| --- | --- | --- |
| `FPSMCodec`、`MapId`、`ItemKey` | JSON 编解码、地图标识和物品堆叠键 | [标识、Codec 与格式化](utilities/identifiers-and-codecs.md) |
| `FPSMFormatUtil`、`PersistenceUtils` | 文本格式化、本地化与缓存路径 | [标识、Codec 与格式化](utilities/identifiers-and-codecs.md) |
| `FPSMUtil` | 枪械分类、弹药、库存、掉落和击杀归因 | [武器、库存与击杀](utilities/gameplay.md) |
| `SpawnPointSafety`、`FileHashUtil`、`HashAlgorithm` | 出生点物理检查和文件摘要 | [出生点与文件哈希](utilities/files-spawns.md) |
| `RenderUtil`、`PreviewColorUtil` | 客户端颜色、动画、贴图与预览色 | [客户端渲染](utilities/rendering.md) |
| `FPSMToolItem`、`EditToolItem`、`WorldToolItem` | 地图编辑工具的点击和选择协议 | [地图编辑工具](utilities/edit-tools.md) |

源码包的完整阅读入口见[模块索引](module-index.md)。
