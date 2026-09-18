---
title: 枪械识别与 Provider
description: 为多枪械模组环境接入识别、弹药和展示数据。
---

# 枪械识别与 Provider

`IGunProvider` 让 FPSMatch 通过统一接口识别不同模组的枪械。`GunCompatManager` 保留多个 Provider，按物品栈选择能识别它的实现。

## 使用已有 Provider

以下片段可以放在服务端装备补给逻辑中：

```java
IGunProvider provider = GunCompatManager.findProvider(stack);
if (provider.isAvailable() && provider.isGun(stack)) {
    provider.useDummyAmmo(stack);
    provider.setDummyAmmo(stack, provider.getMaxDummyAmmo(stack));
}
```

`stack` 是目标 `ItemStack`。`findProvider()` 在没有匹配时返回空实现，不是 `null`。不要假设全服务器只安装一种枪械模组，也不要把所有物品交给第一个 Provider。

## 实现自己的 Provider

创建实现 `IGunProvider` 的兼容类时，需要完成以下接口组：

| 接口组 | 要表达的内容 |
| --- | --- |
| `getModId`、`isAvailable` | 对应模组与可用条件 |
| `isGun`、`getGunId`、`getGunTabType` | 哪些物品可识别及其稳定 ID |
| 当前弹匣与虚拟备弹读写 | 两类弹药的数量、上限和修改方式 |
| `getGunData` | 从物品或 ID 取得枪械数据，可返回空 Optional |
| `getGunHUDTexture` | 客户端展示纹理，默认可为空 |

接口不会替你读取第三方 NBT 或调用它的弹药 API。应在适配类里完成这些转换，而不是把第三方实现细节散布到地图规则中。

## 可选依赖加载

确认目标模组存在后，才实例化引用第三方类的实现：

```java
if (ModList.get().isLoaded("my_guns")) {
    GunCompatManager.register(new MyGunProvider());
}
```

这是公共初始化中的接入片段；`MyGunProvider` 是你实现的类。模组 ID 需要与目标模组一致。将第三方类放入独立兼容类，避免它们出现在通用入口的字段或方法签名中。

## 区分识别与战斗事件

Provider 解决物品与弹药抽象，不自动覆盖第三方枪械的所有伤害事件。需要爆头、穿透或投掷物归属时，仍要接入对应战斗事件并交给 FPSMatch 的[死亡管线](../gameplay/combat.md)。
