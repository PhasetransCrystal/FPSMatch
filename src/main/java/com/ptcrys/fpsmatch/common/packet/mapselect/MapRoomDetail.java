package com.ptcrys.fpsmatch.common.packet.mapselect;

import com.ptcrys.fpsmatch.core.data.AreaData;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record MapRoomDetail(
        MapRoomSummary summary,
        List<MapRoomPlayerInfo> players,
        List<MapRoomSettingInfo> settings,
        List<MapRoomPlayerInfo> availableInviteTargets,
        List<EditableShopInfo> editableShops,
        List<MapRoomTeamInfo> teams,
        Set<UUID> readyPlayers,
        AreaData mapArea,
        List<AreaData> bombAreas,
        boolean demolitionRegionsSupported,
        String rulesKey,
        String iconTexture,
        String backgroundTexture
) {
    private static final int RESOURCE_MAX_LENGTH = 256;

    public static void encode(MapRoomDetail detail, FriendlyByteBuf buf) {
        MapRoomSummary.encode(detail.summary(), buf);
        buf.writeCollection(detail.players(), (buffer, info) -> MapRoomPlayerInfo.encode(info, buffer));
        buf.writeCollection(detail.settings(), (buffer, info) -> MapRoomSettingInfo.encode(info, buffer));
        buf.writeCollection(detail.availableInviteTargets(), (buffer, info) -> MapRoomPlayerInfo.encode(info, buffer));
        buf.writeCollection(detail.editableShops(), (buffer, info) -> info.encode(buffer));
        buf.writeCollection(detail.teams(), (buffer, info) -> MapRoomTeamInfo.encode(info, buffer));
        buf.writeCollection(detail.readyPlayers(), FriendlyByteBuf::writeUUID);
        writeArea(buf, detail.mapArea());
        buf.writeCollection(detail.bombAreas(), MapRoomDetail::writeArea);
        buf.writeBoolean(detail.demolitionRegionsSupported());
        buf.writeUtf(detail.rulesKey(), RESOURCE_MAX_LENGTH);
        buf.writeUtf(detail.iconTexture(), RESOURCE_MAX_LENGTH);
        buf.writeUtf(detail.backgroundTexture(), RESOURCE_MAX_LENGTH);
    }

    public static MapRoomDetail decode(FriendlyByteBuf buf) {
        MapRoomSummary summary = MapRoomSummary.decode(buf);
        List<MapRoomPlayerInfo> players = buf.readCollection(ArrayList::new, MapRoomPlayerInfo::decode);
        List<MapRoomSettingInfo> settings = buf.readCollection(ArrayList::new, MapRoomSettingInfo::decode);
        List<MapRoomPlayerInfo> availableInviteTargets = buf.readCollection(ArrayList::new, MapRoomPlayerInfo::decode);
        List<EditableShopInfo> editableShops = buf.readCollection(ArrayList::new, EditableShopInfo::decode);
        List<MapRoomTeamInfo> teams = buf.readCollection(ArrayList::new, MapRoomTeamInfo::decode);
        Set<UUID> readyPlayers = buf.readCollection(HashSet::new, FriendlyByteBuf::readUUID);
        AreaData mapArea = readArea(buf);
        List<AreaData> bombAreas = buf.readCollection(ArrayList::new, MapRoomDetail::readArea);
        return new MapRoomDetail(summary, players, settings, availableInviteTargets, editableShops,
                teams, readyPlayers, mapArea, bombAreas, buf.readBoolean(),
                buf.readUtf(RESOURCE_MAX_LENGTH), buf.readUtf(RESOURCE_MAX_LENGTH), buf.readUtf(RESOURCE_MAX_LENGTH));
    }

    private static void writeArea(FriendlyByteBuf buf, AreaData area) {
        buf.writeBlockPos(area.pos1());
        buf.writeBlockPos(area.pos2());
    }

    private static AreaData readArea(FriendlyByteBuf buf) {
        return new AreaData(buf.readBlockPos(), buf.readBlockPos());
    }
}
