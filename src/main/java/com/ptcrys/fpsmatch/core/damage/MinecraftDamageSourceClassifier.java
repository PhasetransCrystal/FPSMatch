package com.ptcrys.fpsmatch.core.damage;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;

public final class MinecraftDamageSourceClassifier {

    static {
        registerDefaultType(DamageTypes.IN_FIRE, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.ON_FIRE, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.LAVA, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.HOT_FLOOR, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.FIREBALL, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.UNATTRIBUTED_FIREBALL, DamageSourceCategory.FIRE);
        registerDefaultType(DamageTypes.EXPLOSION, DamageSourceCategory.EXPLOSIVE);
        registerDefaultType(DamageTypes.PLAYER_EXPLOSION, DamageSourceCategory.EXPLOSIVE);
        registerDefaultType(DamageTypes.FALL, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.FLY_INTO_WALL, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.IN_WALL, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.DROWN, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.STARVE, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.CACTUS, DamageSourceCategory.ENVIRONMENT);
        registerDefaultType(DamageTypes.SWEET_BERRY_BUSH, DamageSourceCategory.ENVIRONMENT);
    }

    private MinecraftDamageSourceClassifier() {}

    public static void registerId(ResourceLocation sourceId, DamageSourceCategory category) {
        DamageSourceManager.registerId(sourceId.toString(), category);
    }

    public static void registerType(ResourceKey<DamageType> damageType, DamageSourceCategory category) {
        registerId(damageType.location(), category);
    }

    public static DamageSourceCategory classify(DamageSource source) {
        DamageSourceCategory category = source.typeHolder().unwrapKey()
                .map(key -> DamageSourceManager.classify(key.location().toString()))
                .orElse(DamageSourceCategory.FALLBACK);
        if (category != DamageSourceCategory.FALLBACK) {
            return category;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return DamageSourceCategory.FIRE;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return DamageSourceCategory.EXPLOSIVE;
        }
        return DamageSourceCategory.FALLBACK;
    }

    private static void registerDefaultType(ResourceKey<DamageType> damageType, DamageSourceCategory category) {
        DamageSourceManager.registerDefaultId(damageType.location().toString(), category);
    }
}
