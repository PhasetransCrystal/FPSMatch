package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.item.MatchConfigTool;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomActionService;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;

import java.util.function.Supplier;

public record MatchConfigToolActionC2SPacket(
                                             Action action,
                                             String selectedType,
                                             String selectedMap,
                                             String settingName,
                                             String value) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int VALUE_MAX_LENGTH = 1024;

    public enum Action {
        REFRESH,
        SELECT,
        SET_SETTING
    }

    public static void encode(MatchConfigToolActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.selectedType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.selectedMap(), ID_MAX_LENGTH);
        buf.writeUtf(packet.settingName(), ID_MAX_LENGTH);
        buf.writeUtf(packet.value(), VALUE_MAX_LENGTH);
    }

    public static MatchConfigToolActionC2SPacket decode(FriendlyByteBuf buf) {
        return new MatchConfigToolActionC2SPacket(
                buf.readEnum(Action.class),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(VALUE_MAX_LENGTH));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof MatchConfigTool)) {
                return;
            }

            String type = selectedType() == null ? "" : selectedType();
            String map = selectedMap() == null ? "" : selectedMap();
            if (action() == Action.SET_SETTING) {
                MapRoomActionService.Result result = MapRoomActionService.setSetting(player, type, map, settingName(), value());
                MapRoomActionService.sendMessage(player, result);
                if (!result.success()) {
                    FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(result.message(), true));
                }
            }

            FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.of(player, stack, type, map));
        });
        ctx.get().setPacketHandled(true);
    }
}
