package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

public record MapRoomTeamInfo(String name, int currentPlayers, int playerLimit, boolean spectator) {

    private static final int NAME_MAX_LENGTH = 128;

    public static void encode(MapRoomTeamInfo info, FriendlyByteBuf buf) {
        buf.writeUtf(info.name(), NAME_MAX_LENGTH);
        buf.writeInt(info.currentPlayers());
        buf.writeInt(info.playerLimit());
        buf.writeBoolean(info.spectator());
    }

    public static MapRoomTeamInfo decode(FriendlyByteBuf buf) {
        return new MapRoomTeamInfo(buf.readUtf(NAME_MAX_LENGTH), buf.readInt(), buf.readInt(), buf.readBoolean());
    }

    public boolean isFull() {
        return playerLimit() >= 0 && currentPlayers() >= playerLimit();
    }
}
