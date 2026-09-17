package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FPSMSoundPlayS2CPacket {

    ResourceLocation location;

    public FPSMSoundPlayS2CPacket(ResourceLocation location) {
        this.location = location;
    }

    public static void encode(FPSMSoundPlayS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.location);
    }

    public static FPSMSoundPlayS2CPacket decode(FriendlyByteBuf buf) {
        return new FPSMSoundPlayS2CPacket(buf.readResourceLocation());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }

    public ResourceLocation getLocation() {
        return location;
    }
}
