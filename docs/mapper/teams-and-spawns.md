---
title: 队伍与出生点
description: 管理地图已有队伍，为每个普通队伍添加安全出生点。
---

# 队伍与出生点

队伍由游戏类型创建。地图制作者通常不新增或删除队伍，而是给已有普通队伍配置出生点、装备和商店。旁观队伍由框架单独管理，不需要出生点能力。

## 选择目标队伍

手持复活点工具，按 `Ctrl + 右键` 打开界面。依次选择 `cs`、`training` 和要配置的队伍。界面只列出具有出生点能力的普通队伍。

选定后，右键一个作为地面的方块。实际出生位置是这个方块的上方，记录玩家当前朝向。工具会拒绝以下位置：

- 与地图不在同一维度。
- 位置不在地图区域内。
- 脚下没有可站立碰撞面。
- 脚部或头部空间被方块碰撞体占据。
- 脚部或头部空间含有液体。
- 与该队已有出生点重复。

地图比赛进行中也不能修改出生点。

## 浏览和删除点位

复活点工具界面显示当前队伍的点位数量和坐标。使用前后按钮选择点位，再删除当前点；“清空”会移除该队所有出生点和已有玩家分配。

手持工具时，地图区域和所有队伍的出生点会持续显示。点位预览是客户端辅助显示，不会生成方块或实体。工具界面的显示开关控制所有区域和点位预览的可见性。

## 使用命令

站在希望玩家出生的位置并朝向目标方向，然后执行：

```text
/fpsm map modify cs training team teams <team> capability spawnpoints add
```

该命令使用执行者的精确位置和朝向。清除该队全部出生点：

```text
/fpsm map modify cs training team teams <team> capability spawnpoints clear
```

`<team>` 使用命令补全给出的内部队伍名。展示文本中的翻译名称不能代替内部名。

## 管理地图成员

让自己或指定玩家加入地图的自动分配队伍：

```text
/fpsm map modify cs training team join
/fpsm map modify cs training team join @a[tag=builders]
```

将玩家加入指定普通队伍或移入旁观队伍：

```text
/fpsm map modify cs training team teams <team> players <targets> join
/fpsm map modify cs training team teams spectator players <targets> join
```

对应的 `leave` 动作从该队移除玩家。地图详情页中的队伍管理同样经过服务端容量、比赛状态和换队规则处理。

下一步：[编辑玩法区域](regions.md)。
