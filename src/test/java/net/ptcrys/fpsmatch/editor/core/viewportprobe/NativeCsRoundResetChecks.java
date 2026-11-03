package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.BaseRoundMap;
import net.ptcrys.fpsmatch.core.match.RoundLifecycle;
import net.ptcrys.fpsmatch.core.match.RoundPhase;
import net.ptcrys.fpsmatch.core.match.RoundResult;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.core.map.MapInitialConfiguration;
import net.ptcrys.fpsmatch.core.map.VoteObj;
import net.ptcrys.fpsmatch.editor.common.codec.ExactJson;

/** Real native map behavior with explicit synthetic scores/votes, never a gameplay claim. */
public final class NativeCsRoundResetChecks {
    private static final String CS = "net.ptcrys.blockoffensive.map.CSGameMap";
    private static final String SWITCH = "net.ptcrys.blockoffensive.event.CSGameMapEvent$TeamSwitchEvent";

    private NativeCsRoundResetChecks() {}

    /** Owner-thread entry. Requires the disposable host's existing overtime mode to be VOTE. */
    public static JsonObject execute(MinecraftServer server) throws ReflectiveOperationException {
        require(server.isSameThread() && Boolean.getBoolean("editorViewportProbe.enabled")
                && Boolean.getBoolean("mcagent.testMode"), "Opt-in server owner required");
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
        require(root.contains("/Q82/ui-host-"), "Disposable Q82 host required");
        require(FPSMCore.getInstance().getServer() == server, "Exact active server required");
        Object config = Class.forName("net.ptcrys.blockoffensive.BOConfig").getField("common").get(null);
        Object mode = ((ForgeConfigSpec.ConfigValue<?>) config.getClass().getField("overtimeMode").get(config)).get();
        require("VOTE".equals(mode.toString()), "This check requires existing overtimeMode=VOTE; it never changes global config");
        Object threshold = ((ForgeConfigSpec.ConfigValue<?>) config.getClass().getField("overtimeVoteThreshold").get(config)).get();
        require(threshold instanceof Number && ((Number) threshold).doubleValue() > 0,
                "Pending-vote checks require existing overtimeVoteThreshold > 0; global config is never changed");
        JsonArray passed = new JsonArray();
        var voter = server.getPlayerList().getPlayers().stream().findFirst().orElseThrow(
                () -> new AssertionError("An online isolated-client voter is required"));

        try (Fixture f = new Fixture(server)) {
            AtomicInteger nextRounds = new AtomicInteger();
            f.markRunning();
            f.endedLifecycle(nextRounds);
            f.tick(5);
            require(nextRounds.get() == 1, "An ordinary ended lifecycle must still request its next round once");
            passed.add("ordinary_ended_lifecycle_advances_once");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger nextRounds = new AtomicInteger();
            f.markRunning();
            RoundLifecycle<?, ?> ended = f.endedLifecycle(nextRounds);
            f.scores(1, 0);
            Method score = f.map.getClass().getDeclaredMethod("processRoundScoreAndOvertimeVote", ServerTeam.class);
            score.setAccessible(true);
            invoke(score, f.map, f.map.getMapTeams().getTeamByName("t").orElseThrow());
            Method overtime = f.map.getClass().getDeclaredMethod("startOvertimeSequence");
            overtime.setAccessible(true);
            invoke(overtime, f.map);
            VoteObj pending = f.vote();
            require(pending != null && pending.isOvertime(), "Tied-score sequence must start an overtime vote");
            // Ballot eligibility alone never joins or changes the real player's state.
            pending.addEligiblePlayer(voter.getUUID());
            f.tick(250);
            require(f.vote() == pending && f.resets == 0 && f.clears == 0 && nextRounds.get() == 0
                    && ended.phase() == RoundPhase.ROUND_END_WAITING && ended.phaseElapsedTicks() == 0,
                    "Pending overtime must not advance the ended lifecycle or initialize another round");
            require(pending.processVote(voter, false), "The isolated ballot must accept its eligible voter");
            f.tick(1);
            f.requireTerminal(1);
            require(nextRounds.get() == 0, "Vote rejection must reset without a phantom next round");
            passed.add("pending_overtime_freezes_round_but_rejection_still_resolves");
        }

        try (Fixture f = new Fixture(server)) {
            f.scores(1, 1);
            f.markRunning();
            f.map.reset();
            f.requireTerminal(1);
            require(f.clears == 1 && f.switches == 0 && f.victories == 0, "Tied reset must clean once without round progression");
            f.tick(500);
            f.requireTerminal(1);
            passed.add("tied_reset_stays_terminal_for_500_explicit_map_ticks");
        }
        try (Fixture f = new Fixture(server)) {
            f.scores(1, 0);
            f.markRunning();
            f.map.reset();
            f.requireTerminal(1);
            require(f.switches == 0 && f.clears == 1, "Halftime score must not switch teams during reset");
            passed.add("halftime_reset_does_not_switch_sides");
        }
        try (Fixture f = new Fixture(server)) {
            f.scores(1, 1);
            f.markRunning();
            require(f.map.cleanupMap(), "Round cleanup must complete");
            require(f.vote() != null && "overtime".equals(f.vote().getVoteTitle()) && f.resets == 0,
                    "Normal tied round cleanup must still open overtime voting");
            f.map.reset();
            f.tick(5);
            f.requireTerminal(1);
            passed.add("normal_tied_round_opens_vote_and_later_reset_cancels_it");
        }
        try (Fixture f = new Fixture(server)) {
            f.scores(1, 0);
            f.markRunning();
            require(f.map.cleanupMap(), "Round cleanup must complete");
            require(f.switches == 1 && f.resets == 0, "Normal halftime cleanup must still switch sides once");
            f.map.reset();
            require(f.switches == 1, "Terminal cleanup must not switch those sides again");
            f.requireTerminal(1);
            passed.add("normal_halftime_still_switches_exactly_once");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger callbacks = new AtomicInteger();
            f.setVote(vote(() -> { callbacks.incrementAndGet(); f.map.reset(); }));
            f.markRunning();
            f.map.reset();
            f.tick(500);
            f.requireTerminal(1);
            require(callbacks.get() == 0, "Reset must cancel the old vote without resolving either callback");
            passed.add("pending_vote_cannot_reset_an_ended_match");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger callbacks = new AtomicInteger();
            f.setVote(vote(() -> { callbacks.incrementAndGet(); f.map.reset(); }));
            f.map.mapTick();
            require(callbacks.get() == 1, "The no-voter failure callback must really execute");
            f.requireTerminal(1);
            f.tick(5);
            f.requireTerminal(1);
            passed.add("vote_callback_reset_does_not_dereference_cleared_vote");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger first = new AtomicInteger(), second = new AtomicInteger();
            VoteObj replacement = vote(second::incrementAndGet);
            f.setVote(vote(() -> { first.incrementAndGet(); f.setVote(replacement); }));
            f.map.mapTick();
            require(first.get() == 1 && second.get() == 0 && f.vote() == replacement,
                    "A callback replacement must survive old-vote completion until its own next tick");
            f.map.mapTick();
            require(second.get() == 1 && f.vote() == null, "Replacement must resolve once on its own tick");
            passed.add("vote_callback_replacement_is_not_cleared_or_advanced_early");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger callbacks = new AtomicInteger();
            f.resetAction = () -> f.setVote(vote(callbacks::incrementAndGet));
            f.map.reset();
            f.resetAction = () -> {};
            f.tick(5);
            f.requireTerminal(1);
            require(callbacks.get() == 0, "Reset-event vote must not escape terminal cleanup");
            passed.add("reset_event_cannot_leave_a_new_vote_attached");
        }
        try (Fixture f = new Fixture(server)) {
            AtomicInteger callbacks = new AtomicInteger();
            RuntimeException injected = new RuntimeException("native_reset_injected_failure");
            f.resetAction = () -> { f.setVote(vote(callbacks::incrementAndGet)); throw injected; };
            try {
                f.map.reset();
                throw new AssertionError("Injected reset failure must escape");
            } catch (RuntimeException actual) {
                require(actual == injected, "Vote cleanup must preserve the exact reset failure");
            }
            f.resetAction = () -> {};
            require(f.vote() == null && !f.map.resetCompleted(f.map.resetGeneration()) && f.resets == 1,
                    "Failed reset must remain incomplete and must release its vote");
            f.tick(5);
            require(callbacks.get() == 0 && f.resets == 1, "A failed reset cannot leave a replaying vote callback");
            passed.add("failed_reset_preserves_exception_and_cancels_vote");
        }

        JsonObject result = new JsonObject();
        result.addProperty("action", "native_cs_round_reset");
        result.addProperty("status", "PASS");
        result.addProperty("native_map_class", CS);
        result.addProperty("overtime_mode", mode.toString());
        result.addProperty("synthetic_scores_and_votes", true);
        result.addProperty("manual_map_tick", true);
        result.addProperty("installed_runtime_session", false);
        result.addProperty("asserted_cases", passed.size());
        result.add("cases", passed);
        return result;
    }

    private static VoteObj vote(Runnable callback) {
        return new VoteObj("reset_probe", Component.literal("reset probe"), 20, 1, callback, callback,
                List.of(), VoteObj.TimeoutPolicy.FAIL, VoteObj.AbstentionPolicy.COUNT_AS_NO, () -> 0L, 20L);
    }

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server;
        final BaseMap map;
        final Method getVote, setVote;
        final Consumer<Event> events = this::onEvent;
        Runnable resetAction = () -> {};
        int resets, clears, switches, victories;

        Fixture(MinecraftServer server) throws ReflectiveOperationException {
            this.server = server;
            Class<?> type = Class.forName(CS);
            Class<?> holder = Class.forName("net.ptcrys.blockoffensive.editor.legacy.BoLegacySource$Holder");
            Object csHolder = holder.getField("CS_GAME_MAPS").get(null);
            Object defaults = invoke(Class.forName("net.ptcrys.blockoffensive.editor.settings.BoSettingsCodec")
                    .getMethod("defaults", holder), null, csHolder);
            ExactJson.FrozenJson frozen = (ExactJson.FrozenJson) invoke(defaults.getClass().getMethod("toJson"), defaults);
            JsonObject cfg = frozen.toJson().getAsJsonObject();
            cfg.addProperty("winnerRound", 2);
            cfg.addProperty("warmUpTime", 0);
            cfg.addProperty("knifeSelection", false);
            cfg.addProperty("autoStart", false);
            cfg.addProperty("readyStartEnabled", false);
            Method create = type.getMethod("createConfigured", ServerLevel.class, String.class, AreaData.class, MapInitialConfiguration.class);
            MapInitialConfiguration initial = MapInitialConfiguration.capture(cfg);
            map = MapConstructionScope.construct(() -> (BaseMap) invoke(create, null, server.overworld(),
                    "round_reset_" + UUID.randomUUID(),
                    new AreaData(new BlockPos(20_000_000, 0, 20_000_000), new BlockPos(20_000_001, 1, 20_000_001)), initial));
            getVote = type.getMethod("getVote");
            setVote = type.getMethod("setVote", VoteObj.class);
            addListener(FPSMapEvent.class, events);
            addListener(Class.forName(SWITCH).asSubclass(Event.class), events);
        }

        void onEvent(Event event) {
            if (event instanceof FPSMapEvent mapEvent) {
                if (mapEvent.getMap() != map) return;
                if (event instanceof FPSMapEvent.ResetEvent) { resets++; resetAction.run(); }
                if (event instanceof FPSMapEvent.ClearEvent) clears++;
                if (event instanceof FPSMapEvent.VictoryEvent) victories++;
            } else if (event.getClass().getName().equals(SWITCH)) {
                try {
                    if (invoke(event.getClass().getMethod("getMap"), event) == map) switches++;
                } catch (NoSuchMethodException failure) { throw new AssertionError(failure); }
            }
        }

        void scores(int ct, int t) {
            map.getMapTeams().getTeamByName("ct").orElseThrow().setScores(ct);
            map.getMapTeams().getTeamByName("t").orElseThrow().setScores(t);
        }
        void markRunning() throws ReflectiveOperationException {
            Field field = BaseMap.class.getDeclaredField("isStart");
            field.setAccessible(true);
            field.setBoolean(map, true);
        }
        RoundLifecycle<?, ?> endedLifecycle(AtomicInteger nextRounds) throws ReflectiveOperationException {
            RoundLifecycle<String, String> lifecycle = RoundLifecycle.<String, String>builder()
                    .waitingTicks(0).roundTicks(0).roundEndTicks(2)
                    .timeoutResult(() -> new RoundResult<>("synthetic_winner", "synthetic_result"))
                    .onNextRoundRequested(nextRounds::incrementAndGet).build();
            lifecycle.tick();
            require(lifecycle.phase() == RoundPhase.ROUND_END_WAITING, "Synthetic ended lifecycle setup failed");
            Field field = BaseRoundMap.class.getDeclaredField("roundLifecycle");
            field.setAccessible(true);
            field.set(map, lifecycle);
            return lifecycle;
        }
        VoteObj vote() { return (VoteObj) invoke(getVote, map); }
        void setVote(VoteObj vote) { invoke(setVote, map, vote); }
        void tick(int count) { for (int index = 0; index < count; index++) map.mapTick(); }
        void requireTerminal(int expectedResets) {
            require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty() && vote() == null,
                    "Reset must finish idle with no participants or pending vote");
            require(map.resetGeneration() == expectedResets && resets == expectedResets
                    && map.resetCompleted(map.resetGeneration()), "Exactly one completed reset must remain observable");
            require(map.getMapTeams().getNormalTeams().stream().allMatch(team -> team.getScores() == 0),
                    "Terminal reset must clear scores");
        }
        @Override public void close() {
            MinecraftForge.EVENT_BUS.unregister(events);
            setVote(null);
            try { map.getCapabilityMap().close(); }
            finally { map.getMapTeams().shutdown(server.getScoreboard()); }
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new AssertionError(cause);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    private static void addListener(Class<? extends Event> type, Consumer<Event> listener) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, (Class<Event>) type, listener);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
