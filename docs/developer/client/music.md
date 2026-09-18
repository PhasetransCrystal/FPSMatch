---
title: 音乐播放与 MVP 默认曲目
description: 使用资源声音、配置默认 MVP 音乐，并管理播放生命周期。
---

# 音乐播放与 MVP 默认曲目

FPSMatch 的音乐功能分为默认曲目选择和客户端实际播放。`MvpMusicManager` 保存默认声音 ID；`FPSClientMusicManager` 调用客户端音频系统播放资源声音或在线音频。

## 配置资源声音

在 `assets/elimination_addon/sounds.json` 中声明声音事件，并把音频放到 `assets/elimination_addon/sounds/arena_intro.ogg`：

```json title="assets/elimination_addon/sounds.json"
{
  "arena_intro": {
    "sounds": [{ "name": "elimination_addon:arena_intro", "stream": true }]
  }
}
```

在客户端主线程的场景入口调用：

```java
FPSClientMusicManager.playMusic(
        new ResourceLocation("elimination_addon", "arena_intro"));
```

导入 `common.client.music.FPSClientMusicManager` 和 Minecraft 的 `ResourceLocation`。传入的是声音事件 ID，不是 `.ogg` 文件路径。

## 选择播放入口

`playMusic(ResourceLocation)` 会停止管理器当前追踪的音乐，再播放并记录新曲目；`SoundEvent` 重载最终也使用其资源 ID。`playSound()` 播放一次性声音，不会替换管理器追踪的音乐。资源声音入口使用 `VOICE` 音量分类。

`stopMusic()` 操作共享播放状态，也可能停止原版音乐管理器当前曲目。因此多个演出并存时，不要在某个旧场景的清理回调里无条件停止全局音乐。需要独立拥有、重叠播放或按句柄停止的音效，可以直接持有 Minecraft `SoundInstance`，通过 SoundManager 管理。

## 设置 MVP 默认音乐

```java
MvpMusicManager.setDefaultMvpMusic(
        new ResourceLocation("elimination_addon", "arena_intro"));
```

`MvpMusicManager` 位于 `net.ptcrys.fpsmatch.core.music`。内置默认值是 `fpsmatch:mvp.default`；`getDefaultMvpMusic()` 返回当前 ID，`resetToBuiltinDefault()` 恢复内置值，设置 `null` 也会回退。

这个设置是全局默认曲目，不会立即播放音乐，也不会自动选出某局比赛的 MVP。玩法仍需要在结算流程决定获奖玩家，再由相应同步或客户端流程开始播放。

## 在线音乐

`play(OnlineMusic)` 在后台线程中通过 Java Sound 读取音频流，音量取自 `RECORDS` 分类。当前淡入淡出处理带有 PCM 采样格式假设，不应把它当作支持任意 MP3、OGG 或流媒体格式的通用播放器。模组内置演出优先使用资源声音路径。

需要先获取外部文件时，可阅读[文件下载](../network/downloads.md)。相机场景中的声音时点使用[时钟标记](../camera/sequences.md)，不要在每帧采样时重复播放。

源码入口：[common/client/music/FPSClientMusicManager.java](https://github.com/PhasetransCrystal/FPSMatch/blob/master/src/main/java/net/ptcrys/fpsmatch/common/client/music/FPSClientMusicManager.java)。
