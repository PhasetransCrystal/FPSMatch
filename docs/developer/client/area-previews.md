---
title: 区域和点位预览
description: 用稳定标识同步世界中的地图区域、出生点及编辑提示。
---

# 区域和点位预览

地图编辑不一定需要新的屏幕。FPSMatch 可以向指定玩家发送世界区域和点位，由客户端 `DebugData` 保存，再交给区域和点位渲染器显示。它适合地图边界、出生点和目标区域的编辑提示。

## 给预览分配稳定标识

服务端已有 `ServerPlayer player`、`AreaData area` 和 `Vec3 spawn` 时，可以发送以下片段：

```java
String prefix = "elimination_addon:training/";
FPSMatch.sendToPlayer(player, new AddAreaDataS2CPacket(
        prefix + "arena", Component.literal("竞技场范围"),
        0x55AAFF, area));
FPSMatch.sendToPlayer(player, new AddPointDataS2CPacket(
        prefix + "spawn/red/0", Component.literal("红队出生点"),
        0xFF5555, spawn));
```

数据包位于 `net.ptcrys.fpsmatch.common.packet`。同一个 key 再次同步会更新已有预览；不同 key 则并存。用模组、地图和对象 ID 组成 key，可避免不同工具相互覆盖。

`AddAreaDataS2CPacket` 也有仅接收名称和区域的便捷构造器，但它用 `Component.getString()` 生成 key。可翻译文本适合显示名称，不适合承担稳定对象身份。

## 清除本次编辑内容

```java
FPSMatch.sendToPlayer(player,
        new RemoveDebugDataByPrefixS2CPacket("elimination_addon:training/"));
```

前缀删除同时作用于区域和点位。关闭某个编辑流程时，只删除它拥有的前缀，避免清空其他地图工具的提示。已有地图区域展示入口可以使用 `map.displayAreas(player)`。

客户端通过 `FPSMClient.getGlobalData().getDebugData()` 访问预览集合。`setVisible()` 与 `toggleVisibility()` 只控制显示，隐藏时查询渲染集合得到空集合，原始条目仍保留。`clearAll()` 清除全部内容；当前 `clearAreas()` 也会调用 `clearAll()`，包括点位。

## 接入地图编辑工具

`common.item.tool.FPSMToolItem` 是已有工具物品基类。继承后实现 `onLeftClick(ClickActionContext)` 和 `onRightClick(ClickActionContext)`；上下文携带物品栈、服务端玩家、双击状态、潜行状态和动作。

基类提供所选游戏类型、地图、队伍和编辑模式的 NBT 标签常量，以及查找地图和队伍的辅助方法。原版物品注册仍按 Forge 流程进行；`CreatorToolItem`、`EditToolItem` 和 `WorldToolItem` 是当前具体工具实现。

新操作应在服务端重新解析所选地图并判断可编辑状态，修改真实区域或能力后发送预览。预览包本身只更新显示，不会保存地图，也不会授予玩家编辑权限。保存逻辑继续使用[地图持久化](../persistence.md)与对应能力的修改 API。

源码入口：[common/item/tool](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/item/tool)。
