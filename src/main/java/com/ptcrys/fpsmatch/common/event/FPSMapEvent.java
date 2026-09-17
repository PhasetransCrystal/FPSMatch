package com.ptcrys.fpsmatch.common.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.Event;

import com.ptcrys.fpsmatch.core.data.PlayerData;
import com.ptcrys.fpsmatch.core.map.BaseMap;
import com.ptcrys.fpsmatch.core.team.ServerTeam;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class FPSMapEvent extends Event {

    private final BaseMap map;

    public FPSMapEvent(BaseMap map) {
        this.map = map;
    }

    public BaseMap getMap() {
        return map;
    }

    public static class VictoryEvent extends FPSMapEvent {

        private final Map<UUID, PlayerScoreSnapshot> scoreboard;
        private final Map<String, TeamScoreSummary> teamSummaries;

        public VictoryEvent(BaseMap map) {
            super(map);
            this.scoreboard = buildScoreboardSnapshot(map);
            this.teamSummaries = buildTeamSummaries(map);
        }

        public Map<UUID, PlayerScoreSnapshot> getScoreboard() {
            return scoreboard;
        }

        public Optional<PlayerScoreSnapshot> getPlayerScoreSnapshot(UUID player) {
            return Optional.ofNullable(scoreboard.get(player));
        }

        public Optional<PlayerScoreSnapshot> getPlayerScoreSnapshot(ServerPlayer player) {
            return getPlayerScoreSnapshot(player.getUUID());
        }

        public Optional<PlayerScoreSnapshot> getPlayerScoreSnapshot(Player player) {
            return getPlayerScoreSnapshot(player.getUUID());
        }

        public Map<String, TeamScoreSummary> getTeamSummaries() {
            return teamSummaries;
        }

        public Optional<TeamScoreSummary> getTeamSummary(String teamName) {
            return Optional.ofNullable(teamSummaries.get(teamName));
        }

        public Optional<TeamScoreSummary> getTeamSummary(ServerTeam team) {
            return getTeamSummary(team.getName());
        }

        @Override
        public boolean isCancelable() {
            return false;
        }

        private static Map<UUID, PlayerScoreSnapshot> buildScoreboardSnapshot(BaseMap map) {
            List<PlayerScoreSnapshot> snapshots = new ArrayList<>();
            map.getMapTeams().getNormalTeams().forEach(team -> team.getPlayers().forEach((uuid, data) -> snapshots.add(new PlayerScoreSnapshot(
                    uuid,
                    data.name().copy(),
                    team.getName(),
                    data.getScores(),
                    data.getKills(),
                    data.getDeaths(),
                    data.getAssists(),
                    data.getDamage(),
                    data.getHeadshotRate()))));

            snapshots.sort(Comparator
                    .comparingInt(PlayerScoreSnapshot::scores).reversed()
                    .thenComparingInt(PlayerScoreSnapshot::kills).reversed()
                    .thenComparingDouble(PlayerScoreSnapshot::damage).reversed());

            LinkedHashMap<UUID, PlayerScoreSnapshot> result = new LinkedHashMap<>();
            snapshots.forEach(snapshot -> result.put(snapshot.player(), snapshot));
            return Map.copyOf(result);
        }

        private static Map<String, TeamScoreSummary> buildTeamSummaries(BaseMap map) {
            LinkedHashMap<String, TeamScoreSummary> summaries = new LinkedHashMap<>();
            map.getMapTeams().getNormalTeams().forEach(team -> {
                int totalScores = 0;
                int totalKills = 0;
                int totalDeaths = 0;
                int totalAssists = 0;
                float totalDamage = 0;
                float totalHeadshotRate = 0;
                int playerCount = 0;

                for (PlayerData data : team.getPlayersData()) {
                    totalScores += data.getScores();
                    totalKills += data.getKills();
                    totalDeaths += data.getDeaths();
                    totalAssists += data.getAssists();
                    totalDamage += data.getDamage();
                    totalHeadshotRate += data.getHeadshotRate();
                    playerCount++;
                }

                float averageHeadshotRate = playerCount > 0 ? totalHeadshotRate / playerCount : 0.0f;
                summaries.put(team.getName(), new TeamScoreSummary(
                        team.getName(),
                        team.getScores(),
                        playerCount,
                        totalScores,
                        totalKills,
                        totalDeaths,
                        totalAssists,
                        totalDamage,
                        averageHeadshotRate));
            });

            return Map.copyOf(summaries);
        }
    }

    public record PlayerScoreSnapshot(
                                      UUID player,
                                      Component name,
                                      String team,
                                      int scores,
                                      int kills,
                                      int deaths,
                                      int assists,
                                      float damage,
                                      float headshotRate) {}

    public record TeamScoreSummary(
                                   String team,
                                   int roundScores,
                                   int playerCount,
                                   int totalPlayerScores,
                                   int totalKills,
                                   int totalDeaths,
                                   int totalAssists,
                                   float totalDamage,
                                   float averageHeadshotRate) {}

    public static class ClearEvent extends FPSMapEvent {

        public ClearEvent(BaseMap map) {
            super(map);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class ResetEvent extends FPSMapEvent {

        public ResetEvent(BaseMap map) {
            super(map);
        }
    }

    public static class StartEvent extends FPSMapEvent {

        public StartEvent(BaseMap map) {
            super(map);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class ReloadEvent extends FPSMapEvent {

        public ReloadEvent(BaseMap map) {
            super(map);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class LoadEvent extends FPSMapEvent {

        public LoadEvent(BaseMap map) {
            super(map);
        }
    }

    /**
     * 你不能直接监听这个Event!!!!
     * 未在游戏中的地图不会发布这个事件
     */
    public static class PlayerEvent extends FPSMapEvent {

        private final ServerPlayer player;

        PlayerEvent(BaseMap map, ServerPlayer player) {
            super(map);
            this.player = player;
        }

        public ServerPlayer getPlayer() {
            return player;
        }

        public static class JoinEvent extends PlayerEvent {

            public JoinEvent(BaseMap map, ServerPlayer player) {
                super(map, player);
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class LeaveEvent extends PlayerEvent {

            public LeaveEvent(BaseMap map, ServerPlayer player) {
                super(map, player);
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class HurtEvent extends PlayerEvent {

            private final DamageSource source;
            private float amount;

            public HurtEvent(BaseMap map, ServerPlayer player, DamageSource source, float amount) {
                super(map, player);
                this.source = source;
                this.amount = amount;
            }

            public DamageSource getSource() {
                return source;
            }

            public Optional<ServerPlayer> getAttacker() {
                return this.getMap().getAttackerFromDamageSource(this.source);
            }

            public float getAmount() {
                return amount;
            }

            public void setAmount(float amount) {
                this.amount = amount;
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class DeathEvent extends PlayerEvent {

            private final DamageSource source;

            public DeathEvent(BaseMap map, ServerPlayer dead, DamageSource source) {
                super(map, dead);
                this.source = source;
            }

            public DamageSource getSource() {
                return source;
            }

            public Optional<ServerPlayer> getAttacker() {
                return getMap().getAttackerFromDamageSource(source);
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class KillEvent extends PlayerEvent {

            private final DamageSource source;
            private final ServerPlayer dead;
            private final boolean headshot;

            public KillEvent(BaseMap map, ServerPlayer killer, ServerPlayer dead, DamageSource source) {
                this(map, killer, dead, source, false);
            }

            public KillEvent(BaseMap map, ServerPlayer killer, ServerPlayer dead, DamageSource source, boolean headshot) {
                super(map, killer);
                this.source = source;
                this.dead = dead;
                this.headshot = headshot;
            }

            public DamageSource getSource() {
                return source;
            }

            public ServerPlayer getDead() {
                return dead;
            }

            /**
             * 本次击杀是否为爆头击杀（由死亡管线的爆头标记传递）
             */
            public boolean isHeadshot() {
                return headshot;
            }

            @Override
            public boolean isCancelable() {
                return false;
            }
        }

        /**
         * 在死亡管线中、真正写入击杀统计前触发。
         * 取消该事件将阻止本次“击杀数/爆头击杀数”写入，但不影响后续 KillEvent 广播。
         */
        public static class KillRecordEvent extends PlayerEvent {

            private final DamageSource source;
            private final ServerPlayer dead;

            public KillRecordEvent(BaseMap map, ServerPlayer killer, ServerPlayer dead, DamageSource source) {
                super(map, killer);
                this.source = source;
                this.dead = dead;
            }

            public DamageSource getSource() {
                return source;
            }

            public ServerPlayer getDead() {
                return dead;
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class LoggedInEvent extends PlayerEvent {

            public LoggedInEvent(BaseMap map, ServerPlayer player) {
                super(map, player);
            }
        }

        /*
         * 可以被取消，取消后不会退出队伍，需要额外处理一些逻辑来应对这个情况
         */
        public static class LoggedOutEvent extends PlayerEvent {

            public LoggedOutEvent(BaseMap map, ServerPlayer player) {
                super(map, player);
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class PickupItemEvent extends PlayerEvent {

            private final ItemEntity itemEntity;
            private final ItemStack stack;

            public PickupItemEvent(BaseMap map, ServerPlayer player, ItemEntity originalEntity, ItemStack stack) {
                super(map, player);
                this.itemEntity = originalEntity;
                this.stack = stack;
            }

            public ItemEntity getItemEntity() {
                return itemEntity;
            }

            public ItemStack getStack() {
                return stack;
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class TossItemEvent extends PlayerEvent {

            private final ItemEntity item;

            public TossItemEvent(BaseMap map, ServerPlayer player, ItemEntity item) {
                super(map, player);
                this.item = item;
            }

            public ItemEntity getItemEntity() {
                return item;
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class ChatEvent extends PlayerEvent {

            private final String message;

            public ChatEvent(BaseMap map, ServerPlayer player, String message) {
                super(map, player);
                this.message = message;
            }

            public String getMessage() {
                return message;
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }
    }
}
