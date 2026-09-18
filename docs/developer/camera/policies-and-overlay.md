---
title: 相机策略与场景界面
description: 为镜头配置输入、HUD、玩家模型与专用字幕绘制。
---

# 相机策略与场景界面

镜头的位置由 Rig 决定，玩家还能进行哪些客户端操作则由 `CameraPolicy` 决定。创建会话时传入策略，框架会在该会话持有控制权期间应用它。

## 从预设开始

| 策略 | 视角输入 | 移动 / 交互 | HUD / 手部 | 本地模型 | 旁观切换 |
| --- | --- | --- | --- | --- | --- |
| `PLAYER` | 玩家控制 | 允许 | 显示 / 显示 | 显示 | 允许 |
| `CINEMATIC` | 锁定 | 阻止 | 隐藏 / 隐藏 | 显示 | 阻止 |
| `PREVIEW` | 锁定 | 阻止 | 隐藏 / 隐藏 | 隐藏 | 阻止 |
| `DEATH` | 锁定 | 阻止 | 显示 / 隐藏 | 显示 | 阻止 |
| `SPECTATOR` | 锁定 | 阻止 | 显示 / 显示 | 显示 | 允许 |
| `ORBIT` | 传给 Rig 旋转 | 阻止 | 显示 / 隐藏 | 显示 | 阻止 |

表中的“显示”表示策略不主动隐藏，其他游戏条件仍然可能影响最终渲染。`CINEMATIC` 与 `PREVIEW` 还会隐藏 toast。除 `PLAYER` 和 `SPECTATOR` 外，其余预设都抑制视角效果并锁定视角模式。

开场展示通常使用 `CINEMATIC`；镜头靠近本地玩家、不希望玩家模型遮挡画面时使用 `PREVIEW`。需要保留死亡信息和比分时，可从 `DEATH` 的行为开始选择。

## 自定义策略

`CameraPolicy` 是不可变 record，字段顺序如下。按需求创建一个实例并传给播放方法即可：

```java
CameraPolicy policy = new CameraPolicy(
        CameraPolicy.LookInput.LOCKED,
        true,   // blockMovement
        true,   // blockInteraction
        false,  // allowSpectatorSwitch
        true,   // hideHands
        false,  // hideHud：保留比赛 HUD
        true,   // hideLocalModel
        true,   // hideToasts
        true,   // suppressViewEffects
        true    // lockPerspective
);
```

这些字段管理客户端输入与渲染。比赛中的无敌、禁止伤害、暂停计时或权限限制仍由服务端规则实现；不能仅靠禁用输入建立比赛规则。

## HUD 隐藏时绘制字幕

`hideHud=true` 时，普通 HUD 渲染路径会被隐藏。场景文字应订阅 Forge 总线上的 `CameraOverlayEvent`。下面的订阅类配合第一章的 `ArenaCamera`：

```java title="com/example/elimination/client/ArenaCameraOverlay.java"
package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.common.client.camera.CameraOverlayEvent;

@Mod.EventBusSubscriber(modid = "elimination_addon", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ArenaCameraOverlay {
    @SubscribeEvent
    public static void render(CameraOverlayEvent event) {
        if (!ArenaCamera.isPlaying()) return;
        Minecraft mc = Minecraft.getInstance();
        int width = mc.getWindow().getGuiScaledWidth();
        event.graphics().drawCenteredString(mc.font,
                Component.literal("竞技场"), width / 2, 24, 0xFFFFFF);
    }
}
```

这里为方便阅读使用字面文本，正式内容可按[资源与本地化](../client/resources.md)改为翻译键。必须判断自己的会话是否活动，否则字幕也会出现在其他扩展的场景里。

事件在普通 HUD 被相机隐藏时提供绘制入口，框架随后绘制转场黑幕，所以字幕会一起淡出。若策略保留 HUD，应使用[普通 HUD 入口](../client/hud.md)；不要假设这个专用事件在所有策略下都会触发。

源码入口：[common/client/camera/CameraPolicy.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/camera/CameraPolicy.java)。
