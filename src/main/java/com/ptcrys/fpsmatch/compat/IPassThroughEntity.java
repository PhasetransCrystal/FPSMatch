package com.ptcrys.fpsmatch.compat;

public interface IPassThroughEntity {

    boolean fpsmatch$isWall();

    void fpsmatch$setThroughWall(boolean passed);

    boolean fpsmatch$isSmoke();

    void fpsmatch$setThroughSmoke(boolean passed);

    boolean fpsmatch$isScoped();

    void fpsmatch$setScoped(boolean scoped);
}
