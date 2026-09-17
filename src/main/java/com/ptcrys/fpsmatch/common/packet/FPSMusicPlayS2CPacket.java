package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FPSMusicPlayS2CPacket {

    ResourceLocation location;

    public FPSMusicPlayS2CPacket(ResourceLocation location) {
        this.location = location;
    }

    public static void encode(FPSMusicPlayS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.location);
    }

    public static FPSMusicPlayS2CPacket decode(FriendlyByteBuf buf) {
        return new FPSMusicPlayS2CPacket(buf.readResourceLocation());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }

    public ResourceLocation getLocation() {
        return location;
    }
}
