---
title: HUD 与客户端状态
description: 注册覆盖层，并将网络数据与界面渲染分开。
---

# HUD 与客户端状态

HUD 在每帧读取客户端展示状态。它不应在渲染时访问服务器地图，也不应在每帧发包请求比分。

只为某个 FPSMatch 玩法绘制界面时，可以先阅读[按游戏类型注册 HUD](hud-manager.md)，使用框架现成的玩家 / 旁观分发。本页说明 Forge 通用覆盖层，适合跨玩法显示的界面。

## 注册覆盖层

Forge 1.20.1 使用 MOD 总线上的 `RegisterGuiOverlaysEvent`。事件订阅类需要限定 `Dist.CLIENT`。下面是该类中的方法片段：

```java
@SubscribeEvent
public static void overlays(RegisterGuiOverlaysEvent event) {
    event.registerAboveAll("match_label", (gui, graphics, partialTick, width, height) -> {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) return;
        graphics.drawCenteredString(minecraft.font,
                Component.translatable("hud.my_mode.title"), width / 2, 12, 0xFFFFFF);
    });
}
```

`width`、`height` 是 GUI 坐标尺寸，适合配合用户的界面缩放。翻译键由资源文件提供。完整客户端订阅类、数据缓存和发送端见[第八章](../tutorial/score-hud.md)。

## 在主线程更新状态

网络处理器通过 `enqueueWork()` 接收快照后，替换客户端保存的数据。渲染回调读取同一份不可变快照即可，不需要遍历服务器对象或锁住世界状态。

列表、计分板行和目标信息可以拆成独立展示模型。服务端字段改名时，只需要调整快照生成与解码，不应迫使整个界面跟着访问新的服务端类。

## 管理显示条件

除了是否存在玩家，还应判断是否属于需要显示的玩法、是否打开不兼容界面，以及是否还有有效快照。退出服务器时清空缓存；地图切换时按地图身份替换。

F1 隐藏 HUD、GUI 缩放与屏幕尺寸属于正常显示行为。避免直接使用固定显示器像素，把布局建立在传入的 GUI 宽高上。

## 客户端入口与通用代码

把 `Minecraft`、渲染事件和纹理加载放入物理客户端限定的类。公共包的字段、方法参数或静态初始化不应依赖这些类。教程通过公共数据包的接收回调与客户端初始化连接两端。

继续阅读：[资源与本地化](resources.md) · [服务端快照](../network/s2c.md)。
