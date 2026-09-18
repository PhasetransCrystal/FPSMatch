---
title: Bukkit 事件桥接
description: 在提供 Bukkit API 的混合服务端消费地图通知。
---

# Bukkit 事件桥接

Bukkit 桥接只在检测到相应运行环境时注册。普通 Forge 服务端没有 Bukkit API，不能把桥接插件当成普通模组直接使用。

## 当前桥接范围

| Bukkit 事件 | 来源 | 数据 |
| --- | --- | --- |
| `BukkitPlayerKillOnMapEvent` | FPSMatch 的 `DeathEvent` | 地图、死者 UUID、可空的击杀者 UUID |
| `BukkitGameWinnerEvent` | FPSMatch 的 `VictoryEvent` | 地图、Bukkit 世界 |

虽然第一个事件名称包含 Kill，当前实现监听的是地图 `DeathEvent`，并不是最终 `KillEvent`。它不保证击杀统计已经写入。需要最终击杀奖励时，应在 Forge 的结果事件中实现，或专门扩展桥接。

## 在插件中监听结算

下面是插件监听器的方法片段：

```java
@EventHandler
public void onWinner(BukkitGameWinnerEvent event) {
    if (!event.getMap().getGameType().equals("elimination")) return;
    String mapName = event.getMap().getMapName();
    Bukkit.getLogger().info("Elimination finished: " + mapName);
}
```

将所在类实现为 Bukkit `Listener`，并在插件 `onEnable()` 中通过插件管理器注册。编译时需要能解析 Bukkit API 与 FPSMatch 桥接事件类型的依赖。

## 可空玩家与世界

环境死亡可能没有击杀者；玩家断线后，UUID 对应的在线 Bukkit Player 也可能不存在。因此优先保存 UUID，在需要操作在线玩家时再查询。

世界桥接依赖 Forge 世界名称与 Bukkit 世界的匹配，使用 `getWorld()` 前应处理找不到匹配世界的情况。

## 事件不承担玩法驱动

目前两个桥接事件都没有 Bukkit `Cancellable` 契约。它们用于通知与集成，不应被理解为能取消 FPSMatch 的胜负或死亡流程。需要影响规则时使用框架正式扩展点，并保持服务器线程中的状态一致性。
