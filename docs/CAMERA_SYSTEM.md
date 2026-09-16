# 镜头系统

FPSMatch 提供客户端镜头底座，BlockOffensive 提供死亡和入场场景。普通玩家视角由原版计算；受限观战和临时场景经 `CameraDirector` 仲裁，`CameraBackend` 是项目中唯一的相机实体与自定义姿态写入入口。

## 控制权与生命周期

- 基础视角来自当前玩家状态和 `SpectateState`；观战目标包始终接收，临时场景期间不会直接切镜头。
- `CameraSession` 是独占句柄。低优先级请求返回 `null`，同级或更高级请求替换当前会话并触发一次清理。旧句柄的 `close()` 不会关闭新会话。
- 默认优先级：死亡 100，比赛过场 200。结束时重新解析基础视角，不恢复可能已死亡、卸载或失去授权的旧实体。
- `PLAYER_LIFE` 会话在重生时结束；`SCENE` 可跨过重生，例如传送前的入场预备黑幕。地图/比赛重置、退服和世界卸载会清理所有会话。
- 会话有效条件、求值或标记回调失败会取消场景并清理，错误写入日志。
- 所有 API 在客户端主线程调用；服务端不引用 `common.client.camera`。服务端继续决定观战权限、玩家冻结、传送和比赛阶段。

## 镜头行为与呈现

`CameraRig.sample(double ticks)` 返回 `CameraFrame`，包含可选实体绑定、可选姿态和淡黑强度。姿态包括位置、yaw、pitch、roll 和 FOV（`NaN` 表示继承）。

- `EntityViewRig`：绑定真实目标实体，保留 TaCZ 队友第一人称手部和枪械同步。
- `FixedRig`：独立固定机位，由底座维护幽灵相机。
- `OrbitRig`：实例持有环绕角度与半径；碰墙立即收近，离墙缓慢恢复。`SpectatorCameraController` 保留为输入兼容入口。
- `PathRig`：有序关键帧、平滑插值和可选 look-at。跨 ±180° 取最短旋转路径。
- `CollisionRig`：在位移效果之后，将相机约束到锚点与目标机位之间的可见线段；可用于死亡运镜等需要防穿墙的独立镜头。

`CameraPolicy` 声明输入、切换观战、F5、手部、HUD、本地模型、toast、原版晃动和枪械后坐力策略。界面中的鼠标操作、ESC、聊天与命令输入保留。场景 HUD 通过 `CameraOverlayEvent` 绘制，统一入口保证每帧只派发一次。

相机切换会重置原版实体后处理。使用 `session.onCameraBound(...)` 在绑定后重新安装场景特效；结束回调释放自己持有的资源。死亡滤镜只关闭自己创建的 `PostChain`，不会误关其他系统后来装入的效果。

## 时间轴与代码编排

`SequenceClock` 是镜头、演员和音效共同读取的 tick 时钟。调度器推进会话时钟，业务不要再调用该时钟的 `tick()`。渲染只读取 `sample(partialTick)`，不触发事件。

```java
SequenceClock clock = new SequenceClock();
clock.at(0, true, actors::start);
clock.at(20, false, audio::play); // 迟到 seek 时不补播瞬时音效

CameraSequence sequence = new CameraSequence(List.of(
    new CameraSequence.Shot(40, new FixedRig(startPose),
        CameraSequence.Transition.CUT, 0),
    new CameraSequence.Shot(80, pathRig,
        CameraSequence.Transition.BLEND, 10)
));
CameraSession session = CameraDirector.playSequence(
    "mymod:scene", CameraDirector.CINEMATIC_PRIORITY,
    sequence, CameraPolicy.CINEMATIC, clock,
    () -> sceneStillValid(), reason -> cleanupScene()
);
```

每个片段有自己的局部时间。过渡占用新片段开头的时间；支持硬切、平滑混合和淡黑切换。平滑混合用于两个提供姿态的镜头；实体附着镜头应使用硬切或淡黑。`playSequence` 按总时长自动完成；`play` 由场景显式结束，适用于等待观战目标等可变时长流程。

## BO 场景接入

- 死亡：等待游戏模式更新时使用受限固定镜头；正式演出共享 `DeathCameraTimeline` 的时钟。位置、旋转、roll 用同一次连续求值生成。40 tick 后接入有效观战目标，最多再等 40 tick，随后降级为受限死亡地点视角。
- 入场：保留现有构图、演员、武器覆盖和音效。`IntroClientController` 不再创建幽灵实体或直接写相机；镜头在相邻 authored tick 间插值。预备与正式阶段直接交接控制权，旧 STOP 只对匹配的 sequence ID 生效。
- 入场包携带服务端起始 game time，客户端初次收到时追到对应进度，再使用本地逻辑 tick 推进。它依赖原版客户端与服务端 game time 同步，并非额外的精确时钟同步协议。
- BO 网络协议版本从 `1.5.0` 升至 `1.6.0`，服务端与客户端必须一起更新。

## 验证与调试

客户端 `/fpsm camera` 显示当前会话所有者、优先级和 tick，或基础观战模式。

`gradlew check` 包含以下独立回归检查：

- `FPSMatch:cameraSystemCheck`：抢占、旧句柄、清理重入、标记补执行与去重、关键帧和切镜过渡。
- `deathCameraTimelineCheck`：死亡阶段时长、请求间隔、等待超时和重置。
- `deathCameraRigCheck`：不同采样顺序结果一致、帧间插值、最终姿态保持。

游戏内验收还应覆盖：预备阶段重生、死亡中换边、目标未加载/掉线、多人目标切换、墙边环绕、F5、打开聊天/菜单、TaCZ 手部/开镜/后坐力，以及退出重进后无残留黑幕或输入限制。

2026-09-16 本地实机检查：在开发存档副本中成功播放换边预览、用新预览替换旧预览并恢复 `base=FREE`；通过现有 `physics_ragdoll_test` 触发真实死亡流程，观察到 `blockoffensive:death` → `base=C4_ORBIT` → 清理后的 `base=FREE`，并检查死亡画面截图。该环境没有加载 playerAnimator/bendy-lib，演员动画走降级分支；多人队友第一人称同步尚未完成实机验收。
