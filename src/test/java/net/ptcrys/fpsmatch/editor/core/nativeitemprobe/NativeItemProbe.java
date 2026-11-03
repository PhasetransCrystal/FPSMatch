package net.ptcrys.fpsmatch.editor.core.nativeitemprobe;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.ptcrys.fpsmatch.editor.common.host.forge.EditorRegistrationEvent;

/** Explicit development-only consumer of the same public registration event as both mods. */
@Mod.EventBusSubscriber(modid = "blockoffensive", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class NativeItemProbe {
    private NativeItemProbe() { }

    @SubscribeEvent
    public static void register(EditorRegistrationEvent event) {
        if (!Boolean.getBoolean("q48.probe.enabled")) {
            throw new IllegalStateException("Q48 probe requires its isolated acceptance launch");
        }
        NativeItemContractChecks.register(event.registry());
    }
}
