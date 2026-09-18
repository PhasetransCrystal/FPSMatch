---
title: HTTP 客户端
description: 使用 NetworkModule 查询外部服务并处理异步响应。
---

# HTTP 客户端

`core.network.NetworkModule` 封装 Java HTTP 客户端。它适合读取外部赛季信息或比赛服务，与 Minecraft 的 C2S/S2C 消息频道相互独立。

## 创建模块

```java
NetworkModule module = new NetworkModule.Builder()
        .baseUrl("https://example.invalid/api")
        .connectTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build();
```

导入 `core.network.NetworkModule` 与 `java.util.concurrent.TimeUnit`。示例域名需替换成你的服务地址。当前 API 使用 `new NetworkModule.Builder()`，没有静态 `builder()` 方法。

模块适合由一个服务器会话或业务服务持有，重复请求复用它。不要每 tick 创建客户端和线程池。

## 请求与解码

使用 [Codec 专题](data/codecs.md)中的 `SeasonStats`：

```java
module.newRequest(SeasonStats.CODEC)
        .setRequestMethod(RequestMethod.GET)
        .addPath("seasons/current")
        .executeAsync()
        .thenAccept(response -> {
            if (!response.isSuccessful() || response.getData() == null) return;
            SeasonStats snapshot = response.getData();
            server.execute(() -> SeasonStore.load(snapshot));
        });
```

`RequestMethod` 位于 `core.network`；`server` 是发起任务的服务器，`SeasonStore` 见[独立数据保存](persistence.md)。真实业务还需处理异常并丢弃已经过期的请求结果。

直接在请求构造器上调用 `executeAsync()`。`buildRequest()` 返回底层 `HttpRequest`，不能再链式调用框架的 `executeAsync()`。

## 响应与失败

`ApiResponse` 提供状态码、响应头、原始正文、解码数据和错误。非 2xx 响应或 Codec 解码错误应通过 `isSuccessful()` 等字段处理；连接等异常也可能使 Future 异常完成。

连接超时只限制建立连接的等待，不等于完整业务请求的超时。需要整体截止时间时，可在异步任务外明确设置超时策略，并决定失败后保留旧数据还是显示不可用状态。

## 生命周期

异步线程负责 HTTP 与纯数据解码，服务器执行器负责地图和玩家状态。服务器关闭或业务服务销毁时调用 `shutdown()`。当前实现会关闭通过客户端可取得的自定义执行器并标记模块关闭；不要把共享线程池交给一个随地图销毁的模块独占管理。

文件落盘、进度和下载任务的执行器行为见[文件下载](network/downloads.md)。
