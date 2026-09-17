package com.ptcrys.fpsmatch.common.event.register;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

import com.mojang.datafixers.util.Function3;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.map.BaseMap;

public class RegisterFPSMapEvent extends Event {

    private final FPSMCore fpsmCore;

    public RegisterFPSMapEvent(FPSMCore fpsmCore) {
        this.fpsmCore = fpsmCore;
    }

    @Override
    public boolean isCancelable() {
        return false;
    }

    public void registerGameType(String typeName, Function3<ServerLevel, String, AreaData, BaseMap> map) {
        this.fpsmCore.registerGameType(typeName, map);
    }
}
