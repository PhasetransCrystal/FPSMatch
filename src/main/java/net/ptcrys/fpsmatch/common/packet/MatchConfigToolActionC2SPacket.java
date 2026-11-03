package net.ptcrys.fpsmatch.common.packet;

import net.ptcrys.fpsmatch.FPSMatch;
import net.ptcrys.fpsmatch.common.item.MatchConfigTool;
import net.ptcrys.fpsmatch.common.mapselect.MapRoomActionService;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomToastS2CPacket;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record MatchConfigToolActionC2SPacket(Action action, String selectedType, String selectedMap,
                                             String settingName, String value, long session, long request) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int VALUE_MAX_LENGTH = 1024;

    // Existing ordinals are retained. SET_SETTING is server-guarded legacy functionality only.
    public enum Action {
        REFRESH,
        SELECT,
        SET_SETTING,
        OPEN
    }

    /** Old integrations can still request a guarded write, but cannot acquire view-opening authority. */
    public MatchConfigToolActionC2SPacket(Action action, String type, String map, String setting, String value) {
        this(action, type, map, setting, value, 0, 0);
    }

    public static void encode(MatchConfigToolActionC2SPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUtf(packet.selectedType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.selectedMap(), ID_MAX_LENGTH);
        buf.writeUtf(packet.settingName(), ID_MAX_LENGTH);
        buf.writeUtf(packet.value(), VALUE_MAX_LENGTH);
        buf.writeLong(packet.session());
        buf.writeLong(packet.request());
    }

    public static MatchConfigToolActionC2SPacket decode(FriendlyByteBuf buf) {
        Action action = buf.readEnum(Action.class);
        String type = buf.readUtf(ID_MAX_LENGTH), map = buf.readUtf(ID_MAX_LENGTH);
        String setting = buf.readUtf(ID_MAX_LENGTH), value = buf.readUtf(VALUE_MAX_LENGTH);
        if (!buf.isReadable()) return new MatchConfigToolActionC2SPacket(action, type, map, setting, value);
        return new MatchConfigToolActionC2SPacket(action, type, map, setting, value, buf.readLong(), buf.readLong());
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) return;
            boolean correlated = session > 0 && request > 0;
            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof MatchConfigTool)) {
                if (correlated) FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.failure(this, "no_tool"));
                return;
            }
            if (!correlated && action != Action.SET_SETTING) return;
            try {
                if (action == Action.SET_SETTING) {
                    MapRoomActionService.Result result = MapRoomActionService.setSetting(player, selectedType, selectedMap, settingName, value);
                    MapRoomActionService.sendMessage(player, result);
                    if (!result.success()) {
                        FPSMatch.sendToPlayer(player, new MapRoomToastS2CPacket(result.message(), true));
                        if (correlated) FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.failure(this, "failed"));
                        return;
                    }
                    // Legacy writes receive their ordinary message, never an unsolicited view packet.
                    if (!correlated) return;
                }
                FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.snapshot(player, stack, this));
            } catch (RuntimeException failure) {
                FPSMatch.LOGGER.error("Match configuration request failed", failure);
                if (correlated) FPSMatch.sendToPlayer(player, OpenMatchConfigToolScreenS2CPacket.failure(this, "failed"));
            }
        });
        context.setPacketHandled(true);
    }
}
