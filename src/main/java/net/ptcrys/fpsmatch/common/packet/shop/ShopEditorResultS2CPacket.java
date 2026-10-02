package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ShopEditorResultS2CPacket(long requestId, Operation operation, ShopEditorSnapshot.Target target,
                                        ShopEditorResult result, ShopEditorSnapshot snapshot) {

    public enum Operation {
        LOAD,
        SAVE_SLOT,
        SET_GROUPS
    }

    public static void encode(ShopEditorResultS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        buf.writeEnum(packet.operation);
        packet.target.write(buf);
        buf.writeEnum(packet.result);
        buf.writeBoolean(packet.snapshot != null);
        if (packet.snapshot != null) packet.snapshot.write(buf);
    }

    public static ShopEditorResultS2CPacket decode(FriendlyByteBuf buf) {
        return new ShopEditorResultS2CPacket(buf.readLong(), buf.readEnum(Operation.class), ShopEditorSnapshot.Target.read(buf),
                buf.readEnum(ShopEditorResult.class), buf.readBoolean() ? ShopEditorSnapshot.read(buf) : null);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
