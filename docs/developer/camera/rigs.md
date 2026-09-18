---
title: 镜头位置、轨迹与碰撞
description: 用 CameraPose、CameraFrame 和内置 Rig 组合镜头。
---

# 镜头位置、轨迹与碰撞

一个 Rig 只负责“这个时间点应该看到什么”。它不申请控制权、不自行切换玩家相机，也不推进播放时间。同一个 Rig 的 `sample(double ticks)` 可能在不同渲染帧被多次调用，因此音效和比赛事件应放到时钟标记或业务逻辑中。

## 描述画面

`CameraPose` 保存世界坐标、yaw、pitch、roll 和 FOV。角度单位是度；位置和角度必须为有限值。FOV 可以是 `Double.NaN`，表示沿用玩家 FOV；固定值必须严格介于 0 与 180 之间。

```java
CameraPose pose = new CameraPose(center.add(0, 6, 10), 0, 0)
        .lookAt(center);
CameraFrame frame = CameraFrame.independent(pose);
```

`CameraFrame.independent(pose)` 使用独立镜头；`CameraFrame.attached(entity)` 跟随实体视角。完整构造器 `CameraFrame(entity, pose, fade)` 允许实体与姿态同时存在，并携带黑幕透明度。选择实体影响绑定，选择姿态影响位置与角度，两者并非同一个字段。

## 选择内置 Rig

| Rig | 构造参数与用途 |
| --- | --- |
| `FixedRig` | 一个 `CameraPose`；固定展示点 |
| `EntityViewRig` | `Supplier<Entity>`；动态查找要跟随的实体 |
| `PathRig` | 关键帧列表与可选注视目标；沿路径移动 |
| `OrbitRig` | `Supplier<SpectateTarget>`；围绕目标旋转，内置平滑与碰撞 |
| `CollisionRig` | 原始 Rig、锚点供应器和退让距离；裁剪会穿墙的位置 |

这些类位于 `net.ptcrys.fpsmatch.common.client.camera.rig`。跟随实体时按 ID 从当前客户端世界查找，并在会话的有效性条件中处理目标消失，避免长期持有旧世界中的实体。

## 沿竞技场移动

下面的片段替换第一章中的 `FixedRig`。它使用该章已有的 `arenaCenter`，需要导入 `PathRig` 和 `java.util.List`。

```java
CameraRig rig = new PathRig(List.of(
        new PathRig.Keyframe(0,
                new CameraPose(arenaCenter.add(8, 5, 8), 0, 0)),
        new PathRig.Keyframe(60,
                new CameraPose(arenaCenter.add(-8, 5, 8), 0, 0))
), () -> arenaCenter);
```

关键帧列表不能为空，时间必须是有限非负数，并严格递增。内置插值在每段起止处缓入缓出，yaw 和 roll 按角度环绕插值。超过最后一帧时保持最终姿态；Rig 自身不会结束会话，持续时间由 `Shot` 或调用者决定。

第二个参数让镜头持续朝向竞技场中心。传 `null` 时使用关键帧自身的朝向。若采用移动目标供应器，供应器返回 `null` 的那一帧也会保留关键帧朝向。

## 避免镜头穿墙

```java
CameraRig safeRig = new CollisionRig(rig, () -> arenaCenter, 0.2);
```

碰撞包装器从锚点向候选位置检测方块轮廓，并在碰撞面前退让指定距离。它忽略流体，也不会替你加载远方区块。应在位置偏移、抖动等处理完成后再包装，以免后续偏移把镜头重新推入墙体。

## 围绕目标旋转

`OrbitRig` 从 `Supplier<SpectateTarget>` 取得锚点和半径。目标已由服务端旁观流程同步时，可以使用 `new OrbitRig(SpectateState::getTarget)`，并通过 `setAngles(yaw, pitch)` 设置初始方向。需要导入 `common.client.spec.SpectateState` 与 `rig.OrbitRig`。

交互式环绕配合 `CameraPolicy.ORBIT`，框架把鼠标输入转交给 Rig 的 `turn()`。目标为空时采样返回 null，因此临时会话的有效性条件应包含目标仍存在。若只是更新常规旁观目标，应直接使用基础旁观系统，不必额外申请会话。

OrbitRig 保存自己的角度和平滑状态，并按实际帧间隔平滑恢复距离；它不同于完全由时间计算的 PathRig。同一个有状态 OrbitRig 不宜被多个并行用途共享，也不适合当作可以任意回溯采样的离线轨迹。

## 自定义 Rig

实现 `CameraRig` 的 `sample(double ticks)` 并返回有效 `CameraFrame` 即可。使用传入时间计算姿态，将播放时长留给序列。若策略使用 `LookInput.ORBIT`，可以实现 `turn(float yaw, float pitch)` 接收鼠标输入；不需要同时修改玩家旋转。

继续阅读：[序列与转场](sequences.md) · [客户端旁观](../client/spectator.md)。

源码入口：[common/client/camera/rig](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/common/client/camera/rig)。
