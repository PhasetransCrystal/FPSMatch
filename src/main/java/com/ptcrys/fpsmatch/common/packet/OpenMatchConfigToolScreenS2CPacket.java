package com.ptcrys.fpsmatch.common.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.MatchConfigTool;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public record OpenMatchConfigToolScreenS2CPacket(
                                                 List<MapEntry> maps,
                                                 String selectedType,
                                                 String selectedMap,
                                                 List<MapRoomSettingInfo> settings) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 1024;

    public static OpenMatchConfigToolScreenS2CPacket fromStack(ServerPlayer viewer, ItemStack stack) {
        List<MapEntry> maps = collectMaps();
        String selectedType = MatchConfigTool.getSelectedType(stack);
        String selectedMap = MatchConfigTool.getSelectedMap(stack);
        MapEntry selected = selectMap(maps, selectedType, selectedMap).orElse(null);
        if (selected != null) {
            selectedType = selected.gameType();
            selectedMap = selected.mapName();
            MatchConfigTool.setSelected(stack, selectedType, selectedMap);
        } else {
            selectedType = "";
            selectedMap = "";
        }

        return new OpenMatchConfigToolScreenS2CPacket(
                maps,
                selectedType,
                selectedMap,
                collectSettings(viewer, selectedType, selectedMap));
    }

    public static OpenMatchConfigToolScreenS2CPacket of(ServerPlayer viewer, ItemStack stack, String selectedType, String selectedMap) {
        MatchConfigTool.setSelected(stack, selectedType, selectedMap);
        return fromStack(viewer, stack);
    }

    public static void encode(OpenMatchConfigToolScreenS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.maps().size());
        for (MapEntry map : packet.maps()) {
            buf.writeUtf(map.gameType(), ID_MAX_LENGTH);
            buf.writeUtf(map.mapName(), ID_MAX_LENGTH);
            buf.writeUtf(map.displayName(), TEXT_MAX_LENGTH);
            buf.writeUtf(map.dimension(), TEXT_MAX_LENGTH);
        }
        buf.writeUtf(packet.selectedType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.selectedMap(), ID_MAX_LENGTH);
        buf.writeVarInt(packet.settings().size());
        for (MapRoomSettingInfo setting : packet.settings()) {
            MapRoomSettingInfo.encode(setting, buf);
        }
    }

    public static OpenMatchConfigToolScreenS2CPacket decode(FriendlyByteBuf buf) {
        int mapCount = buf.readVarInt();
        List<MapEntry> maps = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) {
            maps.add(new MapEntry(buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(TEXT_MAX_LENGTH), buf.readUtf(TEXT_MAX_LENGTH)));
        }
        String selectedType = buf.readUtf(ID_MAX_LENGTH);
        String selectedMap = buf.readUtf(ID_MAX_LENGTH);
        int settingCount = buf.readVarInt();
        List<MapRoomSettingInfo> settings = new ArrayList<>(settingCount);
        for (int i = 0; i < settingCount; i++) {
            settings.add(MapRoomSettingInfo.decode(buf));
        }
        return new OpenMatchConfigToolScreenS2CPacket(maps, selectedType, selectedMap, settings);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ClientPacketExecutor.execute(ctx, this);
    }

    private static List<MapEntry> collectMaps() {
        List<MapEntry> maps = new ArrayList<>();
        if (!FPSMCore.initialized()) {
            return maps;
        }
        FPSMCore.getInstance().getAllMaps().forEach((gameType, mapList) -> {
            for (BaseMap map : mapList) {
                maps.add(new MapEntry(gameType, map.getMapName(), map.getDisplayName(), map.getServerLevel().dimension().location().toString()));
            }
        });
        maps.sort(Comparator.comparing(MapEntry::gameType).thenComparing(MapEntry::mapName));
        return maps;
    }

    private static Optional<MapEntry> selectMap(List<MapEntry> maps, String selectedType, String selectedMap) {
        Optional<MapEntry> requested = maps.stream()
                .filter(map -> map.gameType().equals(selectedType) && map.mapName().equals(selectedMap))
                .findFirst();
        return requested.or(() -> maps.stream().findFirst());
    }

    private static List<MapRoomSettingInfo> collectSettings(ServerPlayer viewer, String selectedType, String selectedMap) {
        if (selectedType.isBlank() || selectedMap.isBlank()) {
            return List.of();
        }
        return MapRoomQueryService.findMap(selectedType, selectedMap)
                .map(map -> MapRoomQueryService.settings(viewer, map))
                .orElseGet(List::of);
    }

    public record MapEntry(String gameType, String mapName, String displayName, String dimension) {}
}
