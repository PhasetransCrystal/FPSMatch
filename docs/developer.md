---
title: 开发者指南
description: 为具有 Java 与 Forge 基础的开发者介绍 FPSMatch 的玩法、扩展和客户端开发。
---

# 开发者指南

FPSMatch 为团队竞技玩法提供地图实例、队伍、玩家数据、回合驱动和扩展接口。你可以用它开发一个独立游戏模式，也可以为已有模式补充规则、装备、界面或外部服务。

本文档假设你已了解 Java、Forge 模组入口、事件订阅和 Gradle 项目结构。示例使用 **Minecraft 1.20.1、Forge 47.4.10、Java 17**，与当前仓库源码配套。示例工程直接依赖该源码；旧发布版本不一定具有相同的 API。

## 第一个游戏模式

初次使用 FPSMatch，建议从[团队淘汰赛教程](developer/game-mode-tutorial.md)开始。教程贯穿同一个 `elimination_addon` 工程：先注册空地图，再添加队伍、出生流程、胜负和回合，最后加入设置、能力和比分 HUD。

每章只引入当前需要的概念。代码块会指出文件或放置位置，需要保留的上一章代码会明确说明。仓库的 `examples/elimination/chapters/01` 至 `08` 保存各章完整源码。

## 按功能阅读

| 开发任务 | 从这里开始 |
| --- | --- |
| 理解游戏类型、地图实例和比赛状态 | [对象关系](developer/architecture.md) |
| 创建、开始、结束和复用地图 | [地图生命周期](developer/maps-and-matches.md) |
| 组织队伍、准备大厅、出生和重连 | [队伍与玩家](developer/teams-and-players.md) |
| 添加计时阶段、结束规则和暂停 | [回合系统](developer/rounds.md) |
| 暴露地图规则和复杂配置 | [设置系统](developer/settings.md) |
| 编写可复用地图或队伍功能 | [能力系统](developer/capabilities.md) |
| 监听伤害、死亡、结算，添加命令 | [事件](developer/events.md)与[命令](developer/commands.md) |
| 保存配置、能力或独立业务数据 | [持久化](developer/persistence.md) |
| 同步比分、处理客户端操作 | [网络通信](developer/networking.md) |
| 编写 HUD、配置资源和接入其他模组 | [客户端与集成](developer/client-and-compatibility.md) |

除了玩法主线，框架还提供独立的客户端和扩展模块。已有明确目标时，可以直接进入：

| 开发任务 | 专题 |
| --- | --- |
| 开场、死亡或地图预览镜头 | [相机与演出](developer/camera/first-scene.md) |
| 按游戏类型绘制 HUD 与 Tab | [HUD 管理器](developer/client/hud-manager.md) · [Tab](developer/client/tab.md) |
| 读取客户端状态、处理旁观 | [客户端数据](developer/client/global-data.md) · [旁观视角](developer/client/spectator.md) |
| 播放音乐和配置 MVP 曲目 | [音乐](developer/client/music.md) |
| 开发房间列表和详情界面 | [房间查询与订阅](developer/network/rooms.md) |
| 定制商品分类、组内装备逻辑 | [商店分类](developer/gameplay/shop-types.md) · [监听模块](developer/gameplay/shop-listeners.md) |
| 添加投掷物、爆破目标和编辑预览 | [投掷物](developer/gameplay/projectiles.md) · [内置能力](developer/capability/built-ins.md) · [编辑预览](developer/client/area-previews.md) |
| 下载文件或适配 TaCZ 表现 | [下载](developer/network/downloads.md) · [TaCZ 动画](developer/integration/tacz-client.md) |

完整的包与专题对应关系见[源码模块索引](developer/module-index.md)。

## 示例中的三个名字

`elimination_addon` 是 Forge 模组 ID，用于入口、依赖声明和资源命名空间。`elimination` 是 FPSMatch 游戏类型 ID，用于找到地图工厂。`training` 是某一张地图的 ID，用于区分区域、配置和比赛状态。这三个名字承担不同职责，后续章节会持续使用它们。

已经熟悉框架时，可以从 [API 索引](developer/api-reference.md)按类型寻找专题。
