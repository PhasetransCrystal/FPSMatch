---
title: 自定义 Tab 玩家列表
description: 按游戏类型接入 TabManager，并理解原版列表的替换条件。
---

# 自定义 Tab 玩家列表

`TabManager` 为每个游戏类型保存一个 `TabRenderer`。玩家打开 Tab 列表时，FPSMatch 的注入点根据客户端游戏状态将渲染交给它。适合展示队伍、金钱、击杀和回合数据。

## 实现并注册

`TabRenderer` 位于 `net.ptcrys.fpsmatch.common.client.tab`。实现下面两个方法：

```java
@Override
public String getGameType() {
    return "elimination";
}

@Override
public void render(GuiGraphics graphics, int windowWidth,
        List<PlayerInfo> players, Scoreboard scoreboard, Objective objective) {
    Minecraft mc = Minecraft.getInstance();
    int y = 30;
    for (PlayerInfo info : players) {
        graphics.drawString(mc.font, info.getProfile().getName(),
                windowWidth / 2 - 80, y, 0xFFFFFF);
        y += 12;
    }
}
```

这是客户端渲染器类中的方法片段，使用 Minecraft 1.20.1 的 `GuiGraphics`、`PlayerInfo`、`Scoreboard` 和 `Objective`，以及 `java.util.List`。实际界面需要根据屏幕高度安排分页或列布局。`objective` 可能为空，不应直接解引用。

在客户端初始化的 `enqueueWork()` 中调用 `TabManager.getInstance().registerRenderer(new EliminationTab())`，其中 `EliminationTab` 是实现这些方法的类。同一个游戏类型再次注册会替换之前的渲染器，和 HUD 的追加机制不同。

## 原版列表何时被替换

当前 `PlayerTabOverlayMixin` 在 `FPSMGameHudManager.shouldRender()` 为真、取得玩家列表后调用 Tab 管理器，并取消原版渲染。**即使当前类型没有注册 TabRenderer，也不会自动回退到原版列表。** 因此玩法希望在该状态下保留可见 Tab 时，需要提供自己的渲染器。

该条件依赖全局 HUD 开关和客户端游戏类型身份，不能据此判断比赛已经开始。切换地图后，管理器会按新的游戏类型选择渲染器。

## 合并玩家资料和比赛数据

`players` 提供客户端连接中的玩家资料；比赛归属与统计来自 `FPSMClient.getGlobalData()`。使用玩家 UUID 将两者关联，再按当前地图队伍组织行。不要把服务器在线玩家列表直接当作当前比赛名单。

队伍或统计尚未同步时显示占位信息，下一帧读取新快照。渲染方法应只构建画面，数据更新走[服务端快照](../network/s2c.md)。

源码入口：[common/client/tab](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/client/tab)。
