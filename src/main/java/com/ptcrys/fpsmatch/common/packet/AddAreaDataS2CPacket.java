package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.core.data.AreaData;

import java.util.function.Supplier;

public record AddAreaDataS2CPacket(String key, Component name, int color, AreaData areaData) {

    public AddAreaDataS2CPacket(Component name, AreaData areaData) {
        this(name.getString(), name, 0xFFFFFF00, areaData);
    }

    public static void encode(AddAreaDataS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.key());
        buf.writeComponent(packet.name());
        buf.writeInt(packet.color());
        buf.writeJsonWithCodec(AreaData.CODEC, packet.areaData());
    }

    public static AddAreaDataS2CPacket decode(FriendlyByteBuf buf) {
        return new AddAreaDataS2CPacket(
                buf.readUtf(),
                buf.readComponent(),
                buf.readInt(),
                buf.readJsonWithCodec(AreaData.CODEC));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
