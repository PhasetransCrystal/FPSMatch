package com.ptcrys.fpsmatch.common.entity.throwable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import com.ptcrys.fpsmatch.common.entity.EntityRegister;
import com.ptcrys.fpsmatch.common.item.FPSMItemRegister;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.entity.BaseProjectileLifeTimeEntity;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;

import java.util.Optional;

public class SmokeShellEntity extends BaseProjectileLifeTimeEntity {

    private static final EntityDataAccessor<ParticleOptions> PARTICLE_OPTIONS = SynchedEntityData.defineId(SmokeShellEntity.class, EntityDataSerializers.PARTICLE);
    private static final EntityDataAccessor<Integer> Particle_COOLDOWN = SynchedEntityData.defineId(SmokeShellEntity.class, EntityDataSerializers.INT);

    public SmokeShellEntity(EntityType<? extends SmokeShellEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    public SmokeShellEntity(LivingEntity shooter, Level level) {
        super(EntityRegister.SMOKE_SHELL.get(), shooter, level);
        this.noCulling = true;
        setTimeLeft(FPSMConfig.common.smokeShellLivingTime.get());
        setTimeoutTicks(-1);
        if (this.getOwner() instanceof Player player) {
            Optional<BaseMap> baseMap = FPSMCore.getInstance().getMapByPlayer(player);
            baseMap.flatMap(map -> map.getMapTeams().getTeamByPlayer(player)).ifPresent(t -> {
                this.setParticleOptions(new DustParticleOptions(t.getColorVec3f(), 10F));
            });
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(PARTICLE_OPTIONS, new DustParticleOptions(new Vector3f(1, 1, 1), 10F));
        entityData.define(Particle_COOLDOWN, 0);
    }

    @Override
    protected @NotNull Item getDefaultItem() {
        return FPSMItemRegister.SMOKE_SHELL.get();
    }

    @Override
    protected void onActiveTimeExpired() {
        spawnExpireParticles();
        discard();
    }

    @Override
    protected void onActiveTick() {
        if (this.getParticleCoolDown() > 0) {
            this.setParticleCoolDown(this.getParticleCoolDown() - 1);
        }
    }

    private void spawnExpireParticles() {
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SMOKE,
                    getX(), getY(), getZ(), 30,
                    0.5, 0.5, 0.5, 0.2);
        }
    }

    public boolean isInSmokeArea(AABB checker) {
        if (this.getParticleCoolDown() > 0) return false;

        int r = 4;
        double offset = 0.2;

        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        double minY, maxY;
        if (level().getBlockState(this.blockPosition().below()).isAir()) {
            minY = y - r - offset;
            maxY = y + offset;
        } else {
            minY = y - offset;
            maxY = y + r + offset;
        }

        return new AABB(
                x - r - offset,
                minY,
                z - r - offset,
                x + r + offset,
                maxY,
                z + r + offset).intersects(checker);
    }

    public int getParticleCoolDown() {
        return entityData.get(Particle_COOLDOWN);
    }

    public void setParticleCoolDown(int particleCoolDown) {
        entityData.set(Particle_COOLDOWN, particleCoolDown);
    }

    public ParticleOptions getParticleOptions() {
        return entityData.get(PARTICLE_OPTIONS);
    }

    public void setParticleOptions(ParticleOptions options) {
        entityData.set(PARTICLE_OPTIONS, options);
    }
}
