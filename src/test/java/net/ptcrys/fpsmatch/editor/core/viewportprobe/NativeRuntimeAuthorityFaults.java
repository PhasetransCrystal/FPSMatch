package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.loading.FMLLoader;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeInstallations;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeTests;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.installation.LocalInstallationIndex;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException;

/**
 * Worker-only development entry for an already explicitly restored disposable Q82 host.
 * Does not restore/install a runtime, edit op/config files, manufacture players, or call tick().
 * Native start/reset callbacks retain their normal gameplay effects. Only this probe's exact
 * returned RUNNING token may be stopped. Revocation is restored by issuing a NEW binding;
 * an old revoked token cannot be resurrected. The UI must obtain its own new binding afterwards.
 * The final authorized native respawn deliberately hands the same connection to a new player;
 * the old player is never restored, and normal host shutdown is the only subsequent action.
 */
public final class NativeRuntimeAuthorityFaults {
    private static final InstallationPlan.Key KEY = new InstallationPlan.Key("csdm", "Q82_UI51_SAFE_DM");
    private static final AtomicBoolean EXECUTING = new AtomicBoolean();
    private NativeRuntimeAuthorityFaults() {}

    /**
     * Separate fresh-host entry; never call after the terminal native-respawn suite.
     * current_permission_loss restores only the captured in-memory host authority sources.
     * queued_server_shutdown returns ARMED, then requires the caller's normal fixtures.shutdown.
     * Its CREATE_NEW result file, written by the real ServerStoppedEvent, is the final receipt;
     * an ARMED module.done is not acceptance. No token or connection credential is serialized.
     */
    public static JsonObject executeRemaining(MinecraftServer server, UUID actorId,
            ExtensionRegistry registry, String action) throws Exception {
        Objects.requireNonNull(server); Objects.requireNonNull(actorId); Objects.requireNonNull(registry);
        require(!server.isSameThread(), "module_worker_required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"),
                "explicit_development_flags_required");
        require("current_permission_loss".equals(action) || "queued_server_shutdown".equals(action),
                "unknown_remaining_authority_action");
        require(EXECUTING.compareAndSet(false, true), "authority_probe_already_running");
        boolean armed = false;
        try {
            Context c = owner(server, () -> capture(server, actorId, registry));
            Map<String,String> before = fingerprints(c.root);
            if (action.equals("current_permission_loss")) return permissionLoss(c, before);
            bind(c);
            WorldBindingCheckResult checked = check(c);
            Path game = FMLLoader.getGamePath().toAbsolutePath().normalize();
            require(game.equals(game.toRealPath()) && game.getFileName().toString().equals("game")
                    && game.getParent().getFileName().toString().matches("ui-host-[0-9]+")
                    && game.getParent().getParent().getFileName().toString().equals("Q82")
                    && c.root.startsWith(game), "canonical_Q82_ui_host_game_required");
            Path receipt = game.resolve("q82-authority-queued-shutdown-" + UUID.randomUUID() + ".json");
            require(!Files.exists(receipt, LinkOption.NOFOLLOW_LINKS), "new_shutdown_receipt_required");
            ShutdownProbe probe = new ShutdownProbe(c, checked, before, receipt);
            owner(server, () -> { probe.arm(); return null; });
            armed = true; // The stopped listener owns the lock and final receipt from this point.
            JsonObject result = remainingResult(c, "QUEUED_SERVER_SHUTDOWN");
            result.addProperty("status", "ARMED"); result.addProperty("result_file", receipt.toString());
            result.addProperty("module_done_is_acceptance", false);
            result.addProperty("normal_host_shutdown_required", true);
            result.addProperty("shutdown_requested_by_probe", false);
            result.addProperty("next_operation", "Normal fixtures.shutdown for this exact host; read result_file after ServerStoppedEvent");
            return result;
        } finally { if (!armed) EXECUTING.set(false); }
    }

    private static JsonObject remainingResult(Context c, String id) {
        JsonObject result = new JsonObject(); result.addProperty("id", id);
        result.addProperty("map", KEY.name()); result.addProperty("server_root", c.root.toString());
        result.addProperty("installation_id", c.record.plan().installationId().toString());
        result.addProperty("commit_id", c.record.plan().commitId().toString());
        result.addProperty("installation_or_project_write_requested", false);
        result.addProperty("manual_runtime_tick_requested", false);
        result.addProperty("op_or_configuration_file_write_requested", false);
        result.addProperty("native_actions_by_name_requested", false);
        return result;
    }

    private static JsonObject permissionLoss(Context c, Map<String,String> before) throws Exception {
        JsonObject row = remainingResult(c, "CURRENT_PERMISSION_LOSS");
        row.addProperty("operation", "Start exact RUNNING token, withdraw actual unopped singleplayer authority, await real END tick, restore captured sources and issue new binding");
        row.addProperty("expected", "hasPermissions(2) true/false/true; original token STOPPED/PERMISSION_DENIED; binding STALE; join/start/reset 1/1/1; exact authority and installation restored");
        AuthoritySource source = owner(c.server, () -> {
            c.idle();
            if (!c.server.isSingleplayer() || c.server.getSingleplayerProfile() == null
                    || c.server.getPlayerList().getOps().get(c.actor.getGameProfile()) != null) return null;
            return new AuthoritySource(c.server.getSingleplayerProfile(), c.server.getPlayerList().isAllowCheatsForAllPlayers());
        });
        if (source == null) {
            row.addProperty("status", "NOT_COVERED");
            row.addProperty("safe_to_continue", true);
            row.addProperty("precondition", "Requires one current unopped integrated-host player with a captured singleplayer profile; no op/config mutation is attempted");
            return row;
        }
        row.addProperty("has_permission_before", true);
        row.addProperty("allow_cheats_before", source.cheats());
        row.addProperty("op_entry_before", "absent");
        AtomicBoolean changed = new AtomicBoolean();
        AtomicBoolean permissionActuallyLost = new AtomicBoolean();
        Consumer<FPSMapEvent> events = c::event;
        CompletableFuture<Void> tickDone = new CompletableFuture<>();
        Consumer<TickEvent.ServerTickEvent> ticks = event -> {
            if (!c.server.isSameThread() || event.phase != TickEvent.Phase.END || !changed.get() || tickDone.isDone()) return;
            try {
                c.observedTicks++;
                require(c.server.getPlayerList().getPlayer(c.actor.getUUID()) == c.actor
                        && c.actor.connection != null && c.actor.connection.player == c.actor
                        && c.actor.connection.connection.isConnected() && !c.actor.hasPermissions(2)
                        && c.server.getSingleplayerProfile() == null
                        && !c.server.getPlayerList().isAllowCheatsForAllPlayers(), "actual_permission_loss_not_retained_until_tick");
                if (c.session.state() != MinecraftRuntimeTests.State.RUNNING) tickDone.complete(null);
            } catch (RuntimeException | Error failure) { tickDone.completeExceptionally(failure); }
        };
        boolean listening = false;
        try {
            bind(c); WorldBindingCheckResult checked = check(c);
            owner(c.server, () -> {
                try {
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, FPSMapEvent.class, events);
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, TickEvent.ServerTickEvent.class, ticks);
                } catch (RuntimeException | Error failure) {
                    MinecraftForge.EVENT_BUS.unregister(events); MinecraftForge.EVENT_BUS.unregister(ticks); throw failure;
                }
                return null;
            });
            listening = true;
            owner(c.server, () -> {
                c.idle(); c.mode = Mode.RUNNING_TICK; c.generation = c.map.resetGeneration();
                c.attempt = c.tests.newStartAttempt();
                c.session = c.tests.start(c.attempt, c.installed, c.registry, c.binding, checked, () -> { c.suppliers++; return false; });
                require(c.session.state() == MinecraftRuntimeTests.State.RUNNING, "real_RUNNING_required");
                row.add("before_actual_permission_loss", c.snapshot());
                require(c.server.getSingleplayerProfile() == source.profile()
                        && c.server.getPlayerList().isAllowCheatsForAllPlayers() == source.cheats()
                        && c.server.getPlayerList().getOps().get(c.actor.getGameProfile()) == null, "authority_source_changed_before_withdrawal");
                changed.set(true);
                c.server.getPlayerList().setAllowCheatsForAllPlayers(false);
                c.server.setSingleplayerProfile(null);
                row.addProperty("has_permission_during_loss", c.actor.hasPermissions(2));
                permissionActuallyLost.set(!c.actor.hasPermissions(2));
                require(!c.actor.hasPermissions(2), "actual_ServerPlayer_permission_loss_required");
                return null;
            });
            tickDone.get(15, TimeUnit.SECONDS);
            owner(c.server, () -> {
                require(c.observedTicks > 0 && c.session.state() == MinecraftRuntimeTests.State.STOPPED
                        && c.session.diagnostic() instanceof WorldBindingException denied
                        && denied.code() == WorldBindingException.Code.PERMISSION_DENIED,
                        "real_tick_did_not_stop_original_token_with_PERMISSION_DENIED");
                require(c.attempt.retainedSession() == null && c.joins == 1 && c.starts == 1 && c.resets == 1
                        && c.map.resetGeneration() - c.generation == 1 && c.clears == 2 && c.initialClears == 1
                        && c.injections == 0 && !c.map.isStart()
                        && c.map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "permission_loss_native_counts_or_cleanup_mismatch");
                require(c.tests.stop(c.session) == MinecraftRuntimeTests.State.STOPPED
                        && c.map.resetGeneration() - c.generation == 1, "permission_loss_stop_not_idempotent");
                row.add("after_real_tick_before_authority_restoration", c.snapshot());
                return null;
            });
            row.addProperty("status", "PASS");
        } catch (Exception | Error failure) {
            boolean unsupportedSource = changed.get() && !permissionActuallyLost.get()
                    && failure instanceof IllegalStateException
                    && "actual_ServerPlayer_permission_loss_required".equals(failure.getMessage());
            row.addProperty("status", unsupportedSource ? "NOT_COVERED" : "FAIL");
            row.add(unsupportedSource ? "unsupported_authority_source" : "failure", diagnostic(failure));
        } finally {
            final boolean registered = listening;
            try {
                owner(c.server, () -> {
                    try {
                        if (changed.get()) {
                            require(c.server.getSingleplayerProfile() == null || c.server.getSingleplayerProfile() == source.profile(),
                                    "foreign_authority_change_refuses_restoration");
                            c.server.setSingleplayerProfile(source.profile());
                            c.server.getPlayerList().setAllowCheatsForAllPlayers(source.cheats());
                        }
                        require(c.server.getSingleplayerProfile() == source.profile()
                                && c.server.getPlayerList().isAllowCheatsForAllPlayers() == source.cheats()
                                && c.server.getPlayerList().getOps().get(c.actor.getGameProfile()) == null,
                                "exact_original_authority_not_restored");
                        c.exact(); row.addProperty("has_permission_after_restoration", c.actor.hasPermissions(2));
                        row.addProperty("exact_profile_reference_restored", true);
                        row.addProperty("exact_cheats_value_restored", true);
                        return null;
                    } finally {
                        changed.set(false);
                        if (registered) { MinecraftForge.EVENT_BUS.unregister(events); MinecraftForge.EVENT_BUS.unregister(ticks); }
                    }
                });
                owner(c.server, () -> {
                    // Even a failed invalidation oracle must release only our returned token.
                    require(c.finish(row), "permission_cleanup_not_safe");
                    if (permissionActuallyLost.get()) {
                        Throwable invalidated = null;
                        try { c.core.getWorldBindings().verify(c.binding); } catch (RuntimeException | Error failure) { invalidated = failure; }
                        row.add("old_binding_after_authority_restoration", diagnostic(invalidated));
                        require(stale(invalidated), "permission_loss_must_invalidate_old_binding");
                    }
                    return null;
                });
                bind(c);
                owner(c.server, () -> { c.core.getWorldBindings().verify(c.binding); c.idle(); return null; });
                row.addProperty("new_current_binding_restored", true);
                row.addProperty("ui_rebind_required", true);
            } catch (Exception | Error failure) {
                row.addProperty("status", "FAIL"); row.add("restoration_failure", diagnostic(failure));
            }
        }
        try {
            Map<String,String> after = fingerprints(c.root);
            JsonObject proof = owner(c.server, c::installationProof);
            proof.addProperty("installation_files_byte_identical", before.equals(after));
            proof.add("files_before", jsonMap(before)); proof.add("files_after", jsonMap(after));
            row.add("installation_unchanged_proof", proof);
            require(before.equals(after) && proof.get("all_records_equal").getAsBoolean()
                    && proof.get("exact_installed_handle_identical").getAsBoolean()
                    && proof.get("exact_published_map_identical").getAsBoolean(), "permission_loss_installation_changed");
        } catch (Exception | Error failure) { row.addProperty("status", "FAIL"); row.add("proof_failure", diagnostic(failure)); }
        row.addProperty("safe_to_continue", row.get("status").getAsString().equals("PASS"));
        row.addProperty("normal_host_shutdown_required", !row.get("status").getAsString().equals("PASS"));
        return row;
    }

    private record AuthoritySource(GameProfile profile, boolean cheats) {}

    private static final class ShutdownProbe {
        final Context c; final WorldBindingCheckResult checked; final Map<String,String> before; final Path receipt;
        final JsonObject result; final Consumer<FPSMapEvent> events; final Consumer<ServerStoppingEvent> stopping;
        final Consumer<ServerStoppedEvent> stopped;
        int stoppingEvents, stoppedEvents, queuedCalls; boolean enqueued; Throwable failure, refusal;
        ShutdownProbe(Context c, WorldBindingCheckResult checked, Map<String,String> before, Path receipt) {
            this.c=c; this.checked=checked; this.before=before; this.receipt=receipt;
            result=remainingResult(c,"QUEUED_SERVER_SHUTDOWN");
            result.addProperty("result_file",receipt.toString());
            result.addProperty("operation","Real ServerStoppingEvent queues captured start with tell(TickTask); normal host shutdown alone drains it");
            result.addProperty("expected","Queued body runs after exact core/controller shutdown; INACTIVE before supplier/join/start/reset; real ServerStoppedEvent and unchanged installation bytes");
            events=c::event; stopping=this::stopping; stopped=this::stopped;
        }
        void arm() {
            c.idle(); c.core.getWorldBindings().verify(c.binding);
            c.mode=Mode.RUNNING_TICK; c.generation=c.map.resetGeneration(); c.attempt=c.tests.newStartAttempt();
            result.add("precondition",c.snapshot()); result.add("installation_before",c.installationProof());
            try {
                MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,FPSMapEvent.class,events);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,ServerStoppingEvent.class,stopping);
                MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ServerStoppedEvent.class,stopped);
            } catch (RuntimeException | Error problem) { unregister(); throw problem; }
        }
        void stopping(ServerStoppingEvent event) {
            if (event.getServer()!=c.server) return;
            stoppingEvents++;
            try {
                require(stoppingEvents==1,"single_real_stopping_event_required");
                // Normal client shutdown may already have disconnected the captured player.
                // We queue the captured request solely to prove the closed-host refusal; no
                // fresh authority is obtained from this event and no binding is reissued.
                require(c.server.isSameThread() && FPSMCore.getInstance()==c.core && c.core.isActive()
                        && c.core.getServer()==c.server
                        && c.root.equals(c.server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize())
                        && !c.map.isStart() && c.map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(),
                        "exact_idle_host_required_at_stopping");
                JsonObject installed=c.installationProof();
                require(installed.get("all_records_equal").getAsBoolean()
                        && installed.get("exact_installed_handle_identical").getAsBoolean()
                        && installed.get("exact_published_map_identical").getAsBoolean(),"armed_installation_changed_before_stopping");
                result.addProperty("captured_player_current_at_stopping",c.server.getPlayerList().getPlayer(c.actor.getUUID())==c.actor);
                result.addProperty("captured_connection_connected_at_stopping",c.actor.connection!=null && c.actor.connection.connection.isConnected());
                int pending=c.server.getPendingTasksCount();
                c.server.tell(new TickTask(c.server.getTickCount(),this::queuedStart)); enqueued=true;
                result.addProperty("pending_tasks_before_tell",pending);
                result.addProperty("pending_tasks_after_tell",c.server.getPendingTasksCount());
                result.addProperty("queued_body_calls_at_enqueue_return",queuedCalls);
                require(queuedCalls==0 && c.server.getPendingTasksCount()>=pending+1,"native_task_was_not_queued");
            } catch (RuntimeException | Error problem) { failure=problem; }
        }
        void queuedStart() {
            queuedCalls++;
            try {
                require(c.server.isSameThread() && enqueued && stoppingEvents==1 && queuedCalls==1
                        && !c.core.isActive()
                        && c.core.runtimeInstallations().status()==MinecraftRuntimeInstallations.Status.CLOSED,
                        "queued_body_must_follow_normal_exact_core_shutdown");
                result.addProperty("exact_core_inactive_before_queued_start",true);
                result.addProperty("installation_controller_CLOSED_before_queued_start",true);
                try {
                    c.session=c.tests.start(c.attempt,c.installed,c.registry,c.binding,checked,()->{ c.suppliers++; return false; });
                } catch (RuntimeException | Error problem) { refusal=problem; }
                result.add("queued_start_refusal",diagnostic(refusal));
                require(refusal instanceof RuntimeMapLeaseException inactive
                        && inactive.problem().code().toString().equals("editor:runtime_map/inactive")
                        && c.session==null && c.attempt.retainedSession()==null,
                        "queued_start_not_refused_as_INACTIVE_without_token");
            } catch (RuntimeException | Error problem) { failure=problem; }
        }
        void stopped(ServerStoppedEvent event) {
            if (event.getServer()!=c.server) return;
            stoppedEvents++;
            try {
                require(c.server.isSameThread() && c.server.isStopped() && stoppingEvents==1 && stoppedEvents==1
                        && !c.core.isActive() && !FPSMCore.initialized()
                        && c.core.runtimeInstallations().status()==MinecraftRuntimeInstallations.Status.CLOSED,
                        "normal_ServerStopped_and_closed_exact_core_required");
                require(c.suppliers==0 && c.joins==0 && c.starts==0 && c.resets==0 && c.clears==0
                        && c.map.resetGeneration()==c.generation,"queued_shutdown_produced_native_actions");
                Map<String,String> after=fingerprints(c.root);
                JsonObject proof=new JsonObject(); proof.addProperty("installation_files_byte_identical",before.equals(after));
                proof.add("files_before",jsonMap(before)); proof.add("files_after",jsonMap(after));
                result.add("installation_unchanged_proof",proof);
                require(before.equals(after),"shutdown_changed_installation_bytes");
                if (failure!=null) { result.add("queued_failure",diagnostic(failure)); throw new IllegalStateException("queued_shutdown_case_failed",failure); }
                require(enqueued,"stopping_event_did_not_enqueue");
                result.addProperty("status",queuedCalls==1?"PASS":"NOT_COVERED");
                if (queuedCalls==0) result.addProperty("not_covered_reason","Normal host shutdown did not drain the real queued task; no manual drain or replay requested");
                else require(queuedCalls==1 && refusal!=null,"exactly_one_rejected_queued_start_required");
            } catch (Exception | Error problem) { result.addProperty("status","FAIL"); result.add("failure",diagnostic(problem)); }
            finally {
                result.addProperty("real_ServerStopping_events",stoppingEvents); result.addProperty("real_ServerStopped_events",stoppedEvents);
                result.addProperty("real_queued_body_calls",queuedCalls); result.addProperty("native_queue_task_enqueued",enqueued);
                result.addProperty("supplier_calls",c.suppliers); result.addProperty("join_events",c.joins);
                result.addProperty("start_events",c.starts); result.addProperty("reset_events",c.resets);
                result.addProperty("clear_events",c.clears); result.addProperty("manual_queue_drain_requested",false);
                result.addProperty("normal_host_shutdown_observed",true);
                unregister(); EXECUTING.set(false);
                try { Files.writeString(receipt,result.toString(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE); }
                catch (Exception problem) { System.err.println("Q82 queued shutdown receipt write failed at " + receipt + ": " + problem); }
            }
        }
        void unregister() {
            c.mode=null; MinecraftForge.EVENT_BUS.unregister(events);
            MinecraftForge.EVENT_BUS.unregister(stopping); MinecraftForge.EVENT_BUS.unregister(stopped);
        }
    }

    public static JsonObject execute(MinecraftServer server, UUID actorId, ExtensionRegistry registry) throws Exception {
        Objects.requireNonNull(server); Objects.requireNonNull(actorId); Objects.requireNonNull(registry);
        require(!server.isSameThread(), "module_worker_required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"),
                "explicit_development_flags_required");
        require(EXECUTING.compareAndSet(false, true), "authority_probe_already_running");
        try {
            Context c = owner(server, () -> capture(server, actorId, registry));
            JsonObject result = new JsonObject();
            result.addProperty("map", KEY.name()); result.addProperty("server_root", c.root.toString());
            result.addProperty("installation_id", c.record.plan().installationId().toString());
            result.addProperty("commit_id", c.record.plan().commitId().toString());
            result.addProperty("document_id", c.record.plan().documentId().toString());
            result.addProperty("world_id", c.installed.worldId().toString());
            result.addProperty("ui_rebind_required", true);
            result.addProperty("installation_or_project_write_requested", false);
            result.addProperty("manual_runtime_tick_requested", false);
            result.addProperty("gameplay_effects_rolled_back", false);
            result.addProperty("terminal_respawn_authorized", true);
            result.addProperty("native_respawn_gameplay_side_effects_authorized", true);
            result.addProperty("normal_host_shutdown_required_after_terminal_case", true);
            JsonArray rows = new JsonArray(); result.add("cases", rows);
            Map<String, String> diskBefore = fingerprints(c.root);
            Consumer<FPSMapEvent> events = c::event;
            Consumer<TickEvent.ServerTickEvent> ticks = c::tick;
            Consumer<PlayerEvent> playerEvents = c::playerEvent;
            owner(server, () -> {
                try {
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, FPSMapEvent.class, events);
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, TickEvent.ServerTickEvent.class, ticks);
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, PlayerEvent.class, playerEvents);
                } catch (RuntimeException | Error failure) {
                    MinecraftForge.EVENT_BUS.unregister(events); MinecraftForge.EVENT_BUS.unregister(ticks);
                    MinecraftForge.EVENT_BUS.unregister(playerEvents); throw failure;
                }
                return null;
            });
            boolean safe = true;
            try {
                for (Mode mode : Mode.values()) {
                    if (!safe) { rows.add(uncovered(mode.name(), "Earlier case did not restore an idle exact runtime")); continue; }
                    JsonObject row = new JsonObject(); row.addProperty("id", mode.name());
                    row.addProperty("operation", mode.operation); row.addProperty("expected", mode.expected);
                    rows.add(row);
                    try {
                        bind(c);
                        WorldBindingCheckResult check = check(c);
                        owner(server, () -> { c.begin(mode, row, check); return null; });
                        if ((mode == Mode.RUNNING_TICK || mode == Mode.SAME_UUID_NATIVE_RESPAWN) && c.runningTick != null) {
                            // The real core tick must perform cleanup. A timeout is a failure, never
                            // replaced by a manual call to the production tick method.
                            c.runningTick.get(15, TimeUnit.SECONDS);
                        }
                        owner(server, () -> { c.assertOutcome(row); return null; });
                        row.addProperty("status", "PASS");
                    } catch (Exception | Error failure) {
                        row.addProperty("status", "FAIL"); row.add("failure", diagnostic(failure));
                    } finally {
                        safe = owner(server, () -> mode==Mode.SAME_UUID_NATIVE_RESPAWN ? c.finishRespawn(row) : c.finish(row));
                        if (safe && mode!=Mode.SAME_UUID_NATIVE_RESPAWN) {
                            try {
                                bind(c);
                                owner(server, () -> { c.core.getWorldBindings().verify(c.binding); return null; });
                                row.addProperty("new_current_binding_restored", true);
                            } catch (Exception | Error failure) {
                                safe = false; row.addProperty("status", "FAIL");
                                row.add("binding_restoration_failure", diagnostic(failure));
                            }
                        }
                        if (!safe) row.addProperty("status", "FAIL");
                        safe = safe && row.get("status").getAsString().equals("PASS");
                        row.addProperty("safe_to_continue", safe && mode!=Mode.SAME_UUID_NATIVE_RESPAWN);
                    }
                }
            } finally {
                owner(server, () -> {
                    c.mode = null;
                    MinecraftForge.EVENT_BUS.unregister(events); MinecraftForge.EVENT_BUS.unregister(ticks);
                    MinecraftForge.EVENT_BUS.unregister(playerEvents);
                    return null;
                });
            }
            rows.add(uncovered("CURRENT_PERMISSION_LOSS",
                    "No reversible owner-only authority source is exposed for this host. Sending a permission packet or changing a command source does not change ServerPlayer.hasPermissions(2). Removing ops/cheats would change persistent/host configuration and is deliberately not attempted."));
            rows.add(uncovered("QUEUED_SERVER_SHUTDOWN", "This entry does not stop the host or leave queued native work behind."));
            Map<String, String> diskAfter = Map.of();
            boolean diskReadSucceeded = false;
            try { diskAfter = fingerprints(c.root); diskReadSucceeded = true; }
            catch (Exception | Error failure) { result.add("installation_files_after_read_failure", diagnostic(failure)); }
            JsonObject proof = owner(server, c::installationProof);
            proof.addProperty("installation_files_byte_identical", diskReadSucceeded && diskBefore.equals(diskAfter));
            proof.add("files_before", jsonMap(diskBefore)); proof.add("files_after", jsonMap(diskAfter));
            result.add("installation_unchanged_proof", proof);
            result.add("terminal", owner(server, c::snapshot));
            boolean executedPassed = safe && diskReadSucceeded && diskBefore.equals(diskAfter)
                    && proof.get("all_records_equal").getAsBoolean()
                    && proof.get("exact_installed_handle_identical").getAsBoolean()
                    && proof.get("exact_published_map_identical").getAsBoolean();
            for (var value : rows) if (value.getAsJsonObject().get("status").getAsString().equals("FAIL")) executedPassed = false;
            result.addProperty("all_executed_cases_passed", executedPassed);
            result.addProperty("complete_authority_coverage", false);
            return result;
        } finally { EXECUTING.set(false); }
    }

    private enum Mode {
        BEFORE_START("Revoke after physical check, before start", "STALE; join/start/reset 0/0/0"),
        FINAL_SUPPLIER("Revoke in second cancellation-supplier call", "STALE; join/start/reset 0/0/0"),
        JOIN_EVENT("Revoke inside exact actor JoinEvent", "STALE; join/start/reset 1/0/1"),
        START_EVENT("Revoke inside exact map StartEvent", "STALE before teleport/delivery; join/start/reset 1/1/1"),
        CLEAR_EVENT("Revoke inside exact actor ClearEvent before delivery", "STALE; join/start/reset 1/1/1; one initial Clear and one cleanup Clear"),
        RUNNING_TICK("Start RUNNING, revoke, await real Forge server tick", "Original token STOPPED with STALE diagnostic; exactly one reset; repeated stop adds zero resets"),
        SAME_UUID_NATIVE_RESPAWN("Terminal: PlayerList.respawn(original,false), native listener handoff, await real END tick",
                "Different ServerPlayer, same UUID/current connection; original test token STOPPED with DISCONNECTED; join/start/reset 1/1/1, Clone/Respawn 1/1, participants zero, new inventory empty; normal shutdown next");
        final String operation, expected;
        Mode(String operation, String expected) { this.operation=operation; this.expected=expected; }
    }

    private static Context capture(MinecraftServer server, UUID actorId, ExtensionRegistry registry) throws Exception {
        Path root = server.getWorldPath(LevelResource.ROOT).toRealPath();
        String path = root.toString().replace('\\', '/');
        require(path.contains("/.local-artifacts/world-editor/rewrite/platform-batch07/Q82/ui-host-"), "isolated_Q82_ui_host_required");
        Path lexical = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(root.equals(lexical), "aliased_world_root_refused");
        FPSMCore core = FPSMCore.getInstance();
        require(core != null && core.isActive() && core.getServer() == server, "exact_active_core_required");
        var record = core.runtimeInstallations().records().get(KEY);
        require(record != null && record.state() == LocalInstallationIndex.State.ACTIVE, "exact_ACTIVE_record_required");
        var installed = core.runtimeInstallations().captureInstalled(record);
        BaseMap map = core.getMapByTypeWithName(KEY.type(), KEY.name()).orElseThrow();
        ServerPlayer actor = server.getPlayerList().getPlayer(actorId);
        require(actor != null && actor.hasPermissions(2) && server.getPlayerList().getPlayers().size() == 1,
                "single_current_authorized_actor_required");
        require(map.hasRuntimeOwnership(), "runtime_owned_map_required");
        Context c = new Context(server, core, actor, map, registry, record, installed, root);
        c.idle(); return c;
    }

    private static final class Context {
        final MinecraftServer server; final FPSMCore core; final ServerPlayer actor; final BaseMap map;
        final ExtensionRegistry registry; final LocalInstallationIndex.Record record;
        final MinecraftRuntimeInstallations.InstalledRuntime installed; final MinecraftRuntimeTests tests;
        final Map<InstallationPlan.Key, LocalInstallationIndex.Record> records; final Path root;
        WorldBinding binding; Mode mode; MinecraftRuntimeTests.Session session;
        MinecraftRuntimeTests.StartAttempt attempt; Throwable refusal;
        int joins, starts, resets, clears, initialClears, injections, suppliers, observedTicks, supplierAtClear, supplierAtObservation;
        long generation; CompletableFuture<Void> runningTick;
        JsonArray inventoryAtClear, inventoryBeforeGuard;
        JsonObject positionBefore;
        boolean emptyAtClear, nonemptyKit;
        ServerPlayer replacement, clonedPlayer, respawnEventPlayer;
        ServerGamePacketListenerImpl originalListener;
        int respawnCalls, cloneEvents, respawnEvents;
        boolean respawnRequested, listenerHandoff;
        JsonArray oldInventoryAfterRespawn, inventoryAtReset;
        Context(MinecraftServer server, FPSMCore core, ServerPlayer actor, BaseMap map, ExtensionRegistry registry,
                LocalInstallationIndex.Record record, MinecraftRuntimeInstallations.InstalledRuntime installed, Path root) {
            this.server=server; this.core=core; this.actor=actor; this.map=map; this.registry=registry;
            this.record=record; this.installed=installed; this.root=root;
            tests=core.runtimeInstallations().tests(); records=Map.copyOf(core.runtimeInstallations().records());
        }
        void exact() {
            require(server.isSameThread() && FPSMCore.getInstance()==core && core.isActive() && core.getServer()==server
                    && server.getPlayerList().getPlayer(actor.getUUID())==actor && actor.connection!=null
                    && actor.connection.player==actor && actor.connection.connection.isConnected()
                    && actor.hasPermissions(2) && server.getPlayerList().getPlayers().size()==1,
                    "captured_host_actor_authority_changed");
            require(core.getMapByTypeWithName(KEY.type(),KEY.name()).orElse(null)==map
                    && records.equals(core.runtimeInstallations().records())
                    && core.runtimeInstallations().captureInstalled(record)==installed, "captured_installation_changed");
        }
        void idle() {
            exact(); require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "idle_exact_map_required");
            for (var maps : core.getAllMaps().values()) for (BaseMap other : maps)
                require(!other.checkGameHasPlayer(actor) && !other.checkSpecHasPlayer(actor), "actor_already_participating");
        }
        void begin(Mode requested, JsonObject row, WorldBindingCheckResult check) {
            idle(); mode=requested; session=null; refusal=null; runningTick=null;
            joins=starts=resets=clears=initialClears=injections=suppliers=observedTicks=0; generation=map.resetGeneration();
            inventoryAtClear=null; inventoryBeforeGuard=null; positionBefore=position();
            emptyAtClear=false; nonemptyKit=false;
            supplierAtClear=0; supplierAtObservation=0;
            row.add("precondition", snapshot());
            row.add("actor_position_before", positionBefore);
            attempt=tests.newStartAttempt();
            if (mode==Mode.BEFORE_START) revoke();
            try {
                session=tests.start(attempt,installed,registry,binding,check,()->{
                    suppliers++;
                    if (mode==Mode.FINAL_SUPPLIER && suppliers==2) revoke();
                    if (mode==Mode.CLEAR_EVENT && injections==1 && resets==0 && inventoryBeforeGuard==null) {
                        inventoryBeforeGuard=inventory(); supplierAtObservation=suppliers;
                    }
                    return false;
                });
            } catch (RuntimeException | Error failure) { refusal=failure; }
            row.add("start_refusal", diagnostic(refusal));
            row.add("actor_position_after_start", position());
            if (mode==Mode.RUNNING_TICK) {
                require(refusal==null && session!=null && session.state()==MinecraftRuntimeTests.State.RUNNING, "real_RUNNING_required");
                runningTick=new CompletableFuture<>(); revoke();
                row.add("after_revocation_before_tick", snapshot());
            }
            if (mode==Mode.SAME_UUID_NATIVE_RESPAWN) nativeRespawn(row);
        }
        void nativeRespawn(JsonObject row) {
            // All six revocation cases precede this enum value. Reconfirm the exact isolated
            // host and original caller immediately before the one irreversible native call.
            exact(); require(root.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()),"respawn_root_changed");
            require(refusal==null && session!=null && session.state()==MinecraftRuntimeTests.State.RUNNING
                    && session.plan().equals(installed.plan()) && map.isStart() && map.checkGameHasPlayer(actor)
                    && map.getMapTeams().getJoinedPlayersWithSpec().size()==1,"exact_original_RUNNING_token_required");
            for (var maps : core.getAllMaps().values()) for (BaseMap other : maps)
                if (other!=map) require(!other.checkGameHasPlayer(actor) && !other.checkSpecHasPlayer(actor),"respawn_actor_in_other_map");
            row.addProperty("captured_isolated_root_reconfirmed",true);
            row.addProperty("single_original_actor_reconfirmed",true);
            row.addProperty("original_installation_and_RUNNING_token_reconfirmed",true);
            row.addProperty("other_map_memberships_before_respawn",0);
            originalListener=actor.connection; replacement=null; clonedPlayer=null; respawnEventPlayer=null;
            respawnCalls=cloneEvents=respawnEvents=0; listenerHandoff=false; inventoryAtReset=null;
            row.add("old_inventory_before_respawn",inventory(actor));
            row.add("old_inventory_counts_before_respawn",inventoryCounts(actor));
            row.add("original_RUNNING_before_respawn",snapshot());
            runningTick=new CompletableFuture<>(); respawnRequested=true; respawnCalls++;
            replacement=server.getPlayerList().respawn(actor,false);
            // Vanilla handleClientCommand assigns its listener.player from this return value.
            // PlayerList.respawn itself does not perform that assignment in Forge 1.20.1.
            require(replacement!=null && replacement!=actor && replacement.getUUID().equals(actor.getUUID())
                    && replacement.server==server && replacement.connection==originalListener
                    && server.getPlayerList().getPlayer(actor.getUUID())==replacement
                    && (originalListener.player==actor || originalListener.player==replacement),"unexpected_native_respawn_handoff");
            originalListener.player=replacement; listenerHandoff=true;
            requireHandoff();
            oldInventoryAfterRespawn=inventory(actor);
            row.add("old_inventory_after_respawn",oldInventoryAfterRespawn);
            row.add("new_inventory_after_respawn_before_tick",inventory(replacement));
            row.add("new_inventory_counts_after_respawn_before_tick",inventoryCounts(replacement));
            row.add("handoff_before_tick",handoff());
        }
        void requireHandoff() {
            // Deliberately independent of exact(), whose original player identity has expired.
            require(server.isSameThread() && FPSMCore.getInstance()==core && core.isActive() && core.getServer()==server
                    && root.equals(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize()),"terminal_host_changed");
            require(replacement!=null && replacement!=actor && replacement.getUUID().equals(actor.getUUID())
                    && replacement.server==server && server.getPlayerList().getPlayer(actor.getUUID())==replacement
                    && server.getPlayerList().getPlayers().size()==1 && replacement.connection==originalListener
                    && originalListener.player==replacement && originalListener.connection.isConnected()
                    && replacement.hasPermissions(2),"terminal_replacement_handoff_changed");
            JsonObject proof=installationProof();
            require(proof.get("all_records_equal").getAsBoolean() && proof.get("exact_installed_handle_identical").getAsBoolean()
                    && proof.get("exact_published_map_identical").getAsBoolean(),"terminal_exact_installation_changed");
            for (var maps : core.getAllMaps().values()) for (BaseMap other : maps)
                if (other!=map) require(!other.checkGameHasPlayer(replacement) && !other.checkSpecHasPlayer(replacement),"replacement_in_other_map");
        }
        void playerEvent(PlayerEvent event) {
            if (!server.isSameThread() || mode!=Mode.SAME_UUID_NATIVE_RESPAWN || !respawnRequested) return;
            if (event instanceof PlayerEvent.Clone clone && clone.getOriginal()==actor) {
                cloneEvents++; if (clone.getEntity() instanceof ServerPlayer player) clonedPlayer=player;
            }
            if (event instanceof PlayerEvent.PlayerRespawnEvent respawn && respawn.getEntity() instanceof ServerPlayer player
                    && player.getUUID().equals(actor.getUUID())) { respawnEvents++; respawnEventPlayer=player; }
        }
        void revoke() { exact(); core.getWorldBindings().revoke(actor.getUUID()); injections++; }
        void event(FPSMapEvent event) {
            if (!server.isSameThread() || event.getMap()!=map || mode==null) return;
            if (event instanceof FPSMapEvent.PlayerEvent.JoinEvent join && join.getPlayer()==actor) {
                joins++; if (mode==Mode.JOIN_EVENT && injections==0) revoke();
            }
            if (event instanceof FPSMapEvent.StartEvent) {
                starts++; if (mode==Mode.START_EVENT && injections==0) revoke();
            }
            if (event instanceof FPSMapEvent.ResetEvent) {
                resets++;
                if (mode==Mode.SAME_UUID_NATIVE_RESPAWN && replacement!=null) inventoryAtReset=inventory(replacement);
            }
            if (event instanceof FPSMapEvent.ClearEvent) {
                clears++; if (resets==0) initialClears++;
                if (mode==Mode.CLEAR_EVENT && resets==0 && injections==0) {
                    inventoryAtClear=inventory(); emptyAtClear=actor.getInventory().isEmpty();
                    nonemptyKit=hasNonemptyKit(); supplierAtClear=suppliers; revoke();
                }
            }
        }
        void tick(TickEvent.ServerTickEvent event) {
            if (!server.isSameThread() || event.phase!=TickEvent.Phase.END
                    || (mode!=Mode.RUNNING_TICK && mode!=Mode.SAME_UUID_NATIVE_RESPAWN)
                    || runningTick==null || runningTick.isDone()) return;
            observedTicks++;
            try {
                if (mode==Mode.SAME_UUID_NATIVE_RESPAWN) requireHandoff(); else exact();
                if (session.state()!=MinecraftRuntimeTests.State.RUNNING) runningTick.complete(null);
            }
            catch (RuntimeException | Error failure) { runningTick.completeExceptionally(failure); }
        }
        void assertOutcome(JsonObject row) {
            if (mode==Mode.SAME_UUID_NATIVE_RESPAWN) { assertRespawn(row); return; }
            exact(); require(injections==1, "exactly_one_revocation_required");
            if (mode==Mode.RUNNING_TICK) {
                require(observedTicks>0 && session.state()==MinecraftRuntimeTests.State.STOPPED
                        && stale(session.diagnostic()), "real_tick_did_not_clean_original_token_with_STALE");
                require(tests.stop(session)==MinecraftRuntimeTests.State.STOPPED, "idempotent_terminal_stop_failed");
            } else require(session==null && stale(refusal), "revoked_start_not_refused_as_STALE");
            require(attempt.retainedSession()==null, "FAILED_ownership_retained");
            int expectedJoin=(mode==Mode.BEFORE_START || mode==Mode.FINAL_SUPPLIER)?0:1;
            int expectedStart=(mode==Mode.START_EVENT || mode==Mode.CLEAR_EVENT || mode==Mode.RUNNING_TICK)?1:0;
            require(joins==expectedJoin && starts==expectedStart && resets==expectedJoin
                    && map.resetGeneration()-generation==expectedJoin, "native_event_counts_mismatch");
            if (mode==Mode.CLEAR_EVENT) require(clears==2 && initialClears==1, "initial_and_cleanup_Clear_counts_mismatch");
            if (mode==Mode.START_EVENT) require(positionBefore.equals(position()), "actor_moved_after_StartEvent_authority_loss");
            if (mode==Mode.CLEAR_EVENT) {
                row.add("inventory_at_clear_before_revocation",inventoryAtClear);
                row.add("inventory_at_next_supplier_before_guard_and_cleanup",inventoryBeforeGuard);
                row.addProperty("inventory_empty_at_clear",emptyAtClear); row.addProperty("nonempty_start_kit_configured",nonemptyKit);
                row.addProperty("supplier_calls_at_clear",supplierAtClear); row.addProperty("supplier_call_at_observation",supplierAtObservation);
                require(supplierAtObservation==supplierAtClear+1, "inventory_observation_must_be_next_supplier_before_cleanup");
                require(emptyAtClear && nonemptyKit, "empty_inventory_and_nonempty_kit_delivery_oracle_required");
                require(inventoryAtClear!=null && inventoryBeforeGuard!=null
                        && inventoryAtClear.equals(inventoryBeforeGuard), "inventory_changed_before_authority_guard");
            }
            idle(); row.add("actual", snapshot());
        }
        void assertRespawn(JsonObject row) {
            requireHandoff();
            require(respawnCalls==1 && listenerHandoff && cloneEvents==1 && respawnEvents==1
                    && clonedPlayer==replacement && respawnEventPlayer==replacement,"real_native_replacement_events_required");
            require(observedTicks>0 && session.state()==MinecraftRuntimeTests.State.STOPPED
                    && session.diagnostic() instanceof WorldBindingException failure
                    && failure.code()==WorldBindingException.Code.DISCONNECTED,"real_tick_did_not_stop_original_token_after_replacement");
            require(attempt.retainedSession()==null && joins==1 && starts==1 && resets==1
                    && map.resetGeneration()-generation==1 && injections==0 && clears==2 && initialClears==1,
                    "respawn_native_event_counts_mismatch");
            require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty()
                    && !map.checkGameHasPlayer(replacement) && !map.checkSpecHasPlayer(replacement),"respawn_cleanup_participants_remain");
            require(replacement.getInventory().isEmpty() && inventoryAtReset!=null,"new_player_inventory_not_cleared_by_original_map_cleanup");
            row.add("new_inventory_at_ResetEvent_before_cleanup",inventoryAtReset);
            row.add("new_inventory_after_tick_cleanup",inventory(replacement));
            row.add("new_inventory_counts_after_tick_cleanup",inventoryCounts(replacement));
            row.add("old_inventory_after_tick_cleanup",inventory(actor));
            row.addProperty("old_inventory_unchanged_by_tick_cleanup",oldInventoryAfterRespawn.equals(inventory(actor)));
            row.add("actual",snapshot()); row.add("terminal_player_handoff",handoff());
        }
        boolean finishRespawn(JsonObject row) {
            // Never replay reset, replace a player again, rebind with the old actor, or clean
            // a newly discovered object. Even a failed terminal case is handed to host shutdown.
            row.addProperty("old_player_restoration_requested",false);
            row.addProperty("new_binding_after_terminal_requested",false);
            row.addProperty("normal_host_shutdown_required",true);
            row.addProperty("fallback_owned_stop_requested",false);
            try {
                requireHandoff();
                row.add("terminal_observation",snapshot()); row.add("terminal_player_handoff",handoff());
                row.add("installation_unchanged_proof",installationProof());
                require(session!=null && session.state()==MinecraftRuntimeTests.State.STOPPED
                        && attempt.retainedSession()==null && !map.isStart()
                        && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(),"terminal_token_requires_normal_shutdown_cleanup");
                row.addProperty("normal_shutdown_handoff_verified",true); return true;
            } catch (RuntimeException | Error failure) {
                row.add("terminal_handoff_failure",diagnostic(failure));
                row.add("terminal_observation",snapshot()); row.add("terminal_player_handoff",handoff());
                row.addProperty("normal_shutdown_handoff_verified",false); return false;
            } finally { mode=null; }
        }
        JsonObject handoff() {
            JsonObject value=new JsonObject(); value.addProperty("respawn_requested",respawnRequested);
            value.addProperty("respawn_calls",respawnCalls); value.addProperty("clone_events",cloneEvents); value.addProperty("respawn_events",respawnEvents);
            value.addProperty("old_object_identity_hash",System.identityHashCode(actor));
            value.addProperty("new_object_identity_hash",replacement==null?0:System.identityHashCode(replacement));
            value.addProperty("different_object",replacement!=null && replacement!=actor);
            value.addProperty("same_uuid",replacement!=null && replacement.getUUID().equals(actor.getUUID()));
            value.addProperty("old_uuid",actor.getUUID().toString());
            value.addProperty("new_uuid",replacement==null?"none":replacement.getUUID().toString());
            value.addProperty("PlayerList_current_is_new",replacement!=null && server.getPlayerList().getPlayer(actor.getUUID())==replacement);
            value.addProperty("listener_player_is_new",originalListener!=null && replacement!=null && originalListener.player==replacement);
            value.addProperty("same_listener",replacement!=null && replacement.connection==originalListener);
            value.addProperty("listener_handoff_completed",listenerHandoff); return value;
        }
        boolean finish(JsonObject row) {
            try {
                // Cleanup belongs only to our successful returned token; a FAILED reset is never replayed.
                if (session!=null && session.state()==MinecraftRuntimeTests.State.RUNNING) {
                    row.addProperty("fallback_owned_stop_requested", true); tests.stop(session);
                }
                require(attempt==null || attempt.retainedSession()==null, "FAILED_requires_normal_host_shutdown");
                idle(); row.addProperty("owned_session_cleanup_complete", true);
                row.add("restoration", snapshot()); row.add("installation_unchanged_proof", installationProof());
                return true;
            } catch (RuntimeException | Error failure) {
                row.add("cleanup_failure", diagnostic(failure)); row.add("restoration", snapshot()); return false;
            } finally { mode=null; }
        }
        JsonObject snapshot() {
            JsonObject value=new JsonObject(); value.addProperty("map_started",map.isStart());
            value.addProperty("participants",map.getMapTeams().getJoinedPlayersWithSpec().size());
            value.addProperty("session_state",session==null?"none":session.state().name());
            value.add("session_diagnostic",diagnostic(session==null?null:session.diagnostic()));
            value.addProperty("join_events",joins); value.addProperty("start_events",starts); value.addProperty("reset_events",resets);
            value.addProperty("clear_events",clears); value.addProperty("initial_clear_events",initialClears);
            value.addProperty("reset_generation_delta",map.resetGeneration()-generation);
            value.addProperty("revocations",injections); value.addProperty("supplier_calls",suppliers);
            value.addProperty("respawn_calls",respawnCalls); value.addProperty("native_clone_events",cloneEvents);
            value.addProperty("native_respawn_events",respawnEvents);
            value.addProperty("observed_real_server_END_ticks",observedTicks); return value;
        }
        JsonObject position() {
            JsonObject value=new JsonObject(); value.addProperty("x",actor.getX()); value.addProperty("y",actor.getY());
            value.addProperty("z",actor.getZ()); value.addProperty("dimension",actor.level().dimension().location().toString()); return value;
        }
        JsonArray inventory() {
            return inventory(actor);
        }
        JsonArray inventory(ServerPlayer player) {
            JsonArray value=new JsonArray();
            for (int slot=0; slot<player.getInventory().getContainerSize(); slot++) {
                JsonObject stack=new JsonObject(); stack.addProperty("slot",slot);
                stack.addProperty("empty",player.getInventory().getItem(slot).isEmpty());
                stack.addProperty("item_count",player.getInventory().getItem(slot).getCount());
                stack.addProperty("stack_nbt",player.getInventory().getItem(slot).save(new CompoundTag()).toString()); value.add(stack);
            }
            return value;
        }
        JsonObject inventoryCounts(ServerPlayer player) {
            int occupied=0, total=0;
            for (int slot=0; slot<player.getInventory().getContainerSize(); slot++) {
                var stack=player.getInventory().getItem(slot);
                if (!stack.isEmpty()) { occupied++; total+=stack.getCount(); }
            }
            JsonObject value=new JsonObject(); value.addProperty("slots",player.getInventory().getContainerSize());
            value.addProperty("occupied_slots",occupied); value.addProperty("total_item_count",total); return value;
        }
        boolean hasNonemptyKit() {
            for (var team : map.getMapTeams().getNormalTeams()) {
                if (!team.hasPlayer(actor.getUUID())) continue;
                var capability=team.getCapabilityMap().get(StartKitsCapability.class);
                if (capability.isEmpty()) continue;
                var kits=capability.orElseThrow(); var runtime=kits.runtimeKits();
                if (runtime.isPresent()) {
                    var templates=runtime.orElseThrow();
                    for (int index=0; index<templates.templates().size(); index++) if (!templates.isEmpty(index)) return true;
                } else if (kits.getTeamKits().stream().anyMatch(stack->!stack.isEmpty())) return true;
            }
            return false;
        }
        JsonObject installationProof() {
            JsonObject value=new JsonObject(); value.addProperty("all_records_equal",records.equals(core.runtimeInstallations().records()));
            value.addProperty("exact_record_equal",record.equals(core.runtimeInstallations().records().get(KEY)));
            value.addProperty("exact_installed_handle_identical",false);
            try { value.addProperty("exact_installed_handle_identical",core.runtimeInstallations().captureInstalled(record)==installed); }
            catch (RuntimeException | Error failure) { value.add("installed_handle_verification_failure",diagnostic(failure)); }
            value.addProperty("exact_published_map_identical",core.getMapByTypeWithName(KEY.type(),KEY.name()).orElse(null)==map);
            value.addProperty("full_record",record.toString()); return value;
        }
    }

    private static void bind(Context c) throws Exception {
        var request=owner(c.server,()->{ c.exact(); return c.core.getWorldBindings().begin(c.actor.getUUID(),c.installed.plan().dimensions()); });
        var read=request.read(new WindowsFileIdentityProvider(),()->false);
        owner(c.server,()->{ c.exact(); c.binding=c.core.getWorldBindings().accept(read); return null; });
    }
    private static WorldBindingCheckResult check(Context c) throws Exception {
        var physical=owner(c.server,()->{ c.exact(); return c.core.getWorldBindings().check(c.binding); });
        return physical.verify(()->false);
    }
    private static boolean stale(Throwable failure) {
        return failure instanceof WorldBindingException binding && binding.code()==WorldBindingException.Code.STALE;
    }
    private static JsonObject diagnostic(Throwable failure) {
        JsonObject result=new JsonObject(); result.addProperty("thrown",failure!=null);
        if (failure!=null) {
            result.addProperty("type",failure.getClass().getName()); result.addProperty("message",failure.getMessage());
            if (failure instanceof WorldBindingException binding) result.addProperty("binding_code",binding.code().name());
        }
        return result;
    }
    private static JsonObject uncovered(String id, String reason) {
        JsonObject row=new JsonObject(); row.addProperty("id",id); row.addProperty("status","NOT_COVERED");
        row.addProperty("precondition",reason); row.addProperty("operation","none");
        row.addProperty("expected","Requires independent safe host orchestration"); row.addProperty("actual","Not executed");
        row.addProperty("native_actions_requested",0); row.addProperty("restoration","No mutation requested"); return row;
    }
    private static Map<String,String> fingerprints(Path root) throws Exception {
        TreeMap<String,String> result=new TreeMap<>(); Path editor=root.resolve(".editor-platform");
        for (Path top : new Path[]{editor.resolve("world.identity"),editor.resolve("installations.owner"),editor.resolve("installations")}) {
            require(Files.exists(top,LinkOption.NOFOLLOW_LINKS),"installed_disk_node_missing");
            try (var paths=Files.walk(top)) {
                for (Path path : paths.sorted().toList()) {
                    require(!Files.isSymbolicLink(path),"installation_symlink_refused");
                    String key=root.relativize(path).toString().replace('\\','/');
                    if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) result.put(key,"directory");
                    else if (path.equals(editor.resolve("installations/.editor.lock"))) {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"installation_lock_type_refused");
                        result.put(key,"process_lock_existence_only_not_content_evidence");
                    }
                    else {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"installation_special_file_refused");
                        require(Files.size(path)<=512L*1024*1024,"installation_proof_file_budget");
                        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long length=0;
                        try (var stream=Files.newInputStream(path)) {
                            byte[] buffer=new byte[65536]; int count;
                            while ((count=stream.read(buffer))!=-1) { length+=count; require(length<=512L*1024*1024,"installation_proof_file_budget"); digest.update(buffer,0,count); }
                        }
                        result.put(key,length+":"+HexFormat.of().formatHex(digest.digest()));
                    }
                }
            }
        }
        return result;
    }
    private static JsonObject jsonMap(Map<String,String> map) { JsonObject value=new JsonObject(); map.forEach(value::addProperty); return value; }
    private static void require(boolean condition,String reason) { if (!condition) throw new IllegalStateException(reason); }
    private static <T> T owner(MinecraftServer server, Callable<T> action) throws Exception {
        require(!server.isSameThread(),"module_worker_required"); CompletableFuture<T> result=new CompletableFuture<>();
        server.execute(()->{ try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        boolean interrupted=false;
        try {
            for (;;) { try { return result.get(); } catch (InterruptedException waiting) { interrupted=true; } }
        } catch (ExecutionException failure) {
            Throwable cause=failure.getCause(); if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error; throw new IllegalStateException(cause);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
