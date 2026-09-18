package com.example.elimination;

import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.DeathContext;
import net.minecraft.server.level.ServerLevel;

import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.core.team.TeamData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

public final class EliminationMap extends BaseMap {
    public static final String GAME_TYPE = "elimination";

    private final ServerTeam red;
    private final ServerTeam blue;

    public EliminationMap(ServerLevel level, String name, AreaData area) {
        super(level, name, area);
        red = addTeam(TeamData.of("red", 8));
        blue = addTeam(TeamData.of("blue", 8));
        allowJoinInProgress.set(false);
        readyStartEnabled.set(false);

    }

    @Override
    public String getGameType() { return GAME_TYPE; }

    @Override
    public boolean victoryGoal() {
        return isStart() && (red.getLivingPlayers().isEmpty()
                || blue.getLivingPlayers().isEmpty() || getElapsedMatchTicks() >= 90 * 20);
    }

    @Override
    public void victory() {
        if (!isStart()) return;
        boolean redAlive = !red.getLivingPlayers().isEmpty();
        boolean blueAlive = !blue.getLivingPlayers().isEmpty();
        ServerTeam winner = redAlive == blueAlive ? null : redAlive ? red : blue;
        isStart = false;
        if (winner != null) winner.setScores(1);
        announce(winner == null ? Component.translatable("message.elimination.draw")
                : Component.translatable("message.elimination.win", winner.getName()));
        super.victory();
    }

    private boolean hasSpawns(ServerTeam team) {
        return team.getCapabilityMap().get(SpawnPointCapability.class)
                .map(cap -> !cap.getSpawnPointsData().isEmpty()).orElse(false);
    }

    private boolean canBegin() {
        if (red.getOnline().isEmpty() || blue.getOnline().isEmpty()) {
            announce(Component.translatable("message.elimination.players"));
            return false;
        }
        if (!hasSpawns(red) || !hasSpawns(blue)) {
            announce(Component.translatable("message.elimination.spawns"));
            return false;
        }
        return true;
    }

    private void preparePlayers() {
        getMapTeams().startNewRound();
        getMapTeams().getOnline().forEach(player -> {
            player.setCamera(player);
            player.setGameMode(GameType.ADVENTURE);
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5);
            player.clearFire();
            player.removeAllEffects();
            player.fallDistance = 0;
            teleportPlayerToReSpawnPoint(player);
        });
    }

    private void announce(Component text) {
        getMapTeams().getOnlineWithSpec().forEach(player -> player.sendSystemMessage(text));
    }

    @Override
    public boolean start() {
        if (isStart() || !canBegin() || !super.start()) return false;
        isStart = true;
        preparePlayers();
        announce(Component.translatable("message.elimination.started"));
        return true;
    }

    @Override
    public void reset() {
        isStart = false;
        red.setScores(0);
        blue.setScores(0);
        getMapTeams().getOnline().forEach(player -> {
            player.setCamera(player);
            player.setGameMode(GameType.ADVENTURE);
        });
        super.reset();
    }

    @Override
    public boolean cleanupMap() {
        if (!super.cleanupMap()) return false;
        reset();
        return true;
    }

    @Override
    public void handleDeath(DeathContext context) {
        super.handleDeath(context);
        context.getDeadPlayer().setGameMode(GameType.SPECTATOR);
    }
}
