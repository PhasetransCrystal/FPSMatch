package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.core.FPSMCore;

import java.util.function.Supplier;

public class FPSMSoundPlayC2SPacket {

    ResourceLocation location;
    boolean playToTeam;

    public FPSMSoundPlayC2SPacket(ResourceLocation location, boolean playToTeam) {
        this.location = location;
        this.playToTeam = playToTeam;
    }

    public static void encode(FPSMSoundPlayC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeResourceLocation(packet.location);
        buf.writeBoolean(packet.playToTeam);
    }

    public static FPSMSoundPlayC2SPacket decode(FriendlyByteBuf buf) {
        return new FPSMSoundPlayC2SPacket(buf.readResourceLocation(), buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            long now = player.serverLevel().getGameTime();
            if (!SoundRequestPolicy.allow(player.getUUID(), location, now)) return;
            FPSMSoundPlayS2CPacket packet = new FPSMSoundPlayS2CPacket(location);
            FPSMCore.getInstance().getMapByPlayer(player).ifPresent(map -> {
                if (playToTeam) {
                    map.getMapTeams().getTeamByPlayer(player).ifPresent(team -> {
                        map.sendPacketToTeamPlayer(team, packet, false);
                    });
                } else {
                    map.sendPacketToAllPlayer(packet);
                }
            });
        });
        ctx.get().setPacketHandled(true);
    }
}
