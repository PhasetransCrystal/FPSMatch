package net.ptcrys.fpsmatch.common.client.screen.team;

import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomDetail;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomPlayerInfo;
import net.ptcrys.fpsmatch.common.packet.mapselect.MapRoomTeamInfo;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Client-side affordance model. The server remains authoritative for every action. */
public final class TeamActionModel {

    private TeamActionModel() {}

    public static List<String> availableTargetTeams(MapRoomDetail detail, UUID player) {
        if (detail == null || player == null || !canSwitchInRoom(detail)) {
            return List.of();
        }
        MapRoomPlayerInfo source = findPlayer(detail, player);
        if (source == null || !source.online()) {
            return List.of();
        }
        return detail.teams().stream()
                .filter(team -> !team.spectator() || detail.summary().currentPlayerOp())
                .filter(team -> !team.name().equals(source.teamName()))
                .filter(team -> !team.isFull())
                .map(MapRoomTeamInfo::name)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    public static boolean canDropTo(MapRoomDetail detail, UUID player, String targetTeam) {
        return targetTeam != null && availableTargetTeams(detail, player).contains(targetTeam);
    }

    public static boolean canKick(MapRoomDetail detail, UUID player) {
        if (detail == null || player == null || !detail.summary().currentPlayerOp()) {
            return false;
        }
        MapRoomPlayerInfo target = findPlayer(detail, player);
        return target != null && target.online();
    }

    private static boolean canSwitchInRoom(MapRoomDetail detail) {
        return !detail.summary().started() || detail.summary().allowJoinInProgress();
    }

    private static MapRoomPlayerInfo findPlayer(MapRoomDetail detail, UUID player) {
        return detail.players().stream().filter(info -> info.uuid().equals(player)).findFirst().orElse(null);
    }
}
