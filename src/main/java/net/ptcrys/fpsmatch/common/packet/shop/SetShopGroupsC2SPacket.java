package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorService;
import net.ptcrys.fpsmatch.common.shop.editor.ShopEditorSnapshot;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SetShopGroupsC2SPacket(long requestId, ShopEditorSnapshot.Target target, String revision, int groupId, int[] indices) {

    public SetShopGroupsC2SPacket {
        indices = indices.clone();
    }

    @Override
    public int[] indices() {
        return indices.clone();
    }

    public static void encode(SetShopGroupsC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.requestId);
        packet.target.write(buf);
        buf.writeUtf(packet.revision, 64);
        buf.writeInt(packet.groupId);
        buf.writeVarIntArray(packet.indices);
    }

    public static SetShopGroupsC2SPacket decode(FriendlyByteBuf buf) {
        return new SetShopGroupsC2SPacket(buf.readLong(), ShopEditorSnapshot.Target.read(buf), buf.readUtf(64),
                buf.readInt(), buf.readVarIntArray(ShopEditorSnapshot.MAX_SLOTS));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ShopEditorService.setGroups(ctx.get().getSender(), requestId, target, revision, groupId, indices));
        ctx.get().setPacketHandled(true);
    }
}
