package net.ptcrys.fpsmatch.common.mapselect;

import net.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.packet.mapselect.EditableShopInfo;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSettingInfo;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomSummary;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomTeamInfo;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.PlayerData;
import net.ptcrys.fpsmatch.core.data.Setting;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.team.ServerTeam;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class MapRoomQueryService {

    private MapRoomQueryService() {}

    public static Optional<BaseMap> findMap(String gameType, String mapName) {
        if (!FPSMCore.initialized()) {
            return Optional.empty();
        }
        return FPSMCore.getInstance().getMapByTypeWithName(gameType, mapName);
    }

    public static List<EditableShopInfo> listEditableShops(String gameType, String mapName) {
        return findMap(gameType, mapName)
                .map(MapRoomQueryService::editableShops)
                .orElseGet(List::of);
    }

    public static boolean supportsShopEditing(String gameType, String mapName) {
        return !listEditableShops(gameType, mapName).isEmpty();
    }

    public static boolean isMapOperator(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    private static List<EditableShopInfo> editableShops(BaseMap map) {
        return map.getMapTeams().getNormalTeams().stream()
                .filter(team -> ShopCapability.getShop(team).isPresent())
                .map(team -> editableShopInfo(map, team))
                .sorted(Comparator.comparing(EditableShopInfo::displayName).thenComparing(EditableShopInfo::teamName))
                .toList();
    }

    private static EditableShopInfo editableShopInfo(BaseMap map, ServerTeam team) {
        String teamName = team.getName();
        return new EditableShopInfo(map.getGameType(), map.getMapName(), teamName, teamName);
    }

    public static List<MapRoomSummary> summaries(ServerPlayer viewer) {
        List<MapRoomSummary> summaries = new ArrayList<>();
        if (!FPSMCore.initialized()) {
            return summaries;
        }
        FPSMCore.getInstance().getAllMaps().forEach((type, maps) -> maps.forEach(map -> summaries.add(summary(viewer, map))));
        summaries.sort(Comparator.comparing(MapRoomSummary::gameType).thenComparing(MapRoomSummary::mapName));
        return summaries;
    }

    public static MapRoomDetail detail(ServerPlayer viewer, BaseMap map) {
        return new MapRoomDetail(
                summary(viewer, map),
                players(map),
                settings(viewer, map),
                availableInviteTargets(viewer, map),
                viewer != null && isMapOperator(viewer) ? editableShops(map) : List.of(),
                teams(map),
                map.getReadyPlayers(),
                map.getMapArea(),
                map.getCapabilityMap().get(DemolitionModeCapability.class)
                        .map(capability -> List.copyOf(capability.getBombAreaData()))
                        .orElseGet(List::of),
                map.getCapabilityMap().get(DemolitionModeCapability.class).isPresent(),
                "gui.fpsm.map_select.rules." + map.getGameType(),
                map.getIconTexture(),
                map.getBackgroundTexture());
    }

    public static MapRoomSummary summary(ServerPlayer viewer, BaseMap map) {
        int joined = map.getMapTeams().getJoinedUUID().size();
        List<ServerTeam> normalTeams = map.getMapTeams().getNormalTeams();
        boolean unlimited = normalTeams.stream().anyMatch(team -> team.getPlayerLimit() < 0);
        int max = unlimited ? -1 : normalTeams.stream()
                .mapToInt(ServerTeam::getPlayerLimit)
                .filter(limit -> limit > 0)
                .sum();
        boolean joinedMap = map.checkGameHasPlayer(viewer);
        boolean spectating = map.checkSpecHasPlayer(viewer);
        return new MapRoomSummary(
                map.getGameType(),
                map.getMapName(),
                map.getDisplayName(),
                map.getIconTexture(),
                map.getServerLevel().dimension().location().toString(),
                areaText(map.getMapArea().pos1(), map.getMapArea().pos2()),
                map.isStart(),
                map.isDebug(),
                map.allowJoinInProgress(),
                joined,
                max,
                joinedMap,
                spectating,
                viewer.hasPermissions(2),
                map.getReadyCountdownSeconds());
    }

    public static List<MapRoomPlayerInfo> players(BaseMap map) {
        List<MapRoomPlayerInfo> players = new ArrayList<>();
        map.getMapTeams().getTeamsWithSpectator().forEach(team -> team.getPlayersData().forEach(data -> players.add(playerInfo(team, data, map))));
        players.sort(Comparator.comparing(MapRoomPlayerInfo::teamName).thenComparing(MapRoomPlayerInfo::name));
        return players;
    }

    public static List<MapRoomPlayerInfo> availableInviteTargets(ServerPlayer viewer, BaseMap map) {
        List<MapRoomPlayerInfo> targets = new ArrayList<>();
        if (viewer != null && !map.checkGameHasPlayer(viewer) && !map.checkSpecHasPlayer(viewer)) {
            return targets;
        }
        if (!canInviteInto(map)) {
            return targets;
        }
        FPSMCore.getInstance().getServer().getPlayerList().getPlayers().stream()
                .filter(player -> viewer == null || !player.getUUID().equals(viewer.getUUID()))
                .filter(MapRoomQueryService::isAvailableInviteTarget)
                .map(player -> new MapRoomPlayerInfo(player.getUUID(), player.getGameProfile().getName(), "", false, true, false))
                .sorted(Comparator.comparing(MapRoomPlayerInfo::name))
                .forEach(targets::add);
        return targets;
    }

    public static boolean canInviteInto(BaseMap map) {
        return !map.isStart() || map.allowJoinInProgress();
    }

    private static boolean isAvailableInviteTarget(ServerPlayer player) {
        return FPSMCore.getInstance().getMapByPlayerWithSpec(player).isEmpty();
    }

    public static long computeInviteTargetSignature(BaseMap map) {
        if (!canInviteInto(map) || FPSMCore.getInstance().getServer() == null) {
            return 0L;
        }
        return FPSMCore.getInstance().getServer().getPlayerList().getPlayers().stream()
                .filter(MapRoomQueryService::isAvailableInviteTarget)
                .map(ServerPlayer::getUUID)
                .sorted()
                .reduce(0xcbf29ce484222325L,
                        (hash, uuid) -> ((hash ^ uuid.getMostSignificantBits()) * 0x100000001b3L ^ uuid.getLeastSignificantBits()) * 0x100000001b3L,
                        (left, right) -> (left ^ right) * 0x100000001b3L);
    }

    public static List<MapRoomSettingInfo> settings(ServerPlayer viewer, BaseMap map) {
        boolean editable = viewer.hasPermissions(2);
        String gameType = map.getGameType();
        return map.settings().stream()
                .map(setting -> settingInfo(setting, editable, gameType))
                .toList();
    }

    private static MapRoomPlayerInfo playerInfo(ServerTeam team, PlayerData data, BaseMap map) {
        return new MapRoomPlayerInfo(
                data.getOwner(),
                data.getPlayer().map(player -> player.getGameProfile().getName())
                        .orElseGet(() -> data.name().getString()),
                team.getName(),
                team.isSpectator(),
                data.getPlayer().isPresent(),
                map.isReady(data.getOwner()));
    }

    public static List<MapRoomTeamInfo> teams(BaseMap map) {
        return map.getMapTeams().getTeamsWithSpectator().stream()
                .map(team -> new MapRoomTeamInfo(
                        team.getName(),
                        team.getPlayerCount(),
                        team.getPlayerLimit(),
                        team.isSpectator()))
                .sorted(Comparator.comparing(MapRoomTeamInfo::spectator).thenComparing(MapRoomTeamInfo::name))
                .toList();
    }

    static MapRoomSettingInfo settingInfo(Setting<?> setting, boolean editable, String gameType) {
        String configName = setting.getConfigName();
        String translationScope = isBaseSetting(configName) ? "base" : gameType;
        String translationKey = "setting." + translationScope + "." + configName;
        Object value = setting.get();
        Object defaultValue = setting.getDefaultValue();
        MapRoomSettingInfo.SettingType type;
        if (value instanceof Boolean) {
            type = MapRoomSettingInfo.SettingType.BOOLEAN;
        } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            type = MapRoomSettingInfo.SettingType.INTEGER;
        } else if (value instanceof Float || value instanceof Double) {
            type = MapRoomSettingInfo.SettingType.DECIMAL;
        } else if (value instanceof String) {
            type = MapRoomSettingInfo.SettingType.STRING;
        } else {
            type = MapRoomSettingInfo.SettingType.OTHER;
        }

        NumericPresentation numeric = value instanceof Number number ? numericPresentation(configName, number.doubleValue(), defaultValue) : NumericPresentation.none();
        return new MapRoomSettingInfo(configName, setting.toString(), String.valueOf(defaultValue), editable,
                translationKey, type, translationKey + ".desc", numeric.slider(),
                numeric.min(), numeric.max(), numeric.step(), setting.getCategory());
    }

    private static boolean isBaseSetting(String name) {
        return switch (name) {
            case "minAssistDamageRatio", "allowJoinInProgress", "teammateGlow", "enemyGlow", "hideEnemyNameTag", "displayName", "iconTexture", "backgroundTexture", "autoStart", "autoStartTime", "readyStartEnabled", "readyStartTime" -> true;
            default -> false;
        };
    }

    private static NumericPresentation numericPresentation(String name, double value, Object defaultValue) {
        if ("minAssistDamageRatio".equals(name)) {
            return new NumericPresentation(true, 0.0, 1.0, 0.01);
        }
        double defaultNumber = defaultValue instanceof Number number ? number.doubleValue() : value;
        double magnitude = Math.max(Math.abs(value), Math.abs(defaultNumber));
        if (!Double.isFinite(magnitude) || magnitude > 100.0) {
            return NumericPresentation.none();
        }
        double extent = Math.max(1.0, Math.ceil(magnitude * 2.0));
        double min = Math.min(value, defaultNumber) < 0.0 ? -extent : 0.0;
        double max = extent;
        double step = value == Math.rint(value) && defaultNumber == Math.rint(defaultNumber) ? 1.0 : 0.01;
        return new NumericPresentation(max > min, min, max, step);
    }

    private record NumericPresentation(boolean slider, double min, double max, double step) {

        private static NumericPresentation none() {
            return new NumericPresentation(false, 0.0, 0.0, 1.0);
        }
    }

    private static String areaText(BlockPos from, BlockPos to) {
        return from.getX() + " " + from.getY() + " " + from.getZ() + " -> " + to.getX() + " " + to.getY() + " " + to.getZ();
    }
}
