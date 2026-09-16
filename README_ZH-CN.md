# FPSMatch

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/SSOrangeCATY/FPSMatch)
[English Documentation](README.md) · [本地 Wiki](WIKI.md)

FPSMatch 是面向 Minecraft 1.20.1 Forge 的库模组，为团队制竞技 FPS 玩法提供基础框架。它专注于可复用的对局系统、地图实例、经济接口、商店、HUD 工具、统计数据与战术武器模组集成点，本身不直接提供完整游戏模式。当前工作区中，[BlockOffensive](../README_ZH-CN.md) 注册并提供了可玩的爆破 `cs` 与死斗 `csdm`；单独安装 FPSMatch 时仍需要等价的玩法提供模组。

## 功能概览

| 模块 | 说明 |
| --- | --- |
| 对局框架 | 团队制比赛生命周期、地图实例、房间/准备流程、旁观流程与可扩展模式逻辑 |
| 队伍系统 | 队伍分配、队伍状态、对局侧数据、出生点、开局套件与回合制玩法支持 |
| 经济系统 | 自定义金钱系统与队伍商店基础设施，可用于武器、装备和投掷物购买 |
| 地图工具 | OP 2 级地图/出生点编辑工具、地图设置、区域预览与比赛中编辑保护 |
| HUD 与统计 | 支持自定义 HUD，并记录 KDA、爆头、伤害等玩家统计数据 |
| 地图房间 | 地图选择、房间详情、加入/准备管理与可选图标/详情背景贴图 |
| 兼容集成 | 必需 Modern UI 和 Kotlin for Forge，客户端另需 Modern UI；TaCZ 及其他玩法模组为可选集成 |
| 指令帮助 | 游戏内可通过 `/fpsm help` 查看指令帮助 |

地图 ID 是持久化标识：长度为 1-48，仅允许小写 `a-z`、数字 `0-9`、`_`、`-`。出生点必须在地图边界和同一维度内，脚下有实体支撑，玩家占用的两格无碰撞体且没有流体。完整服主/开发者指引见[本地 Wiki](WIKI.md)，其中包含[缩略图与地图图标教程](WIKI.md#地图缩略图与图标填写教程)。

当前工作区客户端需要 Modern UI **1.20.1-3.12.0.1**。地图浏览、房间子页面、商店配置和装备购买界面使用原生 Modern UI 控件；文字由 Modern UI 字体引擎处理。专用服务器不要求安装 Modern UI。

## 版本兼容矩阵

带 `*` 的列为必须依赖，未标注的模组列为兼容集成项。`1.3.0` 行表示当前源码工作区快照，并不代表已确认存在公开发布产物。

| FPSMatch | 分发来源 | Minecraft* | Forge* | Modern UI* | Kotlin for Forge* | TaCZ | TaCZ Tweaks | LR Tactical | CounterStrikeGrenade | KubeJS |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1.3.0 | GitHub 工作区快照 | 1.20.1 | 47.4.10 | 2.2.27+1.20.1 | 4.11.0+ | 1.1.7-hotfix | 2.11.2 | 0.4.3 | 1.20.1-1.5.2 | 2001.6.5-build.14 |
| 1.2.5 | Modrinth / CurseForge | 1.20.1 | 47.3.11 | - | - | 1.1.7-hotfix | - | 0.3.0 | 1.4.1 | - |

FPSMatch 的元数据将 TaCZ 标记为可选。CounterStrikeGrenade、LR Tactical、KubeJS 及其他未标注条目由玩法扩展或服务器整合包按需选择，不是框架元数据声明的基础硬依赖。

## 下载

| 平台 | 链接 |
| --- | --- |
| GitHub Releases | [Releases](https://github.com/SSOrangeCATY/FPSMatch/releases) |
| Modrinth | [Modrinth 上的 FPSMatch](https://modrinth.com/mod/fpsmatch) |
| CurseForge | [CurseForge 上的 FPSMatch](https://www.curseforge.com/minecraft/mc-mods/fpsmatch) |

## 如何依赖 FPSMatch

FPSMatch 可以从公开的模组分发 Maven 仓库中拉取。根据你希望使用的分发平台，在 Gradle 中选择对应仓库和依赖坐标即可。下列公开坐标是已确认的历史发布版本，不代表存在公开的 `1.3.0` 快照产物。

### CurseForge Maven

CurseForge Maven 通过 CurseForge 项目 ID 与文件 ID 解析产物。当前已确认的 CurseForge 项目 ID 为 `1331710`，`1.2.5` 对应的公开文件 ID 为 `7109977`。

```gradle
repositories {
    maven {
        name = "CurseMaven"
        url = "https://www.cursemaven.com"
    }
}

dependencies {
    modImplementation "curse.maven:fpsmatch-1331710:7109977"
}
```

### Modrinth Maven

Modrinth Maven 通过项目 slug 与 Modrinth 版本号解析产物。当前已确认的项目 slug 为 `fpsmatch`。

```gradle
repositories {
    maven {
        name = "Modrinth"
        url = "https://api.modrinth.com/maven"
    }
}

dependencies {
    modImplementation "maven.modrinth:fpsmatch:1.2.5"
}
```

如果使用项目自身 Maven 发布配置生成的源码构建产物，依赖坐标为 `com.ptcrys:fpsmatch:<FPSMatch 版本>`。

## 社区与链接

| 资源 | 链接 |
| --- | --- |
| GitHub | [SSOrangeCATY/FPSMatch](https://github.com/SSOrangeCATY/FPSMatch) |
| Wiki | [开发者 Wiki](https://github.com/SSOrangeCATY/FPSMatch/wiki) · [本地 Wiki](WIKI.md) |
| BlockOffensive | [SSOrangeCATY/BlockOffensive](https://github.com/SSOrangeCATY/BlockOffensive) |
| 指令帮助 | [CommandHelper_en-us.md](https://github.com/SSOrangeCATY/FPSMatch/blob/master/CommandHelper_en-us.md) |
| Bilibili | [作者主页](https://space.bilibili.com/21254202) |
| QQ 群 | 771884981 |
| 反馈表 | [腾讯文档](https://docs.qq.com/sheet/DQnZtS2l6dmNsaHBw?tab=BB08J2) |

## 许可证

使用 FPSMatch 1.1.13 或更高版本即表示你接受 GPL v3 条款。完整许可证见 [LICENSE](LICENSE)。1.1.12 及更早版本使用此前的 MIT 许可证。
