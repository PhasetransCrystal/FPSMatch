---
title: 房间查询、操作与订阅
description: 复用地图房间快照和服务端订阅推送，开发房间界面。
---

# 房间查询、操作与订阅

房间系统将地图查询、玩家操作和状态推送拆成三个服务。自定义房间界面可以继续使用这些服务和已有数据包，不必自己扫描全部地图并每帧发包。

## 查询面向当前玩家的视图

`common.mapselect.MapRoomQueryService` 提供 `findMap(gameType, mapName)`、`summaries(viewer)`、`detail(viewer, map)` 等入口。`viewer` 是服务端 `ServerPlayer`，决定当前玩家可见的设置、编辑入口和操作信息。

`MapRoomSummary` 是列表行；`MapRoomDetail` 包含房间玩家、队伍、设置等详细信息。可编辑商店通过 `listEditableShops()` 和 `supportsShopEditing()` 描述。不要把一个管理员的 detail 缓存后发送给所有玩家，因为快照含有与查看者有关的内容。

## 打开列表和详情

在客户端按钮回调中发送框架已经注册的数据包：

```java
FPSMatch.sendToServer(new OpenMapSelectionC2SPacket());
```

该请求经过访问条件判断后，服务端发送 `MapSelectionSnapshotS2CPacket`，并登记列表订阅。请求某个房间详情时：

```java
FPSMatch.sendToServer(new MapRoomActionC2SPacket(
        MapRoomActionC2SPacket.Action.REQUEST_DETAIL,
        "elimination", "training", null));
```

以上类位于 `common.packet.mapselect`，`FPSMatch` 位于根包。这个动作不需要目标玩家 UUID，构造器会规范化空值。成功后服务端发送 `MapRoomDetailS2CPacket` 并切换为详情订阅。客户端从[全局数据](../client/global-data.md)读取快照构建界面。

## 提交房间动作

`MapRoomActionC2SPacket.Action` 包含加入、离开、准备、邀请、接受邀请、踢出和切换队伍，以及管理员调试动作。参数中的 `targetPlayer` 用于指定操作对象，`data` 用于队伍等附加信息；应按对应动作的服务端实现填写。

处理器从网络上下文取得真实发送者，交给 `MapRoomActionService` 执行并返回结果。界面应等待服务端快照更新，不要先修改本地队伍数据来模拟成功。设置提交另有 `MapRoomSettingsC2SPacket`，仍需经过服务端配置解析和权限判断。

## 订阅的生命周期

`MapRoomSyncManager.watchList(UUID)` 订阅列表，`watchDetail(UUID, gameType, mapName)` 订阅单个详情，`unwatch(UUID)` 取消。每个玩家只保存一个目标；从列表进入详情会替换原订阅。

订阅推送由 FPSMCore 的服务端 tick 驱动，按同步间隔比较状态签名后发送变化。只调用 `watchDetail()` 不应被理解为已经发送首份快照；已有请求处理器负责即时回应，再登记后续推送。

服务器选项 `roomSyncPushEnabled`、`roomSyncIntervalTicks` 和 `roomSyncMaxWatchersPerRoom` 控制推送行为。扩展如果新增关闭界面的取消订阅请求，应由服务端取发送者 UUID 调用 `unwatch()`，不能由客户端指定任意玩家取消。

继续阅读：[C2S 请求](c2s.md) · [地图设置](../settings.md)。

源码入口：[common/mapselect](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/mapselect)。
