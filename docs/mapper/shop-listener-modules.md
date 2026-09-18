---
title: 商店监听模块
description: 理解槽位分组与监听模块的触发方向，并配置退货、护甲和动态换物联动。
---

# 商店监听模块

监听模块为商店槽位增加联动行为。它不是一条独立商品，也不是监听全服所有购买的事件；模块挂在某个槽位上，只在**同一分组中的另一个槽位**被购买或退回时响应。

地图制作者只能选择服务器已经注册的模块。FPSMatch 自带退货、两种护甲状态和一个示例换物模块；玩法模组还可以注册更多模块。编辑器中的模块目录始终以当前服务器实际返回的列表为准。

## 先理解触发方向

假设槽位 A 和槽位 B 的 `groupId` 都是 `100`：

1. 玩家购买或退回槽位 A。
2. FPSMatch 找到同组内除 A 以外的槽位。
3. FPSMatch 依次调用槽位 B 上挂载的模块。
4. 槽位 A 上的模块不会由 A 自己这次操作触发。

因此，模块要挂在“需要对另一件商品作出反应”的槽位上：

| 想要的效果 | 模块应挂在哪里 |
| --- | --- |
| 购买 B 时退掉已经买过的 A | 把 `returnGoods` 挂在 A 上 |
| 购买 A 时让 B 变成另一件商品 | 把 `changeItem_...` 挂在 B 上 |
| 购买含头盔护甲时启用头盔属性 | 把 `bulletproof_with_helmet` 挂在同组的另一格护甲上 |

同一个 `groupId` 可以连接不同商品分类中的槽位。分组只存在于当前队伍的商店配置内；另一个队伍即使使用相同数字，也需要单独配置商品和模块。

`groupId = -1` 表示不参与分组。没有分组时，购买和退回不会向其他槽位广播，挂在该槽位上的联动模块也没有触发来源。图形编辑器允许填写 `-1`；当前命令的 `group_id` 参数只接受 `0` 及以上的值，清除分组应使用图形编辑器。

## 内置模块目录

### `returnGoods`

当同组另一格商品被购买时，`returnGoods` 检查当前槽位的商品是否已经购买、仍在玩家物品栏中并且可以退回。如果可以，它会移除一件匹配物品，并把当前槽位的价格退回给玩家。

把它挂在旧商品上，可以在购买升级商品时自动回收旧商品；在同组每个槽位都挂载它，可以让这一组商品互相替换。

当前版本会先按目标槽位的**完整价格**检查余额，随后才执行同组退货并返还金钱。例如玩家已经拥有价格 650 的护甲，要购买价格 1000 的头盔护甲，购买前仍需至少拥有 1000，不能只准备 350 的差价。

以下情况不会退款：旧商品已经被丢弃、消耗或不在玩家物品栏；槽位没有已购买记录；槽位因其他来源被锁定；两件商品没有相同的非负分组 ID。

### `bulletproof_without_helmet`

收到同组购买事件时，为玩家写入 100 点护甲耐久，并把 `hasHelmet` 设为 `false`。身体受到枪械伤害时护甲生效，爆头不受头盔保护。收到同组退回事件时，移除玩家的整个防弹护甲属性。

模块不会自行发放 `fpsmatch:bulletproof_armor`。商品槽位仍需配置正确的物品和价格。

### `bulletproof_with_helmet`

收到同组购买事件时，为玩家写入 100 点护甲耐久，并把 `hasHelmet` 设为 `true`；身体和爆头都能使用护甲。收到同组退回事件时，同样会移除整个防弹护甲属性。

它也不会自行发放 `fpsmatch:bulletproof_with_helmet`。由于模块响应的是另一个槽位的操作，两个护甲模块必须交叉挂载，具体配置见下文。

### `changeItem_...`

换物模块挂在“将要改变”的目标槽位上。购买同组另一格商品后，目标槽位会切换为预先定义的商品和价格；退回触发商品时，目标槽位恢复默认商品和价格。如果玩家已经买下改变后的商品，恢复时会先退回该商品并返还目标槽位的当前价格。商店重置时也会恢复默认状态。

FPSMatch 默认注册一个示例模块：

```text
changeItem_minecraft_apple
```

它把苹果（价格 50）切换为金苹果（价格 300）。服务器管理员还可以用命令创建其他换物模块，创建方法见[创建动态换物模块](#创建动态换物模块)。

### 扩展模组提供的模块

服务器安装的玩法或附属模组可以增加任意模块名称。FPSMatch 编辑器只显示名称，不会猜测第三方模块的业务含义。使用前应查阅提供该模块的模组文档；若服务器移除了相应模组，已经保存的模块名在载入时无法还原为功能实例。

## 使用图形编辑器添加模块

1. 先按[商店配置](shop.md)初始化队伍商店。
2. 从地图详情页进入“管理 → 商店编辑”，或手持 `fpsmatch:shop_config_tool` 打开商店配置界面。
3. 选择队伍、商品分类和槽位，打开槽位编辑器。
4. 在字段页给需要联动的槽位填写相同的非负分组 ID。
5. 点击右上角“监听模块”页签。
6. 从服务器模块目录选择名称，点击“添加”。同一个名称不能在同一槽位重复添加。
7. 返回字段页检查商品、价格、虚拟弹药和分组，点击“保存”。
8. 对同组的其他槽位重复配置。每个队伍拥有独立商店，因此双方商店需要分别编辑。

一次保存最多携带 64 个互不重复的模块名，每个名称最多 256 个字符。服务端还会重新检查管理员权限、槽位身份、模块是否仍已注册以及所有字段范围。保存槽位会替换默认模板、重建该队现有玩家的商店槽位状态并同步客户端；应在正式比赛开始前完成配置。

## 使用命令添加和移除

共同前缀为：

```text
/fpsm map modify <game_type> <map_name> team teams <team> capability shop modify set <shop_type> <slot>
```

添加模块：

```text
... listener_module add <module_name>
```

移除模块：

```text
... listener_module remove <module_name>
```

`<shop_type>` 使用商店分类名称，输入时可以使用小写；服务端会转换为大写枚举名。`<slot>` 从 1 开始。添加和移除参数都提供补全：添加只列出服务器已注册且当前槽位尚未挂载的模块，移除只列出当前槽位已有的模块。

为两个槽位设置相同分组：

```text
... group_id 100
```

命令编辑会立即重建玩家商店数据，但不会自动刷新玩家已经打开的商店界面。连续执行多条命令后，执行对应队伍的 `shop sync` 让客户端取得新槽位，再使用 `/fpsm save` 保存服务端数据。

## 完整示例：防弹护甲与头盔升级

下面把 `equipment` 分类第 1 格设为普通护甲，第 2 格设为护甲与头盔，两格使用分组 `100`。为了让任意购买顺序都保持一种护甲，并自动退回另一件商品，在两格上都挂 `returnGoods`，护甲属性模块则交叉挂载。

| 槽位 | 商品 | 价格 | 分组 | 挂载模块 |
| --- | --- | ---: | ---: | --- |
| `equipment` 1 | `fpsmatch:bulletproof_armor` | 650 | 100 | `returnGoods`、`bulletproof_with_helmet` |
| `equipment` 2 | `fpsmatch:bulletproof_with_helmet` | 1000 | 100 | `returnGoods`、`bulletproof_without_helmet` |

交叉挂载的原因是：购买第 1 格时触发第 2 格上的 `bulletproof_without_helmet`；购买第 2 格时触发第 1 格上的 `bulletproof_with_helmet`。

以 `cs training` 地图的 `ct` 队为例，命令写法如下：

```text
/fpsm map modify cs training team teams ct capability shop modify set equipment 1 item fpsmatch:bulletproof_armor
/fpsm map modify cs training team teams ct capability shop modify set equipment 1 cost 650
/fpsm map modify cs training team teams ct capability shop modify set equipment 1 group_id 100
/fpsm map modify cs training team teams ct capability shop modify set equipment 1 listener_module add returnGoods
/fpsm map modify cs training team teams ct capability shop modify set equipment 1 listener_module add bulletproof_with_helmet

/fpsm map modify cs training team teams ct capability shop modify set equipment 2 item fpsmatch:bulletproof_with_helmet
/fpsm map modify cs training team teams ct capability shop modify set equipment 2 cost 1000
/fpsm map modify cs training team teams ct capability shop modify set equipment 2 group_id 100
/fpsm map modify cs training team teams ct capability shop modify set equipment 2 listener_module add returnGoods
/fpsm map modify cs training team teams ct capability shop modify set equipment 2 listener_module add bulletproof_without_helmet
```

对另一个队伍重复配置时可以使用不同的分类和槽位，但同一队伍中相互联动的两格必须使用同一个分组 ID。

## 创建动态换物模块

创建命令要求玩家执行且拥有 OP 2 权限：

```text
/fpsm listener_module add change_item_module <changed_cost> <default_cost>
```

执行前把默认商品放在副手，把改变后的商品放在主手。参数顺序是“改变后价格”在前、“默认价格”在后，两个价格都必须至少为 1。

例如副手拿苹果、主手拿金苹果：

```text
/fpsm listener_module add change_item_module 300 50
```

非枪械模块的名称来自默认商品 ID，因此这里得到 `changeItem_minecraft_apple`。改变后的商品是枪械时，名称改用改变后枪械 ID，例如 `example:rifle` 会生成类似 `changeItem_example_rifle` 的名称。相同名称再次创建会覆盖服务器注册表中的旧定义，因此同一种默认非枪械商品不能同时承载多套不同换物规则。

创建后按以下关系配置：

1. 触发槽位和目标槽位使用相同分组 ID。
2. 把生成的 `changeItem_...` 模块挂在目标槽位上。
3. 玩家购买触发槽位时，目标槽位切换商品和价格；玩家退回触发槽位时，目标槽位恢复默认状态。
4. 执行 `/fpsm save` 保存动态模块定义和商店配置。

## 模块顺序和重置

同一槽位可以挂多个模块。FPSMatch 按优先级从高到低执行：`returnGoods` 为 5，`changeItem_...` 为 1，两种护甲模块为 0；相同优先级按该槽位中的加入顺序执行。组内默认槽位处理发生在模块回调之前。

商店重置会恢复槽位价格、购买次数和锁定状态，并调用模块重置逻辑。换物模块会恢复默认商品与价格；护甲模块没有槽位重置回调，玩家护甲状态由死亡、伤害耗尽和组内退回流程管理。

## 常见配置问题

### 模块在列表中不存在

编辑器只列出服务器当前注册的模块。第三方模块缺失通常表示提供它的模组没有安装、版本不匹配或注册失败。动态换物模块需要先通过命令创建；创建后重新打开槽位编辑器以刷新目录。

### 购买商品后模块没有反应

检查两个槽位是否使用相同的非负分组 ID，以及模块是否挂在需要响应的**另一个槽位**上。模块挂在触发槽位自身时，不会由自身购买触发。

### 自动退货没有发生

`returnGoods` 只能移除仍在玩家物品栏中的匹配商品。已丢弃、消耗或由其他来源锁定的商品不会被自动退款。购买新商品时还需要先满足新商品的完整价格。

### 保存提示模块无效

打开编辑器后服务器的模块目录可能因重载或模组状态变化而改变。关闭并重新打开槽位编辑器，从新的目录重新选择。重复名称、空名称、未注册名称或超过 64 个模块都会被服务端拒绝。

### 只配置了一支队伍

模块注册表是全服务器共享的，但槽位、分组和挂载关系属于具体队伍的商店。复制地图配置时只有结构兼容的商店才能导入；否则需要逐队配置。

继续阅读：[管理房间与比赛](room-management.md)。需要编写新的 Java 模块时，参阅开发者文档的[商店监听模块](../developer/gameplay/shop-listeners.md)。
