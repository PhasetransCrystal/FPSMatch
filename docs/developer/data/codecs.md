---
title: Codec 与数据模型
description: 用 Codec 描述可保存的规则、列表和独立数据对象。
---

# Codec 与数据模型

Codec 同时描述数据怎样编码，以及怎样还原为 Java 对象。FPSMatch 用它保存地图设置、能力快照和独立存档，因此可以为不同宿主复用同一个数据模型。

## 描述一个复合对象

下面定义赛季名和各玩家的胜场数：

```java
package com.example.elimination;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;

public record SeasonStats(String season, Map<String, Integer> wins) {
    public SeasonStats {
        wins = Map.copyOf(wins);
    }

    public static final Codec<SeasonStats> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("season").forGetter(SeasonStats::season),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("wins")
                            .forGetter(SeasonStats::wins)
            ).apply(instance, SeasonStats::new));
}
```

`fieldOf` 指定文件中的字段名，`forGetter` 提供从对象取值的方法，`apply` 指定如何根据字段创建对象。构造器复制映射，使传给保存线程的数据不再跟随原集合变化。

## 必需字段与可选字段

`fieldOf("wins")` 要求数据中存在该字段。新增一个可以缺省的字段时，可用 `optionalFieldOf(name, defaultValue)`；原字段改名或改变含义则需要迁移。

不要仅为了让旧文件“能读”就给所有字段加默认值。例如丢失赛季身份后，默认归入新赛季可能混淆统计。

## 集合与标识

列表使用 `elementCodec.listOf()`；键值表使用 `Codec.unboundedMap(keyCodec, valueCodec)`。玩家身份应使用稳定 UUID 或它的字符串表示，而不是会改名的显示名。资源使用稳定 ID，不保存实际加载的纹理或世界对象。

## 接入保存工具

地图规则把 Codec 交给 `Setting<T>`，能力实现 `Savable<T>`，独立业务对象交给 `SaveHolder<T>`。Codec 本身不决定保存目录、频率、线程或消息接收者。

接下来阅读[独立数据保存](../persistence.md)或[版本迁移](migration.md)。
