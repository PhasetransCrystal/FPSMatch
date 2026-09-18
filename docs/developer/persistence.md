---
title: 独立数据保存
description: 用 SaveHolder 注册独立数据，并实现完整的加载和保存回调。
---

# 独立数据保存

每张地图的规则使用 `Setting`，组件自己的配置使用 `Savable`。当数据独立于地图实例，例如赛季胜场或全局配置时，再使用 `SaveHolder`。

## 定义数据与内存持有者

先使用 [Codec 专题](data/codecs.md)中的 `SeasonStats`。下面的类保存已加载赛季，并提供数据管理器需要的两个回调：

```java
package com.example.elimination;

import java.util.HashMap;
import java.util.Map;
import net.ptcrys.fpsmatch.core.persistence.FPSMDataManager;

public final class SeasonStore {
    private static final Map<String, SeasonStats> seasons = new HashMap<>();

    public static void clear() { seasons.clear(); }

    public static void load(SeasonStats value) {
        seasons.put(value.season(), value);
    }

    public static void save(FPSMDataManager manager) {
        for (SeasonStats value : seasons.values()) {
            manager.saveData(value, value.season(), true);
        }
    }
}
```

`load` 接收解码完成的数据；`save` 接收管理器，由业务代码枚举需要保存的对象。注册一个 Codec 不会让管理器自动找到所有内存实例。

## 注册类型

将以下方法放入 Forge 事件订阅类：

```java
@SubscribeEvent
public static void registerData(RegisterFPSMSaveDataEvent event) {
    SeasonStore.clear();
    event.registerData(SeasonStats.class, "EliminationSeasons",
            new SaveHolder.Builder<>(SeasonStats.CODEC)
                    .withVersion(1)
                    .withInitializer(() -> new SeasonStats("default", Map.of()))
                    .withLoadHandler(SeasonStore::load)
                    .withSaveHandler(SeasonStore::save)
                    .build());
}
```

`RegisterFPSMSaveDataEvent` 位于 `common.event.register`，`SaveHolder` 位于 `core.persistence`。这里清空上一个服务器会话的内存，避免集成服务器切换存档时混入旧数据。若业务允许多个并行管理器，应进一步按管理器作用域保存数据。

## 实际保存流程

`saveAllData()` 调用各类型的保存回调，示例再通过 `saveData(value, fileName, overwrite)` 写入注册目录。文件名不含扩展名；`overwrite = true` 表示使用当前快照覆盖内容。设置为 `false` 时，已有数据会交给合并器；默认合并器仍选择新值。

`readAllData()` 扫描注册类型对应目录，解码后调用加载回调。`withInitializer()` 只提供初始对象，不会自动往 `SeasonStore` 添加一条记录，也不是任意解码失败时的回退处理。

## 当前按名读取的边界

当前源码的 `readSpecificData()` 按基础数据目录查找文件，而 `saveData()` 会使用类型注册的子目录。不能假设相同文件名在这两条路径中自然对应。需要按类型目录加载时，优先使用现有目录读取流程，或在自己的访问层明确处理路径。

## 异步数据

调用 `saveDataAsync()` 前，在服务器线程生成不可变快照。后台编码时继续修改同一个集合，会让保存结果不再对应某一时刻的比赛状态。

当前写入器会记录部分 I/O 错误而不向外重新抛出，因此 Future 正常完成也不能被当成强持久化确认。需要外部服务或排行榜严格确认落盘时，应提供明确的错误传播与业务确认机制。

继续阅读：[存档迁移](data/migration.md) · [线程与资源生命周期](reliability.md)。
