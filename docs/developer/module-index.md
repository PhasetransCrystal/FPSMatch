---
title: 源码模块与阅读入口
description: 从框架提供的实际功能查找专题，并区分扩展入口与内部实现。
---

# 源码模块与阅读入口

连续教程只覆盖开发第一个玩法所必需的流程。查找相机、音乐、编辑预览等独立系统时，可以从本页按源码模块进入对应专题。

以下包名均相对于 `net.ptcrys.fpsmatch`。文档对应当前仓库的 Forge 1.20.1 实现；阅读其他版本时，应同时查看该版本的源码签名。

## 玩法与服务端扩展

| 源码模块 | 主要入口 | 阅读顺序 |
| --- | --- | --- |
| `core.map`、`core.match` | `BaseMap`、`BaseRoundMap`、`RoundLifecycle` | [对象关系](architecture.md) → [地图](maps-and-matches.md) → [回合](rounds.md) |
| `core.team` | `MapTeams`、`ServerTeam`、`PlayerData` | [队伍与玩家](teams-and-players.md) → [出生点](gameplay/spawn-points.md) |
| `core.capability`、`common.capability` | 能力工厂、容器、地图和队伍能力 | [能力系统](capabilities.md) → [内置能力](capability/built-ins.md) |
| `common.mapselect` | 房间查询、动作、订阅服务 | [大厅](gameplay/lobby.md) → [房间同步](network/rooms.md) |
| `core.shop`、`common.shop` | 分类、个人商店、槽位和监听模块 | [商店](gameplay/shop.md) → [分类](gameplay/shop-types.md) → [监听模块](gameplay/shop-listeners.md) |
| `core.entity`、`core.item`、`common.entity`、`common.drop` | 投掷物基类、安放炸弹抽象、携带分类 | [投掷物](gameplay/projectiles.md) → [爆破目标](capability/built-ins.md) |
| `core.damage` | 伤害来源分类 | [战斗管线](gameplay/combat.md) → [伤害分类](gameplay/projectiles.md) |
| `common.event`、`common.command` | 地图/队伍事件、注册事件、命令入口 | [事件](events.md) → [命令](commands.md) |

## 客户端扩展

| 源码模块 | 主要入口 | 阅读顺序 |
| --- | --- | --- |
| `common.camera`、`common.client.camera` | `CameraDirector`、`CameraSession`、`SequenceClock` | [第一个镜头](camera/first-scene.md) → [轨迹](camera/rigs.md) → [序列](camera/sequences.md) → [策略](camera/policies-and-overlay.md) → [生命周期](camera/lifecycle.md) |
| `common.client.spec` | `SpectateState`、`SpectateTarget` | [客户端旁观](client/spectator.md) |
| `common.client`、`common.client.screen.hud` | `FPSMGameHudManager`、`IHudRenderer` | [HUD 管理器](client/hud-manager.md) |
| `common.client.tab` | `TabManager`、`TabRenderer` | [Tab 列表](client/tab.md) |
| `common.client.data` | `FPSMClientGlobalData`、客户端快照 | [数据与重置](client/global-data.md) |
| `common.client.music`、`core.music` | 客户端播放器、MVP 默认音乐 | [音乐播放](client/music.md) |
| `common.item.tool`、客户端预览数据 | `FPSMToolItem`、`DebugData`、区域和点位包 | [区域预览与编辑工具](client/area-previews.md) |

## 数据、网络和兼容

| 源码模块 | 主要入口 | 文档 |
| --- | --- | --- |
| `core.data`、`core.persistence` | Codec、SaveHolder、数据管理与迁移 | [Codec](data/codecs.md) · [持久化](persistence.md) · [迁移](data/migration.md) |
| `common.packet` | 模组通道、请求与客户端快照 | [通信](networking.md) · [C2S](network/c2s.md) · [S2C](network/s2c.md) |
| `core.network` | NetworkModule、请求、拦截器 | [HTTP](http-client.md) |
| `core.network.download` | DownloadBuilder、Downloader、文件描述 | [文件下载](network/downloads.md) |
| `compat.gun` | IGunProvider、GunCompatManager | [枪械提供器](integration/gun-provider.md) |
| `compat.tacz`、`compat.spectate` | TaCZ 动画、旁观镜像与表现同步 | [TaCZ 客户端适配](integration/tacz-client.md) |
| `compat.kubejs` | 脚本绑定 | [KubeJS](integration/kubejs.md) |
| `bukkit` | 混合端插件桥接与事件 | [Bukkit](integration/bukkit.md) |

## 公共工具

| 源码模块 | 主要入口 | 文档 |
| --- | --- | --- |
| `util` | `FPSMUtil`、`FPSMFormatUtil`、`FPSMCodec`、`MapId`、`ItemKey` | [工具类总览](utilities.md) |
| `util.hash` | `FileHashUtil`、`HashAlgorithm` | [出生点与文件哈希](utilities/files-spawns.md) |
| `core.persistence.PersistenceUtils` | 文件名清理、缓存目录和目录创建 | [标识、Codec 与格式化](utilities/identifiers-and-codecs.md) |
| `common.item.tool`、`common.item.tool.handler` | `FPSMToolItem`、`EditToolItem`、点击上下文与动作 | [地图编辑工具](utilities/edit-tools.md) |

客户端工具（`RenderUtil`、`PreviewColorUtil`）见[客户端渲染](utilities/rendering.md)。

## 阅读内部实现时

[当前源码目录](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch)还包含用于支撑这些功能的注册、渲染与适配代码。它们不一定是独立的扩展系统：

- `mixin` 与 accessor：连接原版或兼容模组行为。相机、Tab 和旁观已经有上层入口，通常从对应管理器扩展。
- `common.client.screen`、渲染和粒子实现：框架自带界面的具体布局与表现。新增 HUD 用渲染器接口，新增 Screen 继续使用 Forge 客户端流程。
- `common.attributes`、`common.effect`、`common.gamerule`、`common.sound` 及物品注册：内置游戏内容。新增内容依循 Forge 注册规则，再接入 FPSMatch 的战斗、资源或物品专题。
- `compat.cloth`、`compat.impl`：已有配置界面与集成实现。它们不代表一个额外的通用 UI 框架。
- `core.function`、`util` 和哈希辅助类：供具体模块使用的工具，调用时以使用它们的模块生命周期为准。

本页索引覆盖可识别的功能模块；它不是逐类 API 手册。新增一个可独立使用的管理器或扩展接口时，应同时补入本页、[API 索引](api-reference.md)和相应侧栏专题，说明入口、调用时机、数据归属以及与其他系统的关系。
