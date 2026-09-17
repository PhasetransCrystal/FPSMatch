package com.ptcrys.fpsmatch.common.packet.spec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.function.Supplier;

public record SpectateModeS2CPacket(SpectateMode mode) {

    public static void encode(SpectateModeS2CPacket p, FriendlyByteBuf buf) {
        buf.writeEnum(p.mode);
    }

    public static SpectateModeS2CPacket decode(FriendlyByteBuf buf) {
        return new SpectateModeS2CPacket(buf.readEnum(SpectateMode.class));
    }

    public void handle(Supplier<NetworkEvent.Context> ctxSup) {
        ClientPacketExecutor.execute(ctxSup, this);
    }
}
