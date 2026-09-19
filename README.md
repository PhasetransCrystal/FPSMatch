# FPSMatch

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/PhasetransCrystal/FPSMatch)
[中文文档](README_ZH-CN.md) · [Docs](https://fpsmatch.ptcrys.net)

FPSMatch is a Forge library mod for Minecraft 1.20.1 that provides the foundation for team-based competitive FPS
gameplay. It focuses on reusable match systems, map instances, economy hooks, shops, HUD utilities, stats, and
integration points for tactical weapon mods instead of shipping a complete game mode by itself. In this
workspace, [BlockOffensive](https://github.com/PhasetransCrystal/BlockOffensive) registers the playable `cs` demolition
and `csdm` deathmatch modes; standalone FPSMatch needs a comparable game-mode provider.

## Features

|      Area       | Description                                                                                                                   |
|:---------------:|:------------------------------------------------------------------------------------------------------------------------------|
| Match framework | Team-based match lifecycle, map instances, room/ready flow, spectator flow, and extensible mode logic                         |
|      Teams      | Team assignment, team state, match-side data, spawn points, kits, and round-oriented gameplay support                         |
|     Economy     | Custom money systems and team shop infrastructure for weapons, gear, and throwables                                           |
|    Map tools    | OP 2 map/spawn editing tools, map settings, region previews, and active-match edit guards                                     |
|  HUD and stats  | HUD support plus player statistics such as KDA, headshots, and damage                                                         |
|    Map rooms    | Map selection, room details, join/ready management, and optional icon/detail-background textures                              |
|  Compatibility  | Requires Modern UI, Kotlin for Forge and client-side Modern UI; TaCZ and other listed gameplay mods are optional integrations |
|    Commands     | In-game command help is available with `/fpsm help`                                                                           |

Map IDs are persistent identifiers: use 1-48 lowercase `a-z`, `0-9`, `_`, or `-` characters. Spawn points must be in the
map/dimension, have solid support, two collision-free blocks, and no fluid.
The [documentation site](https://fpsmatch.ptcrys.net) contains the full operator/developer guide, including
the [thumbnail and map-icon tutorial](https://fpsmatch.ptcrys.net/master/docs/mapper/resources/#添加图标和背景).

The current workspace requires **Modern UI 1.20.1-3.12.0.1 on clients**. Map browsing, room pages, shop configuration
and equipment purchases use native Modern UI controls and its text engine. Dedicated servers do not require Modern UI.

## Version Compatibility Matrix

Columns marked with `*` are required dependencies. Unmarked mod columns are compatibility integrations. The `1.3.0` row
is the current source-workspace snapshot, not a confirmed public release artifact.

| FPSMatch | Distribution              | Minecraft* | Forge*  | Modern UI*    | Kotlin for Forge* | TaCZ         | TaCZ Tweaks | LR Tactical | CounterStrikeGrenade | KubeJS            |
|----------|---------------------------|------------|---------|---------------|-------------------|--------------|-------------|-------------|----------------------|-------------------|
| 1.3.0    | GitHub workspace snapshot | 1.20.1     | 47.4.10 | 2.2.27+1.20.1 | 4.11.0+           | 1.1.7-hotfix | 2.11.2      | 0.4.3       | 1.20.1-1.5.2         | 2001.6.5-build.14 |
| 1.2.5    | Modrinth / CurseForge     | 1.20.1     | 47.3.11 | -             | -                 | 1.1.7-hotfix | -           | 0.3.0       | 1.4.1                | -                 |

TaCZ is marked optional in FPSMatch metadata. CounterStrikeGrenade, LR Tactical, KubeJS, and other unmarked entries are
selected by the game-mode add-on or server pack rather than declared base-framework requirements.

## Download

| Platform        | Link                                                                            |
|-----------------|---------------------------------------------------------------------------------|
| GitHub Releases | [Releases](https://github.com/PhasetransCrystal/FPSMatch/releases)              |
| Modrinth        | [FPSMatch on Modrinth](https://modrinth.com/mod/fpsmatch)                       |
| CurseForge      | [FPSMatch on CurseForge](https://www.curseforge.com/minecraft/mc-mods/fpsmatch) |

## How to Depend on FPSMatch

FPSMatch is published to the organization Maven repository. The public mod platforms also expose Maven endpoints for
their published files; pick the repository and coordinate that match the build you want to resolve from.

### Organization Maven

Releases and development snapshots live in separate repositories on `maven.ptcrys.net`. The artifact coordinate is
`net.ptcrys:fpsmatch:<FPSMatch version>`.

| Build    | Repository                           |
|----------|--------------------------------------|
| Release  | `https://maven.ptcrys.net/releases`  |
| Snapshot | `https://maven.ptcrys.net/snapshots` |

```gradle
repositories {
    maven {
        name = "Ptcrys"
        url = "https://maven.ptcrys.net/releases"
    }
}

dependencies {
    modImplementation "net.ptcrys:fpsmatch:<FPSMatch version>"
}
```

Snapshot versions carry a `-SNAPSHOT` suffix, for example `net.ptcrys:fpsmatch:1.26.9-SNAPSHOT`; resolve those from the
`/snapshots` repository.

## Community and Links

| Resource       | Link                                                                                    |
|----------------|-----------------------------------------------------------------------------------------|
| GitHub         | [PhasetransCrystal/FPSMatch](https://github.com/PhasetransCrystal/FPSMatch)             |
| Docs           | [FPSMatch Wiki](https://fpsmatch.ptcrys.net)                                            |
| BlockOffensive | [PhasetransCrystal/BlockOffensive](https://github.com/PhasetransCrystal/BlockOffensive) |
| Command helper | [Command reference](https://fpsmatch.ptcrys.net/master/docs/mapper/commands/)           |
| Bilibili       | [Author page](https://space.bilibili.com/21254202)                                      |
| QQ group       | 771884981                                                                               |
| Feedback sheet | [Tencent Docs](https://docs.qq.com/sheet/DQnZtS2l6dmNsaHBw?tab=BB08J2)                  |

## License

By using FPSMatch 1.1.13 or later, you agree to the terms of GPL v3. The complete license is available
in [LICENSE](LICENSE). Earlier versions up to 1.1.12 used the previous MIT license.
