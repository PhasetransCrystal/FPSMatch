---
title: 保存能力数据
description: 为组件实现 Savable，并区分持久配置与比赛临时状态。
---

# 保存能力数据

当数据属于某个能力时，能力可以实现 `FPSMCapability.Savable<T>`。地图设置与能力状态不必混在同一份数据模型中。

## 定义保存快照

下面保存一个区域效果的强度。它是完整能力类，注册和安装方式与教程第七章相同：

```java
package com.example.elimination;

import com.mojang.serialization.Codec;
import net.ptcrys.fpsmatch.core.capability.FPSMCapability;
import net.ptcrys.fpsmatch.core.capability.map.MapCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;

public final class ZoneStrength extends MapCapability implements FPSMCapability.Savable<Integer> {
    private int strength = 1;

    public ZoneStrength(BaseMap map) { super(map); }

    @Override
    public Codec<Integer> codec() { return Codec.intRange(1, 10); }

    @Override
    public Integer read() { return strength; }

    @Override
    public Integer write(Integer value) {
        strength = value;
        return strength;
    }
}
```

`getName()` 已由能力基类提供默认实现。这里的命名值得留意：`read()` 读取能力当前状态，供保存流程编码；`write(value)` 将解码结果写回能力。它们不是直接读写磁盘的方法。

## 保存哪些字段

应该保存可重建运行状态所需的稳定值，例如区域坐标、数量上限、配置名称。`ServerLevel`、`ServerPlayer`、定时任务与监听器都不属于保存数据。

如果快照包含集合，返回副本，避免编码期间继续被比赛逻辑修改。复合对象的 Codec 参见[定义数据模型](../data/codecs.md)。

## 何时写盘

修改字段只改变内存。宿主数据保存流程收集能力快照，再进行编码与写盘。附属模组通过已有的保存入口安排保存，不要在能力 `tick()` 中反复打开 JSON 文件。

能力只实现 `Savable` 不会自动得到网络同步。客户端需要显示强度时，应另外建立同步协议，见[能力同步](synchronization.md)。

## 重置与配置保留

`reset()` 应按业务区分临时状态和配置。上面的强度是持久配置，所以没有在比赛重置时将其设回 1；否则每次开始新比赛都可能覆盖地图制作者的选择。
