package com.example.elimination;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Consumer;
import java.util.function.Supplier;

public record ScoreS2CPacket(String mapName, int red, int blue, String phase) {
    // Installed during physical client setup; the signature contains no client classes.
    public static Consumer<ScoreS2CPacket> receiver = packet -> {};

    public static void encode(ScoreS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.mapName, 48);
        buf.writeVarInt(packet.red);
        buf.writeVarInt(packet.blue);
        buf.writeUtf(packet.phase, 32);
    }

    public static ScoreS2CPacket decode(FriendlyByteBuf buf) {
        return new ScoreS2CPacket(buf.readUtf(48), buf.readVarInt(), buf.readVarInt(), buf.readUtf(32));
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> receiver.accept(this));
        context.setPacketHandled(true);
    }
}
