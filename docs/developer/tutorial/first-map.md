---
title: 2. 注册第一张地图
description: 区分模组 ID、游戏类型和地图实例，并注册地图工厂。
---

# 2. 注册第一张地图

上一章建立了模组入口。本章添加一个空的 `EliminationMap`，让 FPSMatch 能根据它创建地图实例。

## 游戏类型与地图实例

假设服务器有 `training` 和 `courtyard` 两个竞技场，它们都使用 `elimination` 规则。你只需要注册一次游戏类型；每次创建竞技场时，框架都会调用工厂，生成不同的 `EliminationMap` 对象。

`EliminationMap` 中的普通字段属于那张地图。把比分写成静态字段，会让两个竞技场共享同一份比分。

地图实例还会跨越多场比赛：一场结束后，重置对象中的比赛状态即可继续使用原来的地图区域和配置。地图并不等同于只存在一次的一场比赛。

## 定义地图类

创建 `EliminationMap.java`：

```java title="EliminationMap.java"
package com.example.elimination;

import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.minecraft.server.level.ServerLevel;

public final class EliminationMap extends BaseMap {
    public static final String GAME_TYPE = "elimination";

    public EliminationMap(ServerLevel level, String name, AreaData area) {
        super(level, name, area);
    }

    @Override
    public String getGameType() { return GAME_TYPE; }

    @Override
    public boolean victoryGoal() { return false; }
}
```

`ServerLevel` 是实例所在的服务端世界，`name` 是地图 ID，`AreaData` 保存地图区域。工厂创建对象时，框架会提供这三个参数。

`getGameType()` 将实例关联回游戏类型。`victoryGoal()` 是整局胜利条件，此时返回 `false`，因为我们还没有参赛玩家和胜负规则。

## 注册工厂

创建 `EliminationEvents.java`：

```java title="EliminationEvents.java"
package com.example.elimination;

import net.ptcrys.fpsmatch.common.event.register.RegisterFPSMapEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EliminationMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class EliminationEvents {
    @SubscribeEvent
    public static void registerMaps(RegisterFPSMapEvent event) {
        event.registerGameType(EliminationMap.GAME_TYPE, EliminationMap::new);
    }
}
```

`RegisterFPSMapEvent` 发布在 Forge 事件总线上，因此订阅 `Bus.FORGE`。`EliminationMap::new` 只是工厂引用；注册时并不会立即创建竞技场。

不要在这里手动构造某张固定地图。地图持久化加载与管理员创建流程都会使用这份工厂注册。

## 创建地图实例

为竞技场选择一个区域，然后使用已有的地图命令：

```text
/fpsm map create elimination training 0 64 0 32 80 32
```

这条命令将区域与 `training` 绑定。它不会替你生成地板或建筑，应选取已有的场地。地图 ID 使用小写字母、数字、下划线或连字符，长度为 1–48。

目前这张地图只有区域和类型。下一章将添加可加入的队伍与出生位置。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/02) · 上一章：[接入项目](../getting-started.md) · 下一章：[队伍与出生](teams-and-spawns.md)
