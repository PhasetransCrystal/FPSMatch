---
title: TaCZ 动画与旁观物品
description: 了解 TaCZ 专用客户端动画入口及旁观渲染适配边界。
---

# TaCZ 动画与旁观物品

通用枪械接入通过 `IGunProvider` 完成，TaCZ 的第一人称动画和旁观物品显示则还有专用客户端适配。这些类依赖 TaCZ 的状态机和物品结构，应放在确认 TaCZ 已加载的客户端兼容入口中使用。

## 动画控制入口

`compat.tacz.client.animation.GunAnimationController` 提供以下静态方法：

| 方法 | 用途 |
| --- | --- |
| `handleReload(entity, playSound)` | 触发换弹表现，指定是否播放声音 |
| `cancelReload(entity)` | 取消换弹表现 |
| `handleShoot(entity)` | 开火动画及相关枪口、声音和屏幕效果 |
| `handleInspect(entity)` | 检视表现 |
| `cancelInspect(entity)` | 取消检视 |

参数是客户端 `LivingEntity`。控制器使用当前渲染物品和 TaCZ 动画状态机；调用前必须已经有正确的目标与枪械状态。它不是所有武器模组通用的动画 API，也不会代替服务端开火、弹药扣除和换弹判定。

已有兼容流程可能已经根据同步消息调用这些入口。新增表现时应先确定事件的唯一触发点，避免在自己的 tick 和现有网络处理器中同时触发同一动画。

## 旁观中的物品镜像

`compat.tacz.client.fakeitem.ClientFakeItemManager` 在本地旁观者选中的快捷栏槽位中保存原物品，并放入目标的物品副本供渲染使用。它提供 `equipOrUpdateForSpectator()`、`tickUpdate()` 和 `revertFakeItem()`，以及查询当前镜像的接口。

这个镜像会临时修改客户端背包表示，不是发给玩家的真实装备。它也不建立服务端购买或持有关系，不能用于判断玩家是否有枪或有多少弹药。目标离开、旁观结束和重置时应由同一兼容流程负责恢复。

如果只是给新枪械模组提供统一识别、弹药或物品信息，应先实现[枪械提供器](gun-provider.md)，不需要直接复用 TaCZ 镜像管理器。

## 与相机和旁观目标衔接

`compat.spectate` 下还包含 TaCZ、LR Tactical 及其网络适配。职责是让已经授权的旁观目标表现同步到客户端；长期目标由[旁观系统](../client/spectator.md)管理，临时演出由[CameraDirector](../camera/first-scene.md)管理。

新增兼容时，先明确要补的是目标同步、渲染物品还是动画动作，再接入对应层。不要在兼容回调里另建一套每帧相机控制，导致它与 Director 竞争。

源码入口：[compat/tacz/client](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/compat/tacz/client)。
