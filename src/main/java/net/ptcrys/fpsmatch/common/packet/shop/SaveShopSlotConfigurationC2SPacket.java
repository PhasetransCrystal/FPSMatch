package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorService;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SaveShopSlotConfigurationC2SPacket(long requestId, ShopEditorSnapshot.Target target, String revision,
                                                 String type, int index, ShopEditorSnapshot.Slot draft) {

    public static void encode(SaveShopSlotConfigurationC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        packet.target.write(buf);
        buf.writeUtf(packet.revision, 64);
        buf.writeUtf(packet.type, 128);
        buf.writeInt(packet.index);
        packet.draft.write(buf);
    }

    public static SaveShopSlotConfigurationC2SPacket decode(FriendlyByteBuf buf) {
        return new SaveShopSlotConfigurationC2SPacket(buf.readLong(), ShopEditorSnapshot.Target.read(buf), buf.readUtf(64),
                buf.readUtf(128), buf.readInt(), ShopEditorSnapshot.Slot.read(buf));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ShopEditorService.saveSlot(ctx.get().getSender(), requestId, target, revision, type, index, draft));
        ctx.get().setPacketHandled(true);
    }
}
