---
title: 大厅、准备与加入策略
description: 设置开始条件，区分准备开始、自动开始与中途加入。
---

# 大厅、准备与加入策略

大厅负责比赛尚未开始时的玩家准备流程。模式需要决定“谁可以参加”和“满足什么条件才开始”，然后把判定接入 `BaseMap`。

## 自动开始与准备开始

`autoStart` 启用自动倒计时，`canAutoStart()` 返回是否具备开局条件；默认实现返回 `false`。`readyStartEnabled` 启用全员准备开始，`canReadyStart()` 默认检查在线普通队伍玩家是否都已准备。这两个条件不是同一个方法。

若要求两边都有人，地图子类可以这样实现。下面的 `red`、`blue` 是地图持有的 `ServerTeam` 字段：

```java
@Override
protected boolean canAutoStart() {
    return !red.getOnline().isEmpty() && !blue.getOnline().isEmpty();
}

@Override
protected boolean canReadyStart() {
    return canAutoStart() && allNormalOnlinePlayersReady();
}
```

`start()` 自身也应检查开始条件，因为管理员或其他扩展可以直接调用它。准备条件负责倒计时是否开始，`start()` 负责真正接受或拒绝一次开始请求。

## 保存准备状态

玩家点击准备时，可以使用 `toggleReady(player)`；需要明确设置时使用 `setReady(uuid, ready)`。不要另建一份布尔字段，让 HUD 与大厅各读一套状态。

`clearReadyPlayers()` 清除准备集合。比赛成功开始以及未开局时的成员变动会影响准备状态。`readyStartTime` 与 `autoStartTime` 使用 tick，例如 `200` 通常表示 10 秒。

## 加入与换队

`join(player)` 选择人数较少的普通队伍，`join(teamName, player)` 请求加入指定队伍。返回值是 `MapTeams.JoinTeamResult`：

```java
var result = map.join("red", player);
if (!result.isSuccess()) {
    player.sendSystemMessage(Component.translatable("message.my_mode.join_failed"));
}
```

这是服务端处理片段，`map` 与 `player` 分别为目标地图和发起操作的玩家。语言键由附属模组提供。加入可能因容量、取消事件或比赛策略失败，只有成功后才能安排依赖队伍身份的后续操作。

## 比赛中加入

`allowJoinInProgress` 控制比赛中的新参赛者。它不是完整的“换队、重连、旁观”策略：已在比赛中的玩家与新玩家走到的条件可能不同。严格淘汰赛还应在自己的加入入口中定义是否允许换队。

教程暂时关闭自动准备开始和中途加入，先让读者理解比赛流程。准备大厅可以在已有模式上启用，而不必重写注册、出生和计分。

继续阅读：[队伍与玩家](../teams-and-players.md) · [旁观与重连](spectating-and-reconnect.md)。

自定义房间列表、操作按钮及状态订阅见[房间查询与同步](../network/rooms.md)。
