package net.ptcrys.fpsmatch.editor.core.fontprobe;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.api.distmarker.Dist;

/** Loaded only by the explicit isolated development run. */
@Mod.EventBusSubscriber(modid = "fpsmatch", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FontProbe {
    static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
    private boolean started;

    private static boolean initialized;

    private FontProbe() {
        MinecraftForge.EVENT_BUS.addListener(this::tick);
    }

    private static void requireEnabled() {
        if (!Boolean.getBoolean("editorFontSmoke.enabled")) {
            throw new IllegalStateException("Development font probe requires its explicit run task");
        }
    }

    @SubscribeEvent
    public static synchronized void commonSetup(FMLCommonSetupEvent event) {
        requireEnabled();
        if (initialized) return;
        initialized = true;
        new FontProbe();
    }

    @SubscribeEvent
    public static void registerReloadListener(RegisterClientReloadListenersEvent event) {
        requireEnabled();
        event.registerReloadListener((ResourceManagerReloadListener) manager -> RESOURCE_GENERATION.incrementAndGet());
    }

    private void tick(TickEvent.ClientTickEvent event) {
        requireEnabled();
        if (event.phase != TickEvent.Phase.END || started) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
            started = true;
            client.setScreen(new FontProbeScreen());
        }
    }
}
