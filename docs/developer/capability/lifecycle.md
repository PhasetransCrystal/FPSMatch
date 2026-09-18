---
title: 能力的创建与生命周期
description: 管理地图和队伍能力的安装、事件监听、重置与销毁。
---

# 能力的创建与生命周期

能力工厂是全局类型定义，能力实例属于某一张地图或某一支队伍。注册一个工厂不会让所有宿主共享同一个实例。

## 选择宿主

地图目标点、区域效果和整图播报继承 `MapCapability`；套件、经济和队伍资源继承 `TeamCapability`。先按数据属于谁选择宿主，再决定能力需要哪些接口。

`TeamCapability` 的宿主类型是 `BaseTeam`。如果组件只支持服务端，可以像内置套件一样限制实例创建；如果还要建立客户端副本，构造器就不能假设宿主一定是 `ServerTeam`。

## 注册与安装

注册工厂应早于宿主创建。可在公共初始化事件的 `enqueueWork` 中调用 `FPSMCapabilityManager.register()`。同一类型重复注册会抛出异常，不适合放到每次服务器启动都会执行的地图注册回调中。

安装使用宿主容器：

```java
map.getCapabilityMap().add(RoundAnnouncements.class);
map.getCapabilityMap().get(RoundAnnouncements.class)
        .ifPresent(RoundAnnouncements::announceNextRound);
```

示例类型来自[第一种能力教程](../tutorial/first-capability.md)。`add()` 管理实例创建、`init()` 和事件总线注册。`get()` 查询已有实例；想在缺少时创建，使用 `getOrCreate()`，并先确定创建操作符合当前比赛状态。

## 回调的职责

| 回调 | 适合处理 |
| --- | --- |
| 构造器 | 保存宿主引用、建立局部字段 |
| `init()` | 安装后初始化，建立与宿主关联的资源 |
| `tick()` | 宿主容器驱动的持续逻辑 |
| `reset()` | 保留组件，清理一场比赛的临时状态 |
| `destroy()` | 移除组件时释放资源 |

能力 tick 并不会因为地图 `isStart == false` 自动停止。只应在比赛中运行的能力，需要在自身逻辑中判断地图状态。

## 实例事件监听

能力实例会注册到 Forge 总线，因此可以使用非静态 `@SubscribeEvent` 方法。总线会把其他地图事件也发给这个实例，处理前必须检查宿主：

```java
@SubscribeEvent
public void onVictory(FPSMapEvent.VictoryEvent event) {
    if (event.getMap() != getHolder()) return;
    reset();
}
```

这段方法适合放入地图能力。不要依赖全局“当前地图”来决定归属，否则多个竞技场同时运行时会相互影响。

## 移除与彻底关闭

`remove(Type.class)` 经过容器生命周期，可能受到能力不可移除标记或事件取消的影响。`unregisterAllFromBus()` 只解除事件监听，不等同于执行每个组件的 `destroy()`。

普通回合重置保留配置组件；地图彻底删除或服务器关闭才进入宿主的关闭路径。自定义后台资源应明确由谁关闭，不能只把引用从字段设为 `null`。
