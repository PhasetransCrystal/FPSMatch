---
title: 相机会话与控制权
description: 处理相机优先级、结束原因、重生及旁观视角恢复。
---

# 相机会话与控制权

同一个客户端同一时间只有一个临时场景持有相机控制权。`CameraDirector` 协调开场、死亡和其他演出，底层旁观状态则可以在场景播放期间继续更新。

## 申请与替换

`play()` 接收单个 Rig，默认生命周期是 `PLAYER_LIFE`，时长上限为 `Integer.MAX_VALUE` tick，通常由调用者或有效性条件结束。其重载允许指定 `CameraLifetime`。`playSequence()` 使用序列总时长，生命周期为 `SCENE`。

当前内置优先级为 `DEATH_PRIORITY = 100`、`CINEMATIC_PRIORITY = 200`。更低优先级的申请被拒绝；**相同或更高优先级**会替换已有会话，旧会话收到 `REPLACED`。

`owner` 是控制权记录中的场景标识，建议使用 `模组ID:场景名`。它不是队列，也不表示同名申请会合并。`accepts(priority)` 只预判优先级；实际播放仍可能因世界不存在、正在重置或有效性条件不满足而返回 `null`。

## 保存自己的句柄

把成功取得的 `CameraSession` 保存在场景控制器中，通过 `isActive()` 判断是否仍持有控制权。提前结束调用 `close()`，或用 `stop(reason)` 指定结束原因。

旧会话被替换后仍可能被某个界面引用，但旧句柄无法释放新会话。扩展清理自己时应关闭自己的句柄；`CameraDirector.reset()` 会清除整个相机系统和基础旁观状态，属于全局重置路径。

## 结束回调

| `CameraEndReason` | 典型来源 |
| --- | --- |
| `COMPLETED` | 序列到达总时长 |
| `CANCELLED` | 调用 `close()` |
| `REPLACED` | 相同或更高优先级会话取得控制权 |
| `INVALIDATED` | 有效性条件失效，或玩家重生终止生命期镜头 |
| `WORLD_CHANGED` | 世界改变、卸载或退出 |
| `MATCH_RESET` | FPSMatch 客户端比赛状态重置 |
| `FAILED` | 运行中的采样、时钟或有效性处理失败 |

Director 先取消该会话的时钟，再调用结束回调。回调应释放本次场景创建的资源，并允许不同结束原因走同一条清理路径。申请未成功时不应依赖结束回调替你释放申请前创建的资源。

不要在回调中直接清空不属于本次会话的共享字幕、音乐或其他演出状态。尤其要避免旧会话的回调误清掉新会话的字段；可以按会话身份管理资源，或像第一章一样通过句柄的 `isActive()` 判断显示状态。

## 世界、重生与旁观

Director 每次处理时会检查客户端世界和有效性条件。`valid` 应只做轻量状态判断，初次申请时也会调用它。不要让它发包、播放音效或修改比赛状态。

玩家重生会结束 `PLAYER_LIFE` 会话，`SCENE` 会话可继续覆盖重生过程；世界变化仍会结束场景。比赛重置也会统一清理会话。

临时场景结束后恢复的是**当前**基础旁观状态。场景期间队友死亡或旁观目标改变时，不应手动恢复开场前缓存的实体。旁观状态的来源见[客户端旁观](../client/spectator.md)。

## 绑定后的场景效果

`session.onCameraBound(action)` 可以在底层相机重新绑定后重新应用场景专属效果，例如实体切换导致 Minecraft 清除的后处理。注册动作不会立即执行；框架检测到绑定版本改变后调用它。该方法设置的是一个回调，多次调用会覆盖前一个。

相机事件和 `mixin/camera` 已负责应用画面。扩展应围绕 Rig、Policy 和 Session 工作，避免另起一套每帧直接写入相机的控制器。

源码入口：[common/client/camera/CameraSession.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/camera/CameraSession.java)。
