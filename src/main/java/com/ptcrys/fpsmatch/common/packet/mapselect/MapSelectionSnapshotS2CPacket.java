package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record MapSelectionSnapshotS2CPacket(List<MapRoomSummary> maps, boolean viewerOp, boolean nonOpButtonEnabled, boolean passive) {

    /**
     * 主动(active)快照包：会在客户端未打开界面时强制打开地图选择界面（原有行为）。
     */
    public MapSelectionSnapshotS2CPacket(List<MapRoomSummary> maps, boolean viewerOp, boolean nonOpButtonEnabled) {
        this(maps, viewerOp, nonOpButtonEnabled, false);
    }

    public static void encode(MapSelectionSnapshotS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeCollection(packet.maps(), (buffer, summary) -> MapRoomSummary.encode(summary, buffer));
        buf.writeBoolean(packet.viewerOp());
        buf.writeBoolean(packet.nonOpButtonEnabled());
        buf.writeBoolean(packet.passive());
    }

    public static MapSelectionSnapshotS2CPacket decode(FriendlyByteBuf buf) {
        return new MapSelectionSnapshotS2CPacket(
                buf.readCollection(ArrayList::new, MapRoomSummary::decode),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
