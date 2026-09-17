package com.ptcrys.fpsmatch.core.shop.functional;

import net.minecraftforge.common.MinecraftForge;

import com.ptcrys.fpsmatch.common.event.register.RegisterListenerModuleEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 监听模块管理器，用于注册和管理监听模块。
 * <p>
 * 该类通过 Forge 的事件总线注册监听模块，并提供方法添加和获取监听模块。
 * 同时支持将监听模块的数据保存到游戏中。
 */
public class LMManager {

    /**
     * 存储所有注册的监听模块。
     */
    protected final Map<String, ListenerModule> registry = new HashMap<>();

    /**
     * 构造函数，初始化监听模块管理器。
     * <p>
     * 该方法会触发 {@link RegisterListenerModuleEvent}，允许其他监听模块注册。
     */
    public LMManager() {
        MinecraftForge.EVENT_BUS.post(new RegisterListenerModuleEvent(this));
    }

    /**
     * 添加一个新的监听模块。
     * 
     * @param listenerModule 要添加的监听模块
     */
    public void addListenerType(ListenerModule listenerModule) {
        String name = listenerModule.getName();
        registry.put(name, listenerModule);
    }

    /**
     * 获取指定名称的监听模块。
     * 
     * @param name 监听模块的名称
     * @return 如果找到对应的监听模块，返回该模块；否则返回 null
     */
    @Nullable
    public ListenerModule getListenerModule(String name) {
        return registry.getOrDefault(name, null);
    }

    /**
     * 获取所有注册的监听模块名称列表。
     * 
     * @return 监听模块名称列表
     */
    public List<String> getListenerModules() {
        return new ArrayList<>(registry.keySet());
    }

    /**
     * 获取所有注册的监听模块。
     * 
     * @return 监听模块的 Map（键为模块名称，值为模块实例）
     */
    public Map<String, ListenerModule> getRegistry() {
        return registry;
    }
}
