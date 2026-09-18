---
title: 命令、参数与帮助
description: 用 RegisterFPSMCommandEvent 提供管理员和玩家操作。
---

# 命令、参数与帮助

附属模组通过 `RegisterFPSMCommandEvent` 向 `/fpsm` 添加 Brigadier 子树。先确定命令作用于谁，再选择参数与权限。

## 添加一个查询命令

下面的方法放在 `@Mod.EventBusSubscriber(..., bus = Bus.FORGE)` 的订阅类中：

```java
@SubscribeEvent
public static void registerCommands(RegisterFPSMCommandEvent event) {
    event.addPlayerChild(Commands.literal("where_match").executes(context -> {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        var map = FPSMCore.getInstance().getMapByPlayerWithSpec(player);
        if (map.isEmpty()) {
            source.sendFailure(Component.translatable("message.my_mode.no_match"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.my_mode.current_match",
                map.get().getGameType(), map.get().getMapName()), false);
        return 1;
    }));
    event.registerHelp("fpsm where_match", "commands.my_mode.where_match");
}
```

需要导入 `Commands`、`Component`、`FPSMCore`、注册事件和 Forge 订阅注解。命令从执行者推导地图，普通玩家只能查询自己的比赛。语言键由附属模组资源提供。

## 区分普通玩家与管理员

`addChild()` 会在原有 requirement 基础上再要求 OP 2。`addPlayerChild()` 明确允许普通玩家进入子树，但不会替处理器判断目标权限。

查询当前比赛可以对普通玩家开放；修改任意地图设置、强制胜利或清空其他玩家数据，应使用管理员入口。服务器控制台没有玩家实体，需要支持控制台的命令应采用明确的地图参数。

## 参数与帮助必须一致

Brigadier 参数通过 `Commands.argument()` 加入实际命令树。`registerParameters()` 仅注册帮助文案中的参数名称，不会创建参数节点。

因此没有地图参数的 `where_match` 不应在帮助中声明一个 `map`。有整数范围时，在 `IntegerArgumentType.integer(min, max)` 中表达，并在业务层处理地图状态。

## 能力专属命令

能力工厂的 `command()` 可以返回 `FPSMCapability.Factory.Command`，其 `getName()` 决定子命令名，`builder(parent, context)` 补充 Brigadier 子树，`help(helper)` 注册帮助。

内置出生点能力挂在队伍能力路径，地图能力挂在地图能力路径。处理器应从命令上下文找到实际宿主，再通过宿主容器取得组件；不要使用一个全局能力实例处理所有地图。

## 命令执行与后台工作

轻量地图操作在命令执行线程完成。文件导入或 HTTP 请求先创建任务，再把结果交回服务器线程，同时确认地图仍处于允许修改的状态。相关边界见[线程与资源生命周期](reliability.md)。
