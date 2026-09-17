package com.ptcrys.fpsmatch.common.packet.register;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class NetworkPacketRegister {

    private static final Map<NetworkPacketRegister, List<Class<?>>> CACHED = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private final SimpleChannel channel;
    private final ResourceLocation name;
    private final RegistrationSink registrationSink;

    public NetworkPacketRegister(ResourceLocation channel, String version) {
        this(channel, () -> version, version::equals, version::equals);
    }

    public NetworkPacketRegister(ResourceLocation channel, Supplier<String> networkProtocolVersion, Predicate<String> clientAcceptedVersions, Predicate<String> serverAcceptedVersions) {
        this.name = channel;
        this.channel = NetworkRegistry.newSimpleChannel(
                channel,
                networkProtocolVersion,
                clientAcceptedVersions,
                serverAcceptedVersions);
        this.registrationSink = this::registerWithForge;
    }

    NetworkPacketRegister(RegistrationSink registrationSink) {
        this.name = null;
        this.channel = null;
        this.registrationSink = Objects.requireNonNull(
                registrationSink, "registrationSink");
    }

    @SuppressWarnings("unchecked")
    public <T> void registerPacket(Class<T> packetClass) {
        // 依据包类名自动推导方向（C2S=PLAY_TO_SERVER、S2C=PLAY_TO_CLIENT），
        // 对齐 Forge/主流模组显式声明 NetworkDirection 的规范；无法从命名判定的退回双向注册。
        registerPacketInternal(packetClass, inferDirection(packetClass));
    }

    private static NetworkDirection inferDirection(Class<?> packetClass) {
        String simpleName = packetClass.getSimpleName();
        if (simpleName.endsWith("C2SPacket") || simpleName.endsWith("C2S")) {
            return NetworkDirection.PLAY_TO_SERVER;
        }
        if (simpleName.endsWith("S2CPacket") || simpleName.endsWith("S2C")) {
            return NetworkDirection.PLAY_TO_CLIENT;
        }
        return null;
    }

    public <T> void registerPacket(Class<T> packetClass, NetworkDirection direction) {
        registerPacketInternal(packetClass, Objects.requireNonNull(direction, "direction"));
    }

    @SuppressWarnings("unchecked")
    private <T> void registerPacketInternal(Class<T> packetClass, NetworkDirection direction) {
        try {
            // 检查 encode
            Method encode = packetClass.getMethod("encode", packetClass, FriendlyByteBuf.class);
            if (!Modifier.isStatic(encode.getModifiers())) {
                throw new IllegalArgumentException("encode() must be static in " + packetClass.getName());
            }

            // 检查 decode
            Method decode = packetClass.getMethod("decode", FriendlyByteBuf.class);
            if (!Modifier.isStatic(decode.getModifiers())) {
                throw new IllegalArgumentException("decode() must be static in " + packetClass.getName());
            }
            if (!packetClass.isAssignableFrom(decode.getReturnType())) {
                throw new IllegalArgumentException("decode() must return " + packetClass.getName());
            }

            // 检查 handle
            Method handle = packetClass.getMethod("handle", Supplier.class);

            // 注册 Packet
            int messageId = idCounter.getAndIncrement();
            registrationSink.register(
                    messageId,
                    packetClass,
                    direction,
                    (packet, buf) -> {
                        try {
                            encode.invoke(null, packet, buf);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to encode packet", e);
                        }
                    },
                    buf -> {
                        try {
                            return decode.invoke(null, buf);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to decode packet", e);
                        }
                    },
                    (packet, ctx) -> {
                        try {
                            handle.invoke(packet, ctx);
                        } catch (Exception e) {
                            throw new RuntimeException("Failed to handle packet", e);
                        }
                    });
            // LOGGER.info("{} registered", packetClass.getSimpleName());

            if (channel != null) {
                CACHED.computeIfAbsent(this, k -> new ArrayList<>()).add(packetClass);
            }
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Packet class " + packetClass.getName() +
                    " is missing required methods (encode/decode/handle)", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerWithForge(
                                   int messageId,
                                   Class<?> packetClass,
                                   NetworkDirection direction,
                                   BiConsumer<Object, FriendlyByteBuf> encoder,
                                   Function<FriendlyByteBuf, Object> decoder,
                                   BiConsumer<Object, Supplier<NetworkEvent.Context>> consumer) {
        Class<Object> typedPacketClass = (Class<Object>) packetClass;
        SimpleChannel.MessageBuilder<Object> builder = direction == null ? channel.messageBuilder(typedPacketClass, messageId) : channel.messageBuilder(typedPacketClass, messageId, direction);
        builder.encoder(encoder)
                .decoder(decoder)
                .consumerNetworkThread(consumer)
                .add();
    }

    public SimpleChannel getChannel() {
        return channel;
    }

    public ResourceLocation getName() {
        return name;
    }

    public static SimpleChannel getChannelFromCache(Class<?> clazz) {
        if (clazz == null) throw new IllegalArgumentException("Packet class cannot be null");

        for (Map.Entry<NetworkPacketRegister, List<Class<?>>> entry : NetworkPacketRegister.CACHED.entrySet()) {
            if (entry.getValue().contains(clazz)) {
                return entry.getKey().getChannel();
            }
        }

        throw new RuntimeException("Failed to find channel for " + clazz.getName());
    }

    @FunctionalInterface
    interface RegistrationSink {

        void register(
                      int messageId,
                      Class<?> packetClass,
                      NetworkDirection direction,
                      BiConsumer<Object, FriendlyByteBuf> encoder,
                      Function<FriendlyByteBuf, Object> decoder,
                      BiConsumer<Object, Supplier<NetworkEvent.Context>> consumer);
    }
}
