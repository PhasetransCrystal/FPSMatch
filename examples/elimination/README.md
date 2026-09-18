# 团队淘汰赛示例

与 `docs/developer/game-mode-tutorial.md` 的八章教程配套。每个 `chapters/01` 至 `08` 保存该阶段的完整 Java 源码，共用 `resources` 中的模组元数据与语言文件。不要合并不同章节的同名类。

示例直接通过 composite build 依赖所在 FPSMatch 仓库，要求 Java 17 与 Gradle wrapper 对应版本。目标为 Minecraft 1.20.1 / Forge 47.4.10。Kotlin Gradle 插件与 FPSMatch 的发布变体保持一致；示例代码本身使用 Java。

在 FPSMatch 仓库根目录运行：

```text
gradlew -p examples/elimination -Pchapter=03 runClient
gradlew -p examples/elimination -Pchapter=03 runServer
```

默认章节为 `08`。普通开发可以将选定章节内容移入自己的 `src/main/java`，资源放入 `src/main/resources`，并同步修改 Gradle 源集与 FPSMatch 源码路径。

维护者使用 `gradlew -p examples/elimination compileChapters` 编译全部章节；该任务只检查代码与当前 API 的兼容性，不代表多人运行行为已经验证。

教程示例采用 GPL-3.0-only，与仓库许可证配套。

## 独立模块示例

`reference/java` 保存相机场景、场景字幕、HUD 渲染器、商店分类、商店监听模块和文件下载的完整示例。对应文档从 `docs/developer/module-index.md` 进入。这些文件独立于八章教程，不会自动加入选中章节的运行内容；按需要将相应类加入自己的模组，保留客户端隔离。

维护任务 `compileReference` 使用与教程相同的 Forge 和 FPSMatch 依赖编译这些示例。
