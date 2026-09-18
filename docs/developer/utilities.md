---
title: 工具类总览
description: FPSMatch 公共工具类、客户端辅助和编辑工具的入口地图。
---

# 工具类总览

FPSMatch 的 `util` 包提供跨模块复用的“小而稳定”能力。它们不负责创建地图或推进回合，而是把编码、标识、物品分类、伤害归因、颜色计算和文件处理等重复逻辑集中起来。调用工具类前先确认运行端：`FPSMUtil`、`MapId` 和 `SpawnPointSafety` 面向服务端；`RenderUtil`、`PreviewColorUtil` 和部分 `FPSMFormatUtil` 方法只能在客户端使用。

## 按任务查找

| 任务 | 入口 | 说明 |
| --- | --- | --- |
| 保存对象为 JSON | `FPSMCodec` | 使用 Mojang `Codec`，失败时抛出 `DataPersistenceException` |
| 校验地图 ID | `MapId` | 小写字母、数字、`_`、`-`，最多 48 个字符 |
| 检查出生点 | `SpawnPointSafety` | 检查脚下支撑、两格净空和流体 |
| 处理枪械弹药 | `FPSMUtil` | 通过 `IGunProvider` 读写真实弹药和虚拟弹药 |
| 归类或整理物品栏 | `FPSMUtil` | 可注册自定义谓词，再按主武器、手枪、投掷物等槽位整理 |
| 获取击杀者/武器 | `FPSMUtil` | 支持直接伤害、投射物、区域效果和伤害记录回退 |
| 绘制 HUD | `RenderUtil` | 颜色、插值、贴图翻转、玩家列表和文字缩放 |
| 给预览分配颜色 | `PreviewColorUtil` | 根据游戏类型生成稳定的地图/点位颜色 |
| 计算文件摘要 | `FileHashUtil` | MD5、SHA-1、SHA-256、SHA-512 |
| 实现地图编辑物品 | `FPSMToolItem` | 处理点击、双击、NBT 选择和服务端分发 |

## 生命周期与线程

工具类不会切换线程。服务端工具应在 Forge 事件的服务端阶段或地图 tick 中调用；客户端工具应在屏幕、HUD 或渲染事件中调用。`RenderUtil` 内部缓存玩家列表直到客户端 tick 变化，若在同一 tick 手动修改队伍投影，调用 `invalidatePlayerInfoCache()` 清除缓存。

工具类通常直接修改传入对象。例如 `fixGunItem` 会更新 `ItemStack` 的弹药字段，`setTag` 会写入工具物品的 NBT。需要保留原值时先 `copy()`。

## 源码位置

```text
net.ptcrys.fpsmatch.util
net.ptcrys.fpsmatch.util.hash
net.ptcrys.fpsmatch.core.persistence.PersistenceUtils
net.ptcrys.fpsmatch.common.item.tool
```

继续阅读：[标识、Codec 与格式化](utilities/identifiers-and-codecs.md) · [武器、库存与击杀](utilities/gameplay.md) · [客户端渲染](utilities/rendering.md) · [地图编辑工具](utilities/edit-tools.md)
