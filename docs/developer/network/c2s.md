---
title: 客户端操作请求
description: 从客户端意图推导服务端操作，而不是接收客户端提供的结果。
---

# 客户端操作请求

C2S 包表达玩家想执行的操作，例如切换准备状态、选择队伍或购买商品。处理时从网络上下文取得发送者，再在服务器上查找其比赛身份。

## 一个准备状态请求

下面是完整的数据包类。为了保持例子简单，它只携带期望的准备状态，不携带玩家 UUID：

```java
package com.example.elimination;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import java.util.function.Supplier;

public record ReadyC2SPacket(boolean ready) {
    public static void encode(ReadyC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.ready);
    }

    public static ReadyC2SPacket decode(FriendlyByteBuf buf) {
        return new ReadyC2SPacket(buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> {
            var sender = context.getSender();
            if (sender == null) return;
            FPSMCore.getInstance().getMapByPlayer(sender).ifPresent(map -> {
                if (map.isStart() || !map.checkGameHasPlayer(sender)) return;
                map.setReady(sender.getUUID(), ready);
            });
        });
        context.setPacketHandled(true);
    }
}
```

本例面向已有准备大厅的模式。教程为了先讲清手动开局关闭了准备开始；要用请求驱动自动开局，还需要启用并设置[大厅开始条件](../gameplay/lobby.md)。

## 注册与发送

将它加入附属模组的公共网络初始化：

```java
EliminationNetwork.PACKETS.registerPacket(ReadyC2SPacket.class, NetworkDirection.PLAY_TO_SERVER);
```

客户端按钮回调使用同一频道：

```java
EliminationNetwork.PACKETS.getChannel().sendToServer(new ReadyC2SPacket(true));
```

`EliminationNetwork` 定义见[比分 HUD 教程](../tutorial/score-hud.md)。两端应使用相同的消息注册顺序；扩展已发布协议时需要处理版本兼容。

## 从发送者确定身份

服务器不需要相信一个自报的 UUID。队伍名、商品槽位和地图 ID 即使由客户端提供，也只是请求参数；服务器必须重新查找目标并判断权限、归属和当前状态。

昂贵操作还应限制频率。准备状态设置本身很轻量，文件导入或外部查询则不适合在一次网络回调里直接阻塞服务器线程。
