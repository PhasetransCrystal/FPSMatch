---
title: 创建第一个相机场景
description: 从三秒竞技场镜头开始，了解 CameraDirector、CameraRig 和 CameraSession。
---

# 创建第一个相机场景

相机系统负责临时接管玩家视角，适合开场介绍、地图预览、死亡镜头和结算演出。FPSMatch 已经连接客户端 tick、相机位置、角度、FOV 和渲染事件；扩展只需要描述镜头并申请一个会话。

本章在淘汰赛示例中加入一个持续 60 tick 的竞技场镜头。你可以独立阅读本章；只需具备客户端代码隔离和 Forge 网络处理的基础。

## 三个对象的职责

`CameraRig` 根据时间返回当前画面；`CameraDirector` 决定哪个场景可以控制视角；`CameraSession` 是调用者持有的会话句柄，用于查询状态和提前停止。一次播放使用一个 `SequenceClock`，由框架推进。

这些入口位于 `net.ptcrys.fpsmatch.common.client.camera`。时钟位于 `net.ptcrys.fpsmatch.common.camera`。虽然路径包含 `common`，相机客户端类仍然只能在物理客户端加载。

## 定义三秒镜头

在示例工程的客户端包中创建下面的类。完整文件也保存在 `examples/elimination/reference/java/com/example/elimination/client/ArenaCamera.java`，它是独立专题示例，不会自动加入教程八个章节的源码集。

```java title="com/example/elimination/client/ArenaCamera.java"
package com.example.elimination.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.ptcrys.fpsmatch.common.camera.SequenceClock;
import net.ptcrys.fpsmatch.common.client.camera.*;
import net.ptcrys.fpsmatch.common.client.camera.rig.FixedRig;

import java.util.List;

// 只从物理客户端的主线程调用。
public final class ArenaCamera {
    private static CameraSession session;

    public static boolean playIntro(Vec3 arenaCenter) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return false;
        var level = mc.level;
        CameraPose pose = new CameraPose(
                arenaCenter.add(8, 5, 8), 0, 0, 0, 70
        ).lookAt(arenaCenter);
        CameraSequence sequence = new CameraSequence(List.of(
                new CameraSequence.Shot(60, new FixedRig(pose),
                        CameraSequence.Transition.CUT, 0)
        ));
        CameraSession acquired = CameraDirector.playSequence(
                "elimination_addon:arena_intro",
                CameraDirector.CINEMATIC_PRIORITY,
                sequence, CameraPolicy.CINEMATIC, new SequenceClock(),
                () -> mc.level == level && mc.player != null,
                reason -> {
                    // 在这里释放本次场景独占的字幕、音效等资源。
                    // 不要清除其他场景的全局状态。
                }
        );
        if (acquired == null) return false;
        session = acquired;
        return true;
    }

    public static boolean isPlaying() {
        return session != null && session.isActive();
    }

    public static void stop() {
        CameraSession previous = session;
        session = null;
        if (previous != null) previous.close();
    }
}
```

`arenaCenter` 是已加载地图内希望展示的世界坐标。相机位于它上方并向两个水平方向各偏移 8 格，`lookAt()` 让镜头朝向中心。FOV 固定为 70 度；不需要固定 FOV 时可使用只接收位置、yaw、pitch 的构造器。

`Shot` 的时长单位是 tick。这里在正常每秒 20 tick 的节奏下播放约三秒，然后自动结束。`CINEMATIC` 同时管理输入和界面，具体显示行为见[相机策略](policies-and-overlay.md)。

## 从客户端流程启动

在已有客户端按钮回调或已经通过 `enqueueWork()` 调度的 S2C 处理逻辑中调用 `ArenaCamera.playIntro(center)`。若由服务端发起演出，数据包传递地图身份和中心坐标，客户端在确认当前地图后创建场景；不要从服务端直接引用这个类。

返回 `false` 表示没有取得会话，例如玩家已离开世界，或更高优先级的场景正在播放。收到失败结果时结束这次演出请求即可。场景不是必须播放成功才能推进服务端比赛的计时器。

## 结束与恢复

正常播放完毕会自动恢复当前玩家或旁观视角。自己的界面关闭时可以调用 `stop()` 提前结束；旧句柄的 `close()` 不会关掉后来取得控制权的其他会话。

不要用 try-with-resources 包围 `playSequence()`：镜头需要跨帧存在，方法返回时就关闭会让场景立即结束。也不需要再注册 tick 监听器推进这个时钟。

下一步：[镜头位置与轨迹](rigs.md) → [镜头序列与时间标记](sequences.md) → [会话生命周期](lifecycle.md)。

源码入口：[common/client/camera/CameraDirector.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/camera/CameraDirector.java)。
