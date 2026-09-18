---
title: 事件系统
description: 监听 FPSMapEvent、FPSMTeamEvent 与战斗事件，并正确处理可取消性。
---

# 事件系统

FPSMatch 事件发布在 `MinecraftForge.EVENT_BUS`。事件适合低耦合观察、奖励、外部统计和有限拦截；核心胜负规则仍应放在地图或 `RoundRule` 中。

```java
@SubscribeEvent
public static void onKill(FPSMapEvent.PlayerEvent.KillEvent event) {
    if (!(event.getMap() instanceof EliminationMap)) return;
    ServerPlayer killer = event.getPlayer();
    ServerPlayer dead = event.getDead();
}
```

## 地图事件

| 事件 | 可取消 | 说明 |
| --- | --- | --- |
| `LoadEvent` | 否 | 地图数据已加载 |
| `StartEvent` | 是 | 取消后 `start()` 返回 `false` |
| `ReloadEvent` | 是 | 阻止重载 |
| `ResetEvent` | 否 | 重置通知 |
| `ClearEvent` | 是 | 阻止清理 |
| `VictoryEvent` | 否 | 提供比分、玩家和队伍结算快照 |

## 玩家事件

| 事件 | 可取消 | 说明 |
| --- | --- | --- |
| `JoinEvent` / `LeaveEvent` | 是 | 阻止加入或离开默认流程 |
| `HurtEvent` | 是 | 可取消并可修改伤害值 |
| `DeathEvent` | 是 | 取消意味着监听器负责保持生命状态一致 |
| `KillRecordEvent` | 是 | 只阻止击杀统计写入 |
| `KillEvent` | 否 | 击杀已确定的通知 |
| `LoggedInEvent` | 否 | 地图玩家登录通知 |
| `LoggedOutEvent` | 是 | 慎用，可能影响预留状态 |
| `PickupItemEvent` / `TossItemEvent` | 是 | 控制物品行为 |
| `ChatEvent` | 是 | 控制地图内聊天 |

只有 `event.isCancelable()` 为真时才能取消。`VictoryEvent` 和 `KillEvent` 是结果通知，不是改变结果的拦截点。

## 其他事件

- `FPSMTeamEvent.JoinEvent`、`LeaveEvent`
- `FPSMGunDamageEvent`、`FPSMGunFireEvent`、`FPSMGunKillEvent`
- `FPSMGunReloadEvent`、`FPSMGunShootEvent`
- `FPSMThrowGrenadeEvent`
- `PlayerObtainItemEvent`
- `FPSMShopEvent.DataInit`

注册类事件包括 `RegisterFPSMapEvent`、`RegisterFPSMCommandEvent`、`RegisterFPSMSaveDataEvent` 和 `RegisterListenerModuleEvent`。它们只应在注册阶段处理，不能在 tick 中重复注册。

事件处理器应保持短小，并先按地图类型过滤。复杂可变状态放回地图或 Capability，清理时才能被明确释放。

## 选择扩展位置

伤害拦截与最终击杀通知发生在不同阶段，具体流程见[伤害、出局与统计](gameplay/combat.md)。开始事件发生在模式完成初始化之前；需要在开始成功后执行的行为，应放在模式确认 `super.start()` 成功的后续步骤。

能力实例监听事件时，先比较 `event.getMap()` 与宿主对象，避免其他地图的事件改变本组件状态；完整说明见[能力生命周期](capability/lifecycle.md)。
