---
title: 地图设置
description: 在地图详情页或对局配置工具中修改展示、玩家和比赛设置。
---

# 地图设置

每张地图拥有独立设置。FPSMatch 提供一组基础字段，游戏类型还可以增加回合长度、胜利目标等专有字段。界面会从服务器读取当前地图实际注册的设置，不需要依赖固定清单。

## 使用地图详情页

执行 `/fpsm mapselect`，打开 `training` 的详情页，进入“管理 → 设置”。设置按分类展示，可以搜索名称或只显示选中的分类。

布尔值使用开关；带数值范围的字段使用数值控件；其他字段使用文本输入。编辑多项后点击“保存”。尚未提交的字段只保存在当前界面中，退出时会提示保留编辑或放弃改动。

服务端逐项解析并保存。某个值无效时，界面会保留暂存内容，方便修改后重新提交。

## 基础设置

| 设置 | 默认值 | 含义 |
| --- | --- | --- |
| `displayName` | 空 | 玩家看到的地图名；为空时使用地图 ID |
| `iconTexture` | 空 | 地图列表缩略图资源位置 |
| `backgroundTexture` | 空 | 地图详情背景资源位置 |
| `allowJoinInProgress` | `true` | 比赛开始后是否允许新玩家加入 |
| `minAssistDamageRatio` | `0.25` | 助攻所需伤害占目标最大生命值的比例 |
| `teammateGlow` | `false` | 客户端是否显示队友发光 |
| `enemyGlow` | `false` | 客户端是否显示敌人发光 |
| `hideEnemyNameTag` | `true` | 是否隐藏敌方名牌 |
| `autoStart` | `false` | 满足玩法开始条件后启用自动开始计时 |
| `autoStartTime` | `6000` | 自动开始等待 tick，6000 tick 约为 5 分钟 |
| `readyStartEnabled` | `true` | 允许全员准备触发开始倒计时 |
| `readyStartTime` | `200` | 全员准备后的等待 tick，200 tick 约为 10 秒 |

“自动开始”不等于无条件在固定时间启动。玩法仍会判断玩家数量等开始条件；条件不再满足时，相应计时器会复位。

## 使用对局配置工具

手持 `fpsmatch:match_config_tool` 右键，选择游戏类型和地图。布尔值点击后直接提交；文本或数字字段输入后点击该行“应用”。鼠标悬停设置名称可查看对应说明。

工具适合站在地图内快速调整一个值，详情页适合一次编辑多项。两者写入同一组设置。

## 使用命令

```text
/fpsm map modify cs training settings list
/fpsm map modify cs training settings get displayName
/fpsm map modify cs training settings set displayName 训练场
/fpsm map modify cs training settings set readyStartTime 200
/fpsm map modify cs training settings save
```

`set` 会立即修改内存中的值，`save` 将整张地图的设置写入配置文件。还可以使用：

```text
/fpsm map modify cs training settings load
/fpsm map modify cs training settings reset displayName
/fpsm map modify cs training settings reset all
```

`load` 从文件重新读取，可能覆盖尚未保存的命令修改。`reset` 只恢复内存中的默认值；需要保留到下次加载时，再执行 `save`。

地图展示图片的资源位置写法见[展示资源](resources.md)。

下一步：[配置起始装备](kits.md)。
