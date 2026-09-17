package net.ptcrys.fpsmatch.common.event.register;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

import com.mojang.datafixers.util.Function3;

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
