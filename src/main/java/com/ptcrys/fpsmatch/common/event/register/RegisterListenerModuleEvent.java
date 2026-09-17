package com.ptcrys.fpsmatch.common.event.register;

import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.core.shop.functional.LMManager;
import com.ptcrys.fpsmatch.core.shop.functional.ListenerModule;

public class RegisterListenerModuleEvent extends Event {

    LMManager manager;

    public RegisterListenerModuleEvent(LMManager lMManager) {
        this.manager = lMManager;
    }

    /**
     * 注册硬编码的监听模块
     */
    public void register(ListenerModule listenerModule) {
        this.manager.addListenerType(listenerModule);
    }
}
