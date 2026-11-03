package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeInstallations;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime.MinecraftRuntimeTests;
import net.ptcrys.fpsmatch.editor.core.installation.InstallationPlan;
import net.ptcrys.fpsmatch.editor.core.installation.LocalInstallationIndex;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;

/**
 * Explicit development probe for the disposable Q82 UI host, after product/UI testing.
 * Call execute on the module worker with the SAME registry used to install the candidate.
 * begin replaces the actor's UI binding: the UI must bind/check again afterwards.
 * Native join/start/reset can change the actor and entities through normal gameplay callbacks.
 * No document, draft or installation record is written by this probe. Three explicitly
 * authorized support-loss cases replace only (48,-61,59) with AIR in this isolated host,
 * then restore its exact original state only while it still equals the injected AIR state.
 * These fault injections are not evidence of the product's map-authoring workflow.
 * The last case deliberately retains FAILED ownership until normal world/core shutdown.
 */
public final class NativeRuntimeTestFaults {
    private static final InstallationPlan.Key KEY = new InstallationPlan.Key("csdm", "Q82_UI51_SAFE_DM");
    private static final BlockPos SUPPORT = new BlockPos(48, -61, 59);
    private static final BlockState INJECTED_AIR = Blocks.AIR.defaultBlockState();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private NativeRuntimeTestFaults() {}

    /**
     * Independent terminal probe: run only after explicit product recovery of KEY in a fresh
     * disposable host, with the registry used by that installation. This does not run the
     * original eleven cases. No reset/stop cleanup is retried, even on an assertion failure;
     * after this entry the only permitted teardown is normal host/world/core shutdown.
     * SELF_REVIEWED source only; compilation and native execution require separate evidence.
     */
    public static JsonObject executeInternalReset(MinecraftServer server, UUID actorId,
            ExtensionRegistry registry) throws Exception {
        Objects.requireNonNull(server); Objects.requireNonNull(actorId); Objects.requireNonNull(registry);
        require(!server.isSameThread(), "module_worker_required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"),
                "explicit_development_flags_required");
        require(RUNNING.compareAndSet(false,true), "fault_probe_already_running");
        try {
            Context c=owner(server,()->capture(server,actorId,registry));
            Map<String,String> before=internalResetFingerprints(Path.of(c.root));
            JsonObject result=new JsonObject();
            result.addProperty("id","START_EVENT_REENTRANT_RESET_FAILURE");
            result.addProperty("precondition","Explicitly recovered ACTIVE Q82_UI51_SAFE_DM, exact idle map, single real actor, same installation registry, fresh binding and confirmation check");
            result.addProperty("operation","Formal tests.start; exact StartEvent calls original map.reset once; its exact ResetEvent throws one fixed tool exception; observe two stop refusals and one new-start refusal");
            result.addProperty("expected","join/start/reset 1/1/1, generation +1, original attempt retains FAILED; repeated stop and new start busy without further native actions");
            result.addProperty("map",KEY.name()); result.addProperty("server_root",c.root);
            result.addProperty("record_state",c.record.state().name());
            result.addProperty("full_record",c.record.toString());
            result.addProperty("plan",c.record.plan().toString());
            result.addProperty("installation_id",c.record.plan().installationId().toString());
            result.addProperty("commit_id",c.record.plan().commitId().toString());
            result.addProperty("document_id",c.record.plan().documentId().toString());
            result.addProperty("world_id",c.installed.worldId().toString());
            result.addProperty("ui_binding_replaced",false); result.addProperty("ui_rebind_required",true);
            result.addProperty("installation_record_or_draft_mutation_requested",false);
            result.addProperty("world_edit_requested",false);
            result.addProperty("normal_host_shutdown_required",true);
            result.addProperty("cleanup_retry_permitted",false);
            result.addProperty("intended_terminal_state","FAILED retained until normal world/core shutdown; no rollback claim");
            result.addProperty("coverage","START_EVENT_REENTRANT_RESET_FAILURE only");
            result.addProperty("map_automatic_spawn_failure_reset_covered",false);
            result.addProperty("automatic_branch_limitation","DM ready guard rechecks spawn configuration after StartEvent; empty/unsafe spawn injection is rejected before setTeamSpawnPoints. No post-ready native fault seam is introduced.");
            result.addProperty("full_TEST_06_coverage",false);
            result.addProperty("review","SELF_REVIEWED; execution status is reported separately");
            var request=owner(server,()->{
                c.requireIdle();
                return c.core.getWorldBindings().begin(actorId,c.installed.plan().dimensions());
            });
            result.addProperty("ui_binding_replaced",true);
            var read=request.read(new WindowsFileIdentityProvider(),()->false);
            c.binding=owner(server,()->c.core.getWorldBindings().accept(read));
            var inspection=owner(server,()->c.core.getWorldBindings().check(c.binding));
            var inspected=inspection.verify(()->false);
            owner(server,()->{
                c.requireIdle();
                require(c.tests.inspect(c.installed,c.registry,c.binding,inspected).isEmpty(),
                        "same_registry_native_preflight_must_be_clean");
                return null;
            });
            result.addProperty("same_registry_native_preflight_passed",true);
            // inspect consumes its check; start receives a separate physical confirmation.
            var confirmation=owner(server,()->c.core.getWorldBindings().check(c.binding));
            var check=confirmation.verify(()->false);
            result.addProperty("fresh_binding_and_separate_confirmation_check",true);
            InternalResetProbe probe=new InternalResetProbe(c);
            Consumer<FPSMapEvent> listener=probe::event;
            try {
                owner(server,()->{
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST,true,FPSMapEvent.class,listener);
                    return null;
                });
                result.add("case",owner(server,()->probe.run(check)));
            } finally {
                owner(server,()->{ MinecraftForge.EVENT_BUS.unregister(listener); return null; });
            }
            boolean bytesUnchanged=false;
            result.add("files_before",internalResetJsonMap(before));
            try {
                var after=internalResetFingerprints(Path.of(c.root));
                result.add("files_after",internalResetJsonMap(after));
                bytesUnchanged=before.equals(after);
            } catch (Exception | Error failure) { result.add("files_after_failure",diagnostic(failure)); }
            result.addProperty("installation_bytes_identical",bytesUnchanged);
            result.addProperty("project_bytes_verified",false);
            result.addProperty("all_executed_cases_passed",bytesUnchanged
                    && result.getAsJsonObject("case").get("status").getAsString().equals("PASS"));
            result.add("final_observation",owner(server,c::snapshot));
            return result;
        } finally { RUNNING.set(false); }
    }

    private static final class InternalResetProbe {
        final Context c;
        int joins,starts,resets,resetRequests,injections;
        boolean startCall,inStartEventReset;
        boolean nativeStartFrame,nativeResetFrame,coordinatorStartFrame;
        boolean startCallAtReset,inStartEventResetAtReset;
        long generation;
        long generationAtReset;
        JsonArray resetStack=new JsonArray();
        InternalResetProbe(Context c) { this.c=c; }

        void event(FPSMapEvent event) {
            if (!c.server.isSameThread() || event.getMap()!=c.map) return;
            if (event instanceof FPSMapEvent.PlayerEvent.JoinEvent join && join.getPlayer()==c.actor) joins++;
            if (event instanceof FPSMapEvent.StartEvent) {
                starts++;
                c.requireExact();
                require(startCall && starts==1 && resetRequests==0 && c.map.checkGameHasPlayer(c.actor),
                        "exact_first_native_StartEvent_required");
                resetRequests++;
                inStartEventReset=true;
                try { c.map.reset(); }
                finally { inStartEventReset=false; }
            }
            if (event instanceof FPSMapEvent.ResetEvent) {
                resets++;
                if (!startCall || !inStartEventReset || resetRequests!=1 || injections!=0) return;
                startCallAtReset=startCall; inStartEventResetAtReset=inStartEventReset;
                generationAtReset=c.map.resetGeneration();
                StackWalker.getInstance().forEach(frame->{
                    String type=frame.getClassName(), method=frame.getMethodName();
                    resetStack.add(type+"."+method+":"+frame.getLineNumber());
                    if (type.equals("net.ptcrys.blockoffensive.map.CSDeathMatchMap") && method.equals("start")) nativeStartFrame=true;
                    if (type.equals(BaseMap.class.getName()) && method.equals("reset")) nativeResetFrame=true;
                    if (type.equals(MinecraftRuntimeTests.class.getName()) && method.equals("start")) coordinatorStartFrame=true;
                });
                injections++;
                throw new InjectedResetFailure();
            }
        }

        JsonObject run(WorldBindingCheckResult check) {
            JsonObject row=new JsonObject(); row.addProperty("id","START_EVENT_REENTRANT_RESET_FAILURE");
            c.requireIdle(); generation=c.map.resetGeneration();
            row.add("precondition",c.snapshot());
            var attempt=c.tests.newStartAttempt();
            MinecraftRuntimeTests.Session returned=null;
            Throwable startFailure=null;
            boolean passed=false;
            try {
                startCall=true;
                try { returned=c.tests.start(attempt,c.installed,c.registry,c.binding,check,()->false); }
                catch (RuntimeException | Error failure) { startFailure=failure; }
                finally { startCall=false; }
                c.retained=attempt.retainedSession();
                row.add("start_failure",diagnostic(startFailure));
                row.addProperty("start_returned_session",returned!=null);
                row.addProperty("original_attempt_retained_FAILED",c.retained!=null
                        && c.retained.state()==MinecraftRuntimeTests.State.FAILED);
                row.add("after_start",c.snapshot());
                require(returned==null && containsInjected(startFailure),"internal_reset_failure_not_propagated");
                require(c.retained!=null && c.retained.state()==MinecraftRuntimeTests.State.FAILED,
                        "original_start_attempt_did_not_retain_FAILED");
                row.addProperty("retained_diagnostic_is_original_start_failure",c.retained.diagnostic()==startFailure);
                require(c.retained.diagnostic()==startFailure,"retained_original_failure_identity_lost");
                require(nativeStartFrame && nativeResetFrame && coordinatorStartFrame,
                        "reset_must_be_inside_native_and_coordinator_start_stack");
                require(startCallAtReset && inStartEventResetAtReset && generationAtReset-generation==1,
                        "generation_must_advance_inside_StartEvent_reset");
                requireCounts();
                var original=c.retained;
                Throwable stop=thrown(()->c.tests.stop(original));
                row.add("stop_refusal",diagnostic(stop)); row.add("after_stop_refusal",c.snapshot());
                require(code(stop,"busy"),"FAILED_stop_not_busy"); requireCounts();
                Throwable repeated=thrown(()->c.tests.stop(original));
                row.add("repeat_stop_refusal",diagnostic(repeated)); row.add("after_repeat_stop_refusal",c.snapshot());
                require(code(repeated,"busy"),"FAILED_repeat_stop_not_busy"); requireCounts();
                var next=c.tests.newStartAttempt();
                Throwable busy=thrown(()->c.tests.start(next,c.installed,c.registry,c.binding,check,()->false));
                row.add("new_start_refusal",diagnostic(busy));
                row.addProperty("new_attempt_retained_session_null",next.retainedSession()==null);
                require(code(busy,"busy") && next.retainedSession()==null,"FAILED_new_start_not_busy_or_stole_token");
                requireCounts(); c.requireExact();
                require(attempt.retainedSession()==original && original.state()==MinecraftRuntimeTests.State.FAILED
                        && original.diagnostic()==startFailure,"original_FAILED_ownership_or_diagnostic_changed");
                row.addProperty("original_attempt_retained_identity_after_refusals",true);
                passed=true;
            } catch (RuntimeException | Error failure) {
                row.add("assertion_or_unexpected_failure",diagnostic(failure));
                c.retained=attempt.retainedSession();
                // Terminal probe: no reset, stop, replacement, or manual tick cleanup here.
            } finally { startCall=false; }
            row.addProperty("status",passed?"PASS":"FAIL");
            JsonObject counts=new JsonObject(); counts.addProperty("join_events",joins); counts.addProperty("start_events",starts);
            counts.addProperty("reset_events",resets); counts.addProperty("reset_requests_from_StartEvent",resetRequests);
            counts.addProperty("reset_generation_delta",c.map.resetGeneration()-generation); counts.addProperty("injections",injections);
            counts.addProperty("reset_generation_delta_at_ResetEvent",generationAtReset-generation);
            row.add("counts",counts); row.add("reset_event_stack",resetStack);
            row.addProperty("formal_start_call_in_progress_at_ResetEvent",startCallAtReset);
            row.addProperty("StartEvent_reset_call_in_progress_at_ResetEvent",inStartEventResetAtReset);
            row.addProperty("reset_inside_native_start_stack",nativeStartFrame && nativeResetFrame && coordinatorStartFrame);
            row.add("actual",c.snapshot()); row.addProperty("safe_to_continue",false);
            row.addProperty("normal_host_shutdown_required",true); row.addProperty("cleanup_retry_requested",false);
            return row;
        }
        void requireCounts() {
            require(joins==1 && starts==1 && resets==1 && resetRequests==1 && injections==1
                    && c.map.resetGeneration()-generation==1,"internal_reset_replayed_or_wrong_event_counts");
        }
    }

    private static Map<String,String> internalResetFingerprints(Path root) throws Exception {
        require(root.toRealPath().equals(root.toAbsolutePath().normalize()),"aliased_world_root_refused");
        Map<String,String> result=new TreeMap<>();
        Path installations=root.resolve(".editor-platform/installations");
        for (Path top:new Path[]{root.resolve(".editor-platform/world.identity"),
                root.resolve(".editor-platform/installations.owner"),installations}) {
            require(Files.exists(top,LinkOption.NOFOLLOW_LINKS),"persistent_proof_node_missing");
            try (var paths=Files.walk(top)) {
                for (Path path:paths.sorted().toList()) {
                    require(!Files.isSymbolicLink(path),"persistent_proof_symlink_refused");
                    String key=path.toAbsolutePath().normalize().toString().replace('\\','/');
                    if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) result.put(key,"directory");
                    else if (path.equals(installations.resolve(".editor.lock"))) {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"persistent_proof_lock_type");
                        result.put(key,"process_lock_existence_only_not_content_evidence");
                    } else {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)<=512L*1024*1024,
                                "persistent_proof_file_budget");
                        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long size=0;
                        try (var stream=Files.newInputStream(path)) {
                            byte[] bytes=new byte[65536]; int count;
                            while ((count=stream.read(bytes))!=-1) {
                                size+=count; require(size<=512L*1024*1024,"persistent_proof_file_budget"); digest.update(bytes,0,count);
                            }
                        }
                        result.put(key,size+":"+HexFormat.of().formatHex(digest.digest()));
                    }
                }
            }
        }
        return result;
    }
    private static JsonObject internalResetJsonMap(Map<String,String> values) {
        JsonObject result=new JsonObject(); values.forEach(result::addProperty); return result;
    }

    /** No client or server owner may block waiting for this method. */
    public static JsonObject execute(MinecraftServer server, UUID actorId, ExtensionRegistry registry) throws Exception {
        Objects.requireNonNull(server); Objects.requireNonNull(actorId); Objects.requireNonNull(registry);
        if (server.isSameThread()) throw new IllegalStateException("module_worker_required");
        if (!Boolean.getBoolean("editorViewportProbe.enabled") || !Boolean.getBoolean("mcagent.testMode"))
            throw new IllegalStateException("explicit_development_flags_required");
        if (!RUNNING.compareAndSet(false, true)) throw new IllegalStateException("fault_probe_already_running");
        try {
            Context c = owner(server, () -> capture(server, actorId, registry));
            JsonObject result = new JsonObject();
            result.addProperty("map", KEY.name());
            result.addProperty("server_root", c.root);
            result.addProperty("record_state", c.record.state().name());
            result.addProperty("plan", c.record.plan().toString());
            result.addProperty("installation_id", c.record.plan().installationId().toString());
            result.addProperty("commit_id", c.record.plan().commitId().toString());
            result.addProperty("document_id", c.record.plan().documentId().toString());
            result.addProperty("world_id", c.installed.worldId().toString());
            result.addProperty("exact_active_record_captured", true);
            result.addProperty("ui_binding_replaced", false);
            result.addProperty("ui_rebind_required", true);
            result.addProperty("intended_terminal_state", "FAILED until normal world/core shutdown");
            result.addProperty("installation_record_or_draft_mutation_requested", false);
            result.addProperty("authorized_isolated_support_fault_injection", true);
            result.addProperty("product_map_authoring_acceptance", false);
            JsonArray cases = new JsonArray(); result.add("cases", cases);
            // Disk reads and physical checks deliberately run here, on the module worker.
            var request = owner(server, () -> {
                c.requireExact();
                return c.core.getWorldBindings().begin(actorId, c.installed.plan().dimensions());
            });
            result.addProperty("ui_binding_replaced", true);
            var read = request.read(new WindowsFileIdentityProvider(), () -> false);
            c.binding = owner(server, () -> c.core.getWorldBindings().accept(read));
            Consumer<FPSMapEvent> listener = c::event;
            try {
                owner(server, () -> {
                    MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, FPSMapEvent.class, listener);
                    return null;
                });
                boolean proceed = true;
                for (Mode mode : Mode.values()) {
                    if (!proceed) {
                        cases.add(uncovered(mode.id, "Previous case did not leave an idle exact runtime; no further native action"));
                        continue;
                    }
                    var physical = owner(server, () -> c.core.getWorldBindings().check(c.binding));
                    WorldBindingCheckResult check = physical.verify(() -> false);
                    JsonObject observed = owner(server, () -> c.run(mode, check));
                    cases.add(observed);
                    proceed = observed.get("safe_to_continue").getAsBoolean();
                }
                cases.add(uncovered("start_internal_reset", "Not induced: reaching the DM native spawn-assignment failure would require changing installed spawn configuration. The final stop-reset failure is covered separately."));
                cases.add(uncovered("foreign_attempt", "No second live native test coordinator is created in this single-core isolated host; opaque attempts cannot be fabricated without reflection."));
                cases.add(uncovered("authority_and_replacement_races", "Disconnect, same-UUID actor replacement, permission loss, lease replacement and queued shutdown races require separate host orchestration."));
                result.add("final_observation", owner(server, c::snapshot));
                boolean allCoveredPassed = true;
                for (var item : cases) {
                    var row = item.getAsJsonObject();
                    if (row.get("status").getAsString().equals("FAIL")) allCoveredPassed = false;
                }
                result.addProperty("all_executed_cases_passed", allCoveredPassed);
                result.addProperty("full_TEST_04_05_06_07_coverage", false);
                return result;
            } finally {
                owner(server, () -> { MinecraftForge.EVENT_BUS.unregister(listener); return null; });
            }
        } finally { RUNNING.set(false); }
    }

    private static Context capture(MinecraftServer server, UUID actorId, ExtensionRegistry registry) {
        String root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString().replace('\\', '/');
        if (!root.contains("/Q82/ui-host-")) throw new IllegalStateException("isolated_Q82_ui_host_required");
        FPSMCore core = FPSMCore.getInstance();
        if (core == null || !core.isActive() || core.getServer() != server) throw new IllegalStateException("exact_active_core_required");
        var installations = core.runtimeInstallations();
        var record = installations.records().get(KEY);
        if (record == null || record.state() != LocalInstallationIndex.State.ACTIVE)
            throw new IllegalStateException("exact_ACTIVE_record_required");
        var installed = installations.captureInstalled(record);
        BaseMap map = core.getMapByTypeWithName(KEY.type(), KEY.name()).orElseThrow();
        ServerPlayer actor = server.getPlayerList().getPlayer(actorId);
        if (actor == null || server.getPlayerList().getPlayers().size() != 1)
            throw new IllegalStateException("single_exact_actor_required");
        if (!map.hasRuntimeOwnership()) throw new IllegalStateException("runtime_owned_map_required");
        Context c = new Context(server, core, map, actor, registry, record, installed, root);
        c.requireIdle();
        return c;
    }

    private enum Mode {
        CANCEL_BEFORE_JOIN("cancel_before_join", "Supplier cancels before admission; zero joins/starts/resets"),
        CANCEL_AFTER_JOIN("cancel_after_join", "Supplier observes admitted actor then cancels; one join, zero starts, one reset"),
        CANCEL_JOIN_EVENT("cancel_join_event", "Cancel the exact actor JoinEvent once; no start, one cleanup reset"),
        CANCEL_START_EVENT("cancel_start_event", "Cancel exact StartEvent once; refuse RUNNING and reset once"),
        REMOVE_ACTOR_AT_START("remove_actor_at_start", "Leave exact actor inside StartEvent; refuse false RUNNING and reset once"),
        CANCEL_AT_CLEAR("cancel_at_clear_before_gear", "ClearEvent requests cancellation; next supplier observes unchanged empty inventory before reset or gear delivery, join/start/reset 1/1/1"),
        BUSY_AND_STOP("busy_attempt_and_normal_stop", "A RUNNING; B rejected busy with no retained token; A remains RUNNING; stop once, repeated stop no reset; consumed attempt rejected"),
        SUPPORT_AT_SUPPLIER("support_removed_at_final_supplier", "On supplier call 2 remove the unique spawn support; reject spawn_unsafe before admission/teleport, join/start/reset 0/0/0"),
        SUPPORT_AT_JOIN("support_removed_at_join_event", "Remove unique spawn support inside JoinEvent; reject spawn_unsafe before start/teleport, join/start/reset 1/0/1"),
        SUPPORT_AT_START("support_removed_at_start_event", "Remove unique spawn support inside StartEvent; native ready guard rejects spawn_unsafe before teleport, join/start/reset 1/1/1"),
        RESET_THROWS_LAST("stop_reset_throws_last", "Start A, throw once in its stop ResetEvent; FAILED retained; stop/new start refused busy without a second reset");
        final String id, expected;
        Mode(String id, String expected) { this.id = id; this.expected = expected; }
        boolean supportFault() { return this==SUPPORT_AT_SUPPLIER || this==SUPPORT_AT_JOIN || this==SUPPORT_AT_START; }
    }

    private static final class Context {
        final MinecraftServer server;
        final FPSMCore core;
        final BaseMap map;
        final ServerPlayer actor;
        final ExtensionRegistry registry;
        final LocalInstallationIndex.Record record;
        final MinecraftRuntimeInstallations.InstalledRuntime installed;
        final MinecraftRuntimeTests tests;
        final String root;
        WorldBinding binding;
        Mode mode;
        int joins, starts, resets, injections, supplierCalls;
        MinecraftRuntimeTests.Session retained;
        ServerLevel supportLevel;
        BlockState supportOriginal;
        boolean supportWriteRequested;
        JsonObject supportEvidence;
        boolean cancelledAtClear;
        int clears, clearsBeforeReset, clearsDuringReset;
        JsonArray inventoryAtClear, inventoryAtCancellation;
        JsonObject clearEvidence;

        Context(MinecraftServer server, FPSMCore core, BaseMap map, ServerPlayer actor, ExtensionRegistry registry,
                LocalInstallationIndex.Record record, MinecraftRuntimeInstallations.InstalledRuntime installed, String root) {
            this.server=server; this.core=core; this.map=map; this.actor=actor; this.registry=registry;
            this.record=record; this.installed=installed; this.root=root; tests=core.runtimeInstallations().tests();
        }

        void requireExact() {
            if (!server.isSameThread() || FPSMCore.getInstance()!=core || !core.isActive() || core.getServer()!=server
                    || server.getPlayerList().getPlayer(actor.getUUID())!=actor
                    || core.getMapByTypeWithName(KEY.type(), KEY.name()).orElse(null)!=map
                    || !record.equals(core.runtimeInstallations().records().get(KEY)))
                throw new IllegalStateException("captured_native_identity_changed");
            if (core.runtimeInstallations().captureInstalled(record)!=installed)
                throw new IllegalStateException("captured_installation_changed");
        }

        void requireIdle() {
            requireExact();
            if (map.isStart() || !map.getMapTeams().getJoinedPlayersWithSpec().isEmpty() || retained!=null)
                throw new IllegalStateException("idle_exact_map_required_no_foreign_cleanup");
            for (var maps : core.getAllMaps().values()) for (BaseMap other : maps)
                if (other.checkGameHasPlayer(actor) || other.checkSpecHasPlayer(actor))
                    throw new IllegalStateException("actor_already_participating_no_foreign_cleanup");
        }

        void event(FPSMapEvent event) {
            if (!server.isSameThread() || event.getMap()!=map || mode==null) return;
            if (event instanceof FPSMapEvent.PlayerEvent.JoinEvent join && join.getPlayer()==actor) {
                joins++;
                if (mode==Mode.CANCEL_JOIN_EVENT && injections++==0) event.setCanceled(true);
                if (mode==Mode.SUPPORT_AT_JOIN) removeSupport();
            }
            if (event instanceof FPSMapEvent.StartEvent) {
                starts++;
                if (mode==Mode.CANCEL_START_EVENT && injections++==0) event.setCanceled(true);
                if (mode==Mode.REMOVE_ACTOR_AT_START && injections++==0) map.leave(actor);
                if (mode==Mode.SUPPORT_AT_START) removeSupport();
            }
            if (event instanceof FPSMapEvent.ResetEvent) {
                resets++;
                if (mode==Mode.RESET_THROWS_LAST && injections++==0)
                    throw new InjectedResetFailure();
            }
            if (event instanceof FPSMapEvent.ClearEvent && mode==Mode.CANCEL_AT_CLEAR) {
                clears++;
                if(resets==0)clearsBeforeReset++;else clearsDuringReset++;
                if (!cancelledAtClear) {
                    inventoryAtClear=inventory();
                    clearEvidence.add("inventory_at_clear_event",inventoryAtClear);
                    clearEvidence.addProperty("inventory_at_clear_empty",actor.getInventory().isEmpty());
                    clearEvidence.addProperty("nonempty_start_kit_configured",hasNonemptyStartKit());
                    clearEvidence.addProperty("supplier_calls_at_clear",supplierCalls);
                    cancelledAtClear=true;
                    injections++;
                }
            }
        }

        JsonObject run(Mode requested, WorldBindingCheckResult check) {
            if (requested.supportFault()) return runSupport(requested,check);
            JsonObject row = new JsonObject(); row.addProperty("id", requested.id);
            row.addProperty("action", requested.name()); row.addProperty("expected", requested.expected);
            row.add("precondition", snapshot());
            long generation = map.resetGeneration();
            joins=starts=resets=injections=supplierCalls=0; mode=requested;
            cancelledAtClear=false; clears=clearsBeforeReset=clearsDuringReset=0; inventoryAtClear=inventoryAtCancellation=null;
            clearEvidence=new JsonObject();
            if (requested==Mode.CANCEL_AT_CLEAR) row.add("before_gear_evidence",clearEvidence);
            boolean passed=false;
            MinecraftRuntimeTests.Session owned = null;
            var attempt = tests.newStartAttempt();
            try {
                requireIdle();
                if (requested==Mode.BUSY_AND_STOP || requested==Mode.RESET_THROWS_LAST) {
                    owned=tests.start(attempt,installed,registry,binding,check,()->false);
                    require(owned.state()==MinecraftRuntimeTests.State.RUNNING && map.isStart() && map.checkGameHasPlayer(actor), "A_not_RUNNING");
                    if (requested==Mode.BUSY_AND_STOP) {
                        var b=tests.newStartAttempt();
                        Throwable busy=thrown(()->tests.start(b,installed,registry,binding,check,()->false));
                        row.add("B_refusal", diagnostic(busy));
                        row.addProperty("B_retained_session_null", b.retainedSession()==null);
                        row.addProperty("A_still_running_after_B", owned.state()==MinecraftRuntimeTests.State.RUNNING);
                        require(code(busy,"busy") && b.retainedSession()==null && owned.state()==MinecraftRuntimeTests.State.RUNNING, "B_stole_or_changed_A");
                        require(tests.stop(owned)==MinecraftRuntimeTests.State.STOPPED, "normal_stop_not_STOPPED");
                        require(tests.stop(owned)==MinecraftRuntimeTests.State.STOPPED, "repeated_stop_not_idempotent");
                        Throwable duplicate=thrown(()->tests.start(attempt,installed,registry,binding,check,()->false));
                        row.add("duplicate_attempt_refusal", diagnostic(duplicate));
                        require(code(duplicate,"stale") && attempt.retainedSession()==null, "duplicate_attempt_not_rejected");
                        require(joins==1 && starts==1 && resets==1 && map.resetGeneration()-generation==1, "normal_stop_counts");
                        requireIdle();
                    } else {
                        MinecraftRuntimeTests.Session a=owned;
                        Throwable stop=thrown(()->tests.stop(a));
                        row.add("stop_failure", diagnostic(stop));
                        retained=attempt.retainedSession();
                        require(stop!=null && containsInjected(stop) && retained==a && a.state()==MinecraftRuntimeTests.State.FAILED, "failed_cleanup_not_retained");
                        Throwable repeated=thrown(()->tests.stop(a));
                        var b=tests.newStartAttempt();
                        Throwable busy=thrown(()->tests.start(b,installed,registry,binding,check,()->false));
                        row.add("repeat_stop_refusal", diagnostic(repeated)); row.add("new_start_refusal", diagnostic(busy));
                        row.addProperty("B_retained_session_null", b.retainedSession()==null);
                        require(code(repeated,"busy") && code(busy,"busy") && b.retainedSession()==null, "FAILED_did_not_block_retry");
                        require(joins==1 && starts==1 && resets==1 && injections==1 && map.resetGeneration()-generation==1, "failed_stop_repeated_reset");
                        require(a.state()==MinecraftRuntimeTests.State.FAILED && attempt.retainedSession()==a, "FAILED_ownership_lost");
                    }
                } else {
                    BooleanSupplier cancel=()->{
                        supplierCalls++;
                        if (requested==Mode.CANCEL_AT_CLEAR && cancelledAtClear) {
                            if (inventoryAtCancellation==null) {
                                // Observe synchronously in the guard, before its cancellation exception
                                // can trigger reset. The old post-start guard sees delivered kits here.
                                inventoryAtCancellation=inventory();
                                clearEvidence.add("inventory_at_first_cancel_observation",inventoryAtCancellation);
                                clearEvidence.addProperty("supplier_call_at_observation",supplierCalls);
                                clearEvidence.addProperty("reset_events_at_observation",resets);
                                clearEvidence.addProperty("reset_generation_delta_at_observation",map.resetGeneration()-generation);
                                clearEvidence.addProperty("map_started_at_observation",map.isStart());
                                clearEvidence.addProperty("actor_admitted_at_observation",map.checkGameHasPlayer(actor));
                            }
                            return true;
                        }
                        return requested==Mode.CANCEL_BEFORE_JOIN
                                || (requested==Mode.CANCEL_AFTER_JOIN && map.checkGameHasPlayer(actor));
                    };
                    try { owned=tests.start(attempt,installed,registry,binding,check,cancel); }
                    catch (RuntimeException | Error rejected) {
                        row.add("start_refusal", diagnostic(rejected));
                        if (requested==Mode.CANCEL_BEFORE_JOIN || requested==Mode.CANCEL_AFTER_JOIN || requested==Mode.CANCEL_AT_CLEAR)
                            require(rejected instanceof CancellationException,"wrong_cancellation_failure");
                        else require(code(rejected,"start_rejected"),"wrong_native_rejection");
                    }
                    require(owned==null,"false_RUNNING_returned");
                    require(attempt.retainedSession()==null,"unexpected_FAILED_ownership");
                    int expectedReset=requested==Mode.CANCEL_BEFORE_JOIN?0:1;
                    int expectedStart=(requested==Mode.CANCEL_START_EVENT || requested==Mode.REMOVE_ACTOR_AT_START || requested==Mode.CANCEL_AT_CLEAR)?1:0;
                    require(joins==(expectedReset==0?0:1) && starts==expectedStart && resets==expectedReset
                            && map.resetGeneration()-generation==expectedReset,"rejection_counts");
                    if (requested==Mode.CANCEL_JOIN_EVENT || requested==Mode.CANCEL_START_EVENT || requested==Mode.REMOVE_ACTOR_AT_START)
                        require(injections==1,"injection_not_observed_once");
                    if (requested==Mode.CANCEL_AT_CLEAR) {
                        require(clears==2 && clearsBeforeReset==1 && clearsDuringReset==1
                                && injections==1 && inventoryAtClear!=null && inventoryAtCancellation!=null,"start_and_reset_clear_counts");
                        require(clearEvidence.get("inventory_at_clear_empty").getAsBoolean()
                                && clearEvidence.get("nonempty_start_kit_configured").getAsBoolean(),"empty_inventory_and_nonempty_kit_required_for_delivery_oracle");
                        require(clearEvidence.get("reset_events_at_observation").getAsInt()==0
                                && clearEvidence.get("reset_generation_delta_at_observation").getAsLong()==0
                                && clearEvidence.get("supplier_call_at_observation").getAsInt()
                                        ==clearEvidence.get("supplier_calls_at_clear").getAsInt()+1
                                && clearEvidence.get("map_started_at_observation").getAsBoolean()
                                && clearEvidence.get("actor_admitted_at_observation").getAsBoolean(),"clear_observation_must_precede_cleanup");
                        boolean unchanged=inventoryAtClear.equals(inventoryAtCancellation);
                        clearEvidence.addProperty("inventory_unchanged_before_cleanup",unchanged);
                        require(unchanged,"gear_delivered_after_clear_cancellation_before_guard");
                    }
                    requireIdle();
                    Throwable duplicate=thrown(()->tests.start(attempt,installed,registry,binding,check,()->false));
                    row.add("duplicate_attempt_refusal", diagnostic(duplicate));
                    require(code(duplicate,"stale") && attempt.retainedSession()==null,"failed_attempt_reused");
                }
                passed=true;
            } catch (RuntimeException | Error failure) {
                row.add("assertion_or_unexpected_failure",diagnostic(failure));
                retained=attempt.retainedSession();
                // Cleanup is restricted to a session actually returned to this probe.
                // Never replay a FAILED reset, and never reset a map based on its name alone.
                if (owned!=null && owned.state()==MinecraftRuntimeTests.State.RUNNING) {
                    try { tests.stop(owned); }
                    catch (RuntimeException | Error cleanup) { row.add("owned_session_cleanup_failure",diagnostic(cleanup)); retained=attempt.retainedSession(); }
                }
            } finally { mode=null; }
            row.addProperty("status",passed?"PASS":"FAIL");
            JsonObject counts=new JsonObject(); counts.addProperty("join_events",joins); counts.addProperty("start_events",starts);
            counts.addProperty("reset_events",resets); counts.addProperty("reset_generation_delta",map.resetGeneration()-generation);
            if (requested==Mode.CANCEL_AT_CLEAR) {
                counts.addProperty("clear_events",clears);
                counts.addProperty("clear_events_before_reset",clearsBeforeReset);
                counts.addProperty("clear_events_during_reset",clearsDuringReset);
            }
            counts.addProperty("injections",injections); counts.addProperty("supplier_calls",supplierCalls); row.add("counts",counts);
            row.add("actual",snapshot());
            row.addProperty("safe_to_continue",passed && requested!=Mode.RESET_THROWS_LAST && retained==null
                    && !map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty());
            return row;
        }

        JsonObject runSupport(Mode requested, WorldBindingCheckResult check) {
            JsonObject row=new JsonObject(); row.addProperty("id",requested.id);
            row.addProperty("action",requested.name()); row.addProperty("expected",requested.expected);
            row.add("precondition",snapshot()); row.add("position_before",position());
            Vec3 positionBefore=actor.position(); var dimensionBefore=actor.level().dimension();
            long generation=map.resetGeneration();
            joins=starts=resets=injections=supplierCalls=0;
            supportOriginal=null; supportLevel=null; supportWriteRequested=false;
            supportEvidence=new JsonObject(); row.add("support",supportEvidence);
            supportEvidence.addProperty("x",SUPPORT.getX()); supportEvidence.addProperty("y",SUPPORT.getY()); supportEvidence.addProperty("z",SUPPORT.getZ());
            supportEvidence.addProperty("injected_state",INJECTED_AIR.toString());
            supportEvidence.addProperty("write_requested",false); supportEvidence.addProperty("restore_attempted",false);
            supportEvidence.addProperty("restored_exact_original",false); supportEvidence.addProperty("external_change_refused",false);
            mode=requested;
            MinecraftRuntimeTests.Session owned=null;
            var attempt=tests.newStartAttempt();
            boolean passed=false, restorationSafe=true;
            try {
                requireIdle(); prepareSupport();
                BooleanSupplier supplier=()->{
                    supplierCalls++;
                    if (requested==Mode.SUPPORT_AT_SUPPLIER && supplierCalls==2) removeSupport();
                    return false;
                };
                Throwable refusal=null;
                try { owned=tests.start(attempt,installed,registry,binding,check,supplier); }
                catch (RuntimeException | Error rejected) { refusal=rejected; }
                row.add("start_refusal",diagnostic(refusal)); row.add("position_after_start",position());
                row.addProperty("no_position_or_dimension_change_before_refusal",positionBefore.equals(actor.position()) && dimensionBefore.equals(actor.level().dimension()));
                require(owned==null,"support_loss_returned_false_RUNNING");
                require(refusal instanceof DocumentCodecException codec && codec.problems().stream()
                        .anyMatch(p->p.code().equals(EditorId.parse("blockoffensive:test/spawn_unsafe"))),"support_loss_not_spawn_unsafe");
                require(injections==1 && supportWriteRequested,"support_loss_not_injected_once");
                require(positionBefore.equals(actor.position()) && dimensionBefore.equals(actor.level().dimension()),"actor_moved_before_spawn_unsafe_refusal");
                int expectedJoin=requested==Mode.SUPPORT_AT_SUPPLIER?0:1;
                int expectedStart=requested==Mode.SUPPORT_AT_START?1:0;
                require(joins==expectedJoin && starts==expectedStart && resets==expectedJoin
                        && map.resetGeneration()-generation==expectedJoin,"support_loss_native_counts");
                require(attempt.retainedSession()==null,"support_loss_retained_FAILED");
                requireIdle(); passed=true;
            } catch (RuntimeException | Error failure) {
                row.add("assertion_or_unexpected_failure",diagnostic(failure));
                retained=attempt.retainedSession();
                if (owned!=null && owned.state()==MinecraftRuntimeTests.State.RUNNING) {
                    try { tests.stop(owned); }
                    catch (RuntimeException | Error cleanup) { row.add("owned_session_cleanup_failure",diagnostic(cleanup)); retained=attempt.retainedSession(); }
                }
            } finally {
                mode=null;
                try { restorationSafe=restoreSupport(); }
                catch (RuntimeException | Error failure) {
                    restorationSafe=false; supportEvidence.add("restoration_failure",diagnostic(failure));
                }
                row.add("position_after_restore",position());
            }
            passed=passed && restorationSafe;
            row.addProperty("status",passed?"PASS":"FAIL");
            JsonObject counts=new JsonObject(); counts.addProperty("join_events",joins); counts.addProperty("start_events",starts);
            counts.addProperty("reset_events",resets); counts.addProperty("reset_generation_delta",map.resetGeneration()-generation);
            counts.addProperty("injections",injections); counts.addProperty("supplier_calls",supplierCalls); row.add("counts",counts);
            row.add("actual",snapshot());
            row.addProperty("safe_to_continue",passed && retained==null && !map.isStart()
                    && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty());
            return row;
        }

        void prepareSupport() {
            supportLevel=map.getServerLevel();
            require(supportLevel.getServer()==server && server.getLevel(supportLevel.dimension())==supportLevel,"exact_support_level_required");
            int points=0;
            for (var team:map.getMapTeams().getNormalTeams()) {
                var capability=team.getCapabilityMap().get(SpawnPointCapability.class);
                if (capability.isEmpty()) continue;
                for (var point:capability.orElseThrow().getSpawnPointsData()) {
                    points++;
                    require(point!=null && point.getX()==48.5 && point.getY()==-60 && point.getZ()==59.5
                            && point.getDimension().equals(supportLevel.dimension()),"fixed_unique_spawn_required");
                }
            }
            require(points==1,"fixed_unique_spawn_required");
            require(supportLevel.getChunkSource().hasChunk(SUPPORT.getX()>>4,SUPPORT.getZ()>>4),"support_chunk_must_already_be_loaded");
            supportOriginal=supportLevel.getBlockState(SUPPORT);
            supportEvidence.addProperty("dimension",supportLevel.dimension().location().toString());
            supportEvidence.addProperty("original_state",supportOriginal.toString());
            supportEvidence.addProperty("unique_spawn_confirmed",true);
            require(!supportOriginal.isAir() && !supportOriginal.hasBlockEntity(),"non_air_support_without_block_entity_required");
        }

        void removeSupport() {
            if (supportWriteRequested) return;
            requireExact();
            require(supportLevel!=null && supportOriginal!=null && supportLevel.getBlockState(SUPPORT)==supportOriginal,"support_changed_before_injection");
            supportWriteRequested=true; injections++;
            supportEvidence.addProperty("write_requested",true); supportEvidence.addProperty("injection_supplier_call",supplierCalls);
            supportEvidence.add("position_at_injection",position());
            // Client update only: do not propagate neighbouring block updates for this temporary fault.
            boolean changed=supportLevel.setBlock(SUPPORT,INJECTED_AIR,2);
            supportEvidence.addProperty("injection_set_block_return",changed);
            supportEvidence.addProperty("state_after_injection",supportLevel.getBlockState(SUPPORT).toString());
            require(changed && supportLevel.getBlockState(SUPPORT)==INJECTED_AIR,"support_air_injection_failed");
        }

        boolean restoreSupport() {
            if (!supportWriteRequested) return true;
            requireExact();
            require(map.getServerLevel()==supportLevel && server.getLevel(supportLevel.dimension())==supportLevel,"support_level_changed_before_restore");
            BlockState current=supportLevel.getBlockState(SUPPORT);
            supportEvidence.addProperty("state_before_restore",current.toString());
            if (current!=INJECTED_AIR) {
                supportEvidence.addProperty("external_change_refused",true);
                supportEvidence.addProperty("state_after_restore",current.toString());
                return false;
            }
            supportEvidence.addProperty("restore_attempted",true);
            boolean restored=supportLevel.setBlock(SUPPORT,supportOriginal,2);
            boolean exact=supportLevel.getBlockState(SUPPORT)==supportOriginal;
            supportEvidence.addProperty("restore_set_block_return",restored);
            supportEvidence.addProperty("restored_exact_original",restored && exact);
            supportEvidence.addProperty("state_after_restore",supportLevel.getBlockState(SUPPORT).toString());
            return restored && exact;
        }

        JsonObject position() {
            JsonObject result=new JsonObject(); result.addProperty("x",actor.getX()); result.addProperty("y",actor.getY()); result.addProperty("z",actor.getZ());
            result.addProperty("dimension",actor.level().dimension().location().toString()); return result;
        }

        JsonArray inventory() {
            JsonArray slots=new JsonArray();
            for (int slot=0; slot<actor.getInventory().getContainerSize(); slot++) {
                var stack=actor.getInventory().getItem(slot);
                JsonObject item=new JsonObject(); item.addProperty("slot",slot);
                item.addProperty("empty",stack.isEmpty());
                item.addProperty("stack_nbt",stack.save(new CompoundTag()).toString());
                slots.add(item);
            }
            return slots;
        }

        boolean hasNonemptyStartKit() {
            for (var team:map.getMapTeams().getNormalTeams()) {
                if (!team.hasPlayer(actor.getUUID())) continue;
                var capability=team.getCapabilityMap().get(StartKitsCapability.class);
                if (capability.isEmpty()) continue;
                var kits=capability.orElseThrow();
                var runtime=kits.runtimeKits();
                if (runtime.isPresent()) {
                    var templates=runtime.orElseThrow();
                    for (int index=0; index<templates.templates().size(); index++)
                        if (!templates.isEmpty(index)) return true;
                } else if (kits.getTeamKits().stream().anyMatch(stack->!stack.isEmpty())) return true;
            }
            return false;
        }

        JsonObject snapshot() {
            JsonObject state=new JsonObject(); state.addProperty("is_start",map.isStart());
            state.addProperty("actor_participant",map.checkGameHasPlayer(actor));
            state.addProperty("participants",map.getMapTeams().getJoinedPlayersWithSpec().size());
            state.addProperty("reset_generation",map.resetGeneration());
            state.addProperty("retained_state",retained==null?"none":retained.state().name());
            state.addProperty("record_unchanged",record.equals(core.runtimeInstallations().records().get(KEY)));
            return state;
        }
    }

    private static final class InjectedResetFailure extends RuntimeException {
        private static final long serialVersionUID=1L;
        InjectedResetFailure() { super("Q82_exact_runtime_reset_listener_once"); }
    }

    private static boolean containsInjected(Throwable failure) {
        for (Throwable current=failure; current!=null; current=current.getCause())
            if (current instanceof InjectedResetFailure) return true;
        return false;
    }

    private static boolean code(Throwable failure,String reason) {
        return failure instanceof DocumentCodecException codec && codec.problems().stream()
                .anyMatch(p->p.code().equals(EditorId.parse("editor:runtime_test/"+reason)));
    }

    private static JsonObject diagnostic(Throwable failure) {
        JsonObject result=new JsonObject(); result.addProperty("thrown",failure!=null);
        if (failure==null) return result;
        result.addProperty("type",failure.getClass().getName()); result.addProperty("message",failure.getMessage());
        if (failure instanceof DocumentCodecException codec) {
            JsonArray codes=new JsonArray(); for (Problem p:codec.problems()) codes.add(p.code().toString()); result.add("problem_codes",codes);
        }
        result.addProperty("injected_reset_failure_in_cause_chain",containsInjected(failure));
        return result;
    }

    private static JsonObject uncovered(String id,String reason) {
        JsonObject row=new JsonObject(); row.addProperty("id",id); row.addProperty("status","NOT_COVERED");
        row.addProperty("precondition",reason); row.addProperty("action","none");
        row.addProperty("expected","Requires separate isolated-host orchestration"); row.addProperty("actual","Not executed");
        JsonObject counts=new JsonObject(); counts.addProperty("native_actions_requested",0);
        row.add("counts",counts); return row;
    }

    private static Throwable thrown(Runnable action) {
        try { action.run(); return null; } catch (RuntimeException | Error failure) { return failure; }
    }

    private static void require(boolean condition,String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static <T> T owner(MinecraftServer server,Callable<T> operation) throws Exception {
        if (server.isSameThread()) throw new IllegalStateException("module_worker_required");
        CompletableFuture<T> result=new CompletableFuture<>();
        server.execute(()->{
            try { result.complete(operation.call()); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        // Once an owner action is queued, observe its completion before returning. Interruption
        // must not leave a delayed native action or listener registration behind the module run.
        boolean interrupted=false;
        try {
            for (;;) {
                try { return result.get(); }
                catch (InterruptedException waiting) { interrupted=true; }
            }
        }
        catch (ExecutionException failure) {
            Throwable cause=failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException(cause);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
