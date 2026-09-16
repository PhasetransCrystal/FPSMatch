package com.ptcrys.fpsmatch.common.client.camera;

public record CameraPolicy(LookInput look, boolean blockMovement, boolean blockInteraction,
                           boolean allowSpectatorSwitch, boolean hideHands, boolean hideHud,
                           boolean hideLocalModel, boolean hideToasts, boolean suppressViewEffects,
                           boolean lockPerspective) {
    public enum LookInput { PLAYER, LOCKED, ORBIT }
    public static final CameraPolicy PLAYER = new CameraPolicy(LookInput.PLAYER, false, false, true, false, false, false, false, false, false);
    public static final CameraPolicy CINEMATIC = new CameraPolicy(LookInput.LOCKED, true, true, false, true, true, false, true, true, true);
    public static final CameraPolicy PREVIEW = new CameraPolicy(LookInput.LOCKED, true, true, false, true, true, true, true, true, true);
    public static final CameraPolicy DEATH = new CameraPolicy(LookInput.LOCKED, true, true, false, true, false, false, false, true, true);
    public static final CameraPolicy SPECTATOR = new CameraPolicy(LookInput.LOCKED, true, true, true, false, false, false, false, false, false);
    public static final CameraPolicy ORBIT = new CameraPolicy(LookInput.ORBIT, true, true, false, true, false, false, false, true, true);
}
