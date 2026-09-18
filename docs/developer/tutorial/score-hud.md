---
title: 8. 同步比分并绘制 HUD
description: 将服务端比分传给客户端，并注册 Forge HUD 覆盖层。
---

# 8. 同步比分并绘制 HUD

前面的比赛逻辑全部在服务器运行。本章在屏幕顶部显示地图 ID、红蓝比分和当前阶段。客户端保存一份展示快照，服务器继续负责规则判断。

## 定义传输数据

创建 `ScoreS2CPacket.java`：

```java title="ScoreS2CPacket.java"
package com.example.elimination;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

public record ScoreS2CPacket(String mapName, int red, int blue, String phase) {
    // Installed during physical client setup; the signature contains no client classes.
    public static Consumer<ScoreS2CPacket> receiver = packet -> {};

    public static void encode(ScoreS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.mapName, 48);
        buf.writeVarInt(packet.red);
        buf.writeVarInt(packet.blue);
        buf.writeUtf(packet.phase, 32);
    }

    public static ScoreS2CPacket decode(FriendlyByteBuf buf) {
        return new ScoreS2CPacket(buf.readUtf(48), buf.readVarInt(), buf.readVarInt(), buf.readUtf(32));
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> receiver.accept(this));
        context.setPacketHandled(true);
    }
}
```

包只保存字符串和数字。写入与读取顺序一致，并限制字符串长度。`handle()` 将处理切回客户端主线程；`receiver` 由下面的客户端入口安装，公共包类无需引用 `Minecraft`。

## 注册独立频道


```java title="EliminationNetwork.java"
package com.example.elimination;

import net.ptcrys.fpsmatch.common.packet.register.NetworkPacketRegister;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;

public final class EliminationNetwork {
    public static final NetworkPacketRegister PACKETS = new NetworkPacketRegister(
            new ResourceLocation(EliminationMod.MODID, "main"), "1");

    public static void register() {
        PACKETS.registerPacket(ScoreS2CPacket.class, NetworkDirection.PLAY_TO_CLIENT);
    }
}
```

在上一章 `EliminationMod.setup()` 的 `enqueueWork` 回调中，保留能力注册并添加 `EliminationNetwork.register();`。频道和消息注册发生在公共初始化阶段，两端使用相同顺序。明确声明 `PLAY_TO_CLIENT`，使这个展示包只能从服务器发往客户端。

## 从地图发送快照

在 `EliminationMap` 中添加：

```java title="EliminationMap.java"
public void syncToClient() {
    if (getServerLevel().getGameTime() % 20 != 0) return;
    String phase = isStart() && roundLifecycle != null
            ? roundLifecycle.phase().name().toLowerCase(java.util.Locale.ROOT) : "idle";
    ScoreS2CPacket packet = new ScoreS2CPacket(getMapName(), red.getScores(), blue.getScores(), phase);
    getMapTeams().getOnlineWithSpec().forEach(player ->
            EliminationNetwork.PACKETS.getChannel().send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), packet));
}
```

地图每 tick 末尾会调用 `syncToClient()`。本例每 20 tick 发一次完整快照，包含参赛者和旁观者，重连者也会收到下一份快照。这里使用附属模组自己的频道发送，不能把注册在独立频道的包交给 FPSMatch 内置频道。

为使教学代码直接可读，先采用定期全量发送。需要更及时或更省流量时，可以在入场时发送初始快照，在比分或阶段变化时推送增量。

## 安装客户端接收器

创建仅在物理客户端加载的 `EliminationClient.java`：

```java title="EliminationClient.java"
package com.example.elimination;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = EliminationMod.MODID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class EliminationClient {
    private static ScoreS2CPacket latest;
    private static long receivedAt;

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ScoreS2CPacket.receiver = packet -> {
                latest = packet;
                receivedAt = System.nanoTime();
            };
            MinecraftForge.EVENT_BUS.addListener(EliminationClient::logout);
        });
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { latest = null; }

    @SubscribeEvent
    public static void overlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("elimination_score", (gui, graphics, partialTick, width, height) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (latest == null || minecraft.player == null || minecraft.options.hideGui
                    || System.nanoTime() - receivedAt > 2_000_000_000L) return;
            Component text = Component.translatable("hud.elimination.score", latest.mapName(),
                    latest.red(), latest.blue(),
                    Component.translatable("phase.elimination." + latest.phase()));
            graphics.drawCenteredString(minecraft.font, text, width / 2, 12, 0xFFFFFF);
        });
    }
}
```

`FMLClientSetupEvent` 和 `RegisterGuiOverlaysEvent` 订阅 MOD 总线，离开服务器事件订阅 Forge 总线。`value = Dist.CLIENT` 将包含 `Minecraft` 的类限制在物理客户端。

收到包时更新 `latest`，覆盖层只读取这份快照。断开连接时清空数据；离开地图后如果两秒没有新快照，HUD 自动隐藏。因此本例离场显示最多延迟两秒；需要立即隐藏的界面可增加明确的离场消息。

## 添加语言文件

`resources/assets/elimination_addon/lang/zh_cn.json` 中包含：

```json
{
  "hud.elimination.score": "%s | 红 %s : 蓝 %s | %s",
  "phase.elimination.waiting": "准备",
  "phase.elimination.active_round": "进行中",
  "phase.elimination.round_end_waiting": "回合结算",
  "phase.elimination.paused": "暂停",
  "phase.elimination.idle": "未开始"
}
```

把条目合并到已有语言文件，保留前面章节的播报文案。`drawCenteredString` 使用 GUI 缩放后的宽度定位文字，`hideGui` 使 HUD 随 F1 一起隐藏。

## 继续扩展

同一个工程已经贯通地图、队伍、回合、设置、能力、网络与客户端展示。接下来可阅读[HUD 与客户端状态](../client/hud.md)、[客户端操作请求](../network/c2s.md)或[资源与本地化](../client/resources.md)。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/08) · 上一章：[能力](first-capability.md)
