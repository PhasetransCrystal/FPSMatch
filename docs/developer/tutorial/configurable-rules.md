---
title: 6. 添加地图设置
description: 把目标分、准备时长和回合时长交给地图配置。
---

# 6. 添加地图设置

到目前为止，每张淘汰赛地图都使用写死的 5 分、5 秒准备和 90 秒回合。本章用 `Setting<Integer>` 让不同竞技场拥有不同规则。

## 声明设置

导入 `net.ptcrys.fpsmatch.core.data.Setting` 与 `net.minecraft.util.Mth`，在地图类中添加：

```java title="EliminationMap.java"
private final Setting<Integer> targetScore = addSetting("elimination", "targetScore", 5);
private final Setting<Integer> roundSeconds = addSetting("elimination", "roundSeconds", 90);
private final Setting<Integer> waitingSeconds = addSetting("elimination", "waitingSeconds", 5);
private int matchTargetScore;
```

第一个字符串是配置分类，第二个是该地图内唯一的配置名，最后是默认值。它们会进入地图的设置集合，参与配置读写。

## 决定修改何时生效

目标分在一场比赛开始时确定。如果管理员中途改小目标分，不应让正在进行的比赛突然结束。因此，在 `start()` 的 `isStart = true;` 后添加：

```java title="EliminationMap.start()"
matchTargetScore = Mth.clamp(targetScore.get(), 1, 100);
```

然后将 `onNextRoundRequested()` 中的两个 `>= 5` 改为 `>= matchTargetScore`。

回合时长则在创建下一轮计时器时读取：

```java title="EliminationMap.java"
protected RoundLifecycle<ServerTeam, EndReason> buildRoundLifecycle() {
    return lifecycleBuilder()
            .waitingTicks(Mth.clamp(waitingSeconds.get(), 0, 60) * 20)
            .roundTicks(Mth.clamp(roundSeconds.get(), 10, 3600) * 20)
            .roundEndTicks(5 * 20)
            .addRule(lifecycle -> eliminationResult())
            .timeoutResult(() -> new RoundResult<>(null, EndReason.TIMEOUT))
            .build();
}
```

`Setting` 保存用户配置，`Mth.clamp` 限制本次运行实际采用的值。这里的限制不会改写用户保存的配置；若需要在输入阶段拒绝非法值，见[设置解析与生效时机](../configuration/validation.md)。

## 修改与保存

框架已提供读取和修改设置的命令，无需为每个数值单独写命令：

```text
/fpsm map modify elimination training settings set targetScore 3
/fpsm map modify elimination training settings set roundSeconds 60
/fpsm map modify elimination training settings save
```

`set` 修改内存配置，`save` 写入地图配置文件。当前比赛使用开始时捕获的目标分，下一轮使用重新读取的回合时长。`reset()` 清理比赛状态时不重置这些配置。

## 选择数据位置

这里没有引入独立存档管理器，因为数据属于每张地图的规则。比分是比赛过程数据，回合计时器是运行时对象；它们都不应因为“需要保存设置”而一并塞进配置文件。

[本章完整源码](https://github.com/PhasetransCrystal/FPSMatch/tree/master/examples/elimination/chapters/06) · 上一章：[回合](multiple-rounds.md) · 下一章：[能力](first-capability.md)
