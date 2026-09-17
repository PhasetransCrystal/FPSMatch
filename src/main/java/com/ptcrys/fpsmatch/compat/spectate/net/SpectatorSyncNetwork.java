package com.ptcrys.fpsmatch.compat.spectate.net;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import com.ptcrys.fpsmatch.FPSMatch;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated channel for spectator sync packets (TACZ + LRTactical).
 */
@Mod.EventBusSubscriber(modid = FPSMatch.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SpectatorSyncNetwork {

    private static final String PROTOCOL_VERSION = "1";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.tryBuild(FPSMatch.MODID, "spectator_sync"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private SpectatorSyncNetwork() {}

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SpectatorSyncNetwork::registerPackets);
    }

    public static void registerPackets() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        AtomicInteger id = new AtomicInteger(0);
        SpectatorInspectPackets.register(CHANNEL, id);
        SpectatorLrtAttackPackets.register(CHANNEL, id);
    }
}
