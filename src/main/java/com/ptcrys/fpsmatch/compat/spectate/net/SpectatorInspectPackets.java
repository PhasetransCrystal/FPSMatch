package com.ptcrys.fpsmatch.compat.spectate.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.simple.SimpleChannel;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Packets for TACZ inspect sync while spectating.
 */
public final class SpectatorInspectPackets {

    private SpectatorInspectPackets() {}

    public static void register(SimpleChannel channel, AtomicInteger id) {
        channel.messageBuilder(C2SStartInspectPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_SERVER)
                .decoder(C2SStartInspectPacket::decode)
                .encoder(C2SStartInspectPacket::encode)
                .consumerMainThread((pkt, ctx) -> handleStartInspectPacket(channel, pkt, ctx))
                .add();

        channel.messageBuilder(S2CWatchedPlayerInspectPacket.class, id.getAndIncrement(), NetworkDirection.PLAY_TO_CLIENT)
                .decoder(S2CWatchedPlayerInspectPacket::decode)
                .encoder(S2CWatchedPlayerInspectPacket::encode)
                .consumerMainThread(S2CWatchedPlayerInspectPacket::handle)
                .add();
    }

    public record C2SStartInspectPacket() {

        public static C2SStartInspectPacket decode(FriendlyByteBuf b) {
            return new C2SStartInspectPacket();
        }

        public static void encode(C2SStartInspectPacket p, FriendlyByteBuf b) {}
    }

    public record S2CWatchedPlayerInspectPacket(UUID id) {

        public static S2CWatchedPlayerInspectPacket decode(FriendlyByteBuf b) {
            return new S2CWatchedPlayerInspectPacket(b.readUUID());
        }

        public static void encode(S2CWatchedPlayerInspectPacket p, FriendlyByteBuf b) {
            b.writeUUID(p.id);
        }

        public UUID getPlayerId() {
            return this.id;
        }

        public static void handle(S2CWatchedPlayerInspectPacket p, Supplier<NetworkEvent.Context> ctx) {
            ClientPacketExecutor.execute(ctx, p);
        }
    }

    private static void handleStartInspectPacket(SimpleChannel channel, C2SStartInspectPacket m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp == null) return;
            S2CWatchedPlayerInspectPacket pkt = new S2CWatchedPlayerInspectPacket(sp.getUUID());
            for (ServerPlayer pl : sp.server.getPlayerList().getPlayers()) {
                channel.sendTo(pkt, pl.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
