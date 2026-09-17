package com.ptcrys.fpsmatch.common.client.spec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.ptcrys.fpsmatch.common.client.FPSMClient;
import com.ptcrys.fpsmatch.common.client.data.FPSMClientGlobalData;
import com.ptcrys.fpsmatch.core.team.ClientTeam;

import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public final class PlayerOutlineRenderer {

    public static final int NO_OUTLINE_COLOR = -1;

    private static final int TEAM_RED = 64;
    private static final int TEAM_GREEN = 160;
    private static final int TEAM_BLUE = 255;
    private static final int ENEMY_RED = 255;
    private static final int ENEMY_GREEN = 64;
    private static final int ENEMY_BLUE = 64;
    private static final int SPECTATOR_RED = 255;
    private static final int SPECTATOR_GREEN = 220;
    private static final int SPECTATOR_BLUE = 64;

    private static final int TEAM_COLOR = rgb(TEAM_RED, TEAM_GREEN, TEAM_BLUE);
    private static final int ENEMY_COLOR = rgb(ENEMY_RED, ENEMY_GREEN, ENEMY_BLUE);
    private static final int SPECTATOR_COLOR = rgb(SPECTATOR_RED, SPECTATOR_GREEN, SPECTATOR_BLUE);

    private PlayerOutlineRenderer() {}

    public static boolean shouldOutline(Entity target) {
        return getOutlineColor(target) != NO_OUTLINE_COLOR;
    }

    public static boolean controlsOutline(Entity target) {
        if (!(target instanceof Player player)) return false;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null || player == localPlayer) return false;

        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        if (isLocalCurrentMatchNormalTeam(data)) {
            return isCurrentMatchPlayer(data, player);
        }
        return localPlayer.isSpectator() && isLocalCurrentMatchSpectatorTeam(data) && isCurrentMatchPlayer(data, player);
    }

    public static int getOutlineColor(Entity target) {
        if (!(target instanceof Player player)) return NO_OUTLINE_COLOR;

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer localPlayer = minecraft.player;
        if (localPlayer == null || player == localPlayer) return NO_OUTLINE_COLOR;

        if (SpectatorGlowManager.shouldGlow(player)) {
            return SPECTATOR_COLOR;
        }

        FPSMClientGlobalData data = FPSMClient.getGlobalData();
        if (!isLocalCurrentMatchNormalTeam(data)) return NO_OUTLINE_COLOR;

        Optional<Boolean> sameTeam = isSameVisibleTeam(data, localPlayer, player);
        if (sameTeam.isEmpty()) return NO_OUTLINE_COLOR;

        if (sameTeam.get() && data.isTeamGlow()) {
            return TEAM_COLOR;
        }
        if (!sameTeam.get() && data.isEnemyGlow()) {
            return ENEMY_COLOR;
        }
        return NO_OUTLINE_COLOR;
    }

    private static Optional<Boolean> isSameVisibleTeam(FPSMClientGlobalData data, LocalPlayer localPlayer, Player player) {
        if (isFreeForAllRosterTeammate(data, localPlayer, player)) {
            return Optional.of(false);
        }
        return isSameCurrentMatchClientTeam(data, localPlayer, player)
                .or(() -> isSameCurrentMatchScoreboardTeam(data, localPlayer, player));
    }

    private static boolean isFreeForAllRosterTeammate(FPSMClientGlobalData data, Player localPlayer, Player player) {
        if (!data.isCurrentGameType("csdm")) return false;
        Team localTeam = localPlayer.getTeam();
        Team targetTeam = player.getTeam();
        return localTeam instanceof PlayerTeam playerTeam && localTeam == targetTeam && playerTeam.isAllowFriendlyFire();
    }

    static Optional<Boolean> isSameCurrentMatchClientTeam(FPSMClientGlobalData data, Player localPlayer, Player player) {
        Optional<String> localTeam = getCurrentMatchNormalTeamName(data, localPlayer);
        Optional<String> targetTeam = getCurrentMatchNormalTeamName(data, player);
        if (localTeam.isEmpty() || targetTeam.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(localTeam.get().equals(targetTeam.get()));
    }

    private static Optional<Boolean> isSameCurrentMatchScoreboardTeam(FPSMClientGlobalData data, Player localPlayer, Player player) {
        Team localTeam = localPlayer.getTeam();
        Team targetTeam = player.getTeam();
        if (localTeam == null || targetTeam == null) {
            return Optional.empty();
        }
        if (!isCurrentMatchScoreboardTeam(data, localTeam) || !isCurrentMatchScoreboardTeam(data, targetTeam)) {
            return Optional.empty();
        }
        return Optional.of(localTeam.getName().equals(targetTeam.getName()));
    }

    private static boolean isCurrentMatchPlayer(FPSMClientGlobalData data, Player player) {
        return isNormalMatchPlayer(data, player) || isCurrentMatchScoreboardPlayer(data, player);
    }

    private static boolean isCurrentMatchScoreboardPlayer(FPSMClientGlobalData data, Player player) {
        Team team = player.getTeam();
        return team != null && isCurrentMatchScoreboardTeam(data, team);
    }

    private static boolean isCurrentMatchScoreboardTeam(FPSMClientGlobalData data, Team team) {
        if (!data.isInMap() || !data.isInGame()) {
            return false;
        }
        String prefix = data.getCurrentGameType() + "_" + data.getCurrentMap() + "_";
        return team.getName().startsWith(prefix);
    }

    private static boolean isNormalMatchPlayer(FPSMClientGlobalData data, Player player) {
        return getCurrentMatchNormalTeamName(data, player).isPresent();
    }

    private static Optional<String> getCurrentMatchNormalTeamName(FPSMClientGlobalData data, Player player) {
        Optional<String> playerTeamName = data.getPlayerTeamName(player.getUUID())
                .flatMap(teamName -> data.getTeamByName(teamName)
                        .filter(team -> isCurrentMatchNormalTeam(data, team))
                        .map(team -> team.name));
        if (playerTeamName.isPresent()) {
            return playerTeamName;
        }

        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && player.getUUID().equals(localPlayer.getUUID())) {
            return getCurrentMatchNormalTeamName(data);
        }
        return Optional.empty();
    }

    static Optional<ClientTeam> getCurrentMatchClientTeam(FPSMClientGlobalData data) {
        return data.getCurrentClientTeam()
                .filter(team -> isCurrentMatchTeam(data, team));
    }

    static boolean isLocalCurrentMatchNormalTeam(FPSMClientGlobalData data) {
        return getCurrentMatchNormalTeamName(data).isPresent();
    }

    private static Optional<String> getCurrentMatchNormalTeamName(FPSMClientGlobalData data) {
        return getCurrentMatchClientTeam(data)
                .filter(team -> isCurrentMatchNormalTeam(data, team))
                .map(team -> team.name)
                .or(() -> Optional.of(data.getCurrentTeam())
                        .filter(teamName -> isCurrentMatchNormalTeamName(data, teamName)));
    }

    private static boolean isLocalCurrentMatchSpectatorTeam(FPSMClientGlobalData data) {
        return getCurrentMatchClientTeam(data)
                .map(team -> isCurrentMatchSpectatorTeam(data, team))
                .orElseGet(() -> data.isInMap() && data.isInGame() && isSpectatorTeamName(data.getCurrentTeam()));
    }

    static boolean isCurrentMatchNormalTeam(FPSMClientGlobalData data, ClientTeam team) {
        return isCurrentMatchTeam(data, team) && !isSpectatorTeam(team);
    }

    static boolean isCurrentMatchSpectatorTeam(FPSMClientGlobalData data, ClientTeam team) {
        return isCurrentMatchTeam(data, team) && isSpectatorTeam(team);
    }

    private static boolean isCurrentMatchTeam(FPSMClientGlobalData data, ClientTeam team) {
        return data.isInMap() && data.isInGame() && team.gameType.equals(data.getCurrentGameType()) && team.mapName.equals(data.getCurrentMap());
    }

    private static boolean isSpectatorTeam(ClientTeam team) {
        return team.isSpectator() || isSpectatorTeamName(team.name);
    }

    private static boolean isCurrentMatchNormalTeamName(FPSMClientGlobalData data, String teamName) {
        return data.isInMap() && data.isInGame() && !FPSMClientGlobalData.NONE_VALUE.equals(teamName) && !isSpectatorTeamName(teamName);
    }

    private static boolean isSpectatorTeamName(String teamName) {
        return FPSMClientGlobalData.SPECTATOR_TEAM.equals(teamName);
    }

    private static int rgb(int red, int green, int blue) {
        return (red << 16) | (green << 8) | blue;
    }
}
