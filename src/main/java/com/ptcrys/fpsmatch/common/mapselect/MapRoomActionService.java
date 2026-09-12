package com.ptcrys.fpsmatch.common.mapselect;

import com.ptcrys.fpsmatch.FPSMatch;
import com.ptcrys.fpsmatch.common.capability.map.DemolitionModeCapability;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import com.ptcrys.fpsmatch.common.packet.mapselect.MapRoomInvitationS2CPacket;
import com.ptcrys.fpsmatch.core.FPSMCore;
import com.ptcrys.fpsmatch.core.data.AreaData;
import com.ptcrys.fpsmatch.core.data.Setting;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.MapTeams;
import com.ptcrys.fpsmatch.core.team.ServerTeam;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MapRoomActionService {
    private static final long INVITE_TTL_MILLIS = 60_000L;
    private static final int MAX_BOMB_AREAS = 256;
    private static final Map<UUID, PendingInvite> PENDING_INVITES = new ConcurrentHashMap<>();

    private MapRoomActionService() {
    }

    public enum DebugAction {
        START,
        RESET,
        NEW_ROUND,
        CLEANUP,
        SWITCH_DEBUG
    }

    private record PendingInvite(String gameType, String mapName, long expiresAt) {
    }

    public record Result(boolean success, Component message, Optional<MapRoomDetail> detail) {
        public static Result success(Component message, BaseMap map, ServerPlayer viewer) {
            return new Result(true, message, Optional.of(MapRoomQueryService.detail(viewer, map)));
        }

        public static Result failure(Component message) {
            return new Result(false, message, Optional.empty());
        }
    }

    public static Result join(ServerPlayer player, String gameType, String mapName) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> join(player, map))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result join(ServerPlayer player, BaseMap map) {
        return join(player, map, null);
    }

    private static Result join(ServerPlayer player, BaseMap map, String teamName) {
        MapTeams.JoinTeamResult result = teamName == null ? map.join(player) : map.join(teamName, player);
        Component message = switch (result.status()) {
            case JOINED -> Component.translatable("gui.fpsm.map_select.action.join.success", map.getMapName());
            case TEAM_FULL -> Component.translatable("commands.fpsm.team.join.failure.full", safeTeamName(result));
            case NO_AVAILABLE_TEAM -> Component.translatable("commands.fpsm.team.join.failure.all_full");
            case MID_MATCH_JOIN_DISABLED -> Component.translatable("commands.fpsm.team.join.failure.in_progress");
            case TEAM_NOT_FOUND -> Component.translatable("commands.fpsm.team.join.failure.null", safeTeamName(result));
            case CANCELLED -> Component.translatable("gui.fpsm.map_select.action.cancelled");
        };
        if (result.isSuccess()) {
            return Result.success(message, map, player);
        }
        return Result.failure(message);
    }

    public static Result leave(ServerPlayer player, String gameType, String mapName) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> leave(player, map))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result leave(ServerPlayer player, BaseMap map) {
        if (!map.checkGameHasPlayer(player) && !map.checkSpecHasPlayer(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.leave.not_in_map"));
        }
        map.leave(player);
        return Result.success(Component.translatable("gui.fpsm.map_select.action.leave.success", map.getMapName()), map, player);
    }

    public static Result debug(ServerPlayer player, String gameType, String mapName, DebugAction action) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> debug(player, map, action))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result debug(ServerPlayer player, BaseMap map, DebugAction action) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        switch (action) {
            case START -> map.start();
            case RESET -> map.reset();
            case NEW_ROUND -> map.startNewRound();
            case CLEANUP -> map.cleanupMap();
            case SWITCH_DEBUG -> map.switchDebugMode();
        }
        return Result.success(Component.translatable("gui.fpsm.map_select.action.debug.success", map.getMapName()), map, player);
    }

    public static Result kick(ServerPlayer player, String gameType, String mapName, UUID target) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> kick(player, map, target))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result kick(ServerPlayer player, BaseMap map, UUID target) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        Optional<ServerPlayer> targetPlayer = map.getPlayerByUUID(target);
        if (targetPlayer.isEmpty() || (!map.checkGameHasPlayer(targetPlayer.get()) && !map.checkSpecHasPlayer(targetPlayer.get()))) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.player_not_found"));
        }
        map.leave(targetPlayer.get());
        return Result.success(Component.translatable("gui.fpsm.map_select.action.kick.success", targetPlayer.get().getGameProfile().getName()), map, player);
    }

    public static Result ready(ServerPlayer player, String gameType, String mapName) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> ready(player, map))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result ready(ServerPlayer player, BaseMap map) {
        if (!map.checkGameHasPlayer(player) && !map.checkSpecHasPlayer(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.leave.not_in_map"));
        }
        if (map.checkSpecHasPlayer(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.ready.spectator"));
        }
        boolean ready = map.toggleReady(player);
        Component message = ready
                ? Component.translatable("gui.fpsm.map_select.action.ready.on")
                : Component.translatable("gui.fpsm.map_select.action.ready.off");
        return Result.success(message, map, player);
    }

    public static Result switchTeam(ServerPlayer player, String gameType, String mapName, String teamName) {
        return switchTeam(player, gameType, mapName, player.getUUID(), teamName);
    }

    public static Result switchTeam(ServerPlayer player, String gameType, String mapName, UUID target, String teamName) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> switchTeam(player, map, target, teamName))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result switchTeam(ServerPlayer player, BaseMap map, String teamName) {
        return switchTeam(player, map, player.getUUID(), teamName);
    }

    public static Result switchTeam(ServerPlayer player, BaseMap map, UUID target, String teamName) {
        boolean self = player.getUUID().equals(target);
        if (!self && !MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        Optional<ServerPlayer> targetPlayer = self ? Optional.of(player) : map.getPlayerByUUID(target);
        if (targetPlayer.isEmpty() || (!map.checkGameHasPlayer(targetPlayer.get()) && !map.checkSpecHasPlayer(targetPlayer.get()))) {
            if (self && !map.checkGameHasPlayer(player) && !map.checkSpecHasPlayer(player)) {
                return join(player, map, teamName);
            }
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.player_not_found"));
        }
        ServerPlayer targetServerPlayer = targetPlayer.get();

        // Team-switch requests can be duplicated by double-clicks, retries, or
        // delayed packets. Treat an already-satisfied request as a no-op so it
        // does not run BaseMap.join's leave/rejoin path and emit reset/roster
        // packets again.
        Optional<ServerTeam> currentTeam = map.getMapTeams().getTeamByPlayer(targetServerPlayer);
        if (currentTeam.isPresent() && currentTeam.get().getName().equals(teamName)) {
            Component message = self
                    ? Component.translatable("gui.fpsm.map_select.action.switch_team.success.self", teamName)
                    : Component.translatable("gui.fpsm.map_select.action.switch_team.success.other", targetServerPlayer.getGameProfile().getName(), teamName);
            return Result.success(message, map, player);
        }

        MapTeams.JoinTeamResult result = map.join(teamName, targetServerPlayer);
        if (!result.isSuccess()) {
            Component message = switch (result.status()) {
                case TEAM_FULL -> Component.translatable("commands.fpsm.team.join.failure.full", safeTeamName(result));
                case NO_AVAILABLE_TEAM -> Component.translatable("commands.fpsm.team.join.failure.all_full");
                case MID_MATCH_JOIN_DISABLED -> Component.translatable("commands.fpsm.team.join.failure.in_progress");
                case TEAM_NOT_FOUND -> Component.translatable("commands.fpsm.team.join.failure.null", safeTeamName(result));
                case CANCELLED -> Component.translatable("gui.fpsm.map_select.action.cancelled");
                default -> Component.translatable("gui.fpsm.map_select.action.switch_team.failed");
            };
            return Result.failure(message);
        }
        map.setReady(targetServerPlayer.getUUID(), false);
        Component message = self
                ? Component.translatable("gui.fpsm.map_select.action.switch_team.success.self", teamName)
                : Component.translatable("gui.fpsm.map_select.action.switch_team.success.other", targetServerPlayer.getGameProfile().getName(), teamName);
        return Result.success(message, map, player);
    }

    public static Result setSetting(ServerPlayer player, String gameType, String mapName, String settingName, String value) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> setSetting(player, map, settingName, value))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result setSetting(ServerPlayer player, BaseMap map, String settingName, String value) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        for (Setting<?> setting : map.settings()) {
            if (setting.getConfigName().equals(settingName)) {
                if (setting.parse(value)) {
                    return Result.success(Component.translatable("gui.fpsm.map_select.action.setting.success", settingName), map, player);
                }
                return Result.failure(Component.translatable("gui.fpsm.map_select.action.setting.invalid", settingName));
            }
        }
        return Result.failure(Component.translatable("gui.fpsm.map_select.action.setting.not_found", settingName));
    }

    public static Result setMapArea(ServerPlayer player, String gameType, String mapName, AreaData area) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> editMapArea(player, map, area))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    private static Result editMapArea(ServerPlayer player, BaseMap map, AreaData area) {
        Result validation = validateAreaEdit(player, area);
        if (validation != null) {
            return validation;
        }
        map.setMapArea(area);
        map.displayAreas(player);
        FPSMCore.getInstance().getFPSMDataManager().saveAllData();
        return Result.success(Component.translatable("gui.fpsm.map_regions.action.map_updated"), map, player);
    }

    public static Result addBombArea(ServerPlayer player, String gameType, String mapName, AreaData area) {
        return withDemolitionCapability(player, gameType, mapName, area, (map, capability) -> {
            if (capability.getBombAreaData().size() >= MAX_BOMB_AREAS) {
                return Result.failure(Component.translatable("gui.fpsm.map_regions.action.limit"));
            }
            capability.addBombArea(area);
            capability.syncBombAreasToClient(player);
            FPSMCore.getInstance().getFPSMDataManager().saveAllData();
            return Result.success(Component.translatable("gui.fpsm.map_regions.action.bomb_added"), map, player);
        });
    }

    public static Result updateBombArea(ServerPlayer player, String gameType, String mapName, int index, AreaData area) {
        return withDemolitionCapability(player, gameType, mapName, area, (map, capability) -> {
            if (!capability.updateBombArea(index, area)) {
                return Result.failure(Component.translatable("gui.fpsm.map_regions.action.not_found"));
            }
            capability.syncBombAreasToClient(player);
            FPSMCore.getInstance().getFPSMDataManager().saveAllData();
            return Result.success(Component.translatable("gui.fpsm.map_regions.action.bomb_updated", index + 1), map, player);
        });
    }

    public static Result removeBombArea(ServerPlayer player, String gameType, String mapName, int index) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> map.getCapabilityMap().get(DemolitionModeCapability.class)
                        .map(capability -> {
                            if (!capability.removeBombArea(index)) {
                                return Result.failure(Component.translatable("gui.fpsm.map_regions.action.not_found"));
                            }
                            capability.syncBombAreasToClient(player);
                            FPSMCore.getInstance().getFPSMDataManager().saveAllData();
                            return Result.success(Component.translatable(
                                    "gui.fpsm.map_regions.action.bomb_removed", index + 1), map, player);
                        })
                        .orElseGet(() -> Result.failure(Component.translatable(
                                "gui.fpsm.map_regions.action.unsupported"))))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result previewAreas(ServerPlayer player, String gameType, String mapName) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> {
                    map.displayAreas(player);
                    map.getCapabilityMap().get(DemolitionModeCapability.class)
                            .ifPresent(capability -> capability.syncBombAreasToClient(player));
                    return Result.success(Component.translatable("gui.fpsm.map_regions.action.preview"), map, player);
                })
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    private static Result withDemolitionCapability(
            ServerPlayer player,
            String gameType,
            String mapName,
            AreaData area,
            DemolitionEdit edit
    ) {
        Result validation = validateAreaEdit(player, area);
        if (validation != null) {
            return validation;
        }
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> map.getCapabilityMap().get(DemolitionModeCapability.class)
                        .map(capability -> edit.apply(map, capability))
                        .orElseGet(() -> Result.failure(Component.translatable(
                                "gui.fpsm.map_regions.action.unsupported"))))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    private static Result validateAreaEdit(ServerPlayer player, AreaData area) {
        if (!MapRoomQueryService.isMapOperator(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.no_permission"));
        }
        if (area == null || !validPosition(area.pos1()) || !validPosition(area.pos2())) {
            return Result.failure(Component.translatable("gui.fpsm.map_regions.action.invalid"));
        }
        return null;
    }

    private static boolean validPosition(BlockPos pos) {
        return pos != null && Math.abs((long) pos.getX()) <= 30_000_000L
                && Math.abs((long) pos.getZ()) <= 30_000_000L
                && pos.getY() >= -2048 && pos.getY() <= 2047;
    }

    @FunctionalInterface
    private interface DemolitionEdit {
        Result apply(BaseMap map, DemolitionModeCapability capability);
    }

    public static Result invite(ServerPlayer player, String gameType, String mapName, UUID target) {
        return MapRoomQueryService.findMap(gameType, mapName)
                .map(map -> invite(player, map, target))
                .orElseGet(() -> Result.failure(Component.translatable("gui.fpsm.map_select.action.map_not_found")));
    }

    public static Result invite(ServerPlayer player, BaseMap map, UUID target) {
        if (!map.checkGameHasPlayer(player) && !map.checkSpecHasPlayer(player)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.invite.not_in_map"));
        }
        Optional<ServerPlayer> targetPlayer = FPSMCore.getInstance().getPlayerByUUID(target);
        if (targetPlayer.isEmpty()) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.player_not_found"));
        }
        if (FPSMCore.getInstance().getMapByPlayerWithSpec(targetPlayer.get()).isPresent()) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.invite.target_in_map"));
        }
        if (!MapRoomQueryService.canInviteInto(map)) {
            return Result.failure(Component.translatable("commands.fpsm.team.join.failure.in_progress"));
        }
        if (isFull(map)) {
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.invite.full"));
        }
        Component message = Component.translatable("gui.fpsm.map_select.action.invite.received", player.getGameProfile().getName(), map.getMapName());
        PENDING_INVITES.put(targetPlayer.get().getUUID(), new PendingInvite(map.getGameType(), map.getMapName(), System.currentTimeMillis() + INVITE_TTL_MILLIS));
        FPSMatch.sendToPlayer(targetPlayer.get(), new MapRoomInvitationS2CPacket(map.getGameType(), map.getMapName(), message));
        return Result.success(Component.translatable("gui.fpsm.map_select.action.invite.success", targetPlayer.get().getGameProfile().getName()), map, player);
    }

    public static Result acceptInvite(ServerPlayer player, String gameType, String mapName) {
        PendingInvite invite = PENDING_INVITES.get(player.getUUID());
        if (invite == null || !invite.gameType().equals(gameType) || !invite.mapName().equals(mapName) || invite.expiresAt() < System.currentTimeMillis()) {
            PENDING_INVITES.remove(player.getUUID());
            return Result.failure(Component.translatable("gui.fpsm.map_select.action.invite.invalid"));
        }
        PENDING_INVITES.remove(player.getUUID());
        return join(player, gameType, mapName);
    }

    public static void sendMessage(ServerPlayer player, Result result) {
        player.displayClientMessage(result.message(), false);
    }

    private static boolean isFull(BaseMap map) {
        if (map.getMapTeams().getNormalTeams().stream().anyMatch(team -> team.getPlayerLimit() < 0)) {
            return false;
        }
        int max = map.getMapTeams().getNormalTeams().stream()
                .mapToInt(team -> Math.max(team.getPlayerLimit(), 0))
                .sum();
        return max > 0 && map.getMapTeams().getJoinedUUID().size() >= max;
    }

    private static String safeTeamName(MapTeams.JoinTeamResult result) {
        return result.teamName() == null ? "" : result.teamName();
    }
}
