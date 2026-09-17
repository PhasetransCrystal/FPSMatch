package com.ptcrys.fpsmatch.common.packet.spec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.client.spec.SpectatorSwitchDirection;
import com.ptcrys.fpsmatch.common.client.spec.SpectatorSwitchInputEvent;

import java.util.function.Supplier;

public record SpectatorSwitchC2SPacket(SpectatorSwitchDirection direction) {

    public static void encode(SpectatorSwitchC2SPacket p, FriendlyByteBuf b) {
        b.writeEnum(p.direction());
    }

    public static SpectatorSwitchC2SPacket decode(FriendlyByteBuf b) {
        return new SpectatorSwitchC2SPacket(b.readEnum(SpectatorSwitchDirection.class));
    }

    public void handle(Supplier<NetworkEvent.Context> s) {
        NetworkEvent.Context c = s.get();
        c.enqueueWork(() -> {
            ServerPlayer p = c.getSender();
            if (p != null && p.isSpectator()) MinecraftForge.EVENT_BUS.post(new SpectatorSwitchInputEvent(p, direction));
        });
        c.setPacketHandled(true);
    }
}
