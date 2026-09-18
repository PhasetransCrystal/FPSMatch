---
title: 商店配置
description: 初始化队伍商店，编辑商品槽位、价格、分组和购买区域。
---

# 商店配置

商店属于队伍。游戏类型决定可用商店类型、商品分类和槽位数量；地图制作者为每个队伍选择类型，并修改槽位内容。

## 初始化队伍商店

已有 `ShopCapability` 的队伍先执行：

```text
/fpsm map modify cs training team teams <team> capability shop initialize <type> 800
```

`<type>` 使用命令补全列出的已注册商店类型，`800` 是玩家初始资金。省略资金时也使用 800。初始化成功后，该队才会出现在地图详情页和商店配置工具的可编辑商店列表中。

初始化会建立玩法定义的分类和默认槽位。商店类型不同的配置不能随意互换；重复初始化失败时，先确认当前商店信息：

```text
/fpsm map modify cs training team teams <team> capability shop info
```

## 使用图形编辑器

从地图详情页进入“管理 → 商店编辑”，选择队伍；也可以手持 `fpsmatch:shop_config_tool` 右键进入。总览按商品分类显示槽位，点击一个槽位打开内容编辑器。

在槽位编辑器中：

- 从背包选择或替换商品。
- 修改价格，范围为 0 至 1,000,000。
- 为枪械设置虚拟弹药，范围为 0 至 999,999。
- 设置分组 ID，`-1` 表示不使用互斥组。
- 添加玩法已注册的监听模块。

修改保存在草稿中，点击“保存”后由服务端再次检查。带有未保存改动时返回，会要求再次确认放弃。

## 理解分组和监听模块

分组 ID 把同一队商店中的相关槽位连接起来。购买某个槽位后，组内其他槽位可以锁定、退款或升级，具体行为由挂载的监听模块决定。相同数字本身不定义“护甲升级”等业务含义。

可选监听模块由 FPSMatch 和玩法模组注册。编辑器只接受服务器列出的模块；地图制作者不能在界面中创建新的 Java 模块。内置护甲、退货和换物逻辑是否适合当前商品，取决于模块实现。

## 设置购买区域

商店区域也按队伍保存。没有配置商店区域时，当前实现默认允许在全图范围内开启商店。限制购买地点时，添加一个或多个长方体：

```text
/fpsm map modify cs training team teams <team> capability shop areas add <pos1> <pos2>
```

显示或清除该队商店区域：

```text
/fpsm map modify cs training team teams <team> capability shop areas display
/fpsm map modify cs training team teams <team> capability shop areas clear
```

这里的 `clear` 会清除已配置区域，不只是隐藏预览。要让多个队伍共用同一购买空间，需要分别为每个队伍配置。

## 命令修改单个槽位

命令路径以商品分类名和 1 至 5 的槽位编号定位：

```text
/fpsm map modify cs training team teams <team> capability shop modify set <shop_type> <slot> cost <value>
/fpsm map modify cs training team teams <team> capability shop modify set <shop_type> <slot> item
/fpsm map modify cs training team teams <team> capability shop modify set <shop_type> <slot> group_id <value>
```

`item` 不带物品参数时读取执行者主手；其重载也接受物品参数。可用 `/fpsm help` 展开当前版本的槽位命令和监听模块补全。

`shop reset` 清除玩家的本局购买状态，`shop sync` 向玩家同步当前数据；它们不会恢复地图制作者的商品模板。

下一步：[管理房间与比赛](room-management.md)。
