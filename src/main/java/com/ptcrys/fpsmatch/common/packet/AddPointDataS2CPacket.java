package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AddPointDataS2CPacket(String key, Component name, int color, Vec3 position) {

    public static void encode(AddPointDataS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.key());
        buf.writeComponent(packet.name());
        buf.writeInt(packet.color());
        buf.writeDouble(packet.position().x);
        buf.writeDouble(packet.position().y);
        buf.writeDouble(packet.position().z);
    }

    public static AddPointDataS2CPacket decode(FriendlyByteBuf buf) {
        return new AddPointDataS2CPacket(
                buf.readUtf(),
                buf.readComponent(),
                buf.readInt(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
