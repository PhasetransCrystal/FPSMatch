---
title: 设置解析与生效时机
description: 为复杂设置提供 Codec、命令解析器与明确的生效边界。
---

# 设置解析与生效时机

配置系统负责保存值，玩法决定值何时生效。整数类型正确，并不意味着目标分可以为负数；已经创建的回合计时器也不会因为配置变化自动重建。

## 在输入时拒绝非法值

`Setting` 可以同时接收 Codec 和字符串解析器。下面放在地图字段中：

```java
private final Setting<Integer> targetScore = addSetting(new Setting<>(
        "elimination", "targetScore", Codec.intRange(1, 100), 5,
        text -> {
            int value = Integer.parseInt(text);
            if (value < 1 || value > 100) throw new IllegalArgumentException("Expected 1..100");
            return value;
        }));
```

Codec 限制 JSON 与网络解码值，解析器限制命令输入。`Setting.set(T)` 直接赋值，不会先通过 Codec；程序自己的调用仍要遵守范围。

## 为复杂对象定义设置

使用 `new Setting<>(category, name, codec, defaultValue)` 可以保存复杂数据，但这个构造器没有字符串解析器，`parse(String)` 会返回 `false`。因此不能仅提供 Codec，就宣称复杂配置已经能通过 `settings set` 编辑。

可选择增加明确的解析器、提供专门命令，或只允许通过配置文件编辑。配置对象示例见 [Codec](../data/codecs.md)。

## 选择生效边界

| 配置 | 合适的读取位置 | 修改后的行为 |
| --- | --- | --- |
| 目标分 | `start()` | 下一场比赛使用 |
| 准备、战斗时长 | `buildRoundLifecycle()` | 下一轮使用 |
| 是否允许中途加入 | 加入入口 | 后续请求使用 |
| HUD 颜色 | 客户端快照更新 | 后续渲染使用 |

不要在修改一个数值后无条件重建全部比赛对象。例如重建回合计时器会丢失已经消耗的时间，可能改变正在进行的比赛。

## 稳定配置名

分类用于组织 UI，配置名用于识别值。修改默认值不会自动覆盖现有存档；改名需要迁移旧字段。地图 `reset()` 与 `Setting.reset()` 也不同：前者重置比赛，后者将单个配置恢复为默认值。
