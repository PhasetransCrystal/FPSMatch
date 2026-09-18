---
sidebar_position: 1
title: FPSMatch Wiki
slug: /
---

# FPSMatch Wiki

FPSMatch 是 Minecraft 1.20.1 Forge 的团队竞技 FPS 框架。它负责地图实例、队伍、回合生命周期、经济、商店、HUD、旁观、统计与网络同步；具体玩法由 BlockOffensive 或其他模组注册。

## 从这里开始

| 你的目标 | 推荐入口 | 你会先看到 |
| --- | --- | --- |
| 参加或旁观一场比赛 | [玩家手册](./player) | 地图选择、加入队伍、准备、商店与常见问题 |
| 制作一张可玩的地图 | [地图制作者手册](./mapper) | 连续建图路线、区域、出生点、装备、商店与管理工具 |
| 注册玩法或扩展框架 | [模组开发者手册](./developer) | `BaseMap`、Capability、事件、命令与同步 |

## 先确认环境

- Minecraft `1.20.1`、Forge `47.4.10+`、Java `17`。
- 服务端需要 Kotlin for Forge `4.11.0+`；客户端需要 Modern UI `1.20.1-3.12.0.1`。
- FPSMatch 本身不是完整游戏模式。安装 BlockOffensive 后会提供 `cs` 爆破和 `csdm` 死斗；只安装 FPSMatch 时需要其他模组或脚本注册游戏类型。
- 管理命令默认要求 OP 2。游戏内执行 `/fpsm help` 可查看当前版本实际注册的命令树。

## 三个重要对象

**地图**负责边界、比赛状态与玩家生命周期；**队伍**保存玩家、统计、出生点、商店和开局装备；**能力**把可复用功能挂到地图或队伍上，并可以附带保存、tick、命令与客户端同步逻辑。

## 版本边界

本站内容对应当前源码工作区的 `1.3.0` snapshot。具体模式、能力和命令可能由其他模组追加，遇到差异时以 `/fpsm help` 和服务器日志为准。

## 链接

- [FPSMatch 源码](https://github.com/PhasetransCrystal/FPSMatch)
- [BlockOffensive 源码](https://github.com/PhasetransCrystal/BlockOffensive)
- [FPSMatch Releases](https://github.com/PhasetransCrystal/FPSMatch/releases)
- [GPL v3 许可证](https://github.com/PhasetransCrystal/FPSMatch/blob/master/LICENSE)
