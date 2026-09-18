---
title: 按游戏类型注册 HUD
description: 使用 FPSMGameHudManager 分发玩家和旁观者 HUD。
---

# 按游戏类型注册 HUD

FPSMatch 已提供按游戏类型分发的 HUD 管理器。开发只在某个玩法中显示的界面时，实现 `IHudRenderer` 并注册到 `FPSMGameHudManager`，即可复用框架的游戏类型和旁观状态判断。

## 实现渲染器

下面的完整客户端类为 `elimination` 注册一个简单标题。它将初始化订阅与渲染实现放在同一个类中：

```java title="com/example/elimination/client/EliminationHud.java"
package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.ptcrys.fpsmatch.common.client.FPSMGameHudManager;
import net.ptcrys.fpsmatch.common.client.screen.hud.IHudRenderer;

@Mod.EventBusSubscriber(modid = "elimination_addon", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EliminationHud implements IHudRenderer {
    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> FPSMGameHudManager.INSTANCE
                .registerHud("elimination", new EliminationHud()));
    }

    @Override
    public void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        // 需要调整原版覆盖层时，再按覆盖层 ID 选择性处理。
    }

    @Override
    public void onPlayerRender(ForgeGui gui, GuiGraphics graphics,
            float partialTick, int width, int height) {
        draw(graphics, width, "淘汰赛");
    }

    @Override
    public void onSpectatorRender(ForgeGui gui, GuiGraphics graphics,
            float partialTick, int width, int height) {
        draw(graphics, width, "淘汰赛 · 旁观中");
    }

    private void draw(GuiGraphics graphics, int width, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        graphics.drawCenteredString(mc.font, text, width / 2, 12, 0xFFFFFF);
    }
}
```

注册键是 FPSMatch 游戏类型 `elimination`，不是 Forge 模组 ID `elimination_addon`。同一个类型允许多个 HUD，注册行为是追加，所以应在客户端初始化时注册一次。每次加入地图都注册会产生重复绘制。

## 理解三种回调

`onPlayerRender()` 与 `onSpectatorRender()` 按客户端旁观状态二选一。`onRenderGuiOverlayPre()` 是普通覆盖层绘制前的入口，只向匹配游戏类型且非旁观的渲染器分发。它可能针对不同覆盖层多次发生，不适合当作每帧唯一的状态更新点。

管理器自身已经由 FPSMatch 注册为 Forge 覆盖层，无需再把同一个管理器注册一次。其 `enable` 字段是全局开关，也影响框架的 Tab 接管条件；只隐藏自己的某一块 HUD 时，在自己的绘制条件中控制即可。

## 接入比分数据

渲染时读取[客户端全局数据](global-data.md)中的玩家统计，或教程第八章的自定义比分快照。`isInGame()` 表示客户端有游戏类型身份，不表示服务端已经开赛。准备大厅和比赛 HUD 的区别应由对应状态字段决定。

Forge 原生覆盖层也可以继续使用，适合不依赖 FPSMatch 游戏类型的界面，见 [HUD 与客户端状态](hud.md)。相机隐藏普通 HUD 时，场景字幕使用 [CameraOverlayEvent](../camera/policies-and-overlay.md)。

源码入口：[common/client/FPSMGameHudManager.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/FPSMGameHudManager.java)。
