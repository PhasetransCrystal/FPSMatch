package com.ptcrys.fpsmatch.common.client.spec;

public enum SpectateMode {

    ATTACH,
    FREE,
    TEAMMATE,
    C4_ORBIT,
    DEATH_SPOT;

    public boolean isRestricted() {
        return this != FREE;
    }
}
