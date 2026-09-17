package com.ptcrys.fpsmatch.compat;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import me.xjqsh.lrtactical.api.LrTacticalAPI;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import me.xjqsh.lrtactical.api.item.IThrowable;
import me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance;
import me.xjqsh.lrtactical.entity.SmokeGrenadeEntity;
import me.xjqsh.lrtactical.entity.ThrowableItemEntity;
import me.xjqsh.lrtactical.entity.sp.SpEffectCloudEntity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class LrtacticalCompat {

    /** Includes all data-pack throwable variants and their lingering effect clouds. */
    public static boolean isUtilityDamage(DamageSource source) {
        return isProjectile(source) || source.getDirectEntity() instanceof SpEffectCloudEntity || source.getEntity() instanceof SpEffectCloudEntity;
    }

    public static boolean isProjectile(DamageSource source) {
        if (source.getDirectEntity() instanceof ThrowableItemEntity) {
            return true;
        }
        return source.getEntity() instanceof ThrowableItemEntity;
    }

    public static ItemStack getProjectileItem(DamageSource source) {
        if (source.getDirectEntity() instanceof ThrowableItemEntity projectile) {
            return projectile.getItem();
        }
        if (source.getEntity() instanceof ThrowableItemEntity projectile) {
            return projectile.getItem();
        }
        return ItemStack.EMPTY;
    }

    public static boolean isKnife(ItemStack stack) {
        return stack.getItem() instanceof IMeleeWeapon;
    }

    @OnlyIn(Dist.CLIENT)
    public static ResourceLocation getTexture(ItemStack stack) {
        Optional<MeleeDisplayInstance> optional = LrTacticalAPI.getMeleeDisplay(stack);
        if (optional.isPresent()) {
            MeleeDisplayInstance display = optional.get();
            return display.getTexture();
        }
        return null;
    }

    public static boolean itemCheck(Player player) {
        Item main = player.getMainHandItem().getItem();
        Item off = player.getOffhandItem().getItem();
        return check(main) || check(off);
    }

    public static boolean check(Item item) {
        return isKnife(item) || isThrowable(item);
    }

    public static boolean isKnife(Item item) {
        return item instanceof IMeleeWeapon;
    }

    public static boolean isThrowable(Item item) {
        return item instanceof IThrowable;
    }

    @Nullable
    public static String i18n(ItemStack itemStack) {
        IMeleeWeapon melee = IMeleeWeapon.of(itemStack);
        if (melee != null) {
            ResourceLocation id = melee.getDisplayId(itemStack);
            if (id == null || "lrtactical:empty".equals(id.toString())) id = melee.getId(itemStack);
            if (id != null) {
                String[] keys = new String[] {
                        "item." + id.getNamespace() + "." + id.getPath(),
                        "melee." + id.getNamespace() + "." + id.getPath(),
                        id.getNamespace() + "." + id.getPath(),
                        "lrtactical." + id.getPath(),
                        "lrtactical.melee." + id.getPath()
                };
                for (String k : keys) if (I18n.exists(k)) return I18n.get(k);
                return id.getPath().toUpperCase(Locale.ROOT);
            }
        }

        IThrowable thr = IThrowable.of(itemStack);
        if (thr != null) {
            ResourceLocation id = thr.getDisplayId(itemStack);
            if (id == null || "lrtactical:empty".equals(id.toString())) id = thr.getId(itemStack);
            if (id != null) {
                String[] keys = new String[] {
                        "item." + id.getNamespace() + "." + id.getPath(),
                        "throwable." + id.getNamespace() + "." + id.getPath(),
                        id.getNamespace() + "." + id.getPath(),
                        "lrtactical." + id.getPath(),
                        "lrtactical.throwable." + id.getPath()
                };
                for (String k : keys) if (I18n.exists(k)) return I18n.get(k);
                return id.getPath().toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    public static boolean isInSmokeGrenadeArea(List<Entity> entities, AABB checker) {
        List<SmokeGrenadeEntity> smokes = entities.stream()
                .filter(entity -> entity instanceof SmokeGrenadeEntity)
                .map(entity -> (SmokeGrenadeEntity) entity)
                .toList();

        for (SmokeGrenadeEntity smoke : smokes) {
            if (isInSmokeGrenadeArea(smoke, checker)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInSmokeGrenadeArea(SmokeGrenadeEntity smokeGrenade, AABB areaToCheck) {
        if (smokeGrenade.tickCount < 40) {
            return false;
        }

        double grenadeX = smokeGrenade.getX();
        double grenadeY = smokeGrenade.getY();
        double grenadeZ = smokeGrenade.getZ();

        double maxOffsetX = 5.5;
        double maxOffsetZ = 5.5;
        double maxOffsetY = 4.5;

        // 创建烟雾弹影响区域 AABB
        AABB smokeArea = new AABB(
                grenadeX - maxOffsetX,
                grenadeY,
                grenadeZ - maxOffsetZ,
                grenadeX + maxOffsetX,
                grenadeY + maxOffsetY,
                grenadeZ + maxOffsetZ);
        return smokeArea.intersects(areaToCheck);
    }
}
