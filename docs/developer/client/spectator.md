---
title: 客户端旁观视角
description: 理解旁观目标同步、环绕视角和临时相机场景的关系。
---

# 客户端旁观视角

FPSMatch 将长期的旁观目标和临时相机场景分开管理。服务端决定可旁观的对象，客户端 `SpectateState` 保存最新状态；`CameraDirector` 根据该状态生成基础画面。开场或死亡演出可以临时覆盖画面，而不丢弃后来收到的旁观目标。

## 旁观模式与目标

`SpectateMode`、`SpectateTarget` 和 `SpectateState` 位于 `common.client.spec`。

| 模式 | 作用 |
| --- | --- |
| `FREE` | 自由旁观，清除受限目标 |
| `ATTACH` | 附着到指定实体目标 |
| `TEAMMATE` | 队友视角，属于附着模式 |
| `C4_ORBIT` | 围绕炸弹锚点观察 |
| `DEATH_SPOT` | 围绕死亡位置观察 |

目标记录携带模式、实体 ID、锚点坐标、yaw、pitch 和环绕半径。附着目标尚未在客户端出现时，Director 可以暂时使用锚点独立镜头，不会继续使用旧实体。当前附着就绪判断要求目标是存活且非旁观的玩家。

## 从服务端请求切换

`SpectatorSwitchC2SPacket` 传递切换方向。处理器确认发送者存在且处于旁观模式后发布 `SpectatorSwitchInputEvent`；玩法处理该事件，按本地图队伍和存活规则选出允许的目标。

`SpectatorTargetS2CPacket` 把完整目标送回客户端；其客户端应用逻辑调用 `SpectateState.setTarget()`。另有 `SpectateModeS2CPacket` 处理模式切换。扩展自己的旁观流程应延续这个“请求方向 → 服务端选目标 → 同步目标”的过程。

客户端直接修改状态只改变展示，不能建立可旁观权限。服务端事件处理仍需确认玩家属于当前地图，并过滤敌人、离线或失效目标。

## 与相机场景组合

受限基础旁观只在本地玩家处于旁观模式时生效。附着使用 `SPECTATOR` 策略，环绕使用 `ORBIT` 策略；是否接受鼠标旋转和切换请求由[相机策略](../camera/policies-and-overlay.md)控制。

临时场景持有会话时，它的画面优先，但旁观目标仍可更新。会话结束后恢复最新基础状态，因此扩展不需要在开场前保存 `cameraEntity` 再手动恢复。

若想加入一段有限的死亡演出，用死亡优先级申请自己的会话，结束后交回基础旁观。若只需要更新队友目标，走旁观目标同步即可，无需为每次切换重新创建演出会话。

服务端玩家死亡与重连入口见[旁观与重连](../gameplay/spectating-and-reconnect.md)，临时会话见[相机生命周期](../camera/lifecycle.md)。

源码入口：[common/client/spec](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/client/spec)。
