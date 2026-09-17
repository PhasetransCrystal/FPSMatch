package com.ptcrys.fpsmatch.compat.kubejs;

import com.ptcrys.fpsmatch.compat.kubejs.events.FPSMatchCommonEvents;
import com.ptcrys.fpsmatch.compat.kubejs.events.FPSMatchKubeJSEvents;
import dev.latvian.mods.kubejs.KubeJSPlugin;

public class FPSMatchKubeJSPlugin extends KubeJSPlugin {

    @Override
    public void registerEvents() {
        try {
            FPSMatchCommonEvents.INSTANCE.init();
        } catch (Exception e) {
            // init() 失败不应阻止 FPSMatchEvents 事件组的注册
        }
        FPSMatchKubeJSEvents.GROUP.register();
    }
}
