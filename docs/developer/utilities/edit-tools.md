---
title: 地图编辑工具框架
description: FPSMToolItem、EditToolItem 与 WorldToolItem 的交互协议和扩展方式。
---

# 地图编辑工具框架

## 交互管线

客户端只负责捕获主手工具的点击并发送 C2S 包，服务端再次检查主手物品后调用工具。世界点击由 `WorldToolItem` 接收，普通右键传递 `RIGHT_CLICK_BLOCK`，左键传递 `LEFT_CLICK_BLOCK`；按住 Ctrl 或 Shift 的右键传递 `CTRL_RIGHT_CLICK`，没有目标方块时位置为 `null`。事件会被取消，避免工具破坏方块或触发普通交互。

```java
public final class MyTool extends CreatorToolItem implements WorldToolItem {
    @Override
    public void handleWorldInteraction(ServerPlayer player, ItemStack stack,
            ToolInteractionAction action, @Nullable BlockPos clickedPos) {
        // 在服务端检查权限、地图状态和坐标，再修改配置
    }
}
```

## `FPSMToolItem`

覆写 `onLeftClick(ClickActionContext)` 和 `onRightClick(ClickActionContext)` 实现物品行为。`ClickActionContext` 提供物品栈、服务端玩家、是否双击、是否按住 Shift 以及 `ClickAction`。双击窗口为 15 个服务端 tick；工具将计数写入 `DoubleClickCount` 与 `DoubleClickLastTick` NBT。

基类还提供字符串/整数 NBT 读写、可用游戏类型、按类型查找地图、当前队伍和队伍所属地图查询。NBT 选择键约定为 `SelectedType`、`SelectedMap`、`SelectedTeam`、`EditMode`，新增工具应沿用这些键以便提示文本和复制行为一致。

## `EditToolItem` 的三级选择

编辑工具内置 `EditMode.TYPE`、`MAP`、`TEAM`：普通左键循环切换模式；Shift+右键修改当前模式的选择；普通右键执行 `doEdit`。切换游戏类型会自动修正地图选择并清除队伍，切换地图会清除队伍。双击 Shift+左键清空全部选择。

覆写 `getTeamsByMap(type, mapName)` 提供队伍列表；需要过滤可编辑队伍时使用带 `checker` 的重载。`getMissingTagMessage` 可用于在执行编辑前给出缺少类型、地图或队伍的提示。

## 安全边界

客户端发送的坐标、动作和 NBT 都是不可信输入。实现 `handleWorldInteraction` 或 `doEdit` 时必须在服务端重新取得地图对象，检查玩家权限、地图是否正在比赛、维度和区域边界，再执行写入。`WorldToolItem` 只是交互接口，不会自动提供 OP 检查或持久化。

`CreatorToolItem` 默认禁止用工具攻击方块；若工具需要破坏方块，应显式实现另一套受控命令，而不是放开 `canAttackBlock`。

相关页面：[地图工具使用](../../mapper/tools.md) · [区域和点位预览](../client/area-previews.md) · [设置与持久化](../settings.md)
