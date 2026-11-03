package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.match.RoundLifecycle;
import net.ptcrys.fpsmatch.core.match.RoundPhase;
import net.ptcrys.fpsmatch.core.team.ServerTeam;

/** Bounded passive observation. Never starts a match, advances ticks, or changes game state. */
final class NativeCsRoundObservation {
    private static final String ROUND_EVENT = "net.ptcrys.blockoffensive.event.CSGameRoundEndEvent";
    private static final int MAX_TICKS = 600;
    private static final long WALL_NANOS = TimeUnit.SECONDS.toNanos(45);
    private static final long READ_RETENTION_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final int MAX_RETAINED = 16;
    private static final int MAX_RETIRED_REQUESTS = 256;
    private static final Object LOCK = new Object();
    private static final Map<String, Observation> RETAINED = new LinkedHashMap<>();
    private static final ArrayList<RetiredRequest> RETIRED_REQUESTS = new ArrayList<>();
    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdogExecutor();
    // Reserving the slot before queueing start also excludes two unarmed concurrent starts.
    private static Observation active;

    private NativeCsRoundObservation() {}

    private static ScheduledThreadPoolExecutor watchdogExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "fpsmatch-cs-observation-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Short worker call: returns only after server-owned registration, never just queued work. */
    static JsonObject begin(MinecraftServer server, String requestId, Runnable checkActive) throws Exception {
        Objects.requireNonNull(server);
        Objects.requireNonNull(checkActive);
        require(!server.isSameThread(), "cs_round_observation_requires_worker");
        canonicalId(requestId);
        checkActive.run();
        Observation observation;
        boolean created;
        synchronized (LOCK) {
            prune();
            observation = findRequest(server, requestId);
            created = observation == null;
            if (created) {
                require(RETIRED_REQUESTS.stream().noneMatch(retired -> retired.id().equals(requestId)
                        && retired.owner().get() == server), "cs_round_observation_request_expired_use_new_id");
                require(active == null, "cs_round_observation_already_active");
                require(RETAINED.size() < MAX_RETAINED, "cs_round_observation_unread_result_capacity");
                observation = new Observation(server, requestId);
                RETAINED.put(observation.id, observation);
                active = observation;
                try {
                    observation.watchdog = WATCHDOG.scheduleAtFixedRate(observation::watch, 100, 100, TimeUnit.MILLISECONDS);
                    server.execute(observation::start);
                } catch (RuntimeException failure) {
                    observation.fail(failure);
                }
            }
        }
        boolean handedOff = false;
        try {
            long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!observation.startedSignal.isDone()) {
                checkActive.run();
                if (System.nanoTime() >= startDeadline) {
                    synchronized (LOCK) { observation.finish("EXPIRED", "cs_round_observation_start_10_second_limit"); }
                    break;
                }
                try { observation.startedSignal.get(100, TimeUnit.MILLISECONDS); }
                catch (TimeoutException pending) { /* bounded wait; cancellation remains responsive */ }
            }
            checkActive.run();
            synchronized (LOCK) {
                JsonObject response = observation.reply("ARMED", false);
                handedOff = true;
                return response;
            }
        } finally {
            // A cancelled retry must not destroy an already handed-off existing session.
            if (created && !handedOff) synchronized (LOCK) {
                observation.finish("CANCELLED", "cs_round_observation_start_call_cancelled_or_failed");
            }
        }
    }

    /** Each active poll obtains one coherent fresh server-owned sample, then releases the module. */
    static JsonObject poll(MinecraftServer server, String observationId) throws Exception {
        Objects.requireNonNull(server);
        canonicalId(observationId);
        Observation observation;
        synchronized (LOCK) {
            prune();
            observation = find(server, observationId);
            if (observation == null) return unknown("observation_id", observationId);
            if (observation.closed) return observation.reply("OBSERVING", true);
        }
        CompletableFuture<JsonObject> response = new CompletableFuture<>();
        Runnable capture = () -> {
            synchronized (LOCK) {
                try {
                    if (!observation.closed && observation.started) {
                        observation.validateRuntime();
                        observation.currentSample = observation.capture("poll");
                    }
                    response.complete(observation.reply("OBSERVING", true));
                } catch (Throwable failure) {
                    observation.fail(failure);
                    response.complete(observation.reply("OBSERVING", true));
                }
            }
        };
        try {
            if (server.isSameThread()) capture.run(); else server.execute(capture);
            return response.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException unavailable) {
            synchronized (LOCK) {
                observation.finish("EXPIRED", "cs_round_observation_poll_10_second_limit");
                return observation.reply("OBSERVING", true);
            }
        } catch (InterruptedException interrupted) {
            synchronized (LOCK) { observation.finish("CANCELLED", "cs_round_observation_poll_interrupted"); }
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (RuntimeException unavailable) {
            synchronized (LOCK) {
                observation.fail(unavailable);
                return observation.reply("OBSERVING", true);
            }
        }
    }

    /** Exact handles can be cancelled even after the integrated server has disappeared. */
    static JsonObject cancel(MinecraftServer server, String observationId) {
        canonicalId(observationId);
        synchronized (LOCK) {
            prune();
            Observation observation = find(server, observationId);
            if (observation == null) return unknown("observation_id", observationId);
            observation.finish("CANCELLED", "cs_round_observation_cancelled_by_owner");
            return observation.reply("OBSERVING", false);
        }
    }

    static JsonObject cancelByRequest(MinecraftServer server, String requestId) {
        canonicalId(requestId);
        synchronized (LOCK) {
            prune();
            Observation observation = findRequest(server, requestId);
            if (observation == null) return unknown("request_id", requestId);
            observation.finish("CANCELLED", "cs_round_observation_cancelled_by_request");
            return observation.reply("OBSERVING", false);
        }
    }

    /** Compatibility only: the old entry point still waits, with exact cancellation ownership. */
    static JsonObject execute(MinecraftServer server, Runnable checkActive) throws Exception {
        String requestId = UUID.randomUUID().toString();
        try {
            JsonObject started = begin(server, requestId, checkActive);
            String id = started.get("observation_id").getAsString();
            Observation observation;
            synchronized (LOCK) { observation = find(server, id); }
            require(observation != null, "cs_round_observation_missing_after_start");
            while (true) {
                checkActive.run();
                try {
                    JsonObject result = observation.completion.get(100, TimeUnit.MILLISECONDS);
                    checkActive.run();
                    synchronized (LOCK) { result = observation.reply("OBSERVING", true); }
                    require("OBSERVED".equals(result.get("status").getAsString()),
                            result.has("failure") ? result.get("failure").getAsString() : "cs_round_observation_failed");
                    return result;
                } catch (TimeoutException pending) { /* independent watchdog owns the absolute limit */ }
            }
        } finally {
            cancelByRequest(server, requestId);
        }
    }

    static JsonObject status(MinecraftServer server) {
        require(server.isSameThread(), "server_owner_required");
        synchronized (LOCK) {
            JsonObject result = new JsonObject();
            boolean armed = active != null && active.owner.get() == server && active.started && !active.closed;
            result.addProperty("active", armed);
            if (armed) {
                result.addProperty("observation_id", active.id);
                result.addProperty("request_id", active.requestId);
                result.addProperty("target_type", "cs");
                result.addProperty("target_name", NativeRuntimeSnapshot.CS_NAME);
                result.addProperty("runtime_identity", active.runtimeIdentity);
                result.addProperty("start_server_tick", active.startTick);
                result.addProperty("elapsed_server_ticks", active.elapsedTicks);
                result.addProperty("phase", active.previousPhase.name());
            }
            return result;
        }
    }

    private static Observation find(MinecraftServer server, String id) {
        Observation found = RETAINED.get(id);
        return found != null && (server == null || found.owner.get() == server) ? found : null;
    }

    private static Observation findRequest(MinecraftServer server, String requestId) {
        if (RETIRED_REQUESTS.stream().anyMatch(retired -> retired.id().equals(requestId)
                && (server == null || retired.owner().get() == server))) return null;
        Observation found = null;
        for (Observation candidate : RETAINED.values()) {
            if (!candidate.requestId.equals(requestId) || (server != null && candidate.owner.get() != server)) continue;
            if (found != null) return null; // a null-server request must never choose an ambiguous owner
            found = candidate;
        }
        return found;
    }

    private static void prune() {
        long now = System.nanoTime();
        RETIRED_REQUESTS.removeIf(retired -> retired.owner().get() == null);
        Iterator<Observation> iterator = RETAINED.values().iterator();
        while (iterator.hasNext()) {
            Observation observation = iterator.next();
            if (!observation.closed) continue;
            if (observation.owner.get() == null) iterator.remove();
            else if (observation.readAt != 0 && now - observation.readAt >= READ_RETENTION_NANOS
                    && RETIRED_REQUESTS.size() < MAX_RETIRED_REQUESTS) {
                // An expired request may not be reused to create a different observation.
                // At the bounded tombstone limit retain the result and fail capacity closed.
                RETIRED_REQUESTS.add(new RetiredRequest(observation.requestId, observation.owner));
                iterator.remove();
            }
        }
    }

    private record RetiredRequest(String id, WeakReference<MinecraftServer> owner) {}

    private static JsonObject unknown(String key, String value) {
        JsonObject result = new JsonObject();
        result.addProperty("action", "native_cs_round_observe");
        result.addProperty("status", "UNKNOWN");
        result.addProperty("found", false);
        result.addProperty(key, value);
        result.addProperty("failure", "cs_round_observation_unknown_or_expired");
        return result;
    }

    private static void canonicalId(String value) {
        require(value != null && UUID.fromString(value).toString().equals(value), "cs_round_observation_canonical_uuid_required");
    }

    private static final class Observation {
        final WeakReference<MinecraftServer> owner;
        final String requestId;
        final String id = UUID.randomUUID().toString();
        final long deadline = System.nanoTime() + WALL_NANOS;
        final CompletableFuture<Void> startedSignal = new CompletableFuture<>();
        final CompletableFuture<JsonObject> completion = new CompletableFuture<>();
        final Consumer<Event> roundListener = this::onRoundEnd;
        final Consumer<TickEvent.ServerTickEvent> tickListener = this::onTick;
        final Consumer<ServerStoppingEvent> stoppingListener = this::onStopping;
        final Consumer<PlayerEvent.PlayerLoggedOutEvent> logoutListener = this::onLogout;
        final JsonArray samples = new JsonArray();
        final JsonArray settlements = new JsonArray();
        MinecraftServer server;
        BaseMap map;
        RoundLifecycle<?, ?> initialLifecycle;
        RoundLifecycle<?, ?> nextLifecycle;
        Method eventMap, eventWinner, eventReason;
        Set<UUID> initialParticipants = Set.of();
        String runtimeIdentity;
        long resetGeneration, readAt;
        int startTick, elapsedTicks, stage, sampleSequence;
        RoundPhase previousPhase;
        JsonObject currentSample, armedSample, terminalResult;
        ScheduledFuture<?> watchdog;
        boolean roundRegistered, tickRegistered, stoppingRegistered, logoutRegistered;
        boolean started, closed;

        Observation(MinecraftServer server, String requestId) {
            this.server = server;
            this.owner = new WeakReference<>(server);
            this.requestId = requestId;
        }

        void start() {
            synchronized (LOCK) {
                if (closed) return;
                try {
                    require(server.isSameThread(), "server_owner_required");
                    require(active == this, "cs_round_observation_start_owner_changed");
                    require(server.isRunning(), "cs_round_observation_server_not_running");
                    require(System.nanoTime() < deadline, "cs_round_observation_start_expired");
                    require(Boolean.getBoolean("editorViewportProbe.enabled")
                            && Boolean.getBoolean("mcagent.testMode"), "opt_in_probe_flags_required");
                    String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
                    require(root.contains("/Q82/ui-host-"), "isolated_q82_world_required");
                    map = NativeRuntimeSnapshot.requireCsTarget(server);
                    require(map.hasRuntimeOwnership() && map.isStart(), "running_owned_cs_runtime_required");
                    require(!map.isDebug(), "ordinary_non_debug_cs_runtime_required");
                    initialLifecycle = NativeRuntimeSnapshot.csLifecycle(map);
                    require(initialLifecycle != null && initialLifecycle.phase() == RoundPhase.ACTIVE_ROUND,
                            "cs_round_observation_requires_active_round");
                    require(initialLifecycle.lastResult().isEmpty(), "active_round_has_old_result");
                    previousPhase = initialLifecycle.phase();
                    initialParticipants = Set.copyOf(map.getMapTeams().getJoinedPlayersWithSpec());
                    require(!initialParticipants.isEmpty(), "cs_round_observation_requires_participants");
                    runtimeIdentity = NativeRuntimeSnapshot.identity(map);
                    resetGeneration = map.resetGeneration();
                    startTick = server.getTickCount();
                    validateRuntime();
                    Class<? extends Event> eventType = Class.forName(ROUND_EVENT, false, map.getClass().getClassLoader()).asSubclass(Event.class);
                    eventMap = eventType.getMethod("getMap");
                    eventWinner = eventType.getMethod("getWinner");
                    eventReason = eventType.getMethod("getReason");
                    sample("armed");
                    armedSample = currentSample.deepCopy();
                    roundRegistered = true;
                    register(eventType, roundListener);
                    tickRegistered = true;
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, TickEvent.ServerTickEvent.class, tickListener);
                    stoppingRegistered = true;
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, ServerStoppingEvent.class, stoppingListener);
                    logoutRegistered = true;
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, PlayerEvent.PlayerLoggedOutEvent.class, logoutListener);
                    started = true;
                    startedSignal.complete(null);
                } catch (Throwable failure) { fail(failure); }
            }
        }

        void watch() {
            synchronized (LOCK) {
                if (closed) return;
                if (System.nanoTime() >= deadline) finish("EXPIRED", "cs_round_observation_45_second_limit");
                else if (!Boolean.getBoolean("editorViewportProbe.enabled") || !Boolean.getBoolean("mcagent.testMode"))
                    finish("FAILED", "opt_in_probe_flags_revoked");
            }
        }

        void onStopping(ServerStoppingEvent event) {
            synchronized (LOCK) {
                if (!closed && event.getServer() == server) finish("FAILED", "cs_round_observation_server_stopping");
            }
        }

        void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            synchronized (LOCK) {
                if (!closed && event.getEntity().getServer() == server && initialParticipants.contains(event.getEntity().getUUID()))
                    finish("FAILED", "cs_observation_participant_disconnected");
            }
        }

        void validateRuntime() {
            require(server.isSameThread(), "server_owner_required");
            require(NativeRuntimeSnapshot.requireCsTarget(server) == map, "cs_runtime_identity_changed");
            require(map.hasRuntimeOwnership() && map.isStart(), "cs_runtime_stopped_or_ownership_lost");
            require(map.resetGeneration() == resetGeneration, "cs_runtime_reset_during_observation");
            require(!map.isDebug(), "cs_runtime_entered_debug");
            require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"),
                    "opt_in_probe_flags_revoked");
            require(Set.copyOf(map.getMapTeams().getJoinedPlayersWithSpec()).equals(initialParticipants),
                    "cs_observation_participant_membership_changed");
            for (UUID player : initialParticipants)
                require(server.getPlayerList().getPlayer(player) != null, "cs_observation_participant_disconnected");
        }

        void onRoundEnd(Event event) {
            synchronized (LOCK) {
                if (closed) return;
                try {
                    if (eventMap.invoke(event) != map) return;
                    validateRuntime();
                    require(stage == 0 && settlements.isEmpty(), "unexpected_additional_cs_settlement");
                    ServerTeam winner = (ServerTeam) eventWinner.invoke(event);
                    JsonObject result = new JsonObject();
                    result.addProperty("server_tick", server.getTickCount());
                    result.addProperty("winner", winner.getFixedName());
                    result.addProperty("reason", String.valueOf(eventReason.invoke(event)));
                    result.addProperty("source", ROUND_EVENT);
                    result.addProperty("event_order", "before_score_update_use_end_tick_samples_for_scores");
                    JsonArray players = new JsonArray();
                    winner.getPlayerList().forEach(uuid -> players.add(uuid.toString()));
                    result.add("winner_players_at_settlement", players);
                    settlements.add(result);
                } catch (Throwable failure) { fail(failure); }
            }
        }

        void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            synchronized (LOCK) {
                if (closed || !server.isSameThread()) return;
                try {
                    validateRuntime();
                    require(System.nanoTime() < deadline, "cs_round_observation_45_second_limit");
                    elapsedTicks++;
                    RoundLifecycle<?, ?> lifecycle = NativeRuntimeSnapshot.csLifecycle(map);
                    require(lifecycle != null, "cs_lifecycle_disappeared");
                    RoundPhase phase = lifecycle.phase();
                    if (phase != previousPhase || elapsedTicks % 10 == 0) sample(phase != previousPhase ? "phase_change" : "interval");
                    previousPhase = phase;
                    if (stage == 0) {
                        require(lifecycle == initialLifecycle, "cs_initial_lifecycle_replaced_early");
                        require(phase == RoundPhase.ACTIVE_ROUND || phase == RoundPhase.ROUND_END_WAITING, "cs_unexpected_phase_before_settlement");
                        if (phase == RoundPhase.ROUND_END_WAITING) {
                            require(settlements.size() == 1, "cs_settlement_event_missing");
                            var last = lifecycle.lastResult().orElseThrow();
                            JsonObject recorded = settlements.get(0).getAsJsonObject();
                            require(String.valueOf(last.winner()).equals(recorded.get("winner").getAsString())
                                    && String.valueOf(last.reason()).equals(recorded.get("reason").getAsString()), "cs_settlement_event_lifecycle_mismatch");
                            stage = 1;
                        }
                    } else if (stage == 1) {
                        require(phase == RoundPhase.ROUND_END_WAITING || phase == RoundPhase.WAITING, "cs_next_waiting_phase_missing");
                        if (phase == RoundPhase.WAITING) {
                            require(lifecycle != initialLifecycle, "cs_next_lifecycle_not_rebuilt");
                            nextLifecycle = lifecycle;
                            stage = 2;
                        } else require(lifecycle == initialLifecycle, "cs_settlement_lifecycle_changed");
                    } else {
                        require(lifecycle == nextLifecycle, "cs_next_lifecycle_replaced_early");
                        require(phase == RoundPhase.WAITING || phase == RoundPhase.ACTIVE_ROUND, "cs_next_active_phase_missing");
                        if (phase == RoundPhase.ACTIVE_ROUND) {
                            require(lifecycle.lastResult().isEmpty(), "cs_next_round_has_stale_result");
                            finish("OBSERVED", null);
                            return;
                        }
                    }
                    require(elapsedTicks < MAX_TICKS, "cs_round_observation_600_tick_limit");
                } catch (Throwable failure) { fail(failure); }
            }
        }

        JsonObject capture(String reason) {
            JsonObject snapshot = NativeRuntimeSnapshot.captureCsRound(server, map);
            snapshot.addProperty("observation_tick", elapsedTicks);
            snapshot.addProperty("sample_sequence", ++sampleSequence);
            snapshot.addProperty("sample_reason", reason);
            return snapshot;
        }

        void sample(String reason) {
            currentSample = capture(reason);
            samples.add(currentSample.deepCopy());
        }

        JsonObject reply(String activeStatus, boolean readTerminal) {
            if (terminalResult != null) {
                if (readTerminal && readAt == 0) readAt = System.nanoTime();
                return terminalResult.deepCopy();
            }
            return describe(started ? activeStatus : "STARTING", null);
        }

        JsonObject describe(String state, String failure) {
            JsonObject result = new JsonObject();
            result.addProperty("action", "native_cs_round_observe");
            result.addProperty("status", state);
            result.addProperty("found", true);
            result.addProperty("active", started && !closed);
            result.addProperty("request_id", requestId);
            result.addProperty("observation_id", id);
            result.addProperty("target_type", "cs");
            result.addProperty("target_name", NativeRuntimeSnapshot.CS_NAME);
            if (runtimeIdentity != null) result.addProperty("runtime_identity", runtimeIdentity);
            result.addProperty("start_server_tick", startTick);
            result.addProperty("elapsed_server_ticks", elapsedTicks);
            result.addProperty("initial_reset_generation", resetGeneration);
            result.addProperty("sample_sequence", sampleSequence);
            result.addProperty("sample_interval_ticks", 10);
            result.addProperty("max_server_ticks", MAX_TICKS);
            result.addProperty("wall_limit_seconds", 45);
            result.addProperty("listeners_removed", !roundRegistered && !tickRegistered && !stoppingRegistered && !logoutRegistered);
            if (currentSample != null) result.add("current_sample", currentSample.deepCopy());
            if (armedSample != null) result.add("armed_sample", armedSample.deepCopy());
            if (closed) {
                result.add("settlements", settlements.deepCopy());
                result.add("samples", samples.deepCopy());
                if ("OBSERVED".equals(state)) result.addProperty("observed_transition", "ACTIVE_ROUND>ROUND_END_WAITING>WAITING>ACTIVE_ROUND");
            }
            if (failure != null) result.addProperty("failure", failure);
            return result;
        }

        void fail(Throwable failure) {
            String message = failure.getMessage();
            if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
            boolean expired = message.equals("cs_round_observation_600_tick_limit")
                    || message.equals("cs_round_observation_45_second_limit")
                    || message.equals("cs_round_observation_start_expired");
            finish(expired ? "EXPIRED" : "FAILED", message);
        }

        /** Caller holds LOCK. No server tick, client callback, or game mutation is needed. */
        void finish(String state, String failure) {
            if (closed) return;
            closed = true;
            if (watchdog != null) {
                watchdog.cancel(false);
                watchdog = null;
            }
            if (roundRegistered && unregister(roundListener)) roundRegistered = false;
            if (tickRegistered && unregister(tickListener)) tickRegistered = false;
            if (stoppingRegistered && unregister(stoppingListener)) stoppingRegistered = false;
            if (logoutRegistered && unregister(logoutListener)) logoutRegistered = false;
            if (active == this) active = null;
            if (roundRegistered || tickRegistered || stoppingRegistered || logoutRegistered) {
                state = "FAILED";
                failure = failure == null ? "cs_round_observation_listener_cleanup_failed"
                        : failure + ";cs_round_observation_listener_cleanup_failed";
            }
            terminalResult = describe(state, failure);
            startedSignal.complete(null);
            completion.complete(terminalResult.deepCopy());
            // Retained terminal data must not keep a stopped world, player, or runtime alive.
            server = null;
            map = null;
            initialLifecycle = null;
            nextLifecycle = null;
            initialParticipants = Set.of();
            eventMap = null;
            eventWinner = null;
            eventReason = null;
        }

        boolean unregister(Object listener) {
            try {
                MinecraftForge.EVENT_BUS.unregister(listener);
                return true;
            } catch (Throwable failure) {
                return false;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void register(Class<? extends Event> type, Consumer<Event> listener) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, true, (Class<Event>) type, listener);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
