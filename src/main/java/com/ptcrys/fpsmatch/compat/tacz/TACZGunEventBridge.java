package com.ptcrys.fpsmatch.compat.tacz;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import com.ptcrys.fpsmatch.common.event.*;
import com.ptcrys.fpsmatch.compat.PassThroughFlagResolver;
import com.ptcrys.fpsmatch.compat.gun.GunCompatManager;
import com.tacz.guns.api.event.common.*;

/**
 * TACZ 事件 → FPSMatch 自定义事件 桥接器。
 * 仅在 TACZ 模组加载时由 {@link TACZBootstrap} 手动注册到 {@link MinecraftForge#EVENT_BUS}。
 * <p>
 * 当存在多个枪械模组同时加载时，各模组的 EventBridge 独立运行，互不干扰。
 * 核心代码通过 {@link GunCompatManager#findProvider} 路由到正确的 Provider。
 * </p>
 * <p>
 * 注意：此类不使用 {@code @Mod.EventBusSubscriber} 自动注册，
 * 而是由 TACZBootstrap 在确认 TACZ 已加载后手动调用
 * {@code MinecraftForge.EVENT_BUS.register(TACZGunEventBridge.class)}。
 * 其他枪械模组的 EventBridge 也应遵循此模式。
 * </p>
 */
public class TACZGunEventBridge {

    @SubscribeEvent
    public static void onGunFire(GunFireEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        LivingEntity shooter = event.getShooter();
        MinecraftForge.EVENT_BUS.post(new FPSMGunFireEvent(shooter));
    }

    @SubscribeEvent
    public static void onGunReload(GunReloadEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        if (event.isCanceled()) return;
        LivingEntity entity = event.getEntity();
        MinecraftForge.EVENT_BUS.post(new FPSMGunReloadEvent(entity, event.getGunItemStack()));
    }

    @SubscribeEvent
    public static void onGunShoot(GunShootEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        LivingEntity shooter = event.getShooter();
        MinecraftForge.EVENT_BUS.post(new FPSMGunShootEvent(shooter));
    }

    @SubscribeEvent
    public static void onGunKill(EntityKillByGunEvent event) {
        if (event.getLogicalSide() != LogicalSide.SERVER) return;
        LivingEntity dead = event.getKilledEntity();
        LivingEntity attacker = event.getAttacker();
        if (dead == null || attacker == null) return;
        PassThroughFlagResolver.markSmokeIfNeeded(event.getBullet(), dead);
        ItemStack gunStack = findAttackerGunStack(attacker, event.getGunId());
        MinecraftForge.EVENT_BUS.post(new FPSMGunKillEvent(
                attacker, dead, event.isHeadShot(), event.getBullet(), gunStack));
    }

    /**
     * 根据 TACZ 事件中的 gunId，从攻击者背包/副手找到对应枪械栈。
     * <p>
     * 解决延迟击杀或攻击者切物品后，{@code attacker.getMainHandItem()} 已经不是原枪械的问题。
     * 如果未找到（例如枪械已被丢弃），则回退到主手物品作为兜底。
     * </p>
     */
    private static ItemStack findAttackerGunStack(LivingEntity attacker, ResourceLocation gunId) {
        if (gunId == null) return attacker.getMainHandItem();
        if (attacker instanceof Player player) {
            // 优先搜索主手、副手，再搜索背包
            ItemStack mainHand = player.getMainHandItem();
            if (isMatchingGun(mainHand, gunId)) return mainHand;
            ItemStack offhand = player.getOffhandItem();
            if (isMatchingGun(offhand, gunId)) return offhand;
            for (ItemStack stack : player.getInventory().items) {
                if (isMatchingGun(stack, gunId)) return stack;
            }
        }
        return attacker.getMainHandItem();
    }

    private static boolean isMatchingGun(ItemStack stack, ResourceLocation gunId) {
        if (stack == null || stack.isEmpty()) return false;
        if (!GunCompatManager.isGun(stack)) return false;
        ResourceLocation id = GunCompatManager.findProvider(stack).getGunId(stack);
        return gunId.equals(id);
    }

    @SubscribeEvent
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Pre event) {
        LivingEntity hurtEntity = null;
        if (event.getHurtEntity() instanceof LivingEntity he) {
            hurtEntity = he;
        }
        if (hurtEntity == null) return;
        PassThroughFlagResolver.markSmokeIfNeeded(event.getBullet(), hurtEntity);
        FPSMGunDamageEvent fpsmEvent = new FPSMGunDamageEvent(
                event.getAttacker(), hurtEntity, event.getBaseAmount(), event.isHeadShot(), event.getBullet());
        MinecraftForge.EVENT_BUS.post(fpsmEvent);
        event.setBaseAmount(fpsmEvent.getBaseAmount());
        event.setHeadshotMultiplier(fpsmEvent.getHeadshotMultiplier());
    }
}
