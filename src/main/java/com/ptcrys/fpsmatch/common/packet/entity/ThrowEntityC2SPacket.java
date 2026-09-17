package com.ptcrys.fpsmatch.common.packet.entity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.BaseThrowAbleItem;
import com.ptcrys.fpsmatch.core.item.IThrowEntityAble;

import java.util.function.Supplier;

public class ThrowEntityC2SPacket {

    BaseThrowAbleItem.ThrowType type;

    public ThrowEntityC2SPacket(BaseThrowAbleItem.ThrowType type) {
        this.type = type;
    }

    public static void encode(ThrowEntityC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.type == null ? -1 : packet.type.ordinal());
    }

    public static ThrowEntityC2SPacket decode(FriendlyByteBuf buf) {
        return new ThrowEntityC2SPacket(BaseThrowAbleItem.ThrowType.fromNetworkOrdinal(buf.readVarInt()));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                ctx.get().setPacketHandled(true);
                return;
            }
            if (type == null) return;
            if (player.getItemInHand(InteractionHand.MAIN_HAND).getItem() instanceof IThrowEntityAble throwEntityAble) {
                if (throwEntityAble.isThrowTypeAllowed(type)) {
                    throwEntityAble.shoot(player, player.level(), InteractionHand.MAIN_HAND, type.velocity(), type.inaccuracy());
                }
            } else if (player.getItemInHand(InteractionHand.OFF_HAND).getItem() instanceof IThrowEntityAble throwEntityAble) {
                if (throwEntityAble.isThrowTypeAllowed(type)) {
                    throwEntityAble.shoot(player, player.level(), InteractionHand.OFF_HAND, type.velocity(), type.inaccuracy());
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
