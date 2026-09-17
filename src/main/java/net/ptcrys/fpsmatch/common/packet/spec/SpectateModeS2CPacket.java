package net.ptcrys.fpsmatch.common.packet.spec;

import net.ptcrys.fpsmatch.common.client.spec.SpectateMode;
import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

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
