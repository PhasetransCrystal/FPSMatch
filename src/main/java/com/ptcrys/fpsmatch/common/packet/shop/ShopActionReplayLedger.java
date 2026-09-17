package com.ptcrys.fpsmatch.common.packet.shop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ShopActionReplayLedger {

    private static final int MAX_REQUESTS_PER_PLAYER = 256;
    private static final int MAX_PLAYERS = 1024;
    private static final Map<UUID, LinkedHashMap<Long, ShopActionResultS2CPacket>> RESULTS = new LinkedHashMap<>(16, 0.75f, true);

    private ShopActionReplayLedger() {}

    static synchronized ShopActionResultS2CPacket find(UUID player, long requestId) {
        Map<Long, ShopActionResultS2CPacket> results = RESULTS.get(player);
        return results == null ? null : results.get(requestId);
    }

    static synchronized ShopActionResultS2CPacket record(
                                                         UUID player,
                                                         ShopActionResultS2CPacket packet) {
        LinkedHashMap<Long, ShopActionResultS2CPacket> results = RESULTS.computeIfAbsent(
                player, ignored -> new LinkedHashMap<>());
        while (RESULTS.size() > MAX_PLAYERS) {
            RESULTS.remove(RESULTS.keySet().iterator().next());
        }
        ShopActionResultS2CPacket existing = results.get(packet.requestId());
        if (existing != null) {
            return existing;
        }
        results.put(packet.requestId(), packet);
        while (results.size() > MAX_REQUESTS_PER_PLAYER) {
            results.remove(results.keySet().iterator().next());
        }
        return packet;
    }
}
