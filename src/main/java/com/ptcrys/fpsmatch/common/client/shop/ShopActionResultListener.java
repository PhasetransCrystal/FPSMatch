package com.ptcrys.fpsmatch.common.client.shop;

import com.ptcrys.fpsmatch.common.packet.shop.ShopActionResultS2CPacket;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class ShopActionResultListener {

    private static final AtomicReference<Consumer<ShopActionResultS2CPacket>> LISTENER = new AtomicReference<>();

    private ShopActionResultListener() {}

    public static AutoCloseable install(Consumer<ShopActionResultS2CPacket> listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENER.set(listener);
        return () -> LISTENER.compareAndSet(listener, null);
    }

    public static void dispatch(ShopActionResultS2CPacket result) {
        Consumer<ShopActionResultS2CPacket> listener = LISTENER.get();
        if (listener != null) {
            listener.accept(Objects.requireNonNull(result, "result"));
        }
    }
}
