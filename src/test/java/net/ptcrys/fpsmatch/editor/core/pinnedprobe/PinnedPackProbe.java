package net.ptcrys.fpsmatch.editor.core.pinnedprobe;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** Isolated development diagnostic; never included in an ordinary build. */
@Mod.EventBusSubscriber(modid = "blockoffensive", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PinnedPackProbe {
    private PinnedPackProbe() { }

    @SubscribeEvent
    public static void commonSetup(FMLCommonSetupEvent event) {
        if (!Boolean.getBoolean("q47.probe.enabled")) {
            throw new IllegalStateException("Q47 probe requires its isolated acceptance launch");
        }
    }
}
