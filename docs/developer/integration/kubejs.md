---
title: KubeJS 事件脚本
description: 通过 FPSMatchEvents 为已有模式添加脚本行为。
---

# KubeJS 事件脚本

KubeJS 适合给已有地图追加播报、限制或小型规则。FPSMatch 注册了 `FPSMatchEvents` 事件组，脚本可以读取底层地图与事件。

## 添加地图开始播报

将以下脚本放在实例的 `kubejs/server_scripts/elimination.js`：

```js
FPSMatchEvents.mapStart(event => {
    const map = event.getMap()
    if (map.getGameType() !== 'elimination') return
    console.info('Elimination requested: ' + map.getMapName())
})
```

这是开始请求事件，不是比赛所有初始化都已完成的通知。其他监听器仍可能取消开始；如果只要记录确定结算，应订阅 `mapVictory`。

## 拦截开始请求

可以在同一事件中按条件取消：

```js
FPSMatchEvents.mapStart(event => {
    if (event.getMap().getMapName() === 'maintenance') {
        event.cancel()
    }
})
```

这个例子让名为 `maintenance` 的地图拒绝开始。取消只在可取消的 Forge 事件中改变默认流程；对胜利、击杀等通知事件调用取消，不能撤销已经发生的结果。

## 常用事件与对象

| 场景 | 事件 | 常用读取 |
| --- | --- | --- |
| 地图生命周期 | `mapStart`、`mapVictory`、`mapClear`、`mapReset` | `getMap()` |
| 玩家入场和离场 | `playerJoin`、`playerLeave` | `getPlayer()`、`getMap()` |
| 伤害 | `playerHurt` | `getAmount()`、`setAmount()`、`getSource()` |
| 出局与击杀 | `playerDeath`、`playerKill` | 玩家、来源；击杀事件的 `getDead()` |
| 连接变化 | `playerLoggedIn`、`playerLoggedOut` | 玩家与地图 |
| 队伍成员变化 | `teamJoin`、`teamLeave` | `getTeam()`、`getPlayer()` |

需要包装层未暴露的属性时，通过 `getForgeEvent()` 访问底层事件，并以[事件专题](../events.md)确定时机与可取消性。

## 脚本的作用域

先过滤游戏类型或地图 ID，避免脚本影响其他模式。持续可变状态应有明确的地图归属与重置时机。大型回合驱动和可复用组件更适合放在 Java 模组中，再通过事件给脚本留出扩展点。

这些脚本需要实际安装 KubeJS 及其运行依赖，FPSMatch 的事件桥接不会替代 KubeJS 本身。
