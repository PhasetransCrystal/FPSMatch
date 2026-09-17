package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side guard for client-requested match sounds. */
final class SoundRequestPolicy {

    private static final int MAX_REQUESTS = 4;
    private static final long WINDOW_TICKS = 20L;
    private static final Map<UUID, ArrayDeque<Long>> REQUESTS = new ConcurrentHashMap<>();

    private SoundRequestPolicy() {}

    static boolean allow(UUID player, ResourceLocation location, long now) {
        if (player == null || location == null || !isGameplaySound(location)) {
            return false;
        }
        ArrayDeque<Long> requests = REQUESTS.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        synchronized (requests) {
            while (!requests.isEmpty() && now - requests.peekFirst() >= WINDOW_TICKS) {
                requests.removeFirst();
            }
            if (requests.size() >= MAX_REQUESTS) {
                return false;
            }
            requests.addLast(now);
            return true;
        }
    }

    private static boolean isGameplaySound(ResourceLocation location) {
        return (location.getNamespace().equals("fpsmatch") || location.getNamespace().equals("blockoffensive")) && ForgeRegistries.SOUND_EVENTS.containsKey(location);
    }
}
