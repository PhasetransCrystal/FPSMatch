package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.capability.FPSMCapabilityManager;
import net.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.team.BaseTeam;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeInstallations;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeTests;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;

/** Opt-in real native regression. Partial reset cases require normal host shutdown afterwards. */
public final class RuntimeNaturalEndProbe {
    private RuntimeNaturalEndProbe() {}

    /** Owner-thread native helper coverage, using private unpublished maps and their own teams. */
    public static JsonObject observeResetContract(MinecraftServer server) {
        require(server.isSameThread() && Boolean.getBoolean("editorViewportProbe.enabled")
                && Boolean.getBoolean("mcagent.testMode"), "Opt-in server owner required");
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
        require(root.contains("/Q82/ui-host-"), "Disposable Q82 host required");
        ObservedMap map = MapConstructionScope.construct(() -> new ObservedMap(server.overworld()));
        try {
            require(!map.resetCompleted(map.resetGeneration()), "Unattempted reset is not completed");
            require(map.resetObservation(map.resetGeneration()) == BaseMap.ResetObservation.UNOBSERVED, "Initial reset must be unobserved");
            map.reset();
            require(map.resetCompleted(map.resetGeneration()), "Normal complete reset must publish evidence");
            map.observeWithoutReset();
            require(!map.resetCompleted(map.resetGeneration()), "No reset attempt must not publish old evidence");
            map.tail = () -> { throw new InjectedResetFailure(); };
            resetFault(map);
            require(!map.resetCompleted(map.resetGeneration()), "Tail failure must invalidate prior success");
            AtomicInteger depth = new AtomicInteger();
            map.tail = () -> {
                if (depth.getAndIncrement() == 0) {
                    map.reset();
                    require(!map.resetCompleted(map.resetGeneration()), "Nested success must wait for outer return");
                    throw new InjectedResetFailure();
                }
            };
            resetFault(map);
            require(!map.resetCompleted(map.resetGeneration()), "Outer failure must invalidate nested success");
            depth.set(0);
            map.tail = () -> {
                if (depth.getAndIncrement() == 0) {
                    try { map.reset(); } catch (InjectedResetFailure expected) { }
                } else throw new InjectedResetFailure();
            };
            map.reset();
            require(!map.resetCompleted(map.resetGeneration()), "Caught nested failure must remain incomplete");
            require(map.resetObservation(map.resetGeneration()) == BaseMap.ResetObservation.INCOMPLETE, "Caught failure is not legacy unobserved state");
            map.tail = () -> { };
            map.reset();
            require(map.resetCompleted(map.resetGeneration()), "Later independent success must recover completion evidence");
            map.legacyReset();
            require(map.resetObservation(map.resetGeneration()) == BaseMap.ResetObservation.UNOBSERVED, "Unwrapped legacy reset must not inherit old observation");
        } finally { dispose(map, server); }
        ObservedMap unobserved = MapConstructionScope.construct(() -> new UnobservedMap(server.overworld()));
        try {
            unobserved.reset();
            require(!unobserved.resetCompleted(unobserved.resetGeneration()), "Subclass cannot inherit proof for unobserved work");
        } finally { dispose(unobserved, server); }
        JsonObject result = new JsonObject();
        result.addProperty("action", "reset_observation_contract");
        result.addProperty("status", "PASS");
        result.addProperty("asserted_cases", 9);
        return result;
    }

    private static void resetFault(ObservedMap map) {
        try { map.reset(); } catch (InjectedResetFailure expected) { return; }
        throw new AssertionError("Expected reset failure");
    }
    private static void dispose(BaseMap map, MinecraftServer server) {
        try { map.getCapabilityMap().close(); }
        finally { map.getMapTeams().shutdown(server.getScoreboard()); }
    }
    private static class ObservedMap extends BaseMap {
        Runnable tail = () -> { };
        ObservedMap(ServerLevel level) {
            super(level, "reset_proof_" + UUID.randomUUID(), new AreaData(BlockPos.ZERO, new BlockPos(1, 1, 1)));
        }
        @Override public String getGameType() { return "reset_proof"; }
        @Override public boolean victoryGoal() { return false; }
        @Override public void reset() { runObservedReset(ObservedMap.class, () -> { super.reset(); tail.run(); }); }
        void observeWithoutReset() { runObservedReset(ObservedMap.class, () -> { }); }
        void legacyReset() { super.reset(); }
    }
    private static final class UnobservedMap extends ObservedMap {
        UnobservedMap(ServerLevel level) { super(level); }
    }

    /**
     * Worker entry for an explicitly installed idle map in a disposable Q82 host.
     * Creates a fresh binding, replacing this actor's UI binding. Actions are natural_stop,
     * natural_tick, partial_stop, partial_tick, late_partial_stop, late_partial_tick, caught_nested_stop.
     * Late faults add one test-only capability to a later empty team, after native admission.
     * Its reset throws only after all actual participants have been cleaned.
     * Tick actions invoke the coordinator directly;
     * they verify its native behavior, not event-bus tick scheduling. No install or file writes.
     */
    public static JsonObject execute(MinecraftServer server, UUID actorId, ExtensionRegistry registry,
            String type, String name, String action) throws Exception {
        require(!server.isSameThread(), "Worker required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"), "Opt-in test flags required");
        require(List.of("natural_stop", "natural_tick", "partial_stop", "partial_tick",
                "late_partial_stop", "late_partial_tick", "caught_nested_stop").contains(action), "Unknown action");
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
        require(root.contains("/Q82/ui-host-"), "Disposable Q82 host required");
        Context context = owner(server, () -> capture(server, actorId, type, name));
        var request = owner(server, () -> context.core.getWorldBindings().begin(actorId, context.installed.plan().dimensions()));
        var read = request.read(new WindowsFileIdentityProvider(), () -> false);
        WorldBinding binding = owner(server, () -> context.core.getWorldBindings().accept(read));
        var check = owner(server, () -> context.core.getWorldBindings().check(binding));
        WorldBindingCheckResult checked = check.verify(() -> false);
        return owner(server, () -> run(context, registry, binding, checked, action));
    }

    /**
     * Worker entry observing a real first-to-one CS timeout on an already installed map.
     * The author must configure winnerRound=1, warmUpTime=0 and knifeSelection=false.
     * Waiting, round and winner-wait timers must total at most 800 ticks; round time is positive.
     * Replaces this actor's UI binding. Never edits settings, injects scores or drives ticks/reset.
     * On failure the caller must shut down the disposable host normally.
     */
    public static JsonObject executeSingleRoundTimeout(MinecraftServer server, UUID actorId,
            ExtensionRegistry registry, String name) throws Exception {
        require(!server.isSameThread(), "Worker required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"), "Opt-in test flags required");
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
        require(root.contains("/Q82/ui-host-"), "Disposable Q82 host required");
        Context context = owner(server, () -> capture(server, actorId, "cs", name));
        TimeoutObservation observation = owner(server, () -> new TimeoutObservation(context));
        var request = owner(server, () -> context.core.getWorldBindings().begin(actorId, context.installed.plan().dimensions()));
        var read = request.read(new WindowsFileIdentityProvider(), () -> false);
        WorldBinding binding = owner(server, () -> context.core.getWorldBindings().accept(read));
        var check = owner(server, () -> context.core.getWorldBindings().check(binding));
        WorldBindingCheckResult checked = check.verify(() -> false);
        try {
            owner(server, () -> { observation.start(registry, binding, checked); return null; });
            return observation.completion.get(60, TimeUnit.SECONDS);
        } finally {
            owner(server, () -> { observation.unregister(); return null; });
        }
    }

    private static final class TimeoutObservation {
        private static final String ROUND_EVENT = "net.ptcrys.blockoffensive.event.CSGameRoundEndEvent";
        private static final String SWITCH_EVENT = "net.ptcrys.blockoffensive.event.CSGameMapEvent$TeamSwitchEvent";
        private final Context context;
        private final Field overtime;
        private final Field overtimeVote;
        private final int waitingTicks, roundTicks, winnerWaitingTicks;
        private final long baseline;
        private final CompletableFuture<JsonObject> completion = new CompletableFuture<>();
        private final Consumer<Event> events = this::onMapEvent;
        private final Consumer<TickEvent.ServerTickEvent> ticks = this::onTick;
        private MinecraftRuntimeTests.StartAttempt attempt;
        private MinecraftRuntimeTests.Session session;
        private int elapsedTicks, rounds, switches, victories, resets, stoppedTicks;
        private int roundAtTick, victoryCtScore = -1, victoryTScore = -1;
        private String reason, roundWinner;

        TimeoutObservation(Context context) throws ReflectiveOperationException {
            this.context = context;
            BaseMap map = context.map;
            require(map.getClass().getName().equals("net.ptcrys.blockoffensive.map.CSGameMap"), "Concrete CS map required");
            require(Integer.valueOf(1).equals(setting(map, "winnerRound")), "Author winnerRound=1 before installation");
            require(Integer.valueOf(0).equals(setting(map, "warmUpTime")), "Author warmUpTime=0 before installation");
            require(Boolean.FALSE.equals(setting(map, "knifeSelection")), "Author knifeSelection=false before installation");
            waitingTicks = (Integer) setting(map, "waitingTime");
            roundTicks = (Integer) setting(map, "roundTimeLimit");
            winnerWaitingTicks = (Integer) setting(map, "winnerWaitingTime");
            require(waitingTicks >= 0 && roundTicks > 0 && winnerWaitingTicks >= 0
                    && (long) waitingTicks + roundTicks + winnerWaitingTicks <= 800, "Author bounded timers totaling at most 800 ticks");
            overtime = map.getClass().getDeclaredField("isOvertime");
            overtimeVote = map.getClass().getDeclaredField("isWaitingOverTimeVote");
            overtime.setAccessible(true);
            overtimeVote.setAccessible(true);
            baseline = map.resetGeneration();
        }

        void start(ExtensionRegistry registry, WorldBinding binding, WorldBindingCheckResult checked) throws ReflectiveOperationException {
            addEventListener(FPSMapEvent.class, events);
            addEventListener(Class.forName(ROUND_EVENT).asSubclass(Event.class), events);
            addEventListener(Class.forName(SWITCH_EVENT).asSubclass(Event.class), events);
            MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, TickEvent.ServerTickEvent.class, ticks);
            MinecraftRuntimeTests tests = context.core.runtimeInstallations().tests();
            attempt = tests.newStartAttempt();
            session = tests.start(attempt, context.installed, registry, binding, checked, () -> false);
            require(session.state() == MinecraftRuntimeTests.State.RUNNING && context.map.isStart()
                    && context.map.checkGameHasPlayer(context.actor), "Native start must retain the actual participant");
            require(context.map.resetGeneration() == baseline && resets == 0 && victories == 0 && rounds == 0 && switches == 0,
                    "First-to-one start must not reset, win, end a round or switch sides");
            require(score("ct") == 0 && score("t") == 0, "Native start must preserve zero score");
            requireNoOvertime();
        }

        void onMapEvent(Event event) {
            if (completion.isDone()) return;
            try {
                Object map = event instanceof FPSMapEvent fps ? fps.getMap() : event.getClass().getMethod("getMap").invoke(event);
                if (map != context.map) return;
                requireNoOvertime();
                if (event instanceof FPSMapEvent.ResetEvent) resets++;
                else if (event instanceof FPSMapEvent.VictoryEvent) {
                    victories++;
                    victoryCtScore = score("ct");
                    victoryTScore = score("t");
                } else if (event.getClass().getName().equals(ROUND_EVENT)) {
                    rounds++;
                    roundAtTick = elapsedTicks + 1;
                    reason = String.valueOf(event.getClass().getMethod("getReason").invoke(event));
                    roundWinner = ((ServerTeam) event.getClass().getMethod("getWinner").invoke(event)).getName();
                } else if (event.getClass().getName().equals(SWITCH_EVENT)) switches++;
            } catch (Throwable failure) { completion.completeExceptionally(failure); }
        }

        void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || session == null || completion.isDone()) return;
            try {
                require(context.core.getServer().isSameThread(), "Exact server owner required");
                elapsedTicks++;
                requireNoOvertime();
                require(switches == 0 && rounds <= 1 && victories <= 1 && resets <= 1, "Unexpected extra round, switch, victory or reset");
                if (session.state() == MinecraftRuntimeTests.State.RUNNING) {
                    require(stoppedTicks == 0 && context.map.isStart() && context.map.checkGameHasPlayer(context.actor),
                            "Participant or running state lost before natural completion");
                    require(elapsedTicks <= waitingTicks + roundTicks + winnerWaitingTicks + 100, "Natural timeout exceeded its tick budget");
                    return;
                }
                require(session.state() == MinecraftRuntimeTests.State.STOPPED && attempt.retainedSession() == null,
                        "Real server tick must release the completed session");
                require(rounds == 1 && victories == 1 && resets == 1 && "TIME_OUT".equals(reason) && "ct".equals(roundWinner),
                        "One real CT timeout, victory and reset required");
                require(roundAtTick >= waitingTicks + roundTicks && victoryCtScore == 1 && victoryTScore == 0,
                        "Victory must follow the actual timer at 1:0");
                require(context.map.resetGeneration() == baseline + 1 && context.map.resetCompleted(context.map.resetGeneration()),
                        "Exactly one completed native reset required");
                require(!context.map.isStart() && context.map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(),
                        "Natural completion must clean the native roster");
                if (++stoppedTicks >= 3) completion.complete(result());
            } catch (Throwable failure) { completion.completeExceptionally(failure); }
        }

        private void requireNoOvertime() throws IllegalAccessException {
            require(!overtime.getBoolean(context.map) && !overtimeVote.getBoolean(context.map), "First-to-one must not enter overtime or its vote");
        }
        private int score(String team) { return context.map.getMapTeams().getTeamByName(team).orElseThrow().getScores(); }
        private JsonObject result() {
            JsonObject result = new JsonObject();
            result.addProperty("action", "single_round_timeout");
            result.addProperty("status", "PASS");
            result.addProperty("map", session.plan().key().name());
            result.addProperty("installation_id", session.plan().installationId().toString());
            result.addProperty("commit_id", session.plan().commitId().toString());
            result.addProperty("ui_rebind_required", true);
            result.addProperty("manual_map_tick", false);
            result.addProperty("manual_coordinator_tick", false);
            result.addProperty("manual_reset", false);
            result.addProperty("waiting_ticks", waitingTicks);
            result.addProperty("round_time_limit_ticks", roundTicks);
            result.addProperty("winner_waiting_ticks", winnerWaitingTicks);
            result.addProperty("overtime_observed", false);
            result.addProperty("state", session.state().name());
            result.addProperty("elapsed_server_ticks", elapsedTicks);
            result.addProperty("round_end_tick", roundAtTick);
            result.addProperty("round_reason", reason);
            result.addProperty("round_winner", roundWinner);
            result.addProperty("round_events", rounds);
            result.addProperty("victory_events", victories);
            result.addProperty("reset_events", resets);
            result.addProperty("side_switch_events", switches);
            result.addProperty("victory_ct_score", victoryCtScore);
            result.addProperty("victory_t_score", victoryTScore);
            result.addProperty("reset_generation_delta", context.map.resetGeneration() - baseline);
            result.addProperty("post_stop_ticks_observed", stoppedTicks - 1);
            return result;
        }
        void unregister() {
            MinecraftForge.EVENT_BUS.unregister(events);
            MinecraftForge.EVENT_BUS.unregister(ticks);
        }
    }

    private static Object setting(BaseMap map, String name) {
        return map.settings().stream().filter(setting -> setting.getConfigName().equals(name))
                .findFirst().orElseThrow().get();
    }
    @SuppressWarnings("unchecked")
    private static void addEventListener(Class<? extends Event> type, Consumer<Event> listener) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, (Class<Event>) type, listener);
    }

    private static JsonObject run(Context context, ExtensionRegistry registry, WorldBinding binding,
            WorldBindingCheckResult checked, String action) {
        boolean late = action.startsWith("late_partial_");
        boolean caughtNested = action.equals("caught_nested_stop");
        boolean partial = caughtNested || late || action.startsWith("partial_");
        boolean tick = action.endsWith("_tick");
        MinecraftRuntimeTests tests = context.core.runtimeInstallations().tests();
        BaseMap map = context.map;
        AtomicInteger resets = new AtomicInteger();
        Consumer<FPSMapEvent> listener = event -> {
            if (event.getMap() == map && event instanceof FPSMapEvent.ResetEvent) {
                int count = resets.incrementAndGet();
                if (caughtNested) {
                    if (count == 1) {
                        try { map.reset(); } catch (InjectedResetFailure expected) { }
                    } else throw new InjectedResetFailure();
                } else if (partial && !late) throw new InjectedResetFailure();
            }
        };
        var attempt = tests.newStartAttempt();
        MinecraftRuntimeTests.Session session = tests.start(attempt, context.installed, registry, binding, checked, () -> false);
        long baseline = map.resetGeneration();
        LateResetFailure lateFault = late ? installLateFault(context) : null;
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        result.addProperty("map", session.plan().key().name());
        result.addProperty("installation_id", session.plan().installationId().toString());
        result.addProperty("commit_id", session.plan().commitId().toString());
        result.addProperty("ui_rebind_required", true);
        result.addProperty("normal_host_shutdown_required", partial);
        result.addProperty("manual_coordinator_tick", tick);
        result.addProperty("late_capability_fault", late);
        result.addProperty("driver_catches_nested_reset_failure", caughtNested);
        result.addProperty("status", "FAIL");
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, FPSMapEvent.class, listener);
        try {
            require(session.state() == MinecraftRuntimeTests.State.RUNNING && map.isStart()
                    && map.checkGameHasPlayer(context.actor), "Actual RUNNING session required");
            if (caughtNested) refused("stop_failed", () -> tests.stop(session));
            else {
                try { map.reset(); require(!partial, "Expected reset fault"); }
                catch (InjectedResetFailure expected) { require(partial, "Unexpected reset fault"); }
            }
            int expectedResets = caughtNested ? 2 : 1;
            require(map.resetGeneration() == baseline + expectedResets && resets.get() == expectedResets, "Unexpected original reset count");
            require(map.resetCompleted(map.resetGeneration()) == !partial, "Complete-reset evidence differs from actual return");
            if (late) {
                require(lateFault.calls == 1 && !map.isStart()
                        && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "Late fault must leave deceptively idle state");
                result.addProperty("idle_appearance_after_failed_reset", true);
            }
            if (caughtNested) require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "Driver must return with idle appearance after catching nested failure");
            else if (tick) tests.tick();
            else if (partial) refused("stop_failed", () -> tests.stop(session));
            else require(tests.stop(session) == MinecraftRuntimeTests.State.STOPPED, "Completed reset not observed");
            if (partial) {
                require(session.state() == MinecraftRuntimeTests.State.FAILED && attempt.retainedSession() == session, "Partial reset ownership lost");
                refused("busy", () -> tests.stop(session));
                var another = tests.newStartAttempt();
                refused("busy", () -> tests.start(another, context.installed, registry, binding, checked, () -> false));
                require(another.retainedSession() == null, "Other attempt stole retained ownership");
                tests.tick();
            } else {
                require(session.state() == MinecraftRuntimeTests.State.STOPPED && attempt.retainedSession() == null, "Completed reset retained ownership");
                require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "Native cleanup incomplete");
                require(tests.stop(session) == MinecraftRuntimeTests.State.STOPPED, "Repeated stop not idempotent");
                tests.tick();
            }
            require(map.resetGeneration() == baseline + expectedResets && resets.get() == expectedResets, "Coordinator replayed reset");
            result.addProperty("state", session.state().name());
            result.addProperty("reset_events", resets.get());
            result.addProperty("reset_generation_delta", map.resetGeneration() - baseline);
            result.addProperty("status", "PASS");
            return result;
        } finally {
            MinecraftForge.EVENT_BUS.unregister(listener);
            if (lateFault != null) lateFault.getHolder().getCapabilityMap().remove(LateResetFailure.class);
            // No emergency reset: a failed assertion may have exposed partial native effects.
            // The caller must shut down this disposable host if the method throws.
        }
    }

    private static LateResetFailure installLateFault(Context context) {
        List<ServerTeam> ordered = context.map.getMapTeams().getTeamsWithSpectator();
        int occupied = -1;
        for (int i = 0; i < ordered.size(); i++)
            if (ordered.get(i).hasPlayer(context.actor.getUUID())) occupied = i;
        require(occupied >= 0 && occupied < ordered.size() - 1, "Need an empty team after the actual player's team");
        ServerTeam target = ordered.get(ordered.size() - 1);
        require(target.getPlayers().isEmpty(), "Late fault target must already be empty");
        if (!FPSMCapabilityManager.isRegistered(LateResetFailure.class))
            FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, LateResetFailure.class, LateResetFailure::new);
        LateResetFailure fault = new LateResetFailure(target);
        fault.map = context.map;
        require(target.getCapabilityMap().add(fault), "Late fault capability could not be attached");
        return fault;
    }

    public static final class LateResetFailure extends TeamCapability {
        private BaseMap map;
        private int calls;
        public LateResetFailure(BaseTeam team) { super(team); }
        @Override public void reset() {
            require(map != null && !map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(),
                    "All participant cleanup must precede the injected tail failure");
            calls++;
            throw new InjectedResetFailure();
        }
    }

    private static Context capture(MinecraftServer server, UUID actorId, String type, String name) {
        FPSMCore core = FPSMCore.getInstance();
        require(core != null && core.isActive() && core.getServer() == server, "Exact active core required");
        var key = new InstallationPlan.Key(type, name);
        var record = core.runtimeInstallations().records().get(key);
        require(record != null, "Explicit prior installation required");
        var installed = core.runtimeInstallations().captureInstalled(record);
        BaseMap map = core.getMapByTypeWithName(type, name).orElseThrow();
        ServerPlayer actor = server.getPlayerList().getPlayer(actorId);
        require(actor != null && server.getPlayerList().getPlayers().size() == 1, "One actual connected actor required");
        require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "Idle installed map required");
        return new Context(core, installed, map, actor);
    }
    private record Context(FPSMCore core, MinecraftRuntimeInstallations.InstalledRuntime installed, BaseMap map, ServerPlayer actor) {}
    private static final class InjectedResetFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static void refused(String reason, Runnable action) {
        try { action.run(); }
        catch (DocumentCodecException failure) {
            require(failure.problems().stream().anyMatch(problem -> problem.code().path().equals("runtime_test/" + reason)), "Unexpected refusal");
            return;
        }
        throw new AssertionError("Expected refusal: " + reason);
    }
    private static <T> T owner(MinecraftServer server, Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try { result.complete(action.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result.get(20, TimeUnit.SECONDS);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
