---
title: 标识、Codec 与格式化
description: MapId、FPSMCodec、FPSMFormatUtil、ItemKey 和持久化路径辅助。
---

# 标识、Codec 与格式化

## `MapId`

`MapId.isValid(value)` 检查非空且完整合法的地图 ID；`isValidPrefix(value)` 检查同样的字符集，但允许空字符串，适合命令补全或逐字输入校验。两者都拒绝 `null`、超过 48 个字符的字符串以及大写字母、空格、点号和路径分隔符。

```java
if (!MapId.isValid(id)) {
    throw new IllegalArgumentException("Map id must use [a-z0-9_-] and be <= 48 characters");
}
```

ID 是持久化身份；改变显示名称应修改地图设置中的 `displayName`，不要用新 ID 替代旧地图。

## `FPSMCodec`

```java
JsonElement json = FPSMCodec.encodeToJson(MyData.CODEC, data);
MyData decoded = FPSMCodec.decodeFromJson(MyData.CODEC, json);
```

方法固定使用 `JsonOps.INSTANCE`。Codec 的 partial result 或错误不会静默返回：`getOrThrow` 将错误包装为 `DataPersistenceException`。因此调用方不需要再次检查 `DataResult`，但应在存档边界捕获该异常并报告所属文件或地图 ID。Codec 必须描述完整结构；迁移旧字段请使用数据迁移层，而不是在工具方法中修改 JSON。

## `FPSMFormatUtil`

- `fmt2(double)` 使用 `Locale.US` 输出两位小数，适合坐标、距离和 HUD 数值。
- `formatBoolean(boolean)` 返回绿色 `true` 或红色 `false` 的 `Component`。
- `en(ItemStack)` 从描述 ID 的最后一段生成英文标题，例如 `item.mod.weapon_rifle` 变为 `Weapon Rifle`；它不读取当前语言。
- `i18n(ItemStack)` 优先查找枪械提供器和 TaCZ 本地化键，再尝试 LR Tactical，最后使用物品描述键或悬浮名称。空栈返回 `fpsm.unknown_weapon`。

`i18n` 依赖客户端 `I18n`，不能在专用服务器调用。服务端日志或存档字段应使用稳定的物品 ID，而不是格式化后的文本。

## `ItemKey`

`ItemKey` 由物品类型和 NBT 快照组成，不包含数量。它适合作为合并物品堆叠的 Map 键：同一物品但 NBT 不同会被视为不同键。构造时会复制标签，因此之后修改原 `ItemStack` 不会改变键。

## `PersistenceUtils`

`fixFileName` 删除 Windows 和 Unix 常见非法字符；它不会替换空字符串，也不会保证名称唯一。`ensureDirectoryExists` 创建目录，若路径已存在但不是目录则抛出 `DataPersistenceException`。`getLocalCachePath(filename, type)` 将文件放在游戏目录 `fpsmatch/cache/<type>/`，清理文件名后追加 `.<type>` 后缀。不要把用户输入直接拼接到其他目录；缓存 API 只应用于本地可重建数据。
