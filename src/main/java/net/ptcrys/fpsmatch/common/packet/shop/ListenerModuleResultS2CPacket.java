package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import net.ptcrys.fpsmatch.common.shop.editor.ListenerModuleSnapshot;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorResult;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ListenerModuleResultS2CPacket(long requestId, ShopEditorSnapshot.Target target, ListenerModuleActionC2SPacket.Action action,
                                            ShopEditorResult result, ListenerModuleSnapshot catalog, ShopEditorSnapshot shop) {

    public static void encode(ListenerModuleResultS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        packet.target.write(buf);
        buf.writeEnum(packet.action);
        buf.writeEnum(packet.result);
        buf.writeBoolean(packet.catalog != null);
        if (packet.catalog != null) packet.catalog.write(buf);
        buf.writeBoolean(packet.shop != null);
        if (packet.shop != null) packet.shop.write(buf);
    }

    public static ListenerModuleResultS2CPacket decode(FriendlyByteBuf buf) {
        return new ListenerModuleResultS2CPacket(buf.readLong(), ShopEditorSnapshot.Target.read(buf), buf.readEnum(ListenerModuleActionC2SPacket.Action.class),
                buf.readEnum(ShopEditorResult.class), buf.readBoolean() ? ListenerModuleSnapshot.read(buf) : null, buf.readBoolean() ? ShopEditorSnapshot.read(buf) : null);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
