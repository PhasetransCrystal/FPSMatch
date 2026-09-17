package com.ptcrys.fpsmatch.common.entity.throwable;

import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import com.ptcrys.fpsmatch.common.entity.EntityRegister;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.entity.BaseProjectileLifeTimeEntity;
import com.ptcrys.fpsmatch.core.function.IHolder;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.Random;

public class IncendiaryGrenadeEntity extends BaseProjectileLifeTimeEntity {

    private static final EntityDataAccessor<ItemStack> ITEM = SynchedEntityData.defineId(IncendiaryGrenadeEntity.class, EntityDataSerializers.ITEM_STACK);
    private final int effectRadius;
    private final int damage = FPSMConfig.common.incendiaryGrenadeDamage.get();

    public IncendiaryGrenadeEntity(EntityType<? extends IncendiaryGrenadeEntity> type, Level level) {
        super(type, level);
        this.effectRadius = 3;
    }

    public IncendiaryGrenadeEntity(LivingEntity shooter, Level level, int effectRadius, IHolder<Item> defaultItem) {
        super(EntityRegister.INCENDIARY_GRENADE.get(), shooter, level);
        setTimeoutTicks(FPSMConfig.common.incendiaryGrenadeOutTime.get());
        setTimeLeft(FPSMConfig.common.incendiaryGrenadeLivingTime.get());
        this.effectRadius = effectRadius;
        this.setSyncItem(defaultItem.get());
        this.setActivateOnGroundHit(true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(ITEM, new ItemStack(Items.BARRIER));
    }

    private void applyFireEffect() {
        AABB effectArea = new AABB(
                getX() - effectRadius, getY() - effectRadius, getZ() - effectRadius,
                getX() + effectRadius, getY() + effectRadius, getZ() + effectRadius);

        for (LivingEntity entity : level().getEntitiesOfClass(LivingEntity.class, effectArea)) {
            applyPlayerDamage(entity);
        }
    }

    private void applyPlayerDamage(LivingEntity entity) {
        entity.setSecondsOnFire(1);
        if (entity instanceof ServerPlayer player && !player.gameMode.isSurvival()) {
            return;
        }
        entity.hurt(this.damageSource(), damage);
    }

    private void handleParticleTiming() {
        if (tickCount % 10 == 0) {
            playAmbientSound();
        }
        spawnBurstParticles();
    }

    private void playAmbientSound() {
        level().playSound(null, getX(), getY(), getZ(),
                SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1.5F, 1.0F);
    }

    private void handleSmokeInteraction() {
        AABB smokeCheckArea = getBoundingBox().inflate(effectRadius);
        level().getEntitiesOfClass(SmokeShellEntity.class, smokeCheckArea)
                .stream()
                .filter(smoke -> smoke.getState() == 2)
                .findFirst()
                .ifPresent(smoke -> discard());
    }

    @Override
    protected void onActiveTick() {
        handleParticleTiming();
        handleSmokeInteraction();
        if (tickCount % 4 == 0) {
            applyFireEffect();
        }
    }

    @Override
    public void onTimeOut() {
        if (level() instanceof ServerLevel serverLevel) {
            spawnRadialParticles(new AABB(this.getX() - 2, this.getY() - 2, this.getZ() - 2, this.getX() + 2, this.getY() + 2, this.getZ() + 2), serverLevel, 5);
        }
    }

    private void spawnBurstParticles() {
        if (level() instanceof ServerLevel serverLevel) {
            spawnRadialParticles(getParticleArea(), serverLevel, 20);
        }
    }

    private void spawnRadialParticles(AABB area, ServerLevel level, int time) {
        Random random = new Random();

        for (int i = 0; i < time; i++) {
            double x = random.nextDouble(area.minX, area.maxX);
            double y = random.nextDouble(area.minY, area.maxY);
            double z = random.nextDouble(area.minZ, area.maxZ);

            DustColorTransitionOptions options = new DustColorTransitionOptions(
                    new Vector3f(1, 0.25f, 0.25f),
                    new Vector3f(1, 1f, 0.1F),
                    random.nextFloat() * 3f);

            level.sendParticles(options, x, y, z, 2, 0, 0.2, 0, 1);
        }
    }

    public AABB getParticleArea() {
        AABB aabb = getBoundingBox();
        double d0 = aabb.minX - effectRadius;
        double d1 = aabb.minY;
        double d2 = aabb.minZ - effectRadius;
        double d3 = aabb.maxX + effectRadius;
        double d4 = aabb.maxY + 0.2;
        double d5 = aabb.maxZ + effectRadius;
        return new AABB(d0, d1, d2, d3, d4, d5);
    }

    @Override
    protected @NotNull Item getDefaultItem() {
        return getHoldItem();
    }

    public Item getHoldItem() {
        return entityData.get(ITEM).getItem();
    }

    public void setSyncItem(Item item) {
        entityData.set(ITEM, new ItemStack(item));
    }
}
