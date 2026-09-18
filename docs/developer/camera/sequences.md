---
title: 镜头序列、转场与时间标记
description: 组合连续镜头，并用 SequenceClock 安排一次性场景动作。
---

# 镜头序列、转场与时间标记

`CameraSequence` 把多个 Rig 串成一个有限时长的场景。序列本身也是 `CameraRig`，但通过 `CameraDirector.playSequence()` 播放时，框架会在总时长结束后自动释放会话。

## 组合两段镜头

在[第一个相机场景](first-scene.md)中，保留中心坐标和 `playSequence()` 调用，将序列替换为：

```java
CameraPose wide = new CameraPose(arenaCenter.add(12, 8, 12), 0, 0)
        .lookAt(arenaCenter);
CameraPose close = new CameraPose(arenaCenter.add(4, 3, 4), 0, 0)
        .lookAt(arenaCenter);
CameraSequence sequence = new CameraSequence(List.of(
        new CameraSequence.Shot(40, new FixedRig(wide),
                CameraSequence.Transition.CUT, 0),
        new CameraSequence.Shot(60, new FixedRig(close),
                CameraSequence.Transition.BLEND, 20)
));
```

总时长是 **100 tick**。第二段的前 20 tick 用来转场，已经包含在该段的 60 tick 内。每个 Rig 收到的是所在镜头的局部时间；整个会话时钟则持续从 0 走到 100。

每段时长必须大于 0，转场时长必须处于 0 到该段时长之间。序列不能为空。第一段没有前一个镜头，它的转场设置不会生效。

## 选择转场

| 转场 | 行为 | 适用场景 |
| --- | --- | --- |
| `CUT` | 立即使用新画面 | 视点直接切换 |
| `BLEND` | 从上一段末尾姿态插值到当前段姿态 | 两个具有 pose 的空间镜头 |
| `FADE` | 前半段变黑，中点换画面，后半段变亮 | 相距较远的视点，或实体视角切换 |

`BLEND` 需要前后画面都有 `pose`。只附着实体、没有显式姿态的画面会直接使用新镜头，不能据此期待平滑飞行。需要附着视角的黑幕切换时可选 `FADE`。

## 在指定时间执行动作

`SequenceClock` 位于 `net.ptcrys.fpsmatch.common.camera`。在交给 Director 前注册标记：

```java
SequenceClock clock = new SequenceClock();
clock.at(0, true, () -> setCaption("竞技场"));
clock.at(40, true, () -> setCaption("准备开始"));
clock.at(40, false, () -> playTransitionSound());
```

这是场景控制类中的片段，`setCaption` 与 `playTransitionSound` 是扩展自己提供的方法。把这个 `clock` 传给 `playSequence()`，并在结束回调中释放本次字幕。第 0 tick 的标记会在时钟首次推进时执行，不是在注册时执行。

`catchUp=true` 适用于状态切换，如字幕和阶段名称；`false` 适用于一次性声音等错过便不再补播的动作。标记必须在对应 tick 已分发之前注册。

## 跳到后续时间

场景需要对齐服务端进度时，可在成功取得会话之后调用 `session.clock().seek(targetTick)`。时间只能向前跳。跨过的标记中，仅 `catchUp=true` 的动作会执行；其他标记会被跳过，也不会在以后补播。

不要在还没取得相机控制权时先 `seek()`：它可能已经执行字幕或音效动作，而之后的会话申请仍可能失败。渲染用的 `sample(partialTick)` 只读取时间，不触发标记。

`cancel()` 会停止时钟；会话结束时 Director 已负责调用它。`reset()` 会保留已注册动作并重置分发状态，因此复用同一时钟可能重新执行旧场景动作。通常每次播放创建新的时钟更清楚。

继续阅读：[输入与界面策略](policies-and-overlay.md) · [生命周期](lifecycle.md)。

源码入口：[common/client/camera/CameraSequence.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/camera/CameraSequence.java)。
