package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

public record MapRoomSummary(
                             String gameType,
                             String mapName,
                             String displayName,
                             String iconTexture,
                             String dimension,
                             String areaText,
                             boolean started,
                             boolean debug,
                             boolean allowJoinInProgress,
                             int joinedPlayers,
                             int maxPlayers,
                             boolean currentPlayerJoined,
                             boolean currentPlayerSpectating,
                             boolean currentPlayerOp,
                             int readyCountdownSeconds) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 512;
    private static final int HASH_LENGTH = 64;

    public static void encode(MapRoomSummary summary, FriendlyByteBuf buf) {
        buf.writeUtf(summary.gameType(), ID_MAX_LENGTH);
        buf.writeUtf(summary.mapName(), ID_MAX_LENGTH);
        buf.writeUtf(summary.displayName(), TEXT_MAX_LENGTH);
        buf.writeUtf(summary.iconTexture(), TEXT_MAX_LENGTH);
        buf.writeUtf(summary.dimension(), ID_MAX_LENGTH);
        buf.writeUtf(summary.areaText(), TEXT_MAX_LENGTH);
        buf.writeBoolean(summary.started());
        buf.writeBoolean(summary.debug());
        buf.writeBoolean(summary.allowJoinInProgress());
        buf.writeInt(summary.joinedPlayers());
        buf.writeInt(summary.maxPlayers());
        buf.writeBoolean(summary.currentPlayerJoined());
        buf.writeBoolean(summary.currentPlayerSpectating());
        buf.writeBoolean(summary.currentPlayerOp());
        buf.writeInt(summary.readyCountdownSeconds());
    }

    public static MapRoomSummary decode(FriendlyByteBuf buf) {
        return new MapRoomSummary(
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(TEXT_MAX_LENGTH),
                buf.readUtf(TEXT_MAX_LENGTH),
                buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(TEXT_MAX_LENGTH),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readInt(),
                buf.readInt(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readInt());
    }

    public boolean full() {
        return maxPlayers() >= 0 && joinedPlayers() >= maxPlayers();
    }
}
