# FPSMatch Wiki

FPSMatch 是一个面向 Minecraft 1.20.1 Forge 的团队 FPS 竞技框架模组。它提供地图、队伍、回合、经济、商店、投掷物、HUD、统计、旁观与兼容层等基础能力，适合用来搭建类似 Counter-Strike 爆破、团队死斗、枪战房间服等玩法。

需要注意的是，FPSMatch 本身更接近“玩法底座”或“库模组”，并不等同于一个完整开箱即玩的游戏模式。具体的 `cs`、`csdm` 等模式、地图规则和胜利条件通常需要由其他模组、KubeJS 脚本或服务器插件注册到 FPSMatch 中。

当前组合工作区中的 [BlockOffensive](../README_ZH-CN.md) 已经注册并提供了 `cs`（回合制爆破）和 `csdm`（死斗）两种完整玩法；单独安装 FPSMatch 时，仍需要安装该模组或其他玩法提供者，才会在创建地图时看到这些类型。

## 基本信息

| 项目 | 内容 |
| --- | --- |
| 模组名称 | FPSMatch |
| Mod ID | `fpsmatch` |
| Minecraft | `1.20.1` |
| Forge | `47.4.10+` |
| Java | 17 |
| 当前源码版本 | `1.3.0` snapshot |
| 许可证 | GPL v3 |
| 作者 | SSOrangeCATY |

## 适用场景

FPSMatch 适合以下用途：

- 搭建 Minecraft 枪战竞技服务器。
- 为 TaCZ 等枪械模组提供比赛框架。
- 制作团队对抗、爆破、死斗、回合制 FPS 玩法。
- 为地图作者提供地图区域、出生点、商店区域等编辑工具。
- 为服主提供队伍管理、地图选择、准备大厅和商店配置。
- 为开发者提供可复用的事件、能力系统和网络同步接口。

FPSMatch 不适合以下用途：

- 单独作为完整小游戏模组使用。
- 不安装枪械/玩法扩展时期待完整 CS 玩法。
- 只需要普通生存服小功能的轻量工具包。

## 依赖与兼容

### 必需环境

- Minecraft `1.20.1`
- Forge `47.4.10` 或更高的 1.20.1 Forge 版本
- Java 17
- LDLib2 `2.2.27+1.20.1`
- Kotlin for Forge `4.11.0+`

### 常见兼容模组

| 模组 | 作用 |
| --- | --- |
| TaCZ | 枪械数据、开火、换弹、弹药、枪械商店兼容 |
| Modern UI | 部分 GUI/配置页面依赖 |
| CounterStrikeGrenade | CS 风格投掷物兼容 |
| KubeJS | 将 FPSMatch 事件暴露给脚本 |
| Cloth Config | 配置界面兼容 |
| LR Tactical | 旁观与动作兼容 |
| Mohist/Bukkit | Bukkit 事件桥接 |

TaCZ 在 `mods.toml` 中是可选依赖，但 FPSMatch 的主要玩法目标明显围绕枪械竞技设计。若要做完整 FPS 玩法，建议搭配枪械模组使用。

## 核心概念

### 地图

地图是 FPSMatch 的主要对局单位。每张地图包含：

- 地图名称。
- 游戏类型，例如 `cs`、`csdm`。
- 地图区域，也就是对局边界。
- 队伍列表。
- 出生点、商店区、爆破点等能力数据。
- 地图设置项，例如是否允许中途加入、图标贴图、背景贴图等。

源码中的基础地图类是：

```text
map.core.com.ptcrys.fpsmatch.BaseMap
```

地图会在服务器 tick 中持续执行逻辑，并负责处理玩家加入、离开、死亡、回合、胜利、重置和客户端同步。

### 游戏类型

游戏类型是具体玩法模式的注册名，例如：

```text
cs
csdm
```

FPSMatch 通过 `RegisterFPSMapEvent` 暴露注册入口。外部模组可以在事件中注册自己的地图构造器：

```java
event.registerGameType("cs", CsMap::new);
```

如果没有外部模组或脚本注册游戏类型，地图创建工具和 `/fpsm map create` 将没有可用的游戏类型。

### 队伍

每张地图都有自己的队伍集合。队伍系统支持：

- 普通队伍。
- 旁观者队伍。
- 队伍人数限制。
- 玩家加入和离开。
- 玩家 KDA、死亡、生存状态等数据。
- 队伍能力，例如出生点、商店、开局装备、暂停、补偿和换队限制。

玩家加入一张地图后，会被绑定到该地图，方便事件、伤害、HUD 和商店逻辑判断玩家当前所处对局。

### 能力系统

FPSMatch 使用 capability 风格的能力系统扩展地图和队伍功能。

默认注册的队伍能力：

| 能力 | 用途 |
| --- | --- |
| `CompensationCapability` | 经济补偿 |
| `PauseCapability` | 暂停 |
| `SpawnPointCapability` | 出生点 |
| `TeamSwitchRestrictionCapability` | 换队限制 |
| `StartKitsCapability` | 开局装备 |
| `ShopCapability` | 队伍商店 |

默认注册的地图能力：

| 能力 | 用途 |
| --- | --- |
| `DemolitionModeCapability` | 爆破模式，炸弹区、安包队伍、C4 状态 |
| `GameEndTeleportCapability` | 游戏结束后的传送点 |

能力可以附加自己的命令、保存数据和 tick 逻辑。

## 已提供的游戏内容

FPSMatch 注册了一个创造模式物品栏，包含以下物品：

| 物品 | 用途 |
| --- | --- |
| `smoke_shell` | 烟雾弹 |
| `flash_bomb` | 闪光弹 |
| `grenade` | 手雷 |
| `ct_incendiary_grenade` | CT 方燃烧弹 |
| `t_incendiary_grenade` | T 方燃烧弹 |
| `bulletproof_armor` | 防弹甲 |
| `bulletproof_with_helmet` | 防弹甲和头盔 |
| `map_creator_tool` | 地图创建工具 |
| `spawn_point_tool` | 出生点工具 |

同时注册了投掷物实体、闪光致盲效果、音效、HUD 和多种网络包。

## 玩家与管理员功能

### 地图选择 GUI

客户端包含地图选择界面，用于展示当前可加入地图、地图状态、玩家数、是否已开始、是否可中途加入等信息。

地图详情页支持：

- 加入地图。
- 离开地图。
- 查看玩家。
- 管理地图。
- 修改地图设置。
- 邀请玩家。
- 队伍管理。
- 准备/取消准备。
- 编辑商店。

具体可见语言键：

```text
gui.fpsm.map_select.*
gui.fpsm.team_manage.*
gui.fpsm.map_shop.*
```

### 地图创建工具

`map_creator_tool` 用来在游戏内选择地图区域并创建地图。

常见操作：

- 左键方块：设置 `Pos1`。
- 右键方块：设置 `Pos2`。
- `Ctrl + 右键`：打开地图创建界面。

创建地图时需要选择已注册的游戏类型，并填写稳定的地图内部 ID。地图 ID 会作为持久化文件名和命令参数使用，必须为 1-48 个字符，且只允许小写字母 `a-z`、数字 `0-9`、下划线 `_` 和连字符 `-`；例如 `dust2`、`metro_a`。显示给玩家的名称可在地图设置中的 `displayName` 单独填写。

地图创建工具要求 OP 2 级权限。对局已开始时，地图边界编辑会被拒绝，以避免破坏正在进行的比赛。

### 出生点工具

`spawn_point_tool` 用来为指定地图和队伍添加出生点。

常见操作：

- 右键方块：添加出生点。
- `Ctrl + 右键`：打开出生点工具界面。

普通出生点必须位于地图区域内且与地图位于同一维度。系统还会检查脚下是否有可碰撞支撑、玩家脚部和头部两格是否无碰撞体、两格是否没有流体，以及是否为重复点。出生点工具要求 OP 2 级权限；对局进行时不能编辑出生点。

### 商店编辑

FPSMatch 提供商店槽位、价格、分组、弹药数量和监听模块等配置能力。管理员可以通过 GUI 或命令调整商店内容。

商店通常按类型分区：

- 装备
- 手枪
- 中级武器
- 步枪
- 投掷物

实际可购买物品依赖服务器配置和枪械兼容层。

## 命令

主命令为：

```text
/fpsm
```

需要 OP 2 级权限。

### 帮助

```text
/fpsm help
```

显示 FPSMatch 命令树帮助。

### 保存与重载

```text
/fpsm save
/fpsm reload
```

作用：

- `save`：保存 FPSMatch 数据。
- `reload`：触发 FPSMatch 重载事件，并重载地图配置。

### 调试开关

```text
/fpsm debug
```

切换 FPSMatch 全局调试模式。

### 设置 TaCZ 虚拟弹药

```text
/fpsm tacz dummy <amount>
```

对主手枪械设置虚拟弹药数量。要求主手物品能被枪械兼容层识别为枪械。

### 创建地图

```text
/fpsm map create <game_type> <map_name> <from> <to>
```

示例：

```text
/fpsm map create cs dust2 0 64 0 100 90 100
```

参数说明：

| 参数 | 说明 |
| --- | --- |
| `game_type` | 已注册的游戏类型 |
| `map_name` | 地图唯一内部 ID；长度 1-48，仅限小写 `a-z`、数字 `0-9`、`_`、`-` |
| `from` | 地图区域第一个角点 |
| `to` | 地图区域第二个角点 |

### 调试地图

```text
/fpsm map modify <game_type> <map_name> debug start
/fpsm map modify <game_type> <map_name> debug reset
/fpsm map modify <game_type> <map_name> debug new_round
/fpsm map modify <game_type> <map_name> debug cleanup
/fpsm map modify <game_type> <map_name> debug switch
```

作用：

| 子命令 | 说明 |
| --- | --- |
| `start` | 开始游戏 |
| `reset` | 重置游戏 |
| `new_round` | 开始新回合 |
| `cleanup` | 清理地图 |
| `switch` | 切换该地图调试模式 |

### 队伍操作

加入地图：

```text
/fpsm map modify <game_type> <map_name> team join
/fpsm map modify <game_type> <map_name> team join <targets>
```

离开地图：

```text
/fpsm map modify <game_type> <map_name> team leave
/fpsm map modify <game_type> <map_name> team leave <targets>
```

将玩家加入指定队伍：

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> players <targets> join
```

将玩家移出指定队伍：

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> players <targets> leave
```

加入旁观者：

```text
/fpsm map modify <game_type> <map_name> team teams spectator players <targets> join
```

### 地图设置

列出设置：

```text
/fpsm map modify <game_type> <map_name> settings list
```

查看设置：

```text
/fpsm map modify <game_type> <map_name> settings get <setting>
```

修改设置：

```text
/fpsm map modify <game_type> <map_name> settings set <setting> <value>
```

保存设置：

```text
/fpsm map modify <game_type> <map_name> settings save
```

加载设置：

```text
/fpsm map modify <game_type> <map_name> settings load
```

重置设置：

```text
/fpsm map modify <game_type> <map_name> settings reset <setting>
/fpsm map modify <game_type> <map_name> settings reset all
```

基础地图设置包括：

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `minAssistDamageRatio` | `0.25` | 计算助攻所需的最低伤害比例 |
| `allowJoinInProgress` | `true` | 是否允许中途加入 |
| `teammateGlow` | `false` | 是否启用队友透视发光 |
| `hideEnemyNameTag` | `true` | 是否隐藏敌方名牌 |
| `displayName` | 空 | 地图显示名称 |
| `iconTexture` | 空 | 地图卡片图标贴图 |
| `backgroundTexture` | 空 | 地图详情背景贴图 |
| `autoStart` | `false` | 是否自动开始 |
| `autoStartTime` | `6000` | 自动开始倒计时 tick |
| `readyStartEnabled` | `true` | 是否启用全员准备开始 |
| `readyStartTime` | `200` | 全员准备后的开始倒计时 tick |

#### 地图缩略图与图标填写教程

`iconTexture` 和 `backgroundTexture` 填写的是 **Minecraft 客户端资源位置**，不是服务器上的绝对文件路径，也不能填写 `C:\\...`、URL 或世界存档内图片路径。图片必须存在于模组资源或每位玩家已启用的资源包中，否则界面会自动显示色块兜底。

| 设置 | 出现位置 | 建议用途 |
| --- | --- | --- |
| `iconTexture` | 地图列表卡片；详情页未设置背景时也会使用 | 地图总览、俯视图或最容易识别的场景 |
| `backgroundTexture` | 地图详情页的大图；优先于 `iconTexture` | 更完整的宣传图、路线概览或比赛场景 |

推荐使用 PNG。渲染器会将图片铺满控件，不会自动裁剪，因此请使用 `16:9` 图片以避免拉伸：图标建议 `320x180` 或 `640x360`，详情背景建议至少 `1280x720`。图片中应保留地图辨识元素；不要把细小文字、规则说明或重要标记贴在边缘，以免在小尺寸列表中无法阅读。

**1. 放入客户端可加载的资源路径**

以资源命名空间 `fpsmatch`、地图内部名 `dust2` 为例，图片在资源包或模组中的目录应为：

```text
assets/
└── fpsmatch/
    └── textures/
        └── gui/
            └── maps/
                ├── dust2_icon.png
                └── dust2_bg.png
```

对应填写值必须包含命名空间和 `textures/` 目录：

```text
fpsmatch:textures/gui/maps/dust2_icon.png
fpsmatch:textures/gui/maps/dust2_bg.png
```

若图片属于自己的模组或资源包，也可以使用自己的命名空间。例如文件位于 `assets/myserver/textures/gui/maps/metro_icon.png`，则填写：

```text
myserver:textures/gui/maps/metro_icon.png
```

多人服务器必须通过模组包、服务器资源包或其他已约定的分发方式，让所有玩家拥有同一份图片资源；只把 PNG 放到服务器文件夹不会让客户端加载到它。

**2. 写入地图设置**

可在地图选择 GUI 的“设置”页填写 `iconTexture`、`backgroundTexture` 并保存；也可以使用命令：

```text
/fpsm map modify cs dust2 settings set iconTexture fpsmatch:textures/gui/maps/dust2_icon.png
/fpsm map modify cs dust2 settings set backgroundTexture fpsmatch:textures/gui/maps/dust2_bg.png
/fpsm map modify cs dust2 settings save
```

将 `cs` 和 `dust2` 替换为实际的游戏类型与地图内部名。只设置 `iconTexture` 时，列表和详情页都有可用图片；同时设置背景后，列表继续使用图标，详情页使用背景图。

**3. 验证与排错**

1. 打开地图选择界面，确认列表卡片已显示 `iconTexture`。
2. 进入房间详情，确认显示 `backgroundTexture`；未填写背景时应显示图标。
3. 若仍显示模式色块，依次检查文件名大小写、命名空间、是否包含 `textures/`、PNG 是否已随客户端资源加载。
4. 修改贴图路径后刷新地图列表；已打开列表会随设置变化同步更新，但资源包本身的新增图片仍需客户端重新加载资源包或重启游戏。

### 爆破能力命令

添加炸弹区域：

```text
/fpsm map modify <game_type> <map_name> capability demolition bomb_area add <from> <to>
```

显示炸弹区域：

```text
/fpsm map modify <game_type> <map_name> capability demolition bomb_area display
```

爆破能力会保存炸弹区域和安包队伍信息，用于判断玩家是否位于可安包区域。

### 商店与队伍能力命令

队伍能力命令挂载在：

```text
/fpsm map modify <game_type> <map_name> team teams <team_name> capability ...
```

常见能力包括：

- `shop`
- `spawnpoints`
- `kits`
- `match_end_teleport_point`

具体子命令会随能力注册到 `/fpsm help` 中。

## 常见配置

FPSMatch 使用 Forge 配置系统注册了客户端、通用和服务端配置。

常见通用配置包括：

| 配置 | 说明 |
| --- | --- |
| 主武器拾取数量 | 对局中允许拾取的主武器数量 |
| 副武器拾取数量 | 对局中允许拾取的副武器数量 |
| 投掷物拾取数量 | 对局中允许拾取的投掷物数量 |
| RPG/近战武器拾取数量 | 特殊武器拾取数量 |
| 闪光弹半径 | 闪光致盲范围 |
| 手雷半径 | 手雷爆炸范围 |
| 手雷引信时间 | 投出后多久爆炸 |
| 手雷伤害 | 爆炸伤害 |
| 燃烧弹持续时间 | 燃烧弹生效时间 |
| 烟雾弹持续时间 | 烟雾存在时间 |
| 防弹甲穿透 | 防弹甲减伤逻辑 |
| 爆头倍率 | 爆头伤害倍率 |

## 事件与扩展

FPSMatch 对外暴露了多种事件，适合其他模组或 KubeJS 脚本接入。

### 地图事件

| 事件 | 说明 |
| --- | --- |
| `StartEvent` | 地图开始 |
| `VictoryEvent` | 地图胜利 |
| `ClearEvent` | 地图清理 |
| `ResetEvent` | 地图重置 |
| `ReloadEvent` | 地图重载 |
| `LoadEvent` | 地图加载 |
| `PlayerEvent.JoinEvent` | 玩家加入 |
| `PlayerEvent.LeaveEvent` | 玩家离开 |
| `PlayerEvent.HurtEvent` | 玩家受伤 |
| `PlayerEvent.DeathEvent` | 玩家死亡 |
| `PlayerEvent.KillEvent` | 玩家击杀 |

### 队伍事件

| 事件 | 说明 |
| --- | --- |
| `FPSMTeamEvent.JoinEvent` | 加入队伍 |
| `FPSMTeamEvent.LeaveEvent` | 离开队伍 |

### KubeJS 事件名

当安装 KubeJS 时，FPSMatch 会注册事件组，常见事件名包括：

```text
mapStart
mapVictory
mapClear
mapReset
playerJoin
playerLeave
playerHurt
playerDeath
playerKill
playerLoggedIn
playerLoggedOut
teamJoin
teamLeave
```

这些事件可用于在脚本中添加奖励、播报、回合逻辑、统计、特殊规则等。

## 兼容层说明

### TaCZ

TaCZ 兼容层用于：

- 识别枪械物品。
- 读取枪械数据。
- 处理虚拟弹药。
- 监听开火、换弹、击杀、伤害。
- 处理旁观视角下的枪械动画、换弹镜像、开火镜像和后坐力表现。

相关命令：

```text
/fpsm tacz dummy <amount>
```

### CounterStrikeGrenade

CounterStrikeGrenade 兼容层用于接入 CS 风格投掷物或相关行为。

### KubeJS

KubeJS 兼容层将 FPSMatch 的 Forge 事件转换为脚本事件，便于服主快速编写规则。

### Bukkit/Mohist

源码包含 Bukkit 插件入口和事件桥接，适合在 Mohist 等混合服务端环境中把 FPSMatch 事件传递给 Bukkit 插件生态。

## 数据保存

FPSMatch 在服务器启动时创建核心实例并读取所有数据，服务器停止时会保存数据。创建地图以及修改地图设置、出生点、开局套件和商店时，会立即持久化对应数据；`/fpsm save` 仍可用于在测试或批量编辑后手动执行一次全局保存。

主要流程：

1. 服务器启动。
2. 创建 `FPSMCore`。
3. 触发 `RegisterFPSMapEvent`，注册游戏类型。
4. 触发 `RegisterFPSMSaveDataEvent`，注册可保存数据。
5. 读取所有数据。
6. 每 tick 推进所有地图。
7. 服务器停止时保存所有数据。

地图配置可以通过 `settings save` 写入文件，也可以通过 `settings load` 重新加载。

## 典型搭建流程

### 服主流程

1. 安装 Forge 1.20.1。
2. 安装 FPSMatch 和需要的枪械/玩法扩展模组。
3. 启动服务器，确认 `/fpsm help` 可用。
4. 确认有外部模组注册了游戏类型，例如 `cs` 或 `csdm`；本工作区安装 BlockOffensive 后会提供两者。
5. 使用 `map_creator_tool` 或 `/fpsm map create` 创建地图区域，并使用符合规则的稳定地图 ID。
6. 为各队伍设置安全出生点：地图内、同维度、有支撑、两格净空且无流体。
7. 配置商店和开局装备。
8. 配置炸弹区、结束传送点等能力；炸弹区必须完整位于地图边界内。
9. 在地图设置中填写 `displayName`、`iconTexture` 和可选的 `backgroundTexture`。贴图资源的目录、命名空间与排错步骤见[地图缩略图与图标填写教程](#地图缩略图与图标填写教程)。
10. 使用地图选择 GUI 或命令让玩家加入地图，并在开赛前检查房间、队伍和准备流程。
11. 测试开始、死亡、回合、胜利、重置流程；确认编辑锁会在对局期间生效。
12. 执行 `/fpsm save`，并保留一份数据文件备份。

### 管理员测试命令示例

```text
/fpsm help
/fpsm map create cs dust2 0 64 0 100 90 100
/fpsm map modify cs dust2 team join @s
/fpsm map modify cs dust2 settings list
/fpsm map modify cs dust2 debug start
/fpsm map modify cs dust2 debug reset
/fpsm save
```

如果 `cs` 游戏类型不存在，说明当前整合包没有注册该玩法，需要安装或编写额外的玩法模块。在本组合工作区中，安装 BlockOffensive 即可提供 `cs` 和 `csdm`。

## 开发者快速接入

### 注册游戏类型

外部模组可以监听 `RegisterFPSMapEvent`：

```java
@SubscribeEvent
public static void registerFPSMaps(RegisterFPSMapEvent event) {
    event.registerGameType("my_mode", MyMap::new);
}
```

自定义地图类需要继承 `BaseMap`，并实现：

```java
public class MyMap extends BaseMap {
    public MyMap(ServerLevel level, String mapName, AreaData areaData) {
        super(level, mapName, areaData);
    }

    @Override
    public String getGameType() {
        return "my_mode";
    }

    @Override
    public boolean victoryGoal() {
        return false;
    }
}
```

实际玩法中通常还需要覆盖：

- `start()`
- `startNewRound()`
- `tick()`
- `reset()`
- `cleanupMap()`
- `handleDeath()`
- `syncToClient()`

### 添加能力

自定义能力可以继承：

```text
MapCapability
TeamCapability
```

并通过 `FPSMCapabilityManager.register` 注册到地图或队伍上。能力可以提供：

- tick 逻辑。
- 数据保存。
- 命令树。
- 客户端同步。

### 监听事件

可以监听 FPSMatch 事件来扩展玩法：

```java
@SubscribeEvent
public static void onPlayerKill(FPSMapEvent.PlayerEvent.KillEvent event) {
    BaseMap map = event.getMap();
}
```

常见用途：

- 击杀奖励。
- 回合胜负判断。
- 自定义死亡处理。
- 特殊地图规则。
- 统计上报。

## 常见问题

### 为什么装了 FPSMatch 以后没有完整玩法？

因为 FPSMatch 是框架模组。它提供地图、队伍、商店、投掷物、事件和 GUI，但具体游戏类型需要外部注册。

当前工作区可同时安装 BlockOffensive，它已经提供 `cs` 和 `csdm`；若只安装 FPSMatch，则仍需另行提供玩法实现。

### `/fpsm map create cs ...` 提示无效或没有补全怎么办？

说明 `cs` 游戏类型没有被注册。需要安装提供 `cs` 模式的模组，或通过自己的模组监听 `RegisterFPSMapEvent` 注册。

### 能不能只用 FPSMatch 做死斗？

可以，但需要有一个注册 `csdm` 或类似模式的实现。FPSMatch 已经提供队伍、地图、出生点、伤害事件、死亡统计、HUD 和商店等基础能力。

### 为什么商店打不开？

常见原因：

- 玩家没有加入地图。
- 游戏尚未开始。
- 购买时间已结束。
- 玩家离开购买区。
- 当前地图或队伍没有初始化商店能力。
- 当前玩法不支持商店。

### 为什么出生点添加失败？

常见原因：

- 没有选择游戏类型、地图或队伍。
- 地图不存在。
- 当前维度和地图维度不一致。
- 出生点不在地图区域内。
- 脚下没有可碰撞支撑，或脚部/头部两格被方块、流体占用。
- 该队伍不存在。
- 出生点重复。
- 地图正在进行比赛，编辑被锁定。

### 为什么旁观视角下枪械表现特殊？

FPSMatch 对 TaCZ 和 LR Tactical 做了旁观兼容，包括枪械动画、换弹、开火、镜像物品、摄像机后坐力等逻辑，使旁观体验更接近正常第一人称观战。

## 源码导航

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/ptcrys/fpsmatch/FPSMatch.java` | 模组主入口 |
| `core/FPSMCore.java` | 核心运行时、地图注册、数据加载、tick |
| `core/map/BaseMap.java` | 地图基类 |
| `core/team/` | 队伍系统 |
| `core/shop/` | 商店核心 |
| `common/capability/` | 地图和队伍能力 |
| `common/command/` | `/fpsm` 命令 |
| `common/item/` | 物品注册和工具 |
| `common/entity/throwable/` | 投掷物实体 |
| `common/client/screen/` | 客户端 GUI |
| `common/mapselect/` | 地图选择和房间操作 |
| `compat/tacz/` | TaCZ 兼容 |
| `compat/kubejs/` | KubeJS 兼容 |
| `bukkit/` | Bukkit/Mohist 事件桥接 |

## 总结

FPSMatch 的定位是 Minecraft 枪战竞技玩法的基础框架。它把对局生命周期、地图区域、队伍、经济、商店、出生点、爆破能力、投掷物、HUD、旁观和事件系统都抽象出来，让其他模组或脚本可以在它上面实现具体玩法。

如果你是服主，可以把它当作枪战竞技服的底层依赖和管理工具。

如果你是开发者，可以把它当作可扩展的 FPS 玩法 SDK。
