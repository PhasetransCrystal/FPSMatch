package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorService;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenShopEditorC2SPacket(long requestId, ShopEditorSnapshot.Target target) {

    public static void encode(OpenShopEditorC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        packet.target.write(buf);
    }

    public static OpenShopEditorC2SPacket decode(FriendlyByteBuf buf) {
        return new OpenShopEditorC2SPacket(buf.readLong(), ShopEditorSnapshot.Target.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ShopEditorService.load(ctx.get().getSender(), requestId, target));
        ctx.get().setPacketHandled(true);
    }
}
