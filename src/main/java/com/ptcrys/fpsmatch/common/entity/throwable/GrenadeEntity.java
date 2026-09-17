package com.ptcrys.fpsmatch.common.entity.throwable;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.ptcrys.fpsmatch.common.attributes.ammo.BulletproofArmorAttribute;
import com.ptcrys.fpsmatch.common.attributes.ammo.GunDamageHandler;
import com.ptcrys.fpsmatch.common.entity.EntityRegister;
import com.ptcrys.fpsmatch.common.item.FPSMItemRegister;
import com.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.entity.BaseProjectileLifeTimeEntity;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import org.jetbrains.annotations.NotNull;

public class GrenadeEntity extends BaseProjectileLifeTimeEntity {

    private final int explosionRadius = FPSMConfig.common.grenadeRadius.get();
    private final int baseDamage = FPSMConfig.common.grenadeDamage.get();
    private static final double HEAD_DAMAGE_BOOST = 1.5;
    private static final double MAX_EFFECTIVE_DISTANCE = 64.0;
    private static final float EXPLOSION_ARMOR_PENETRATION = 0.8F;

    public GrenadeEntity(EntityType<? extends GrenadeEntity> type, Level level) {
        super(type, level);
    }

    public GrenadeEntity(LivingEntity shooter, Level level) {
        super(EntityRegister.GRENADE.get(), shooter, level);
        this.setTimeLeft(1);
        this.setTimeoutTicks(FPSMConfig.common.grenadeFuseTime.get());
        this.setVerticalReduction(0.1F);
    }

    @Override
    protected void onTimeOut() {
        explode();
    }

    @Override
    protected void onActivated() {
        explode();
    }

    private void explode() {
        if (level().isClientSide) return;

        spawnExplosionParticles();
        applyExplosionDamage();
        playExplosionSound();
        applyStopSmokeShell();

        discard();
    }

    private void applyStopSmokeShell() {
        AABB smokeCheckArea = getBoundingBox().inflate(explosionRadius);

        SmokeShellEntity[] a = level().getEntitiesOfClass(SmokeShellEntity.class, smokeCheckArea)
                .stream()
                .filter(smoke -> smoke.getState() == 2)
                .toArray(SmokeShellEntity[]::new);

        for (SmokeShellEntity smokeShellEntity : a) {
            smokeShellEntity.setParticleCoolDown(30);
        }
    }

    private void spawnExplosionParticles() {
        ServerLevel serverLevel = (ServerLevel) level();

        serverLevel.sendParticles(ParticleTypes.EXPLOSION_EMITTER,
                getX(), getY(), getZ(), 20,
                0, 0, 0, 0.5);

        serverLevel.sendParticles(ParticleTypes.POOF,
                getX(), getY(), getZ(), 100,
                1.5, 1.0, 1.5, 0.2);
    }

    private void applyExplosionDamage() {
        AABB explosionArea = getBoundingBox().inflate(explosionRadius);

        for (LivingEntity entity : level().getEntitiesOfClass(LivingEntity.class, explosionArea)) {
            if (entity instanceof ServerPlayer player && !player.gameMode.isSurvival()) {
                continue;
            }

            double damage = getDamageBlockingState(entity);

            if (damage > 0.0) {
                applyDamageWithArmor(entity, damage);
            }
        }
    }

    private void applyDamageWithArmor(LivingEntity entity, double calculatedDamage) {
        if (!(entity instanceof ServerPlayer player)) {
            entity.hurt(this.damageSource(), (float) calculatedDamage);
            return;
        }

        float armorValue = GunDamageHandler.getArmorValue(player, false);

        if (armorValue > 0) {
            float finalDamage = (float) (calculatedDamage * (EXPLOSION_ARMOR_PENETRATION / 2.0F));

            entity.hurt(this.damageSource(), finalDamage);

            if (finalDamage > player.getHealth()) {
                BulletproofArmorAttribute.removePlayer(player);
            } else {
                int durabilityReduction = (int) Math.ceil(finalDamage);
                GunDamageHandler.reduceArmorDurability(player, durabilityReduction);
            }
        } else {
            entity.hurt(this.damageSource(), (float) calculatedDamage);
        }
    }

    private double getDamageBlockingState(LivingEntity entity) {
        Vec3 explosionPos = this.position();
        Vec3 entityEyePos = entity.getEyePosition();
        Vec3 entityBodyPos = entity.position();

        double headDamage = calculateHeadDamage(explosionPos, entityEyePos, entity);

        double bodyDamage = calculateBodyDamage(explosionPos, entityBodyPos, entity);

        return Math.max(headDamage, bodyDamage);
    }

    private double calculateHeadDamage(Vec3 explosionPos, Vec3 eyePos, LivingEntity entity) {
        if (isLineOfSightBlocked(explosionPos, eyePos)) {
            return 0.0;
        }

        double distance = explosionPos.distanceTo(eyePos);

        double distanceFactor = getDistanceFactor(distance);
        if (distanceFactor <= 0.0) {
            return 0.0;
        }

        return calculateGrenadeDamage(distance, true) * distanceFactor;
    }

    private double calculateBodyDamage(Vec3 explosionPos, Vec3 bodyPos, LivingEntity entity) {
        if (isLineOfSightBlocked(explosionPos, bodyPos)) {
            return 0.0;
        }

        double distance = explosionPos.distanceTo(bodyPos);

        double distanceFactor = getDistanceFactor(distance);
        if (distanceFactor <= 0.0) {
            return 0.0;
        }

        return calculateGrenadeDamage(distance, false) * distanceFactor;
    }

    private boolean isLineOfSightBlocked(Vec3 startPos, Vec3 endPos) {
        ClipContext context = new ClipContext(
                startPos,
                endPos,
                ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE,
                this);
        HitResult result = level().clip(context);
        return result.getType() != HitResult.Type.MISS;
    }

    private double getDistanceFactor(double distance) {
        return Math.max(FPSMUtil.linearInterpolate(1.0, 0.0, distance / MAX_EFFECTIVE_DISTANCE), 0.0);
    }

    private double calculateGrenadeDamage(double distance, boolean headDamageBoost) {
        double baseDmg = headDamageBoost ?
                this.baseDamage * HEAD_DAMAGE_BOOST :
                this.baseDamage;

        double distanceFactor = 1.0 - (distance / this.explosionRadius);
        distanceFactor = Math.max(distanceFactor, 0.0);

        return baseDmg * distanceFactor;
    }

    private void playExplosionSound() {
        level().playSound(null, getX(), getY(), getZ(),
                FPSMSoundRegister.BOOM.get(), SoundSource.HOSTILE,
                4.0F, (1.0F + (random.nextFloat() - random.nextFloat()) * 0.2F) * 0.7F);
    }

    @Override
    protected @NotNull Item getDefaultItem() {
        return FPSMItemRegister.GRENADE.get();
    }

    @Override
    public void onActiveTick() {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.FLASH,
                    getX(), getY(), getZ(), 2,
                    0, 0, 0, 0.1);
        }
    }
}
