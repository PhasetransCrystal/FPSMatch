---
title: 1. 接入项目
description: 准备与当前 FPSMatch 源码配套的附属模组。
---

# 1. 接入项目

本章建立 `elimination_addon`，供后续章节添加游戏模式。你已有 Forge 基础，因此这里重点说明 FPSMatch 的依赖方式与项目布局。

## 选择源码依赖

教程使用当前仓库 API。示例采用 Gradle composite build，将 `net.ptcrys:fpsmatch` 替换成本地 FPSMatch 工程。使用仓库内的 `examples/elimination` 时，配置已包含在 `settings.gradle`：

```groovy title="examples/elimination/settings.gradle"
includeBuild('../..') {
    dependencySubstitution {
        substitute module('net.ptcrys:fpsmatch') using project(':')
    }
}
```

`../..` 指向 FPSMatch 仓库根目录。若把附属模组移到其他位置，应改成 FPSMatch 源码所在的相对路径。还需要在依赖中声明使用它：

```groovy title="examples/elimination/build.gradle"
dependencies {
    modImplementation('net.ptcrys:fpsmatch') { transitive = false }
}
```

示例使用 `net.neoforged.moddev.legacyforge`，目标仍是 Forge 1.20.1。已有 ForgeGradle 项目不能直接照搬 `modImplementation`，应使用其对应的依赖与映射处理方式。完整构建配置以示例工程为准。

`transitive = false` 使附属模组自行声明运行依赖。示例配置了 Kotlin for Forge 4.11.0、Cloth Config，以及客户端需要的 Modern UI 3.12.0.1 和对应 Core/Markflow 库。FPSMatch 源码构建本身还会解析自己的编译依赖。

## 声明模组元数据

`@Mod` 使用的 ID 与 `mods.toml` 的 `modId` 必须一致。依赖表的后缀也必须是附属模组自己的 ID：

```toml title="resources/META-INF/mods.toml"
[[mods]]
modId="elimination_addon"
version="1.0.0"
displayName="Elimination Tutorial"

[[dependencies.elimination_addon]]
modId="fpsmatch"
mandatory=true
versionRange="[1,)"
ordering="AFTER"
side="BOTH"
```

这是与 FPSMatch 相关的部分；完整文件还声明了加载器、许可证、Minecraft 和 Forge。`[1,)` 是配套源码示例的宽范围，不表示所有 1.x 发布版都具备本教程 API；发布自己的模组时，应改成实际支持的版本范围。

## 创建模组入口


```java title="EliminationMod.java"
package com.example.elimination;

import net.minecraftforge.fml.common.Mod;

@Mod(EliminationMod.MODID)
public final class EliminationMod {
    public static final String MODID = "elimination_addon";
}
```

入口暂时不访问地图或服务器。服务器对象尚未建立时，无法从 `FPSMCore` 查询地图；下一章会在 FPSMatch 的注册事件中添加工厂。

## 工程布局

```text
examples/elimination/
  build.gradle
  settings.gradle
  chapters/01/java/com/example/elimination/EliminationMod.java
  resources/META-INF/mods.toml
  resources/assets/elimination_addon/lang/zh_cn.json
  resources/assets/elimination_addon/lang/en_us.json
```

普通附属模组通常使用 `src/main/java` 和 `src/main/resources`。这里使用分章目录，方便保留每个阶段；正文中的 Java 文件名均相对于 `com/example/elimination`。

在 FPSMatch 仓库根目录，通过 `gradlew -p examples/elimination -Pchapter=01 runClient` 使用第一章源集，服务器运行配置为 `runServer`。后续仅需改变章节编号。第一次启动服务器时，按 Minecraft 的要求处理 EULA。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/01) · 下一章：[注册第一张地图](tutorial/first-map.md)
