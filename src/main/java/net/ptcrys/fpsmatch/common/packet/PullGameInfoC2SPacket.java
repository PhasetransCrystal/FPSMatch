package net.ptcrys.fpsmatch.common.packet;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.map.BaseMap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.function.Supplier;

public record PullGameInfoC2SPacket() {

    public static void encode(PullGameInfoC2SPacket packet, FriendlyByteBuf buf) {}

    public static PullGameInfoC2SPacket decode(FriendlyByteBuf buf) {
        return new PullGameInfoC2SPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                Optional<BaseMap> map = FPSMCore.getInstance().getMapByPlayer(player);
                map.ifPresent(baseMap -> baseMap.pullGameInfo(player));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
