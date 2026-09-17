package com.ptcrys.fpsmatch.common.client.spec;

public final class SpectateState {

    private static volatile SpectateMode mode = SpectateMode.FREE;
    private static volatile SpectateTarget target;

    public static void set(SpectateMode value) {
        mode = value == null ? SpectateMode.FREE : value;
        if (mode == SpectateMode.FREE || (target != null && target.mode() != mode)) target = null;
    }

    public static void setTarget(SpectateTarget value) {
        target = value;
        mode = value == null || value.mode() == null ? SpectateMode.FREE : value.mode();
    }

    public static SpectateMode get() {
        return mode;
    }

    public static SpectateTarget getTarget() {
        return target;
    }

    public static boolean isAttach() {
        return mode == SpectateMode.ATTACH || mode == SpectateMode.TEAMMATE;
    }

    public static boolean isRestricted() {
        return mode.isRestricted();
    }

    private SpectateState() {}
}
