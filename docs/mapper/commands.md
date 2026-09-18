---
title: 地图命令参考
description: 按地图、队伍、设置和能力分类查找 FPSMatch 制图命令。
---

# 地图命令参考

地图管理命令默认要求权限等级 2。尖括号表示必填参数，方括号表示可以省略；实际的游戏类型、地图、队伍、设置和能力会由服务器动态补全。

## 帮助和全局数据

```text
/fpsm help
/fpsm save
/fpsm reload
```

`help` 根据当前注册内容生成可展开的命令树。`save` 写出所有 FPSMatch 数据，`reload` 发布框架重载事件并重新读取相应内容。

## 创建地图

```text
/fpsm map create <game_type> <map_name> <from> <to>
```

例如：

```text
/fpsm map create cs training 0 64 0 100 90 100
```

地图 ID 为 1 至 48 位小写字母、数字、下划线或连字符。创建只定义地图边界并调用玩法工厂；出生点和目标区域随后配置。

## 玩家和队伍

```text
/fpsm map modify <game_type> <map_name> team join [targets]
/fpsm map modify <game_type> <map_name> team leave [targets]
/fpsm map modify <game_type> <map_name> team teams <team_name> players <targets> <join|leave>
/fpsm map modify <game_type> <map_name> team teams spectator players <targets> <join|leave>
```

省略 `targets` 的形式只适用于玩家执行者。指定队伍操作始终需要目标选择器。

## 地图设置

```text
/fpsm map modify <game_type> <map_name> settings list
/fpsm map modify <game_type> <map_name> settings get <setting>
/fpsm map modify <game_type> <map_name> settings set <setting> <value>
/fpsm map modify <game_type> <map_name> settings save
/fpsm map modify <game_type> <map_name> settings load
/fpsm map modify <game_type> <map_name> settings reset <setting>
/fpsm map modify <game_type> <map_name> settings reset all
```

`value` 会读取到命令行末尾，因此显示名称等字符串可以包含空格。

## 地图能力

只有地图实际具有对应能力时，命令才会出现在帮助树中：

```text
/fpsm map modify <game_type> <map_name> capability demolition bomb_area add <from> <to>
/fpsm map modify <game_type> <map_name> capability demolition bomb_area display
/fpsm map modify <game_type> <map_name> capability match_end_teleport_point <pos>
```

结束传送点命令记录执行维度、位置和固定朝向。能力只提供可保存点位；具体玩法是否在结束流程使用它，由游戏类型实现决定。

## 队伍出生点和装备

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> capability spawnpoints add
/fpsm map modify <game_type> <map_name> team teams <team_name> capability spawnpoints clear
/fpsm map modify <game_type> <map_name> team teams <team_name> capability kits add [item] [amount]
/fpsm map modify <game_type> <map_name> team teams <team_name> capability kits list
/fpsm map modify <game_type> <map_name> team teams <team_name> capability kits clear
```

不带 `item` 的 `kits add` 读取玩家主手完整物品栈。`spawnpoints add` 读取执行者的当前位置和朝向。

## 队伍商店

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop initialize <type> [startMoney]
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop info
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop reset
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop sync
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop areas add <pos1> <pos2>
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop areas display
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop areas clear
```

修改槽位的共同前缀是：

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> capability shop modify set <shop_type> <slot> ...
```

末尾可以使用 `cost <value>`、`item [item]`、`group_id <value>`、`dummy_ammo_amount <amount>`，或 `listener_module add|remove <name>`。槽位命令编号从 1 开始。

## 比赛管理

```text
/fpsm map modify <game_type> <map_name> debug start
/fpsm map modify <game_type> <map_name> debug reset
/fpsm map modify <game_type> <map_name> debug new_round
/fpsm map modify <game_type> <map_name> debug cleanup
/fpsm map modify <game_type> <map_name> debug switch
```

扩展模组可以继续注册地图或队伍能力命令，因此本页不替代服务器内的 `/fpsm help`。
