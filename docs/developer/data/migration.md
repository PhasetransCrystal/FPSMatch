---
title: 存档版本与迁移
description: 为独立存档定义逐版本 JSON 转换。
---

# 存档版本与迁移

`SaveHolder.withVersion()` 设置当前格式版本。`ISavePort` 保存时使用带 `version` 和 `data` 的包装；读取时发现版本不一致，会在 Codec 解码之前调用 `DataFixer`。

## 改名为什么需要迁移

假设第一版的赛季字段叫 `seasonName`，第二版叫 `season`。直接换 Codec 会让旧文件缺少必需字段。应该先转换原始 JSON：

```java
DataFixer.getInstance().registerJsonFixer(SeasonStats.class, 1, oldJson -> {
    var data = oldJson.getAsJsonObject().deepCopy();
    if (!data.has("season") && data.has("seasonName")) {
        data.add("season", data.remove("seasonName"));
    }
    return data;
});
```

`DataFixer` 位于 `core.persistence.datafixer`。这段注册代码放在公共初始化阶段，早于数据加载。同时将对应 SaveHolder 改为 `.withVersion(2)`。`SeasonStats` 定义见 [Codec](codecs.md)。

## 逐版本转换

`fromVersion = 1` 表示从 1 转为 2。升级到 3 时再注册 2 到 3，旧文件会按顺序走过每一步。缺少任意一步时会抛出异常，不会自动猜测新字段。

没有版本包装的旧数据被视为版本 0；如果确实支持它，就提供 0 到 1 的转换。当前 DataFixer 处理对象形式的 JSON，并且不支持降级读取。

## 默认值不是恢复策略

`withInitializer()` 提供创建初始数据时的默认对象，不代表所有读取异常都会自动回退。损坏 JSON、缺少迁移和业务不合法是不同情况，应由调用方决定保留原文件、拒绝加载还是显式创建新数据。

现有目录读取会记录解码异常。不要在文档或业务代码中把“调用返回了”直接等同于成功恢复全部存档。

## 保留业务含义

计数单位由秒改成 tick 时，迁移必须换算数值；统计从玩家名改成 UUID 时，需要能解释旧身份的来源。只改键名或版本号不能解决业务含义的变化。
