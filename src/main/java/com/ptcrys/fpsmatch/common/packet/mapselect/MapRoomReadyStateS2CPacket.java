package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 地图准备状态同步包。
 * <p>
 * 服务端在全员准备后的倒计时期间向地图内所有玩家广播，
 * 客户端队伍管理界面据此实时显示倒计时与已准备玩家集合。
 */
public record MapRoomReadyStateS2CPacket(
                                         String gameType,
                                         String mapName,
                                         int countdownSeconds,
                                         Set<UUID> readyPlayers) {

    private static final int ID_MAX_LENGTH = 128;

    public static void encode(MapRoomReadyStateS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.gameType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.mapName(), ID_MAX_LENGTH);
        buf.writeInt(packet.countdownSeconds());
        buf.writeCollection(packet.readyPlayers(), FriendlyByteBuf::writeUUID);
    }

    public static MapRoomReadyStateS2CPacket decode(FriendlyByteBuf buf) {
        String gameType = buf.readUtf(ID_MAX_LENGTH);
        String mapName = buf.readUtf(ID_MAX_LENGTH);
        int countdownSeconds = buf.readInt();
        Set<UUID> readyPlayers = buf.readCollection(HashSet::new, FriendlyByteBuf::readUUID);
        return new MapRoomReadyStateS2CPacket(gameType, mapName, countdownSeconds, readyPlayers);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }
}
