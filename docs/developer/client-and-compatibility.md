---
title: 客户端与集成
description: 选择 HUD、资源、枪械、脚本与插件的扩展入口。
---

# 客户端与集成

客户端展示与第三方集成有各自的生命周期。本节按你准备实现的功能提供入口，公共比赛规则仍通过地图、队伍和回合系统组织。

## 客户端开发

[HUD 与客户端状态](client/hud.md)介绍如何注册 Forge 覆盖层、接收快照和清理旧状态。[资源与本地化](client/resources.md)介绍地图贴图路径、资源命名空间和语言键。

第一次实现展示时，可以直接沿着[比分 HUD 教程](tutorial/score-hud.md)完成从服务端到屏幕的整条链路，再替换为自己的布局。

按功能扩展时，以下系统可以独立接入：

| 功能 | 入口 |
| --- | --- |
| 玩法 HUD 与 Tab | [HUD 管理器](client/hud-manager.md) · [玩家列表](client/tab.md) |
| 客户端快照 | [数据与重置](client/global-data.md) |
| 开场、预览和死亡镜头 | [相机与演出](camera/first-scene.md) |
| 队友附着和环绕视角 | [客户端旁观](client/spectator.md) |
| 音乐和 MVP 默认曲目 | [音乐播放](client/music.md) |
| 地图工具的世界提示 | [区域与点位预览](client/area-previews.md) |

## 枪械、脚本与插件

| 入口 | 适合的工作 | 前置环境 |
| --- | --- | --- |
| [枪械 Provider](integration/gun-provider.md) | 识别第三方武器、读写弹药、提供 HUD 数据 | 相应枪械模组 |
| [TaCZ 客户端适配](integration/tacz-client.md) | 动画、旁观物品镜像和表现同步 | TaCZ，物理客户端 |
| [KubeJS 事件](integration/kubejs.md) | 为已有地图追加脚本限制、通知和小型扩展 | KubeJS 及其依赖 |
| [Bukkit 桥接](integration/bukkit.md) | 向插件发布地图事件 | 提供 Bukkit API 的混合服务端 |

这些入口不是可互换的同一套 API。Provider 适配物品，脚本消费包装事件，Bukkit 桥接只覆盖当前已实现的通知。

## 类加载边界

包含 `Minecraft` 或渲染类的入口只在 `Dist.CLIENT` 加载。引用可选模组类型的代码放入独立兼容类，并在确认模组存在之后实例化。仅在方法体中判断一次模组是否安装，不能保护公共字段和方法签名中的缺失类型。

完整的线程与宿主生命周期说明见[线程与资源生命周期](reliability.md)。
