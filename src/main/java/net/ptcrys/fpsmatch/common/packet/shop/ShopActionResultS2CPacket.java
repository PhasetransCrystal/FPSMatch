package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import net.ptcrys.fpsmatch.core.shop.ShopAction;
import net.ptcrys.fpsmatch.core.shop.ShopActionResult;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

public record ShopActionResultS2CPacket(
                                        long requestId,
                                        String type,
                                        int index,
                                        ShopAction action,
                                        ShopActionResult result) {

    public ShopActionResultS2CPacket {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(result, "result");
    }

    public static void encode(ShopActionResultS2CPacket packet, FriendlyByteBuf buffer) {
        buffer.writeLong(packet.requestId);
        buffer.writeUtf(packet.type);
        buffer.writeInt(packet.index);
        buffer.writeEnum(packet.action);
        buffer.writeEnum(packet.result.code());
    }

    public static ShopActionResultS2CPacket decode(FriendlyByteBuf buffer) {
        return new ShopActionResultS2CPacket(
                buffer.readLong(),
                buffer.readUtf(),
                buffer.readInt(),
                buffer.readEnum(ShopAction.class),
                new ShopActionResult(buffer.readEnum(ShopActionResult.Code.class)));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        ClientPacketExecutor.execute(context, this);
    }
}
