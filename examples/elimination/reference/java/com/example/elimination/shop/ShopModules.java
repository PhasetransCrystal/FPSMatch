package com.example.elimination.shop;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.event.register.RegisterListenerModuleEvent;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;

@Mod.EventBusSubscriber(modid = "elimination_addon",
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShopModules {
    @SubscribeEvent
    public static void register(RegisterListenerModuleEvent event) {
        event.register(new ListenerModule() {
            @Override
            public String getName() { return "elimination_addon:group_log"; }

            @Override
            public int getPriority() { return 0; }

            @Override
            public void onChange(ShopSlotChangeEvent change) {
                FPSMatch.LOGGER.debug("Shop group change: player={}, flag={}",
                        change.player.getUUID(), change.flag);
            }
        });
    }
}
