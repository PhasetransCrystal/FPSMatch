---
title: 文件下载与任务生命周期
description: 通过 DownloadBuilder 下载资源，并区分进度、文件结果和执行器所有权。
---

# 文件下载与任务生命周期

`core.network.download` 在 HTTP 请求上增加文件落盘和进度回调。需要获得结果或处理失败时，直接使用 `DownloadBuilder`；只需要登记按 URL 管理的任务时，还可以使用 `Downloader` 与 `IDownloadAble`。

## 下载一个文件

下面的完整工具类使用同步下载入口，**调用它的代码必须在后台工作线程执行**。它不适合放在服务端 tick、客户端渲染或网络主线程回调中。

```java title="com/example/elimination/network/ArenaDownload.java"
package com.example.elimination.network;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.ptcrys.fpsmatch.core.network.NetworkModule;
import net.ptcrys.fpsmatch.core.network.download.DownloadException;
import net.ptcrys.fpsmatch.core.network.download.DownloadResult;

public final class ArenaDownload {
    public static DownloadResult download(String url, Path destination)
            throws IOException, DownloadException {
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        NetworkModule module = NetworkModule.initializeNetworkModule(url);
        try {
            return module.newRequest().downloadRequest()
                    .saveTo(target)
                    .download();
        } finally {
            module.shutdown();
        }
    }
}
```

目标应包含文件名。构建器在目标父目录创建临时文件，下载成功后替换目标文件；父目录要由调用者准备。成功返回的 `DownloadResult` 包含 `filePath()`、`fileSize()`、`fileName()` 和响应头。

当前实现先发送 HEAD 请求读取文件信息，再发送实际下载请求。HEAD 必须返回成功状态；它单独构造请求，不继承原请求的自定义认证头。需要认证或不支持 HEAD 的服务不能直接假定兼容。

## 展示进度

在 `.download()` 前使用 `.callback(progress -> ...)` 接收 `DownloadProgress`。它提供已下载字节、总字节数和进度；总长度未知时为 `-1`，进度也可能为 `-1`，界面应显示已下载大小或不定进度条。

回调随下载执行，不在 Minecraft 主线程。保存最新进度快照后，将真正涉及 Screen、世界或玩家的操作调度到对应主线程。`fileSize()` 来自响应信息，需要实际文件长度时读取文件系统。

## 异步入口的所有权

`downloadAsync()` 和接收 `Executor` 的重载返回 `CompletableFuture<DownloadResult>`。当前实现会在任务完成后对执行器调用 `NetworkModule.shutdown(executor)`，如果它是 `ExecutorService` 就会关闭它。不要传入仍被其他业务使用的共享线程池。

无参数异步入口还依赖 HttpClient 已配置的执行器。若需要严格控制线程池的生命周期，可在自己管理的后台任务里调用同步 `download()`，并像示例一样关闭独立 NetworkModule。

关闭模块会使下载路径可能返回 `DownloadResult.empty()`，因此 future 正常完成本身并不保证取得了可用文件。取消和界面关闭应作为业务状态单独处理。

## 任务描述与当前续传边界

`DownloadHolder` 描述 URL 和目标文件；`HashDownloadHolder` 还在完成回调中检查哈希，不匹配时删除文件。`Downloader.Instance().download(holder)` 按 URL 去重，`stop(holder)` 关闭对应模块；它不返回供调用者等待的 future。

`enableResume()` 虽然存在，但当前实现请求 Range 后，将响应写入一个新临时文件，没有先拼入已有文件内容，随后还会替换原文件。因此本页示例采用完整下载，不将该入口作为可靠续传方案。

继续阅读：[HTTP 客户端](../http-client.md) · [线程与资源生命周期](../reliability.md)。

源码入口：[core/network/download](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/core/network/download)。
