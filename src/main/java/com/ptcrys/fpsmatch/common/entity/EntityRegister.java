package com.ptcrys.fpsmatch.common.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.entity.throwable.FlashBombEntity;
import com.ptcrys.fpsmatch.common.entity.throwable.GrenadeEntity;
import com.ptcrys.fpsmatch.common.entity.throwable.IncendiaryGrenadeEntity;
import com.ptcrys.fpsmatch.common.entity.throwable.SmokeShellEntity;

public class EntityRegister {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FPSMatch.MODID);
    public static final RegistryObject<EntityType<SmokeShellEntity>> SMOKE_SHELL = ENTITY_TYPES.register("smoke_shell", () -> EntityType.Builder.<SmokeShellEntity>of(SmokeShellEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f).build("smoke_shell"));
    public static final RegistryObject<EntityType<IncendiaryGrenadeEntity>> INCENDIARY_GRENADE = ENTITY_TYPES.register("ct_incendiary_grenade", () -> EntityType.Builder.<IncendiaryGrenadeEntity>of(IncendiaryGrenadeEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f).build("ct_incendiary_grenade"));
    public static final RegistryObject<EntityType<GrenadeEntity>> GRENADE = ENTITY_TYPES.register("grenade", () -> EntityType.Builder.<GrenadeEntity>of(GrenadeEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f).build("grenade"));
    public static final RegistryObject<EntityType<FlashBombEntity>> FLASH_BOMB = ENTITY_TYPES.register("flash_bomb", () -> EntityType.Builder.<FlashBombEntity>of(FlashBombEntity::new, MobCategory.MISC)
            .sized(0.25f, 0.25f).build("flash_bomb"));
    public static final RegistryObject<EntityType<MatchDropEntity>> MATCH_DROP_ITEM = ENTITY_TYPES.register("match_drop", () -> EntityType.Builder.<MatchDropEntity>of(MatchDropEntity::new, MobCategory.MISC)
            .sized(0.5f, 0.5f).build("match_drop"));
}
