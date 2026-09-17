package com.ptcrys.fpsmatch.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.ptcrys.fpsmatch.common.entity.throwable.SmokeShellEntity;
import com.ptcrys.fpsmatch.compat.impl.FPSMImpl;

import java.util.List;

import javax.annotation.Nullable;

public final class PassThroughFlagResolver {

    private PassThroughFlagResolver() {}

    public static Flags fromBullet(@Nullable Entity bullet) {
        if (bullet instanceof IPassThroughEntity passThrough) {
            return new Flags(
                    passThrough.fpsmatch$isWall(),
                    passThrough.fpsmatch$isSmoke(),
                    passThrough.fpsmatch$isScoped());
        }
        return Flags.NONE;
    }

    public static Flags fromBulletAndTarget(@Nullable Entity bullet, @Nullable Entity target) {
        Flags flags = fromBullet(bullet);
        if (flags.passSmoke() || bullet == null || bullet.level().isClientSide()) {
            return flags;
        }
        if (isSmokeBetween(bullet, target)) {
            return flags.withPassSmoke(true);
        }
        return flags;
    }

    public static void markSmokeIfNeeded(@Nullable Entity bullet, @Nullable Entity target) {
        if (!(bullet instanceof IPassThroughEntity passThrough) || passThrough.fpsmatch$isSmoke()) {
            return;
        }
        if (isSmokeBetween(bullet, target)) {
            passThrough.fpsmatch$setThroughSmoke(true);
        }
    }

    public static boolean isSmokeBetween(Entity bullet, @Nullable Entity target) {
        if (bullet.level().isClientSide()) {
            return false;
        }
        return isSmokeInArea(bullet, buildTrajectoryChecker(bullet, target));
    }

    public static boolean isSmokeInArea(Entity source, AABB checker) {
        List<Entity> entities = source.level().getEntities(source, checker.inflate(16.0D));
        if (entities.isEmpty()) {
            return false;
        }
        return isSmokeInArea(entities, checker);
    }

    public static boolean isSmokeInArea(List<Entity> entities, AABB checker) {
        if (FPSMImpl.findCounterStrikeGrenadesMod() && CounterStrikeGrenadesCompat.isInSmokeGrenadeArea(entities, checker)) {
            return true;
        }

        if (FPSMImpl.findLrtacticalMod() && LrtacticalCompat.isInSmokeGrenadeArea(entities, checker)) {
            return true;
        }

        return isInFPSMSmokeArea(entities, checker);
    }

    private static boolean isInFPSMSmokeArea(List<Entity> entities, AABB checker) {
        List<SmokeShellEntity> smokes = entities.stream()
                .filter(entity -> entity instanceof SmokeShellEntity)
                .map(entity -> (SmokeShellEntity) entity)
                .toList();

        for (SmokeShellEntity smoke : smokes) {
            if (smoke.isInSmokeArea(checker)) {
                return true;
            }
        }
        return false;
    }

    private static AABB buildTrajectoryChecker(Entity bullet, @Nullable Entity target) {
        AABB movementChecker = bullet.getBoundingBox()
                .expandTowards(bullet.getDeltaMovement())
                .inflate(1.0D);
        if (target == null) {
            return movementChecker;
        }

        Vec3 targetCenter = target.getBoundingBox().getCenter();
        AABB targetChecker = bullet.getBoundingBox()
                .expandTowards(targetCenter.subtract(bullet.position()))
                .minmax(target.getBoundingBox())
                .inflate(1.0D);
        return movementChecker.minmax(targetChecker);
    }

    public record Flags(boolean passWall, boolean passSmoke, boolean scoped) {

        public static final Flags NONE = new Flags(false, false, false);

        public Flags withPassSmoke(boolean passSmoke) {
            return new Flags(passWall, this.passSmoke || passSmoke, scoped);
        }

        public Flags or(Flags other) {
            return new Flags(
                    passWall || other.passWall,
                    passSmoke || other.passSmoke,
                    scoped || other.scoped);
        }
    }
}
