---
title: 网络通信
description: 注册 Minecraft 消息频道，选择请求、快照与能力同步。
---

# 网络通信

FPSMatch 提供 `NetworkPacketRegister`，用于按统一编码方法注册 Forge `SimpleChannel` 消息。附属模组可以创建自己的频道，使协议与框架消息分别管理。

## 创建频道

下面的类来自完整示例：

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

构造器接收频道资源位置与协议版本。`register()` 在公共初始化阶段调用一次，两端注册顺序保持一致。`getChannel()` 返回实际发送消息的 Forge 频道。

## 消息方法契约

注册器通过反射查找三个公开方法：

```java
public static void encode(MyPacket packet, FriendlyByteBuf buf)
public static MyPacket decode(FriendlyByteBuf buf)
public void handle(Supplier<NetworkEvent.Context> context)
```

这是签名说明，不是可直接编译的消息类。`encode` 与 `decode` 必须为静态方法，`decode` 必须返回对应消息类型。完整消息实现见[比分 HUD 教程](tutorial/score-hud.md)。

## 选择消息方向

注册器能从 `C2S`、`C2SPacket`、`S2C`、`S2CPacket` 后缀推断方向。其他名字可能退回双向注册，因此新接口建议显式传入 `NetworkDirection`。

| 需求 | 专题 |
| --- | --- |
| 玩家点击准备、选队或购买 | [客户端操作请求](network/c2s.md) |
| 比分、阶段、目标状态展示 | [服务端快照与接收者](network/s2c.md) |
| 组件字段随宿主同步 | [能力同步](capability/synchronization.md) |
| 查询网页 API 或比赛服务 | [HTTP 客户端](http-client.md) |

## 网络线程与游戏线程

消息处理先完成解码，再通过 `context.enqueueWork()` 访问世界或客户端展示状态。解码方法只读取缓冲区，不查找地图、不修改玩家。

Minecraft 网络包与 HTTP 是两套通信路径。排行榜 HTTP 回调不能直接替代游戏线程中的结算逻辑；应先转换为业务结果，再安排服务器线程处理。
