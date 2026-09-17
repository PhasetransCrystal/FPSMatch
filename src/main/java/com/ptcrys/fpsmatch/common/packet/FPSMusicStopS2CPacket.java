package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FPSMusicStopS2CPacket {

    public FPSMusicStopS2CPacket() {}

    public static void encode(FPSMusicStopS2CPacket packet, FriendlyByteBuf buf) {}

    public static FPSMusicStopS2CPacket decode(FriendlyByteBuf buf) {
        return new FPSMusicStopS2CPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
