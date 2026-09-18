---
title: 客户端数据与重置
description: 读取游戏身份、队伍、玩家、商店和房间快照，处理状态释放。
---

# 客户端数据与重置

`FPSMClient.getGlobalData()` 返回客户端共享的 `FPSMClientGlobalData`。网络接收逻辑更新它，HUD、Tab 和房间界面读取它。这里保存的是展示状态，服务端地图、队伍和商店仍然是规则执行的依据。

## 读取当前身份

| API | 含义 |
| --- | --- |
| `getCurrentGameType()` / `isInGame()` | 当前游戏类型；`none` 表示没有类型 |
| `getCurrentMap()` / `isInMap()` | 当前地图身份 |
| `getCurrentTeam()` / `isInTeam()` | 当前队伍身份 |
| `isInNormalTeam()` | 已加入非旁观队伍 |
| `isSpectator()` | 客户端旁观队伍身份或本地玩家的旁观模式 |

“在游戏中”不是“比赛进行中”。准备阶段也可能已经具有类型、地图和队伍信息。跨地图复用界面时，缓存键应至少包含游戏类型和地图名。

## 查询玩家与队伍

在已有客户端渲染方法中可以这样读取：

```java
Minecraft mc = Minecraft.getInstance();
if (mc.player == null) return;
var data = FPSMClient.getGlobalData();
int money = data.getPlayerMoney(mc.player.getUUID());
var local = data.getLocalData();
```

`getLocalData()` 内部访问本地玩家，调用前应确认玩家存在。`getPlayerData(UUID)`、`getPlayerTeamData(UUID)`、`getTeamByName()` 和 `getTeamByUUID()` 返回 Optional；缺失状态通常意味着尚未同步或玩家已经离开，应按空结果处理。

`getPlayerMoney()` 对缺失条目返回 0。若界面需要区分“没有数据”和“余额为零”，应结合玩家快照是否存在，而不是从零值反推状态。

## 商店和房间快照

只想查看商店槽位时使用 `getSlotDataIfPresent(type, index)`。`getSlotData(type, index)` 会在不存在时创建空槽位，不能把调用后的存在性当作服务端已经同步的证据。

房间入口包括 `getMapSelectionSnapshot()`、`getMapRoomDetail()`、`getMapRoomToast()` 和 `getMapRoomInvitation()`。它们服务于[房间查询与订阅](../network/rooms.md)，不应由 HUD 每帧重新请求。

## 释放扩展自己的状态

框架的 `FPSMClient.reset()` 会清空共享数据、使渲染缓存失效并发布 `FPSMClientResetEvent`。自己的比分缓存、选中行和临时字幕可以订阅这个 Forge 事件一起清理。网络回调与界面状态更新应在客户端主线程完成。

关闭一个私人面板时，只清理该面板持有的数据；调用全局 `reset()` 会同时影响相机、队伍、商店等其他模块。仅退出某个 Screen，也不等同于整场比赛状态重置。

源码入口：[common/client/data/FPSMClientGlobalData.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/data/FPSMClientGlobalData.java)。
