---
title: 地图展示资源
description: 为地图列表和详情页配置显示名称、图标、背景及语言资源。
---

# 地图展示资源

地图列表可以显示自定义标题、缩略图和详情背景。这些内容通过地图设置引用客户端资源，不会从服务器存档自动下载图片。

## 设置显示名称

`displayName` 为空时，界面使用地图 ID。直接填写文本：

```text
/fpsm map modify cs training settings set displayName 训练场
```

展示名称可以包含空格和中文，不会改变命令中的 `training`。重命名展示文本也不会创建新地图或迁移存档。

## 添加图标和背景

假设资源包命名空间是 `my_maps`，可以放置：

```text
assets/my_maps/textures/gui/maps/training_icon.png
assets/my_maps/textures/gui/maps/training_background.png
```

然后设置完整资源位置：

```text
/fpsm map modify cs training settings set iconTexture my_maps:textures/gui/maps/training_icon.png
/fpsm map modify cs training settings set backgroundTexture my_maps:textures/gui/maps/training_background.png
/fpsm map modify cs training settings save
```

这里填写的是资源位置，不是 Windows 文件路径、HTTP URL 或服务器世界目录。每个客户端都需要通过模组资源或服务器资源包取得同样的文件。

图标用于地图列表卡片，背景用于详情页；资源不存在或字段为空时，客户端使用基于地图身份生成的色块。推荐使用清楚、未过度裁切的 PNG，并让缩略图与背景采用接近 `16:9` 的画面比例。

## 用资源包分发

最小资源包结构示例：

```text
my-map-resources/
├─ pack.mcmeta
└─ assets/
   └─ my_maps/
      └─ textures/
         └─ gui/
            └─ maps/
               ├─ training_icon.png
               └─ training_background.png
```

命名空间和文件名使用小写字母、数字、下划线、连字符和目录分隔符。资源包更新后，客户端需要重新加载资源，地图设置本身无需重建。

## 设置名称和翻译的边界

地图的 `displayName` 是一段配置文本，不会自动把 `map.my_maps.training` 解析成翻译键。需要为不同客户端语言显示不同标题时，应由提供游戏类型的扩展模组增加相应本地化能力；当前基础字段适合服务器统一使用一种显示名称。
