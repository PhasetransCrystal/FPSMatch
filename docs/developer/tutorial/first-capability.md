---
title: 7. 提取可复用能力
description: 创建、注册并使用每张地图独立的回合播报能力。
---

# 7. 提取可复用能力

本章为每轮增加“第几回合”的播报。这个功能不参与胜负判断，可以独立于淘汰赛规则复用，因此适合做成地图能力。

FPSMatch 的 `MapCapability` 是框架自己的组件机制，与 Forge 的 `Capability`/`LazyOptional` 体系不同。这里将组件交给地图的 `CapabilityMap` 管理。

## 定义能力

创建 `RoundAnnouncements.java`：

```java title="RoundAnnouncements.java"
package com.example.elimination;

import net.ptcrys.fpsmatch.core.capability.map.MapCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.minecraft.network.chat.Component;

public final class RoundAnnouncements extends MapCapability {
    private int round;

    public RoundAnnouncements(BaseMap map) { super(map); }

    public void announceNextRound() {
        round++;
        Component text = Component.translatable("message.elimination.round", round);
        map.getMapTeams().getOnlineWithSpec().forEach(player -> player.sendSystemMessage(text));
    }

    @Override
    public void reset() { round = 0; }
}
```

构造器只接收宿主。`round` 属于该实例，因此两张地图互不影响。`announceNextRound()` 由模式在确定开始下一轮时调用；能力不自行决定何时推进回合。

## 注册工厂

把第一章的 `EliminationMod` 替换为：

```java title="EliminationMod.java"
package com.example.elimination;

import net.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(EliminationMod.MODID)
public final class EliminationMod {
    public static final String MODID = "elimination_addon";

    public EliminationMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> FPSMCapabilityManager.register(
                FPSMCapabilityManager.CapabilityType.MAP,
                RoundAnnouncements.class, RoundAnnouncements::new));
    }
}
```

工厂注册在公共初始化阶段，并通过 `enqueueWork` 串行执行。地图创建前已能找到工厂，也避免在每次服务器启动时重复注册同一个能力类型。

注册只让框架知道“怎样创建这种能力”。是否将它安装到某张地图，仍由地图决定。

## 安装与调用

在地图构造器的队伍和设置初始化之后添加：

```java title="EliminationMap 构造器"
getCapabilityMap().add(RoundAnnouncements.class);
```

`add()` 创建组件、调用 `init()` 并注册实例事件监听器。这里无需再次调用 `MinecraftForge.EVENT_BUS.register()`。

在 `startNewRound()` 中，紧跟 `rebuildRoundLifecycle()` 添加：

```java title="EliminationMap.startNewRound()"
getCapabilityMap().get(RoundAnnouncements.class).orElseThrow().announceNextRound();
```

本例把组件视为模式的必需部分，缺失时直接报告问题。若允许管理员移除，可改用 `ifPresent` 并定义移除后的行为。

## 重置组件状态

在 `start()` 第一次调用 `startNewRound()` 前，以及 `reset()` 调用父方法前，加入：

```java title="EliminationMap.java"
getCapabilityMap().get(RoundAnnouncements.class).orElseThrow().reset();
```

重置后下一场从第 1 回合播报。该计数不需要跨重启保存，因此本章不实现 `Savable`。需要长期配置或统计的组件，可继续阅读[能力持久化](../capability/persistence.md)。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/07) · 上一章：[设置](configurable-rules.md) · 下一章：[比分 HUD](score-hud.md)
