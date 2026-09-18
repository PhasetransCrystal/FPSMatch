---
title: 同步能力数据
description: 实现缓冲区编码与脏标记，并选择实际发送路径。
---

# 同步能力数据

能力有两种同步接口，承担不同职责。实现接口后，还需要确认宿主使用哪条发送路径，不能把接口名称当成自动广播保证。

## 能力快照接口

`FPSMCapability.CapabilitySynchronizable` 描述如何把能力编码进缓冲区。下面是能力类内部的实现片段：

```java
private int charges;
private boolean dirty = true;

public void setCharges(int value) {
    if (charges == value) return;
    charges = value;
    dirty = true;
}

@Override public boolean isDirty() { return dirty; }
@Override public void writeToBuf(FriendlyByteBuf buf) { buf.writeVarInt(charges); }
@Override public void readFromBuf(FriendlyByteBuf buf) { charges = buf.readVarInt(); }
@Override public void onBroadcast() { dirty = false; }
```

将接口添加到类声明，并导入 `FriendlyByteBuf`。`writeToBuf()` 只编码，不清空脏标记；定向发送给新玩家的快照，不应消耗其他玩家尚未收到的变化。

## 初始快照与广播

队伍提供 `syncCapabilities(player)` 发送定向初始数据，面向玩家集合的能力广播会处理脏状态并调用 `onBroadcast()`。客户端创建能力实例时，工厂必须存在，而且必须能够接受客户端队伍宿主。

地图能力没有一个可直接照搬的客户端 `BaseMap`。需要显示地图级状态时，通常像[比分 HUD 教程](../tutorial/score-hud.md)一样定义专门的 S2C 数据模型，而不是在客户端构造服务端地图。

## 自定义发送接口

`DataSynchronizable` 提供 `sync()` 和 `sync(player)`，默认实现为空。实现者需要写出数据包发送逻辑。内置 `ShopCapability` 采用这类方式同步商店内容和金钱。

因此，“实现 DataSynchronizable 后调用 sync”只有在组件覆盖了方法时才有实际效果。方法名并不会推导需要发送的字段。

## 选择消息接收者

队伍私有资源只发送给相关队伍，需要旁观信息时再显式加入观察者。初次入场、重连和房间切换需要完整状态，随后才可以按脏标记发送变化。

序列化字段的顺序与类型共同构成协议。改变它们时，应更新频道协议版本或定义兼容解码方式。
