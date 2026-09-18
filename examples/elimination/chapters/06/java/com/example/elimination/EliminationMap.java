package com.example.elimination;

import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseRoundMap;
import net.ptcrys.fpsmatch.core.map.DeathContext;
import net.ptcrys.fpsmatch.core.match.RoundLifecycle;
import net.ptcrys.fpsmatch.core.match.RoundPhase;
import net.ptcrys.fpsmatch.core.match.RoundResult;
import java.util.Optional;
import net.ptcrys.fpsmatch.core.data.Setting;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerLevel;

import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.core.team.TeamData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

public final class EliminationMap extends BaseRoundMap<ServerTeam, EliminationMap.EndReason> {
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
    public boolean victoryGoal() { return isStart() && matchComplete; }

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
        matchTargetScore = Mth.clamp(targetScore.get(), 1, 100);
        red.setScores(0);
        blue.setScores(0);
        matchComplete = false;
        startNewRound();
        announce(Component.translatable("message.elimination.started"));
        return true;
    }

    @Override
    public void reset() {
        matchComplete = false;
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

    public enum EndReason { ELIMINATION, TIMEOUT }
    private boolean matchComplete;

    private final Setting<Integer> targetScore = addSetting("elimination", "targetScore", 5);
    private final Setting<Integer> roundSeconds = addSetting("elimination", "roundSeconds", 90);
    private final Setting<Integer> waitingSeconds = addSetting("elimination", "waitingSeconds", 5);
    private int matchTargetScore;


    public boolean isCombatActive() {
        return isStart() && roundLifecycle != null
                && roundLifecycle.phase() == RoundPhase.ACTIVE_ROUND;
    }

    @Override
    protected RoundLifecycle<ServerTeam, EndReason> buildRoundLifecycle() {
        return lifecycleBuilder()
                .waitingTicks(Mth.clamp(waitingSeconds.get(), 0, 60) * 20)
                .roundTicks(Mth.clamp(roundSeconds.get(), 10, 3600) * 20)
                .roundEndTicks(5 * 20)
                .addRule(lifecycle -> eliminationResult())
                .timeoutResult(() -> new RoundResult<>(null, EndReason.TIMEOUT))
                .build();
    }

    private Optional<RoundResult<ServerTeam, EndReason>> eliminationResult() {
        boolean redAlive = !red.getLivingPlayers().isEmpty();
        boolean blueAlive = !blue.getLivingPlayers().isEmpty();
        if (redAlive && blueAlive) return Optional.empty();
        ServerTeam winner = redAlive == blueAlive ? null : redAlive ? red : blue;
        return Optional.of(new RoundResult<>(winner, EndReason.ELIMINATION));
    }

    @Override
    public void startNewRound() {
        if (!isStart()) return;
        preparePlayers();
        rebuildRoundLifecycle();
    }

    @Override
    protected void onRoundEnd(RoundResult<ServerTeam, EndReason> result) {
        if (result.winner() == null) {
            announce(Component.translatable("message.elimination.draw"));
        } else {
            ServerTeam winner = result.winner();
            winner.setScores(winner.getScores() + 1);
            announce(Component.translatable("message.elimination.round_win", winner.getName()));
        }
    }

    @Override
    protected void onNextRoundRequested() {
        if (red.getScores() >= matchTargetScore || blue.getScores() >= matchTargetScore) {
            matchComplete = true;
            victory();
        } else {
            startNewRound();
        }
    }

    @Override
    public void victory() {
        if (!isStart()) return;
        isStart = false;
        matchComplete = false;
        announce(Component.translatable("message.elimination.result", red.getScores(), blue.getScores()));
        super.victory();
    }
}
