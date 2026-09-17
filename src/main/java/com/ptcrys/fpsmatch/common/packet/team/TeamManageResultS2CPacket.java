package com.ptcrys.fpsmatch.common.packet.team;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 队伍管理操作结果数据包，服务端发送给客户端
 */
public record TeamManageResultS2CPacket(boolean success, Component message) {

    public static void encode(TeamManageResultS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.success());
        buf.writeComponent(packet.message());
    }

    public static TeamManageResultS2CPacket decode(FriendlyByteBuf buf) {
        return new TeamManageResultS2CPacket(buf.readBoolean(), buf.readComponent());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {});
        ctx.get().setPacketHandled(true);
    }
}
