---
title: 投掷物、激活与伤害分类
description: 扩展投掷物实体生命周期，并接入携带分类和伤害分类。
---

# 投掷物、激活与伤害分类

投掷物功能由物品、实体和分类注册表共同完成。物品处理玩家投掷操作，实体处理运动、激活和效果，`ThrowableRegistry` 提供携带子类型与数量限制信息。安放式炸弹另有 `BlastBombEntity` 抽象，不属于投掷物基类继承链。

## 选择实体基类

`core.entity.BaseProjectileEntity` 继承原版 `ThrowableItemProjectile`，提供碰撞处理、激活标记和激活后的服务端 tick。需要自定义烟雾或持续区域效果时，覆盖 `onActivated()` 和 `onActiveTick()`，同时按 Forge 方式注册实体类型、物品和客户端渲染器。

`setActivateOnGroundHit()` 控制落地激活，`setHorizontalReduction()` 与 `setVerticalReduction()` 调整碰撞后的运动衰减。效果计算应在服务端执行，视觉表现使用同步数据或客户端渲染。

`markActivated()` 会在服务端设置激活状态并调用 `onActivated()`；它本身没有“已激活就直接返回”的判断。如果自定义逻辑主动调用它，应先检查 `isActivated()`，避免重复生成效果。

## 两段生命周期

`BaseProjectileLifeTimeEntity` 增加两个独立计时器：

| 字段 API | 计时阶段 | 耗尽行为 |
| --- | --- | --- |
| `setTimeoutTicks(int)` | 尚未激活时 | 调用 `onTimeOut()`，随后基类丢弃实体 |
| `setTimeLeft(int)` | 已激活时 | 调用 `onActiveTimeExpired()`，默认实现丢弃实体 |

计时单位是 tick，`-1` 表示禁用。当前实现只递减正值；直接设置为 0 不会立即触发回调。

在自己的子类中，可以用以下方法让激活后的效果持续五秒：

```java
@Override
protected void onActivated() {
    setTimeLeft(100);
}

@Override
protected void onActiveTick() {
    // 更新本次投掷物的服务端效果。
}
```

这是已继承 `BaseProjectileLifeTimeEntity` 的类中的方法片段，实体构造器与默认物品仍由子类提供。特别注意 `onTimeOut()` 返回后实体一定会被丢弃，不能仅在该回调里激活它并期待继续存活。

## 连接投掷物品

`core.item.IThrowEntityAble` 的实现对象应是物品。实现 `getEntity(Player, Level)` 返回自己的投掷物；`shoot()` 已负责冷却、投掷声音、服务端实体生成、初速度、玩家运动叠加和物品消耗。不要在同一次输入路径中再生成第二个实体或再次扣除物品。

`isThrowTypeAllowed()` 默认返回 false。若通过 `common.item.BaseThrowAbleItem` 的投掷模式流程接入，需要明确开放支持的模式，具体输入组织可阅读已有投掷物品实现。

## 登记携带子类型

在自己的物品注册完成后，将注册对象取得的 `Item` 记为 `myThrowable`，再进行分类：

```java
var subtype = ThrowableRegistry.registerSubType(
        "elimination_addon:signal", 1, "信号弹");
ThrowableRegistry.registerItemToSubType(myThrowable, subtype);
```

`ThrowableRegistry` 位于 `common.drop`。内置子类型包括 grenade、flash_bang、smoke、molotov 和 decoy；可用 `getSubTypeById()` 查询已有类型，再将兼容模组物品映射到它。

注册表提供 `getLimitForSubType()` 和 `setLimitForSubType()`，但登记分类本身不会创建实体或实现投掷效果。使用独立 ID，避免重复注册同一 ID 重建其关联集合。

## 伤害来源分类

`core.damage.DamageSourceManager` 将伤害来源字符串映射到 `DamageSourceCategory`：`BULLET`、`EXPLOSIVE`、`INCENDIARY`、`FIRE`、`ENVIRONMENT` 或 `FALLBACK`。

```java
DamageSourceManager.registerId("elimination_addon:signal_blast",
        DamageSourceCategory.EXPLOSIVE);
```

自定义规则优先于默认规则；ID 按 `Locale.ROOT` 转小写后匹配，未命中时返回 `FALLBACK`。这只是分类，不会注册 Minecraft DamageType 或自动施加伤害。需要让分类器收到与你实际伤害来源一致的 ID；伤害结算仍进入[战斗管线](combat.md)。

源码入口：[core/entity](https://github.com/PhasetransCrystal/FPSMatch/tree/master/src/main/java/net/ptcrys/fpsmatch/core/entity)。
