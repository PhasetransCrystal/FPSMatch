---
title: 创建团队淘汰赛
description: 通过八章连续实现同一个团队淘汰赛模组。
---

# 创建团队淘汰赛

本教程实现红蓝两队的团队淘汰赛：一队全部出局时，另一队赢得该轮；双方同时出局或达到时间上限时判平；先达到目标分的一方赢得比赛。

你会先实现只打一局的版本，再把它改造成多回合模式。这样可以先理解玩家怎样加入、出生和出局，然后再引入回合状态机。

## 章节顺序

| 章节 | 本章新增内容 | 工程快照 |
| --- | --- | --- |
| [1. 接入项目](getting-started.md) | 依赖、模组入口、资源目录 | `01` |
| [2. 注册第一张地图](tutorial/first-map.md) | 游戏类型与地图工厂 | `02` |
| [3. 添加队伍与出生流程](tutorial/teams-and-spawns.md) | 红蓝队伍、开始条件、传送 | `03` |
| [4. 实现一局胜负](tutorial/first-match.md) | 出局、平局、胜利和重置 | `04` |
| [5. 改为多回合比赛](tutorial/multiple-rounds.md) | 准备、战斗、结算、下一轮 | `05` |
| [6. 添加地图设置](tutorial/configurable-rules.md) | 目标分和回合时长 | `06` |
| [7. 提取可复用能力](tutorial/first-capability.md) | 每张地图独立的回合播报 | `07` |
| [8. 同步比分并绘制 HUD](tutorial/score-hud.md) | 网络包、客户端状态、覆盖层 | `08` |

## 使用示例工程

[示例工程](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination)与 FPSMatch 源码放在同一个仓库内。`chapters` 保存各章的 Java 源码，`resources` 保存共同使用的模组元数据和语言文件。Gradle 的 `chapter` 属性决定本次使用哪个章节；默认使用 `08`。

阅读时沿用同一个 `EliminationMap` 文件。后续章节说“替换方法”时，替换上一章同名方法；说“添加方法”时，放入现有类中。各章快照是该阶段的完整工程，不要把多个章节的同名类一起加入源集。

## 示例的玩法边界

教程先聚焦比赛规则，装备可以使用原版物品。它没有提供枪械包、商店或专门的旁观摄像机。准备阶段允许玩家移动，但禁止受伤；这与把玩家锁在出生位置的冻结机制不同。

完成后，可以继续阅读[出生装备与商店](gameplay/equipment-and-shop.md)、[枪械兼容](integration/gun-provider.md)和[旁观与重连](gameplay/spectating-and-reconnect.md)，把已有模式扩展为自己的玩法。
