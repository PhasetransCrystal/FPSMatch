---
title: 能力系统
description: 为地图与队伍组合可复用功能。
---

# 能力系统

能力把一个独立功能附加到宿主上。地图级目标点、回合播报属于 `MapCapability`；队伍套件、出生点、商店属于 `TeamCapability`。FPSMatch 的这套能力系统使用自己的工厂和容器，不要求使用 Forge 的 LazyOptional。

## 从一个小功能开始

[教程第七章](tutorial/first-capability.md)把回合播报提取为 `RoundAnnouncements`。地图仍然负责决定何时开始回合，能力负责计数与播报。这样的拆分让别的模式也能安装同一种功能。

```java
getCapabilityMap().add(RoundAnnouncements.class);
getCapabilityMap().get(RoundAnnouncements.class)
        .ifPresent(RoundAnnouncements::announceNextRound);
```

放在地图子类中，能力工厂需要提前注册。示例中的调用分别用于安装组件和使用已存在的组件。

## 工厂与实例

工厂注册在 `FPSMCapabilityManager`，描述怎样从宿主构造能力。`CapabilityMap.add()` 创建具体实例。除非功能确实应附加给所有同类宿主，否则保留工厂 `isOriginal()` 的默认值 `false`，由模式显式安装。

一个组件只负责自己拥有的状态，不应在初始化时销毁宿主、清空其他组件或递归安装自己。

## 按需要增加接口

| 需求 | 接口或入口 | 说明 |
| --- | --- | --- |
| 安装、重置、销毁 | 基类生命周期 | [创建与生命周期](capability/lifecycle.md) |
| 保存组件快照 | `Savable<T>` | [能力持久化](capability/persistence.md) |
| 编码能力缓冲区 | `CapabilitySynchronizable` | [能力同步](capability/synchronization.md) |
| 自定义发送方法 | `DataSynchronizable` | 实现者负责实际发包 |
| 管理员配置功能 | `Factory.command()` | [命令与帮助](commands.md) |

保存和同步是独立需求。服务端播报计数可以两者都不实现；地图配置需要保存但未必展示；客户端 HUD 数据可以同步但不写入存档。

## 查找与移除

`get()` 不会隐式创建组件，`getOrCreate()` 可能创建，`remove()` 则进入移除生命周期。`resetAll()` 调用所有组件的重置方法，但重置的具体含义由组件定义。地图重置时只应调用符合该模式语义的操作。

框架已注册的能力及其职责见[内置能力](capability/built-ins.md)。
