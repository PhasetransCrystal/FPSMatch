package com.ptcrys.fpsmatch.compat.spectate;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Computes movement direction flags for a spectated entity.
 */
public final class SpectatorMotion {

    private static final double SPEED_SQ_EPS = 1.0E-4;
    private static final double DIR_EPS = 0.01;

    private SpectatorMotion() {}

    public static boolean isMoving(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Vec3 vel = entity.getDeltaMovement();
        return vel.x * vel.x + vel.z * vel.z > SPEED_SQ_EPS;
    }

    public static boolean isMovingForward(LivingEntity entity) {
        return resolveForwardDot(entity) > DIR_EPS;
    }

    public static boolean isMovingBackward(LivingEntity entity) {
        return resolveForwardDot(entity) < -DIR_EPS;
    }

    public static boolean isMovingRight(LivingEntity entity) {
        return resolveRightDot(entity) > DIR_EPS;
    }

    public static boolean isMovingLeft(LivingEntity entity) {
        return resolveRightDot(entity) < -DIR_EPS;
    }

    public static boolean isJumping(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        return !entity.onGround() && entity.getDeltaMovement().y > 0.02;
    }

    private static double resolveForwardDot(LivingEntity entity) {
        MotionAxes axes = MotionAxes.from(entity);
        return axes == null ? 0.0 : axes.forward;
    }

    private static double resolveRightDot(LivingEntity entity) {
        MotionAxes axes = MotionAxes.from(entity);
        return axes == null ? 0.0 : axes.right;
    }

    private static final class MotionAxes {

        private final double forward;
        private final double right;

        private MotionAxes(double forward, double right) {
            this.forward = forward;
            this.right = right;
        }

        private static MotionAxes from(LivingEntity entity) {
            if (entity == null) {
                return null;
            }
            Vec3 vel = entity.getDeltaMovement();
            double vx = vel.x;
            double vz = vel.z;
            double speedSq = vx * vx + vz * vz;
            if (speedSq < SPEED_SQ_EPS) {
                return null;
            }
            Vec3 look = entity.getViewVector(1.0f);
            double lx = look.x;
            double lz = look.z;
            double lenSq = lx * lx + lz * lz;
            if (lenSq < 1.0E-6) {
                return null;
            }
            double invLen = 1.0 / Math.sqrt(lenSq);
            lx *= invLen;
            lz *= invLen;
            double forward = vx * lx + vz * lz;
            double right = vx * -lz + vz * lx;
            return new MotionAxes(forward, right);
        }
    }
}
