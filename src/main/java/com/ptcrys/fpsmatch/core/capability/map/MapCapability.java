package com.ptcrys.fpsmatch.core.capability.map;

import com.ptcrys.fpsmatch.core.capability.FPSMCapability;
import com.ptcrys.fpsmatch.core.map.BaseMap;

/**
 * 地图专属能力接口
 * 持有者固定为BaseMap，继承基础能力接口
 */
public abstract class MapCapability extends FPSMCapability<BaseMap> {

    protected final BaseMap map;

    public MapCapability(BaseMap map) {
        this.map = map;
    }

    @Override
    public final BaseMap getHolder() {
        return map;
    }
}
