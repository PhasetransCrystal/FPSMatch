package com.ptcrys.fpsmatch.compat;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import club.pisquad.minecraft.csgrenades.api.CSGrenadesAPI;
import club.pisquad.minecraft.csgrenades.config.ModConfig;
import club.pisquad.minecraft.csgrenades.entity.SmokeGrenadeEntity;
import club.pisquad.minecraft.csgrenades.item.CounterStrikeGrenadeItem;
import club.pisquad.minecraft.csgrenades.registry.ModDamageType;
import club.pisquad.minecraft.csgrenades.registry.ModItems;
import com.ptcrys.fpsmatch.common.drop.ThrowableRegistry;
import com.ptcrys.fpsmatch.core.damage.DamageSourceCategory;
import com.ptcrys.fpsmatch.core.damage.MinecraftDamageSourceClassifier;

import java.util.List;

public class CounterStrikeGrenadesCompat {

    public static void init() {
        ModItems items = ModItems.INSTANCE;

        ThrowableRegistry.registerItemToSubType(items.getFLASH_BANG_ITEM().get(), ThrowableRegistry.FLASH_BANG);
        ThrowableRegistry.registerItemToSubType(items.getDECOY_GRENADE_ITEM().get(), ThrowableRegistry.DECOY);
        ThrowableRegistry.registerItemToSubType(items.getHEGRENADE_ITEM().get(), ThrowableRegistry.GRENADE);
        ThrowableRegistry.registerItemToSubType(items.getSMOKE_GRENADE_ITEM().get(), ThrowableRegistry.SMOKE);
        ThrowableRegistry.registerItemToSubType(items.getMOLOTOV_ITEM().get(), ThrowableRegistry.MOLOTOV);
        ThrowableRegistry.registerItemToSubType(items.getINCENDIARY_ITEM().get(), ThrowableRegistry.MOLOTOV);
        registerDamageSources();
    }

    private static void registerDamageSources() {
        ModDamageType types = ModDamageType.INSTANCE;
        MinecraftDamageSourceClassifier.registerType(types.getHEGRENADE_HIT(), DamageSourceCategory.EXPLOSIVE);
        MinecraftDamageSourceClassifier.registerType(types.getHEGRENADE_EXPLOSION(), DamageSourceCategory.EXPLOSIVE);
        MinecraftDamageSourceClassifier.registerType(types.getINCENDIARY_HIT(), DamageSourceCategory.INCENDIARY);
        MinecraftDamageSourceClassifier.registerType(types.getINCENDIARY_FIRE(), DamageSourceCategory.INCENDIARY);
        MinecraftDamageSourceClassifier.registerType(types.getMOLOTOV_HIT(), DamageSourceCategory.INCENDIARY);
        MinecraftDamageSourceClassifier.registerType(types.getMOLOTOV_FIRE(), DamageSourceCategory.INCENDIARY);
    }

    public static ItemStack getItemFromDamageSource(DamageSource damageSource) {
        ModDamageType types = ModDamageType.INSTANCE;
        ModItems items = ModItems.INSTANCE;
        if (damageSource.is(types.getFLASHBANG_HIT())) {
            return new ItemStack(items.getFLASH_BANG_ITEM().get());
        } else if (damageSource.is(types.getHEGRENADE_HIT()) || damageSource.is(types.getHEGRENADE_EXPLOSION())) {
            return new ItemStack(items.getHEGRENADE_ITEM().get());
        } else if (damageSource.is(types.getINCENDIARY_HIT()) || damageSource.is(types.getINCENDIARY_FIRE())) {
            return new ItemStack(items.getINCENDIARY_ITEM().get());
        } else if (damageSource.is(types.getMOLOTOV_HIT()) || damageSource.is(types.getMOLOTOV_FIRE())) {
            return new ItemStack(items.getMOLOTOV_ITEM().get());
        } else if (damageSource.is(types.getSMOKEGRENADE_HIT())) {
            return new ItemStack(items.getSMOKE_GRENADE_ITEM().get());
        } else if (damageSource.is(types.getDECOY_GRENADE_HIT())) {
            return new ItemStack(items.getDECOY_GRENADE_ITEM().get());
        } else {
            return ItemStack.EMPTY;
        }
    }

    public static boolean itemCheck(Player player) {
        Item main = player.getMainHandItem().getItem();
        Item off = player.getOffhandItem().getItem();
        return main instanceof CounterStrikeGrenadeItem || off instanceof CounterStrikeGrenadeItem;
    }

    public static boolean isPlayerFlashed(Player player) {
        return CSGrenadesAPI.isPlayerFlashed(player);
    }

    public static boolean isInSmokeGrenadeArea(List<Entity> entities, AABB checker) {
        List<SmokeGrenadeEntity> smokes = entities.stream()
                .filter(entity -> entity instanceof SmokeGrenadeEntity)
                .map(entity -> (SmokeGrenadeEntity) entity)
                .toList();

        for (SmokeGrenadeEntity smoke : smokes) {
            if (isInSmoke(checker, smoke)) return true;
        }

        return false;
    }

    public static boolean isInSmoke(AABB checker, SmokeGrenadeEntity smoke) {
        double smokeRadius = ModConfig.SmokeGrenade.SMOKE_RADIUS.get().doubleValue();
        double smokeFallingHeight = ModConfig.SmokeGrenade.SMOKE_MAX_FALLING_HEIGHT.get().doubleValue();
        AABB smokeCloudBoundingBox = new AABB(smoke.blockPosition()).inflate(smokeRadius).expandTowards(0.0, -smokeFallingHeight, 0.0);
        return smokeCloudBoundingBox.intersects(checker);
    }
}
