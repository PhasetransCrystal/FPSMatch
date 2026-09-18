---
title: 设置系统
description: 使用 Setting 和 Codec 暴露每张地图的可配置规则。
---

# 设置系统

`Setting<T>` 适合“每张地图一份”的规则配置，例如目标分数、回合时长和是否允许中途加入。它同时支持命令解析、JSON 保存与网络缓冲区同步。

```java
private final Setting<Integer> targetScore =
        addSetting("match", "targetScore", 5);
private final Setting<Integer> roundSeconds =
        addSetting("match", "roundSeconds", 90);
private final Setting<Boolean> friendlyFire =
        addSetting("combat", "friendlyFire", false);
```

分类用于配置 UI 分组；配置名应在同一地图内唯一且保持稳定。改名等同于配置格式变更。

## 支持类型

`BaseMap.addSetting` 提供 `int`、`long`、`float`、`double`、`byte`、`boolean` 和 `String` 重载。其他类型使用 Codec：

```java
private final Setting<MyRules> rules = addSetting(
        new Setting<>("match", "rules", MyRules.CODEC, MyRules.DEFAULT));
```

核心 API：`get()`、`set(T)`、`reset()`、`getDefaultValue()`、`codec()`、`getConfigName()`、`getCategory()`、`parse(String)`、`toJson()`、`fromJson()`、`readFromBuf()`、`writeToBuf()`。

## 保存流程

`BaseMap.configToJson()` 与 `configFromJson()` 统一处理 `settings()` 中的设置；`loadConfig()` 和 `saveConfig()` 负责地图配置文件。通常不需要为普通 Setting 另建 `SaveHolder`。

为数值设置增加业务范围验证。普通 `Codec.INT` 不限制业务范围；可以使用范围 Codec，但程序直接 `set()` 的值仍需要调用方约束。

```java
int seconds = Mth.clamp(roundSeconds.get(), 10, 3600);
```

跨地图排行榜、赛季或账号数据不属于 Setting，应使用[持久化系统](persistence.md)。

## 复杂配置

[设置解析与生效时机](configuration/validation.md)进一步说明 Codec、命令解析器、程序赋值之间的区别，以及如何决定修改从下一轮还是下一场生效。
