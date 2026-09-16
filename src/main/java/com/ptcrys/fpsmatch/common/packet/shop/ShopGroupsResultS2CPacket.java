package com.ptcrys.fpsmatch.common.packet.shop;

import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record ShopGroupsResultS2CPacket(int containerId, long requestId, int groupId, int[] indices,
                                      EditShopSlotMenu.SaveResult result) {
    public ShopGroupsResultS2CPacket { indices = indices.clone(); }
    public static void encode(ShopGroupsResultS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId); buf.writeLong(packet.requestId);
        buf.writeInt(packet.groupId); buf.writeVarIntArray(packet.indices); buf.writeEnum(packet.result);
    }
    public static ShopGroupsResultS2CPacket decode(FriendlyByteBuf buf) {
        return new ShopGroupsResultS2CPacket(buf.readVarInt(), buf.readLong(), buf.readInt(),
                buf.readVarIntArray(ShopEditorValues.MAX_SELECTION), buf.readEnum(EditShopSlotMenu.SaveResult.class));
    }
    public void handle(Supplier<NetworkEvent.Context> context) { ClientPacketExecutor.execute(context, this); }
}
