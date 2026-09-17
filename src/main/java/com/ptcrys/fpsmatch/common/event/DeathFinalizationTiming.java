package com.ptcrys.fpsmatch.common.event;

final class DeathFinalizationTiming {

    private DeathFinalizationTiming() {}

    static boolean isReady(long createdTick, long currentTick) {
        return createdTick < currentTick;
    }
}
