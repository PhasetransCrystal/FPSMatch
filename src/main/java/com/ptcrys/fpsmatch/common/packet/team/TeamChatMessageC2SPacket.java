package com.ptcrys.fpsmatch.common.packet.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.core.FPSMCore;

import java.util.function.Supplier;

public record TeamChatMessageC2SPacket(Component message) {

    public static void encode(TeamChatMessageC2SPacket packet, FriendlyByteBuf packetBuffer) {
        packetBuffer.writeComponent(packet.message);
    }

    public static TeamChatMessageC2SPacket decode(FriendlyByteBuf packetBuffer) {
        return new TeamChatMessageC2SPacket(
                packetBuffer.readComponent());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            FPSMCore.getInstance().getMapByPlayer(player)
                    .flatMap(map -> map.getMapTeams().getTeamByPlayer(player))
                    .ifPresent(team -> team.sendMessage(message));
        });
        context.setPacketHandled(true);
    }
}
