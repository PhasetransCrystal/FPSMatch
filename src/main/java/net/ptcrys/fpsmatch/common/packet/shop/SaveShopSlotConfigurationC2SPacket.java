package net.ptcrys.fpsmatch.common.packet.shop;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.client.screen.EditShopSlotMenu;
import net.ptcrys.fpsmatch.common.client.screen.shop.ShopEditorValues;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

public record SaveShopSlotConfigurationC2SPacket(int containerId, int ammo, int price, int group, List<String> modules) {

    public SaveShopSlotConfigurationC2SPacket {
        modules = List.copyOf(modules);
    }

    public static void encode(SaveShopSlotConfigurationC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.containerId);
        buf.writeInt(packet.ammo);
        buf.writeInt(packet.price);
        buf.writeInt(packet.group);
        buf.writeCollection(packet.modules, (out, name) -> out.writeUtf(name, ShopEditorValues.MAX_MODULE_NAME));
    }

    public static SaveShopSlotConfigurationC2SPacket decode(FriendlyByteBuf buf) {
        return new SaveShopSlotConfigurationC2SPacket(buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readCollection(FriendlyByteBuf.limitValue(java.util.ArrayList::new, ShopEditorValues.MAX_MODULES),
                        in -> in.readUtf(ShopEditorValues.MAX_MODULE_NAME)));
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            var player = context.get().getSender();
            if (player == null) return;
            var result = player.containerMenu instanceof EditShopSlotMenu menu && menu.containerId == containerId ? menu.trySaveData(player, ammo, price, group, modules) : EditShopSlotMenu.SaveResult.INVALID_MENU;
            FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(Component.translatable(result.translationKey()), !result.success()));
        });
        context.get().setPacketHandled(true);
    }
}
