package com.ptcrys.fpsmatch.common.entity.throwable;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import com.ptcrys.fpsmatch.common.effect.FPSMEffectRegister;
import com.ptcrys.fpsmatch.common.effect.FlashBlindnessMobEffect;
import com.ptcrys.fpsmatch.common.entity.EntityRegister;
import com.ptcrys.fpsmatch.common.item.FPSMItemRegister;
import com.ptcrys.fpsmatch.common.sound.FPSMSoundRegister;
import com.ptcrys.fpsmatch.config.FPSMConfig;
import com.ptcrys.fpsmatch.core.entity.BaseProjectileLifeTimeEntity;
import com.ptcrys.fpsmatch.util.FPSMUtil;
import org.jetbrains.annotations.NotNull;

public class FlashBombEntity extends BaseProjectileLifeTimeEntity {

    private final java.util.Set<java.util.UUID> countedTargets = new java.util.HashSet<>();
    private final int radius;
    private static final double MAX_EFFECTIVE_DISTANCE = 48.0;

    public FlashBombEntity(EntityType<? extends FlashBombEntity> type, Level level) {
        super(type, level);
        this.radius = 0;
    }

    public FlashBombEntity(LivingEntity shooter, Level level) {
        super(EntityRegister.FLASH_BOMB.get(), shooter, level);
        this.radius = FPSMConfig.common.flashBombRadius.get();
        setTimeLeft(1);
        setTimeoutTicks(20);
    }

    @Override
    protected void onActivated() {
        applyFlashEffect();
    }

    @Override
    protected void onTimeOut() {
        applyFlashEffect();
    }

    private void applyFlashEffect() {
        AABB area = getBoundingBox().inflate(radius);
        for (Entity entity : level().getEntitiesOfClass(Entity.class, area)) {
            if (entity instanceof LivingEntity living) {
                if (entity instanceof ServerPlayer player && (player.isSpectator() || player.isCreative())) {
                    continue;
                }
                applyBlindnessEffect(living);
            }
        }
    }

    private void applyBlindnessEffect(LivingEntity target) {
        Vec3 eyePos = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        Vec3 flashPos = position();

        double distance = eyePos.distanceTo(flashPos);
        double distanceFactor = getDistanceFactor(distance);
        double blockingFactor = getBlockingFactor(eyePos, flashPos);

        if (blockingFactor <= 0.0 || distanceFactor <= 0.0) {
            return;
        }

        double angle = calculateViewAngle(target);

        FlashEffectDuration effectDuration = calculateBlindnessDuration(angle, distanceFactor, blockingFactor);

        MobEffectInstance effect = new MobEffectInstance(
                FPSMEffectRegister.FLASH_BLINDNESS.get(),
                effectDuration.totalDuration(),
                1);

        if (effect.getEffect() instanceof FlashBlindnessMobEffect flashEffect) {
            flashEffect.setFullBlindnessTime(effectDuration.fullBlindnessTime());
            flashEffect.setTotalAndTicker(effectDuration.decayTime());
        }

        boolean applied = target.addEffect(effect);
        if (applied && effectDuration.totalDuration() > 0 && target instanceof ServerPlayer player && getOwner() instanceof ServerPlayer thrower && countedTargets.add(player.getUUID())) {
            com.ptcrys.fpsmatch.core.FPSMCore.getInstance().getMapByPlayer(thrower)
                    .ifPresent(map -> map.recordFlashedEnemy(thrower, player));
        }

        if (target instanceof ServerPlayer player) {
            playDistanceBasedSound(player, distance);
        }
    }

    private double getDistanceFactor(double distance) {
        return Math.max(FPSMUtil.linearInterpolate(1.0, 0.0, distance / MAX_EFFECTIVE_DISTANCE), 0.0);
    }

    private double getBlockingFactor(Vec3 eyePos, Vec3 flashPos) {
        ClipContext context = new ClipContext(
                eyePos, flashPos,
                ClipContext.Block.VISUAL,
                ClipContext.Fluid.NONE,
                null);
        HitResult result = level().clip(context);
        return result.getType() == HitResult.Type.MISS ? 1.0 : 0.0;
    }

    private double calculateViewAngle(LivingEntity target) {
        Vec3 lookVec = target.getLookAngle();
        Vec3 toBomb = position().subtract(target.getEyePosition()).normalize();
        return Math.toDegrees(Math.acos(lookVec.dot(toBomb)));
    }

    private FlashEffectDuration calculateBlindnessDuration(double angle, double distanceFactor, double blockingFactor) {
        double fullyBlindedTime;
        double totalEffectTime;

        if (angle <= 53.0) {
            fullyBlindedTime = 1.88;
            totalEffectTime = 4.0;
        } else if (angle <= 72.0) {
            fullyBlindedTime = 0.45;
            totalEffectTime = 3.0;
        } else if (angle <= 101.0) {
            fullyBlindedTime = 0.08;
            totalEffectTime = 1.5;
        } else {
            fullyBlindedTime = 0.08;
            totalEffectTime = 0.5;
        }

        fullyBlindedTime = Math.max(0.0, fullyBlindedTime * distanceFactor * blockingFactor);
        totalEffectTime = Math.max(0.0, totalEffectTime * distanceFactor * blockingFactor);

        int fullBlindnessTicks = (int) (fullyBlindedTime * 20);
        int totalTicks = (int) (totalEffectTime * 20);
        int decayTicks = totalTicks - fullBlindnessTicks;

        return new FlashEffectDuration(fullBlindnessTicks, totalTicks, decayTicks);
    }

    private void playDistanceBasedSound(ServerPlayer player, double distance) {
        float volume = (float) getVolumeFromDistance(distance);

        player.connection.send(new ClientboundSoundPacket(
                Holder.direct(FPSMSoundRegister.FLASH.get()),
                SoundSource.PLAYERS,
                player.getX(),
                player.getY(),
                player.getZ(),
                volume,
                1.0f,
                0));
    }

    private double getVolumeFromDistance(double distance) {
        double maxDistance = 30.0;
        return Math.max(0.0, 1.0 - (distance / maxDistance));
    }

    @Override
    protected @NotNull Item getDefaultItem() {
        return FPSMItemRegister.FLASH_BOMB.get();
    }

    private record FlashEffectDuration(int fullBlindnessTime, int totalDuration, int decayTime) {}
}
