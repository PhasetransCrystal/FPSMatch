package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FPSMatchRespawnS2CPacket {

    public static void encode(FPSMatchRespawnS2CPacket packet, FriendlyByteBuf buf) {}

    public static FPSMatchRespawnS2CPacket decode(FriendlyByteBuf buf) {
        return new FPSMatchRespawnS2CPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
