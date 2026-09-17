package com.ptcrys.fpsmatch.compat.tacz;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.IEventBus;

public class TACZCompat {

    /**
     * 注册TACZ观察者支持
     */
    @OnlyIn(Dist.CLIENT)
    public static void registerSpecClient(IEventBus bus) {}
}
