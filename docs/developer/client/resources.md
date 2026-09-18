---
title: 资源、地图贴图与本地化
description: 配置资源位置、地图展示贴图和玩家可见文本。
---

# 资源、地图贴图与本地化

客户端资源按模组命名空间组织。资源位置是逻辑 ID，与服务器磁盘路径无关。

## 文件路径与资源位置

| 文件 | 代码或配置使用的值 |
| --- | --- |
| `assets/elimination_addon/textures/gui/training.png` | `elimination_addon:textures/gui/training.png` |
| `assets/elimination_addon/lang/zh_cn.json` | 由当前语言自动加载 |
| `assets/elimination_addon/lang/en_us.json` | 英文翻译 |

普通工程将 `assets` 放在 `src/main/resources`；分章示例使用 `resources/assets`。这些文件需要随客户端模组或资源包提供。

## 地图图标和背景

`BaseMap` 的 `iconTexture` 与 `backgroundTexture` 设置用于地图列表和房间详情：

```text
/fpsm map modify elimination training settings set iconTexture elimination_addon:textures/gui/training.png
/fpsm map modify elimination training settings set backgroundTexture elimination_addon:textures/gui/training_background.png
/fpsm map modify elimination training settings save
```

地图保存的是资源位置，不是图片内容。所有需要看到图片的客户端都必须拥有相同资源。图片制作与地图配置可参阅[地图制作者手册](../../mapper.md)。

## 使用语言键

服务端播报使用：

```java
player.sendSystemMessage(Component.translatable("message.elimination.round", round));
```

对应语言文件：

```json
{
  "message.elimination.round": "第 %s 回合"
}
```

服务器传递语言键和参数，客户端按照自己的语言显示。不应先在服务端把可翻译文本变成字符串，再发给所有语言的玩家。

## 资源职责

地图 ID 用于保存和查询，不承担本地化职责。展示标题可以来自地图设置或语言键，纹理 ID 则负责资源定位。保持它们分离，才能在不破坏旧地图引用的前提下改变显示效果。
