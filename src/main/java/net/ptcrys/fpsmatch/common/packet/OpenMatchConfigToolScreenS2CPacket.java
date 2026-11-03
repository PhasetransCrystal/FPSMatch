package net.ptcrys.fpsmatch.common.packet;

import net.ptcrys.fpsmatch.common.client.net.FPSMClientPacketHandlers;
import net.ptcrys.fpsmatch.common.item.MatchConfigTool;
import net.ptcrys.fpsmatch.common.mapselect.MapRoomQueryService;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.map.BaseMap;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Correlated snapshot result; receiving one never creates a client screen. */
public record OpenMatchConfigToolScreenS2CPacket(
                                                 long session, long request, MatchConfigToolActionC2SPacket.Action action, boolean success, String notice,
                                                 List<MapEntry> maps, String selectedType, String selectedMap, List<MapRoomSettingInfo> settings) {

    private static final int ID_MAX_LENGTH = 128;
    private static final int TEXT_MAX_LENGTH = 1024;

    public OpenMatchConfigToolScreenS2CPacket {
        maps = List.copyOf(maps);
        settings = List.copyOf(settings);
        if (maps.size() > 4096 || settings.size() > 4096) throw new IllegalArgumentException("config snapshot exceeds entry budget");
        requireText(notice, ID_MAX_LENGTH);
        requireText(selectedType, ID_MAX_LENGTH);
        requireText(selectedMap, ID_MAX_LENGTH);
        for (MapEntry map : maps) {
            requireText(map.gameType(), ID_MAX_LENGTH);
            requireText(map.mapName(), ID_MAX_LENGTH);
            requireText(map.displayName(), TEXT_MAX_LENGTH);
            requireText(map.dimension(), TEXT_MAX_LENGTH);
        }
        for (MapRoomSettingInfo setting : settings) {
            requireText(setting.name(), ID_MAX_LENGTH);
            requireText(setting.category(), ID_MAX_LENGTH);
            requireText(setting.value(), TEXT_MAX_LENGTH);
            requireText(setting.defaultValue(), TEXT_MAX_LENGTH);
            requireText(setting.translationKey(), TEXT_MAX_LENGTH);
            requireText(setting.descriptionKey(), TEXT_MAX_LENGTH);
        }
    }

    private static void requireText(String text, int maximum) {
        if (text == null || text.length() > maximum) throw new IllegalArgumentException("config snapshot text exceeds wire limit");
    }

    public static OpenMatchConfigToolScreenS2CPacket failure(MatchConfigToolActionC2SPacket request, String notice) {
        return new OpenMatchConfigToolScreenS2CPacket(request.session(), request.request(), request.action(), false, notice,
                List.of(), request.selectedType(), request.selectedMap(), List.of());
    }

    public static OpenMatchConfigToolScreenS2CPacket snapshot(ServerPlayer viewer, ItemStack stack, MatchConfigToolActionC2SPacket request) {
        if (!FPSMCore.initialized()) return failure(request, "failed");
        List<MapEntry> maps = collectMaps();
        boolean opening = request.action() == MatchConfigToolActionC2SPacket.Action.OPEN;
        String type = opening ? MatchConfigTool.getSelectedType(stack) : request.selectedType();
        String name = opening ? MatchConfigTool.getSelectedMap(stack) : request.selectedMap();
        MapEntry selected = null;
        for (MapEntry entry : maps) {
            if (entry.gameType().equals(type) && entry.mapName().equals(name)) {
                selected = entry;
                break;
            }
        }
        String notice = "";
        if (selected == null) {
            boolean hadSelection = !type.isBlank() || !name.isBlank();
            if (!opening && hadSelection) return failure(request, "removed");
            if (!opening && request.action() == MatchConfigToolActionC2SPacket.Action.SELECT) return failure(request, "removed");
            if (opening && hadSelection) notice = "removed";
            else if (opening && !maps.isEmpty()) selected = maps.get(0);
        }
        if (selected == null) {
            type = "";
            name = "";
        } else {
            type = selected.gameType();
            name = selected.mapName();
        }
        List<MapRoomSettingInfo> settings = List.of();
        if (selected != null) {
            BaseMap map = MapRoomQueryService.findMap(type, name).orElse(null);
            if (map == null) return failure(request, "removed");
            settings = MapRoomQueryService.settings(viewer, map);
        }
        // Remember selection only after the whole requested snapshot succeeds.
        OpenMatchConfigToolScreenS2CPacket result = new OpenMatchConfigToolScreenS2CPacket(
                request.session(), request.request(), request.action(), true, notice, maps, type, name, settings);
        if (selected != null) MatchConfigTool.setSelected(stack, type, name);
        return result;
    }

    public static void encode(OpenMatchConfigToolScreenS2CPacket packet, FriendlyByteBuf buf) {
        buf.writeLong(packet.session());
        buf.writeLong(packet.request());
        buf.writeEnum(packet.action());
        buf.writeBoolean(packet.success());
        buf.writeUtf(packet.notice(), ID_MAX_LENGTH);
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
        for (MapRoomSettingInfo setting : packet.settings()) MapRoomSettingInfo.encode(setting, buf);
    }

    public static OpenMatchConfigToolScreenS2CPacket decode(FriendlyByteBuf buf) {
        long session = buf.readLong(), request = buf.readLong();
        var action = buf.readEnum(MatchConfigToolActionC2SPacket.Action.class);
        boolean success = buf.readBoolean();
        String notice = buf.readUtf(ID_MAX_LENGTH);
        int mapCount = boundedCount(buf);
        List<MapEntry> maps = new ArrayList<>(mapCount);
        for (int i = 0; i < mapCount; i++) maps.add(new MapEntry(buf.readUtf(ID_MAX_LENGTH), buf.readUtf(ID_MAX_LENGTH),
                buf.readUtf(TEXT_MAX_LENGTH), buf.readUtf(TEXT_MAX_LENGTH)));
        String type = buf.readUtf(ID_MAX_LENGTH), name = buf.readUtf(ID_MAX_LENGTH);
        int settingCount = boundedCount(buf);
        List<MapRoomSettingInfo> settings = new ArrayList<>(settingCount);
        for (int i = 0; i < settingCount; i++) settings.add(MapRoomSettingInfo.decode(buf));
        return new OpenMatchConfigToolScreenS2CPacket(session, request, action, success, notice, maps, type, name, settings);
    }

    private static int boundedCount(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 4096) throw new IllegalArgumentException("invalid config snapshot count");
        return count;
    }

    public void handle(Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // Capture the receiving connection, not whichever connection is current when queued work runs.
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FPSMClientPacketHandlers.handleOpenMatchConfigToolScreen(this, context.getNetworkManager())));
        context.setPacketHandled(true);
    }

    private static List<MapEntry> collectMaps() {
        List<MapEntry> maps = new ArrayList<>();
        FPSMCore.getInstance().getAllMaps().forEach((gameType, mapList) -> {
            for (BaseMap map : mapList) maps.add(new MapEntry(gameType, map.getMapName(), map.getDisplayName(),
                    map.getServerLevel().dimension().location().toString()));
        });
        maps.sort(Comparator.comparing(MapEntry::gameType).thenComparing(MapEntry::mapName));
        return maps;
    }

    public record MapEntry(String gameType, String mapName, String displayName, String dimension) {}
}
