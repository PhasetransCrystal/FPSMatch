package com.example.elimination;

import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.minecraft.server.level.ServerLevel;

public final class EliminationMap extends BaseMap {
    public static final String GAME_TYPE = "elimination";

    public EliminationMap(ServerLevel level, String name, AreaData area) {
        super(level, name, area);
    }

    @Override
    public String getGameType() { return GAME_TYPE; }

    @Override
    public boolean victoryGoal() { return false; }
}
