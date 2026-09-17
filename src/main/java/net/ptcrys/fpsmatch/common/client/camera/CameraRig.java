package net.ptcrys.fpsmatch.common.client.camera;

@FunctionalInterface
public interface CameraRig {

    CameraFrame sample(double ticks);

    default void turn(float yaw, float pitch) {}
}
