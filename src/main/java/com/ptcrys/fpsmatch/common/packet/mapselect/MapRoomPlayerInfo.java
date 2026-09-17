package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

public record MapRoomPlayerInfo(UUID uuid, String name, String teamName, boolean spectator, boolean online, boolean ready) {

    private static final int NAME_MAX_LENGTH = 128;

    public static void encode(MapRoomPlayerInfo info, FriendlyByteBuf buf) {
        buf.writeUUID(info.uuid());
        buf.writeUtf(info.name(), NAME_MAX_LENGTH);
        buf.writeUtf(info.teamName(), NAME_MAX_LENGTH);
        buf.writeBoolean(info.spectator());
        buf.writeBoolean(info.online());
        buf.writeBoolean(info.ready());
    }

    public static MapRoomPlayerInfo decode(FriendlyByteBuf buf) {
        return new MapRoomPlayerInfo(buf.readUUID(), buf.readUtf(NAME_MAX_LENGTH), buf.readUtf(NAME_MAX_LENGTH), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }
}
