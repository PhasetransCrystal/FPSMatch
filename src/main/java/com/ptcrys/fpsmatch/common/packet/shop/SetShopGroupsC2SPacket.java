package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import com.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import com.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;

import java.util.function.Supplier;

public record SetShopGroupsC2SPacket(int containerId, long requestId, int groupId, int[] indices) {

    public SetShopGroupsC2SPacket {
        indices = indices.clone();
    }

    public static void encode(SetShopGroupsC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeLong(packet.requestId);
        buf.writeInt(packet.groupId);
        buf.writeVarIntArray(packet.indices);
    }

    public static SetShopGroupsC2SPacket decode(FriendlyByteBuf buf) {
        return new SetShopGroupsC2SPacket(buf.readVarInt(), buf.readLong(), buf.readInt(),
                buf.readVarIntArray(ShopEditorValues.MAX_SELECTION));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player == null) return;
            var result = player.containerMenu instanceof EditorShopContainer menu && menu.containerId == containerId ? menu.trySetGroups(player, indices, groupId) : EditShopSlotMenu.SaveResult.INVALID_MENU;
            FPSMatch.sendToPlayer(player, new ShopGroupsResultS2CPacket(containerId, requestId, groupId,
                    result.success() ? indices : new int[0], result));
        });
        context.get().setPacketHandled(true);
    }
}
