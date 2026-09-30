package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.EditorShopContainer;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record OpenShopSlotC2SPacket(int containerId, int slotIndex) {

    public static void encode(OpenShopSlotC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeVarInt(packet.slotIndex);
    }

    public static OpenShopSlotC2SPacket decode(FriendlyByteBuf buf) {
        return new OpenShopSlotC2SPacket(buf.readVarInt(), buf.readVarInt());
    }

    public void handle(Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;
            if (player.containerMenu instanceof EditorShopContainer menu && menu.containerId == containerId) {
                menu.tryOpenSlot(player, slotIndex);
            } else {
                FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(
                        Component.translatable("gui.fpsm.shop_editor.open.invalid_menu"), true));
            }
        });
        context.setPacketHandled(true);
    }
}
