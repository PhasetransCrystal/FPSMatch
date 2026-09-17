package com.ptcrys.fpsmatch.common.packet;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ClientPacketRegistry {

    private static final Map<Class<?>, Consumer<Object>> HANDLERS = new ConcurrentHashMap<>();

    private ClientPacketRegistry() {}

    public static <T> void register(Class<T> packetClass, Consumer<T> handler) {
        HANDLERS.put(packetClass, packet -> handler.accept(packetClass.cast(packet)));
    }

    public static void handle(Object packet) {
        Consumer<Object> handler = HANDLERS.get(packet.getClass());
        if (handler == null) {
            throw new IllegalStateException("No client packet handler registered for " + packet.getClass().getName());
        }
        handler.accept(packet);
    }
}
