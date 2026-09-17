package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

public record EditableShopInfo(String gameType, String mapName, String teamName, String displayName) {

    private static final int ID_MAX_LENGTH = 128;

    public static EditableShopInfo decode(FriendlyByteBuf buf) {
        return new EditableShopInfo(buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH));
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(gameType, ID_MAX_LENGTH);
        buf.writeUtf(mapName, ID_MAX_LENGTH);
        buf.writeUtf(teamName, ID_MAX_LENGTH);
        buf.writeUtf(displayName, ID_MAX_LENGTH);
    }
}
