---
title: 客户端渲染工具
description: RenderUtil 和 PreviewColorUtil 的颜色、动画、贴图及玩家列表辅助。
---

# 客户端渲染工具

本页所有方法都属于客户端代码。将它们放入 `Dist.CLIENT` 类、客户端事件订阅器或客户端初始化路径；服务端类加载 `RenderUtil` 会触发 Minecraft 客户端类解析错误。

## 颜色表示

FPSMatch 使用 ARGB 整数。`color(r,g,b)` 生成不透明颜色，`color(r,g,b,a)` 保留传入的 8 位通道；`color(int)` 与 `color(Vector3f)` 在整数和 0–1 浮点 RGB 之间转换。`WHITE` 是 `0xFFFFFFFF`。

`mulAlpha` 只调整 Alpha；`lerpColor` 插值 ARGB；`lerpARGB` 是同义的显式命名版本；`mixRGB`、`lighten`、`darken` 操作不透明 RGB。比例会被限制到 0–1，超出范围不会产生溢出。

## 动画与像素对齐

`expSmooth(cur, target, dt, halfLifeSec)` 使用半衰期平滑，不依赖帧率；半衰期小于等于零时立即返回目标值。`easeOutBack` 产生带轻微回弹的 0–1 曲线。`snapToPixel(value, scale)` 将坐标吸附到缩放后的像素网格，适合避免细线在 GUI 缩放下抖动。

## 玩家列表与队伍投影

`getPlayerInfos()` 读取在线玩家，按 FPSMatch 的 HUD 排序，最多返回 80 人；`getTeamsPlayerInfo()` 使用客户端全局数据按队伍名称分组。列表在一个客户端 tick 内缓存，`invalidatePlayerInfoCache()` 用于队伍快照刚同步后的主动刷新。`getPlayerData(PlayerInfo)` 返回对应 UUID 的 `PlayerData`，可能为空；`getScoreboard()` 直接取当前世界记分板，因此世界未加载时不要调用。

## 贴图和文字

`renderTexture` 使用当前 `PoseStack` 绘制矩形贴图，可分别水平或垂直翻转；`renderReverseTexture` 是水平翻转快捷方式。纹理应提前注册并绑定到有效 `ResourceLocation`。`drawCenteredScaledString` 会在给定矩形内按字体宽高缩放并居中，不会自动换行。

## 预览颜色

`PreviewColorUtil` 根据游戏类型哈希到固定七色调色板，再向白色混合：地图预览混合 35%，点位预览混合 12%。带 `variantIndex` 的重载可为同一游戏类型的多个点位轮换颜色。相同 key 在不同会话中保持同色；不要把颜色值当作持久化配置。
