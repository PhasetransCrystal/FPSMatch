package com.example.elimination;

import net.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(EliminationMod.MODID)
public final class EliminationMod {
    public static final String MODID = "elimination_addon";

    public EliminationMod() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
    }

    private void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            EliminationNetwork.register();
            FPSMCapabilityManager.register(
                FPSMCapabilityManager.CapabilityType.MAP,
                RoundAnnouncements.class, RoundAnnouncements::new);
        });
    }
}
