package com.ptcrys.fpsmatch.common.attributes.ammo;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.event.FPSMGunDamageEvent;
import com.ptcrys.fpsmatch.common.packet.attribute.BulletproofArmorAttributeS2CPacket;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.FPSMCore;

import java.util.Optional;

@Mod.EventBusSubscriber(modid = FPSMatch.MODID)
public class GunDamageHandler {

    @SubscribeEvent
    public static void onEntityHurtByGun(FPSMGunDamageEvent event) {
        if (!(event.getHurtEntity() instanceof ServerPlayer hurtEntity)) {
            return;
        }

        if (isSameTeamGunDamage(event, hurtEntity)) {
            return;
        }

        float baseDamage = event.getBaseAmount();
        boolean headshot = event.isHeadShot();
        if (headshot) {
            // 从配置获取爆头倍率
            float headshotMultiplier = FPSMConfig.common.headshotMultiplier.get().floatValue();
            event.setHeadshotMultiplier(headshotMultiplier);
        }

        float armorValue = getArmorValue(hurtEntity, headshot);
        if (armorValue > 0) {
            // 从配置获取基础穿透系数
            float baseArmorPenetration = FPSMConfig.common.baseArmorPenetration.get().floatValue();
            float finalDamage = baseDamage * (baseArmorPenetration / 2.0F);
            event.setBaseAmount(finalDamage);

            if (finalDamage > hurtEntity.getHealth()) {
                BulletproofArmorAttribute.removePlayer(hurtEntity);
            } else {
                int durabilityReduction = (int) Math.ceil(finalDamage);
                reduceArmorDurability(hurtEntity, durabilityReduction);
            }
        }
    }

    private static boolean isSameTeamGunDamage(FPSMGunDamageEvent event, ServerPlayer hurtEntity) {
        if (!(event.getAttacker() instanceof ServerPlayer attacker)) {
            return false;
        }
        if (attacker.equals(hurtEntity)) {
            return false;
        }
        return FPSMCore.getInstance().getMapByPlayer(hurtEntity)
                .map(map -> map.getMapTeams().isSameTeam(attacker, hurtEntity))
                .orElse(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer dead)) {
            return;
        }
        BulletproofArmorAttribute.removePlayer(dead);
    }

    @SubscribeEvent()
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BulletproofArmorAttribute.getInstance(player)
                .ifPresent(attribute -> FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new BulletproofArmorAttributeS2CPacket(attribute)));
    }

    public static int getArmorValue(Player player, boolean headshot) {
        Optional<BulletproofArmorAttribute> optional = BulletproofArmorAttribute.getInstance(player);
        if (optional.isPresent()) {
            BulletproofArmorAttribute attribute = optional.get();
            if (headshot) {
                if (!attribute.hasHelmet()) {
                    return 0;
                } else {
                    return attribute.getDurability();
                }
            } else {
                return attribute.getDurability();
            }
        }
        return 0;
    }

    public static void reduceArmorDurability(ServerPlayer player, int damage) {
        Optional<BulletproofArmorAttribute> optional = BulletproofArmorAttribute.getInstance(player);
        if (optional.isPresent()) {
            BulletproofArmorAttribute attribute = optional.get();
            if (attribute.getDurability() <= damage) {
                BulletproofArmorAttribute.removePlayer(player);
            } else {
                attribute.reduceDurability(damage);
                FPSMatch.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new BulletproofArmorAttributeS2CPacket(attribute));
            }
        }
    }
}
