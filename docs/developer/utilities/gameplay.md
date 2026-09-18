---
title: 武器、库存与击杀工具
description: FPSMUtil 的枪械分类、弹药、掉落、归因和库存辅助。
---

# 武器、库存与击杀工具

## 枪械分类

`getGunTypeByGunId` 从 `GunCompatManager` 查询 `GunTabTypeEnum`；`isMainWeapon` 将步枪、狙击枪、霰弹枪、冲锋枪和机枪视为主武器。库存整理使用六组谓词：主武器、副武器、第三武器、投掷物、C4 和杂项。

扩展模组在初始化枪械兼容层后注册谓词：

```java
FPSMUtil.addThirdWeaponPredicate(stack ->
        stack.is(MyItems.RECON_LAUNCHER.get()));
FPSMUtil.addThrowablePredicate(stack ->
        stack.getItem() instanceof MyThrowableItem);
```

谓词按注册顺序和分类顺序执行；一个物品首次匹配后不会进入后续分类。`MISC_PREDICATE` 默认匹配所有物品，因此不要把可能匹配的自定义谓词注册到它之后再期待重新分类。

## 库存整理与发放

`sortPlayerInventory(ServerPlayer)` 只有在 `RULE_AUTO_SORT_PLAYER_INV` 开启时才会修改背包。它按类别把物品放入快捷栏：主武器 0、副武器 1、第三武器 2、C4 3、投掷物 4–7、杂项 8，其余物品填入主背包。相同物品和 NBT 会通过 `ItemKey` 合并，但不会跨越最大堆叠数。

`addItemToPlayerInventory` 对护甲使用对应装备槽，对其他物品调用 `Inventory.add`，并在成功后整理背包。返回 `true` 表示物品完全被接受；调用者仍应检查传入栈是否为空。

`getAllPlayerItems` 以主背包、护甲和副手的顺序返回连接视图，不包含末影箱或容器内部物品。

## 虚拟弹药

`setDummyAmmo`、`setTotalDummyAmmo` 会先恢复枪械真实弹匣，再设置提供器维护的虚拟弹药；`getTotalDummyAmmo` 返回真实弹匣容量加当前虚拟弹药。`fixGunItem` 用于枪械刚发放或复制后，确保当前弹匣和虚拟弹药状态一致；`resetGunAmmo` 重置单把枪，`resetAllGunAmmo` 遍历玩家主背包、护甲和副手。

这些方法要求 `IGunProvider` 能识别物品。对非枪械调用不会抛出业务异常，但不会产生弹药数据；不要把 `ItemStack.EMPTY` 当作有效枪械传入。

## 掉落与击杀归因

`searchInventoryForType` 按主背包、护甲、副手查找 `DropType` 匹配的栈；`findWeaponToDrop` 按 DropType 优先级选择主要武器。`playerDropMatchItem` 创建 FPSMatch 掉落实体并根据玩家朝向施加抛掷速度；`playerDeadDropWeapon` 可额外掉落第一件投掷物。

`getKiller(dead, source)` 依次检查伤害实体、直接实体、投射物拥有者、区域效果拥有者和 TNT 拥有者；若都不存在，则使用地图伤害记录中伤害最高的玩家。结果可能为 `null`。`getKillerWeapon` 优先读取投掷物或 CounterStrikeGrenade 的物品，找不到时回退到攻击者主手。

`calculateAssistPlayer(map, deadPlayer, ratio)` 只检查敌方普通队伍，要求造成伤害严格大于 `maxHealth * ratio`，并返回伤害最高者。`getOwnerIfTraceable` 适合对多个实体逐个查找 `TraceableEntity` 所有者。

## 其他辅助

`linearInterpolate` 是不限制范围的线性插值；`fetchSkin` 和 `isOp` 分别依赖客户端皮肤管理器与当前服务端，不应在错误运行端调用。
