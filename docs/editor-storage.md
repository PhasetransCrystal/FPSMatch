# Editor storage

FPSMatch 项目内部的 Java 17 本地工程存储层，包名为 `net.ptcrys.fpsmatch.editor.core.storage`。公开 API 只使用 JDK 和本包类型，生产实现仅依赖 Gson，隔离编译不需要游戏或 UI。源码直接进入 FPSMatch 主 JAR，不再构建或嵌入独立 editor 库；尚未宣布稳定 SDK。

保存协议见 [工程持久提交基线](PROJECT-PERSISTENCE.md)。存储层只维护模块元数据、不可变字节版本、依赖闭包与发布入口，不包含固定地图字段，也不实例化运行地图。类型注册、领域校验、迁移和缺模块时的编辑限制由上层提供。

## 使用

所有方法均执行阻塞 I/O，应由应用的后台 I/O 协调器调用。一个实例持有整个目录的独占文件锁，使用 try-with-resources 关闭；同工程不同目录可以各自打开。

```java
UUID projectId = UUID.randomUUID();
UUID documentId = UUID.randomUUID();
UUID requestId = UUID.randomUUID(); // 一次逻辑保存只分配一次，重试复用
try (LocalProjectStore store = LocalProjectStore.create(newDirectory)) {
    BlobRef content = store.stage(BlobRef.Kind.DOCUMENT, encodedDocument);
    ProjectState.Entry document = new ProjectState.Entry(
            documentId, "example:module", "example:document", "example:codec",
            1, userDocumentName, content, Set.of(), Set.of());
    ProjectState snapshot = new ProjectState(projectId, userProjectName,
            Map.of("example:module", "1.0.0"), Map.of(documentId, document), Map.of(), null);
    LocalProjectStore.CommitResult result = store.commit(null, requestId, snapshot);
    SavedProject saved = result.saved();
}

try (LocalProjectStore store = LocalProjectStore.open(existingDirectory)) {
    SavedProject baseline = store.load();
    // 从同一份不可变草稿编码全部内容，再以 baseline.commitId() 提交完整快照。
    store.exportSnapshot(newArchiveOutsideProject);
    // 固定版本分享：captureSnapshot 验证并认证当前已发布版本。
    ProjectSnapshot fixed = store.captureSnapshot();
    store.exportSnapshot(anotherArchiveOutsideProject, fixed);
}
SavedProject copy = LocalProjectStore.importSnapshot(archive, newCopyDirectory);
```

上述类型名为扩展示例，不是已接入注册系统的功能。`displayName` 和工程名是用户内容；类型显示标签应从上层注册项翻译键取得。诊断异常仅供开发定位，UI 应把 `ProjectStorageException.Code` 映射到带参数的双语言翻译键，不能直接显示异常英文消息。

首次 `commit` 的 expectedCommitId 为 null，后续保存使用已确认基线。`stage` 不关闭调用者的输入流，暂存成功不等于保存成功；`copyContent` 遇到损坏或输出失败前可能写出部分字节，调用方不得提前发布该输出。

`RESULT_UNKNOWN` 表示发布可能已经发生：保留原 requestId、原 expectedCommitId 与原快照，先调用 `lookup(requestId)`。PUBLISHED 只确认这一请求；其 `currentCommitId` 可能已是后续保存。查询不更新会话基线；核对结果后关闭并重开，再决定重试或继续编辑。重试必须使用原参数；保留窗口内的相同请求返回原提交，异内容拒绝。UNKNOWN 同时包括从未发布和回执已过期，不能据此显示保存失败或分配新 ID 盲目再写。

分享导出当前已确认快照及其实际依赖；不含个人设置、锁、撤销、恢复点、全部历史或保存回执。导入仅新建目录，保留工程／文档／资源身份，以新本机提交记录直接来源，不执行包内代码。失败可能留下没有 CURRENT 的未完成新目录；原包与已有工程保留。

`captureSnapshot()` 只认已发布版本：未发布工程以 `MISSING_PROJECT` 拒绝。它返回的 `ProjectSnapshot` 是当前实例签发的固定版本能力，携带私有签发标识与当前清单摘要，不持有存储实例、不暴露摘要、也没有全局能力表；两个参数的 `exportSnapshot` 只接受本实例签发的能力，直接构造的等价对象（含包内伪造、其他工程实例、关闭后重开的同路径实例）一律以 `REQUEST_MISMATCH` 拒绝，已关闭实例以 `CLOSED` 拒绝。导出时按能力的提交与摘要重读原清单、逐字节校验全部引用内容，并只据该版本组装包，不回退到当前入口：能力所指向的清单或内容此后损坏则以 `CORRUPT_CONTENT` 失败。该能力不锁定版本、不阻止后续保存、不延长内容寿命；本实例后续保存新版本后，同一能力仍重复导出捕获时的版本，内容一致。

## 验证与限制

下述旧独立库测试数字只表示当时验收，不能作为当前工程构建证据。示例现位于 `src/test/java/StorageSmoke.java`；当前开发验证命令和边界见 [DEVELOPMENT.md](../DEVELOPMENT.md)。

从 FPSMatch 目录使用 JDK 17 和现有 wrapper 执行：

```text
gradlew.bat test --tests "net.ptcrys.fpsmatch.editor.core.storage.*" verifyEditorBoundaries
```

独立测试覆盖保存／重开、二进制保真、请求重试、预算、损坏、外部入口变化、同 JVM／独立进程文件锁，以及在三个发布阶段由子 JVM 强制退出。分享测试覆盖独立副本、来源、路径、重复条目、缺项和篡改。固定版本分享测试覆盖：捕获后工程推进仍只导出捕获版本、同一能力重复导出内容一致、内容与引用逐项一致、跨实例／同路径重开／包内伪造／已关闭实例拒绝、未发布暂存拒绝、被捕获清单或内容损坏时不回退到当前版本、已有目标不被替换、发布边界外部改动以及同边界子 JVM 强制退出都不产生目标也不留下临时包。公开 API 测试扫描编译产物，约束对外签名不泄漏 Gson、AWT、Minecraft 或加载器类型。

2026-10-15主代理实际运行独立／FPSMatch集成测试各64项，全部通过；实际嵌入库由`examples/StorageSmoke.java`在仅JDK17＋Gson环境消费，保存、重开与独立副本分享成功。状态`LOCAL_STORAGE_VERIFIED / SELF_REVIEWED`，没有新增独立审查。其后新增固定版本分享测试14项，本机以JDK17 javac与JUnit Console运行同一套件78项全部通过，未重复运行共享Gradle；共享构建记录仍以上一句的64项为准。

当前只验证本机文件系统的进程中断一致性；不保证掉电、任意网盘或网络文件系统。工程提交需要原子移动；ZIP导出使用同目录完整临时文件的硬链接发布，保证同时导出时也不替换已有输出，并在目标名可见前再次核对当前入口：临时包已完成而 CURRENT 已被外部改动时删除临时产物并以 `CONFLICT` 拒绝，不发布已过期的包。不支持所需操作时拒绝发布，不降级覆盖或直接写目标。文件锁只协调受控写者，不能阻止任意外部进程改文件。容量预算针对当前引用内容和单次元数据／流，不是累计历史或磁盘占用配额。

本存储层不自动清历史／孤立内容，不提供恢复选择 UI、自动恢复点、结果取消或自动合并；DocumentSession、注册表、编辑器 UI、资源、旧地图导入和运行安装由上层实现并分别验证。当前入口损坏明确报错，绝不自动启用孤立清单。
