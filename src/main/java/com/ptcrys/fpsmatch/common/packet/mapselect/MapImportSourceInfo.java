package com.ptcrys.fpsmatch.common.packet.mapselect;

import net.minecraft.network.FriendlyByteBuf;

/** A map-level import source and the configuration groups it can provide. */
public record MapImportSourceInfo(
                                  String id,
                                  String archiveName,
                                  String storageGroup,
                                  String mapName,
                                  boolean currentArchive,
                                  boolean hasSettings,
                                  boolean hasShop,
                                  boolean hasStartKits,
                                  int mappedTeams) {

    private static final int MAX_LENGTH = 128;

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(id, MAX_LENGTH);
        buf.writeUtf(archiveName, MAX_LENGTH);
        buf.writeUtf(storageGroup, MAX_LENGTH);
        buf.writeUtf(mapName, MAX_LENGTH);
        buf.writeBoolean(currentArchive);
        buf.writeBoolean(hasSettings);
        buf.writeBoolean(hasShop);
        buf.writeBoolean(hasStartKits);
        buf.writeVarInt(mappedTeams);
    }

    public static MapImportSourceInfo decode(FriendlyByteBuf buf) {
        return new MapImportSourceInfo(
                buf.readUtf(MAX_LENGTH), buf.readUtf(MAX_LENGTH), buf.readUtf(MAX_LENGTH),
                buf.readUtf(MAX_LENGTH), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readVarInt());
    }
}
