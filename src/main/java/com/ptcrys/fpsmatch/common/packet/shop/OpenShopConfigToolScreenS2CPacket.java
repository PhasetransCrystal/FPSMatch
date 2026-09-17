package com.ptcrys.fpsmatch.common.packet.shop;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import com.ptcrys.fpsmatch.common.item.ShopConfigTool;
import com.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import com.ptcrys.fpsmatch.common.packet.ClientPacketExecutor;
import com.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.map.BaseMap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * S2C 包：打开商店配置工具界面。
 * <p>
 * 发送可用地图列表和当前选中地图的可编辑商店列表到客户端。
 */
public record OpenShopConfigToolScreenS2CPacket(
                                                List<MapEntry> maps,
                                                String selectedType,
                                                String selectedMap,
                                                List<EditableShopInfo> shops) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 1024;

    public static OpenShopConfigToolScreenS2CPacket fromStack(ServerPlayer viewer, ItemStack stack) {
        List<MapEntry> maps = collectMaps();
        String selectedType = ShopConfigTool.getSelectedType(stack);
        String selectedMap = ShopConfigTool.getSelectedMap(stack);
        MapEntry selected = selectMap(maps, selectedType, selectedMap).orElse(null);
        if (selected != null) {
            selectedType = selected.gameType();
            selectedMap = selected.mapName();
            ShopConfigTool.setSelected(stack, selectedType, selectedMap);
        } else {
            selectedType = "";
            selectedMap = "";
        }

        return new OpenShopConfigToolScreenS2CPacket(
                maps,
                selectedType,
                selectedMap,
                collectShops(selectedType, selectedMap));
    }

    public static OpenShopConfigToolScreenS2CPacket of(ServerPlayer viewer, ItemStack stack, String selectedType, String selectedMap) {
        ShopConfigTool.setSelected(stack, selectedType, selectedMap);
        return fromStack(viewer, stack);
    }

    public static void encode(OpenShopConfigToolScreenS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.maps().size());
        for (MapEntry map : packet.maps()) {
            buf.writeUtf(map.gameType(), ID_MAX_LENGTH);
            buf.writeUtf(map.mapName(), ID_MAX_LENGTH);
            buf.writeUtf(map.displayName(), TEXT_MAX_LENGTH);
        }
        buf.writeUtf(packet.selectedType(), ID_MAX_LENGTH);
        buf.writeUtf(packet.selectedMap(), ID_MAX_LENGTH);
        buf.writeVarInt(packet.shops().size());
        for (EditableShopInfo shop : packet.shops()) {
            shop.encode(buf);
        }
    }

    public static OpenShopConfigToolScreenS2CPacket decode(FriendlyByteBuf buf) {
        int mapCount = buf.readVarInt();
        List<MapEntry> maps = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) {
            maps.add(new MapEntry(buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH), buf.readUtf(TEXT_MAX_LENGTH)));
        }
        String selectedType = buf.readUtf(ID_MAX_LENGTH);
        String selectedMap = buf.readUtf(ID_MAX_LENGTH);
        int shopCount = buf.readVarInt();
        List<EditableShopInfo> shops = new ArrayList<>(shopCount);
        for (int i = 0; i < shopCount; i++) {
            shops.add(EditableShopInfo.decode(buf));
        }
        return new OpenShopConfigToolScreenS2CPacket(maps, selectedType, selectedMap, shops);
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
                maps.add(new MapEntry(gameType, map.getMapName(), map.getDisplayName()));
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

    private static List<EditableShopInfo> collectShops(String selectedType, String selectedMap) {
        if (selectedType.isBlank() || selectedMap.isBlank()) {
            return List.of();
        }
        return MapRoomQueryService.listEditableShops(selectedType, selectedMap);
    }

    public record MapEntry(String gameType, String mapName, String displayName) {}
}
