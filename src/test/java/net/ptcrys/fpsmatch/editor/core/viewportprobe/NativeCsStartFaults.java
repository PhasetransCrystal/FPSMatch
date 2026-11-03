package net.ptcrys.fpsmatch.editor.core.viewportprobe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.data.PlayerData;
import net.ptcrys.fpsmatch.core.data.SpawnPointData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.editor.api.EditorId;
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
 * Explicit worker-only acceptance entry for the product-installed CS in disposable Q82.
 * Uses the installation's SAME registry, current native actor, fresh binding and physical checks.
 * No installation/project writes, world edits, manufactured kit or extra spawn are requested.
 * Native join/start/reset retain their normal gameplay effects. Only our own returned RUNNING
 * token can be stopped; a retained FAILED token is handed to normal host shutdown without retry.
 * The pool regression changes only the exact runtime list, restored by identity in finally.
 * The actor's UI binding is replaced and the UI must bind/check again after this entry.
 */
public final class NativeCsStartFaults {
    private static final InstallationPlan.Key KEY = new InstallationPlan.Key("cs", "Q82_UI56_SAFE_CS");
    private static final Path Q82 = Path.of("F:/BlockOffensive/.local-artifacts/world-editor/rewrite/platform-batch07/Q82");
    private static final Path PROJECT = Q82.resolve("ui-project-test56");
    private static final AtomicBoolean EXECUTING = new AtomicBoolean();
    private NativeCsStartFaults() {}

    public static JsonObject execute(MinecraftServer server, UUID actorId, ExtensionRegistry registry) throws Exception {
        Objects.requireNonNull(server); Objects.requireNonNull(actorId); Objects.requireNonNull(registry);
        require(!server.isSameThread(), "module_worker_required");
        require(Boolean.getBoolean("editorViewportProbe.enabled") && Boolean.getBoolean("mcagent.testMode"),
                "explicit_development_flags_required");
        require(EXECUTING.compareAndSet(false, true), "CS_probe_already_running");
        try {
            Context c = owner(server, () -> capture(server, actorId, registry));
            Map<String,String> filesBefore = fingerprints(c.root);
            JsonObject result = new JsonObject();
            result.addProperty("type", KEY.type()); result.addProperty("name", KEY.name());
            result.addProperty("server_root", c.root.toString()); result.addProperty("project_root", PROJECT.toString());
            result.addProperty("installation_id", c.record.plan().installationId().toString());
            result.addProperty("world_id", c.installed.worldId().toString());
            result.addProperty("full_record", c.record.toString());
            result.addProperty("installation_project_or_world_write_requested", false);
            result.addProperty("ui_rebind_required", true);
            result.addProperty("product_authoring_acceptance", false);
            JsonArray rows = new JsonArray(); result.add("cases", rows);
            Consumer<FPSMapEvent> listener = c::event;
            owner(server, () -> {
                MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, true, FPSMapEvent.class, listener);
                return null;
            });
            boolean safe = true;
            try {
                for (Mode mode : Mode.values()) {
                    if (!safe) { rows.add(uncovered(mode, "Earlier case failed; normal host shutdown required")); continue; }
                    JsonObject row;
                    try {
                        bind(c);
                        var physical = owner(server, () -> { c.exact(); return c.core.getWorldBindings().check(c.binding); });
                        WorldBindingCheckResult inspected = physical.verify(() -> false);
                        owner(server, () -> {
                            require(c.tests.inspect(c.installed,c.registry,c.binding,inspected).isEmpty(),
                                    "same_registry_physical_CS_preflight_must_be_clean");
                            return null;
                        });
                        // Confirmed-world checks are single-use: inspect consumed its own
                        // result, just as the product obtains a fresh check at confirmation.
                        var confirmation = owner(server, () -> { c.exact(); return c.core.getWorldBindings().check(c.binding); });
                        WorldBindingCheckResult check = confirmation.verify(() -> false);
                        row = owner(server, () -> c.run(mode, check));
                    } catch (Exception | Error failure) {
                        row = uncovered(mode, "Binding/physical check could not complete");
                        row.addProperty("status", "FAIL"); row.add("failure", diagnostic(failure));
                        row.addProperty("safe_to_continue", false);
                    }
                    rows.add(row); safe = row.get("safe_to_continue").getAsBoolean();
                }
            } finally {
                owner(server, () -> { c.mode = null; MinecraftForge.EVENT_BUS.unregister(listener); return null; });
            }
            Map<String,String> filesAfter = Map.of(); boolean filesRead = false;
            try { filesAfter = fingerprints(c.root); filesRead = true; }
            catch (Exception | Error failure) { result.add("files_after_read_failure", diagnostic(failure)); }
            JsonObject proof = owner(server, c::proof);
            proof.addProperty("installation_and_project_bytes_identical", filesRead && filesBefore.equals(filesAfter));
            proof.add("files_before", jsonMap(filesBefore)); proof.add("files_after", jsonMap(filesAfter));
            result.add("unchanged_proof", proof); result.add("terminal", owner(server, c::snapshot));
            boolean passed = safe && filesRead && filesBefore.equals(filesAfter)
                    && proof.get("all_records_equal").getAsBoolean()
                    && proof.get("exact_map_and_handle_identical").getAsBoolean()
                    && proof.get("all_spawn_pools_original_by_identity").getAsBoolean();
            result.addProperty("all_executed_cases_passed", passed);
            boolean allCovered=true;
            for (var item:rows) {
                var row=item.getAsJsonObject();
                if (!row.get("status").getAsString().equals("PASS")) allCovered=false;
                if (row.has("inventory_delivery_oracle") && !row.getAsJsonObject("inventory_delivery_oracle")
                        .get("status").getAsString().equals("PASS")) allCovered=false;
            }
            result.addProperty("all_requested_seams_and_inventory_oracle_covered",allCovered);
            result.addProperty("normal_host_shutdown_required", !passed);
            result.addProperty("full_CS_gameplay_acceptance", false);
            return result;
        } finally { EXECUTING.set(false); }
    }

    private enum Mode {
        NORMAL("Formal start to RUNNING, stop, repeat stop", "RUNNING then STOPPED; join/start/reset 1/1/1; repeated stop adds zero reset"),
        START_CANCEL("Cancel exact StartEvent once", "start_rejected; no teleport/equipment delivery; original map reset once"),
        CLEAR_CANCEL("Cancel initial exact ClearEvent once", "start_rejected before player delivery; original map reset once; inventory oracle only with empty inventory and real nonempty kit"),
        ASSIGNED_OUTSIDE("At initial ClearEvent replace exact actor assigned point with base SpawnPointData outside map", "spawn_unsafe; original pool unchanged; no teleport; original map reset once"),
        POOL_REPLACED("At initial ClearEvent replace exact actor team pool with existing safe B, retain assigned A", "spawn_unsafe for assigned A absent from pool B; no teleport; original map reset once; finally restore exact list");
        final String operation, expected;
        Mode(String operation, String expected) { this.operation = operation; this.expected = expected; }
    }

    private static Context capture(MinecraftServer server, UUID actorId, ExtensionRegistry registry) throws Exception {
        Path lexical = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path root = lexical.toRealPath();
        require(root.equals(lexical), "aliased_world_root_refused");
        require(root.startsWith(Q82.toRealPath()) && root.toString().replace('\\','/').contains("/Q82/ui-host-"),
                "isolated_Q82_ui_host_required");
        require(PROJECT.toRealPath().equals(PROJECT.toAbsolutePath().normalize()), "aliased_project_refused");
        FPSMCore core = FPSMCore.getInstance();
        require(core != null && core.isActive() && core.getServer() == server, "exact_active_core_required");
        var record = core.runtimeInstallations().records().get(KEY);
        require(record != null && record.state() == LocalInstallationIndex.State.ACTIVE, "product_installed_ACTIVE_CS_required");
        require(record.plan().factoryId().equals(EditorId.parse("blockoffensive:cs_map_1_20_1"))
                && record.plan().adapterId().equals(EditorId.parse("blockoffensive:cs_runtime_1_20_1")), "CS_factory_adapter_required");
        var installed = core.runtimeInstallations().captureInstalled(record);
        BaseMap map = core.getMapByTypeWithName(KEY.type(), KEY.name()).orElseThrow();
        require(map.getClass().getName().equals("net.ptcrys.blockoffensive.map.CSGameMap") && map.hasRuntimeOwnership(),
                "exact_owned_CSGameMap_required");
        ServerPlayer actor = server.getPlayerList().getPlayer(actorId);
        require(actor != null && server.getPlayerList().getPlayers().size() == 1, "single_real_actor_required");
        Context c = new Context(server, core, map, actor, registry, record, installed, root);
        c.idle(); return c;
    }

    private static final class Context {
        final MinecraftServer server; final FPSMCore core; final BaseMap map; final ServerPlayer actor;
        final ExtensionRegistry registry; final LocalInstallationIndex.Record record;
        final Map<InstallationPlan.Key,LocalInstallationIndex.Record> records;
        final MinecraftRuntimeInstallations.InstalledRuntime installed; final MinecraftRuntimeTests tests; final Path root;
        final IdentityHashMap<ServerTeam,Pool> pools = new IdentityHashMap<>();
        WorldBinding binding; Mode mode; MinecraftRuntimeTests.Session session;
        MinecraftRuntimeTests.StartAttempt attempt;
        int joins, starts, clears, initialClears, resets, injections, suppliers;
        long generation; JsonObject evidence;
        JsonArray inventoryAtEvent, inventoryAtReset; JsonObject positionAtEvent, positionAtReset;
        boolean emptyAtEvent, nonemptyKit, skippedPool;
        ServerTeam injectedTeam; PlayerData injectedPlayer; SpawnPointData assignedA, injectedAssigned, pointB;
        Pool injectedPool; List<SpawnPointData> injectedValues;

        Context(MinecraftServer server, FPSMCore core, BaseMap map, ServerPlayer actor, ExtensionRegistry registry,
                LocalInstallationIndex.Record record, MinecraftRuntimeInstallations.InstalledRuntime installed, Path root) {
            this.server=server; this.core=core; this.map=map; this.actor=actor; this.registry=registry;
            this.record=record; this.installed=installed; this.root=root;
            records=Map.copyOf(core.runtimeInstallations().records()); tests=core.runtimeInstallations().tests();
            for (var team : map.getMapTeams().getNormalTeams()) {
                var cap=team.getCapabilityMap().get(SpawnPointCapability.class).orElseThrow();
                pools.put(team,new Pool(cap,cap.getSpawnPointsData(),List.copyOf(cap.getSpawnPointsData())));
            }
        }

        void exact() {
            require(server.isSameThread() && FPSMCore.getInstance()==core && core.isActive() && core.getServer()==server,
                    "captured_core_changed");
            require(server.getPlayerList().getPlayers().size()==1 && server.getPlayerList().getPlayer(actor.getUUID())==actor,
                    "captured_single_actor_changed");
            require(core.getMapByTypeWithName(KEY.type(),KEY.name()).orElse(null)==map
                    && records.equals(core.runtimeInstallations().records())
                    && core.runtimeInstallations().captureInstalled(record)==installed, "captured_map_record_handle_changed");
        }
        void idle() {
            exact();
            require(!map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty(), "exact_map_not_idle_no_foreign_cleanup");
            require(attempt==null || attempt.retainedSession()==null, "FAILED_requires_normal_host_shutdown");
            for (var maps : core.getAllMaps().values()) for (BaseMap other : maps)
                require(!other.checkGameHasPlayer(actor) && !other.checkSpecHasPlayer(actor), "actor_in_foreign_map_no_cleanup");
        }
        void event(FPSMapEvent event) {
            if (!server.isSameThread() || event.getMap()!=map || mode==null) return;
            if (event instanceof FPSMapEvent.PlayerEvent.JoinEvent join && join.getPlayer()==actor) joins++;
            if (event instanceof FPSMapEvent.StartEvent) {
                starts++;
                if (mode==Mode.START_CANCEL && injections==0) {
                    captureEvent(); event.setCanceled(true); injections++;
                }
            }
            if (event instanceof FPSMapEvent.ResetEvent) {
                resets++; inventoryAtReset=inventory(); positionAtReset=position();
                evidence.add("inventory_at_reset_event_before_cleanup",inventoryAtReset);
                evidence.add("position_at_reset_event_before_cleanup",positionAtReset);
                if (injectedPlayer!=null) evidence.add("assigned_at_reset_before_cleanup",spawn(injectedPlayer.getSpawnPointsData()));
            }
            if (event instanceof FPSMapEvent.ClearEvent) {
                clears++;
                if (resets!=0) return;
                initialClears++;
                if (initialClears!=1 || mode==Mode.NORMAL || mode==Mode.START_CANCEL) return;
                exact(); captureEvent();
                if (mode==Mode.CLEAR_CANCEL) { event.setCanceled(true); injections++; return; }
                injectedTeam=map.getMapTeams().getTeamByPlayer(actor.getUUID()).orElseThrow();
                require(pools.containsKey(injectedTeam) && injectedTeam.getMap()==map, "exact_actor_team_required");
                injectedPlayer=injectedTeam.getPlayerData(actor.getUUID()).orElseThrow();
                assignedA=injectedPlayer.getSpawnPointsData(); injectedPool=pools.get(injectedTeam);
                require(assignedA!=null && assignedA.getClass()==SpawnPointData.class
                        && injectedPool.original.contains(assignedA), "assigned_A_must_be_existing_native_point");
                evidence.addProperty("team",injectedTeam.getName()); evidence.add("assigned_A",spawn(assignedA));
                evidence.addProperty("player_data_identity",System.identityHashCode(injectedPlayer));
                evidence.add("original_pool",spawnList(injectedPool.original));
                if (mode==Mode.ASSIGNED_OUTSIDE) {
                    var area=map.getMapArea().aabb();
                    double outsideX=area.maxX+Math.max(16,Math.ulp(area.maxX)*2);
                    require(Double.isFinite(outsideX) && outsideX>area.maxX, "finite_outside_coordinate_required");
                    injectedAssigned=new SpawnPointData(map.getServerLevel().dimension(),
                            new Vec3(outsideX,assignedA.getY(),assignedA.getZ()),assignedA.getYaw(),assignedA.getPitch());
                    require(!area.contains(injectedAssigned.getPosition()), "injection_must_be_outside_map");
                    injectedPlayer.setSpawnPointsData(injectedAssigned); injections++;
                    evidence.add("injected_assigned",spawn(injectedAssigned));
                    evidence.addProperty("injected_base_class_exact",injectedAssigned.getClass()==SpawnPointData.class);
                    evidence.addProperty("spawn_pool_unchanged_at_injection",poolsOriginal());
                } else {
                    // Every original point was inspected by the production driver immediately before
                    // start; choose only an existing distinct point. Never manufacture safe B.
                    pointB=injectedPool.original.stream().filter(p->!p.equals(assignedA)).findFirst().orElse(null);
                    if (pointB==null) {
                        skippedPool=true; event.setCanceled(true);
                        evidence.addProperty("not_covered_reason","Exact actor team has only one distinct existing spawn; no point manufactured");
                        return;
                    }
                    require(poolOriginal(injectedTeam,injectedPool), "original_runtime_pool_changed_before_injection");
                    injectedValues=List.of(pointB);
                    injectedPool.list.clear(); injectedPool.list.addAll(injectedValues); injections++;
                    evidence.add("existing_B",spawn(pointB)); evidence.add("injected_pool",spawnList(injectedValues));
                    evidence.addProperty("assigned_A_preserved_at_injection",injectedPlayer.getSpawnPointsData()==assignedA);
                    evidence.addProperty("assigned_A_absent_from_pool_B",!injectedPool.list.contains(assignedA));
                }
            }
        }
        void captureEvent() {
            inventoryAtEvent=inventory(); positionAtEvent=position(); emptyAtEvent=actor.getInventory().isEmpty(); nonemptyKit=hasKit();
            evidence.add("inventory_at_injection_event",inventoryAtEvent); evidence.add("position_at_injection_event",positionAtEvent);
            evidence.addProperty("inventory_empty_at_event",emptyAtEvent); evidence.addProperty("real_nonempty_actor_team_kit",nonemptyKit);
            evidence.addProperty("supplier_calls_at_event",suppliers);
        }

        JsonObject run(Mode requested, WorldBindingCheckResult check) {
            idle(); require(poolsOriginal(), "original_pools_required_before_case");
            JsonObject row=new JsonObject(); row.addProperty("id",requested.name());
            row.addProperty("operation",requested.operation); row.addProperty("expected",requested.expected);
            row.add("precondition",snapshot()); row.add("precondition_identity_proof",proof()); row.add("position_before",position());
            generation=map.resetGeneration(); joins=starts=clears=initialClears=resets=injections=suppliers=0;
            session=null; attempt=null; evidence=new JsonObject(); row.add("injection_evidence",evidence);
            inventoryAtEvent=inventoryAtReset=null; positionAtEvent=positionAtReset=null;
            injectedTeam=null; injectedPlayer=null; assignedA=injectedAssigned=pointB=null;
            injectedPool=null; injectedValues=null; skippedPool=false; emptyAtEvent=nonemptyKit=false;
            boolean passed=false, restored=true;
            JsonObject beforePosition=position();
            try {
                // The worker already inspected with a separate consumed physical check.
                row.addProperty("same_registry_and_native_preflight_verified",true);
                row.addProperty("preflight_inspected_all_original_team_spawns",true);
                mode=requested; attempt=tests.newStartAttempt();
                Throwable refusal=null;
                try { session=tests.start(attempt,installed,registry,binding,check,()-> {
                    suppliers++;
                    if (injections==1 && resets==0 && !evidence.has("inventory_at_next_supplier_before_guard")) {
                        evidence.add("inventory_at_next_supplier_before_guard",inventory());
                        evidence.add("position_at_next_supplier_before_guard",position());
                        evidence.addProperty("supplier_call_at_observation",suppliers);
                        evidence.addProperty("reset_events_at_observation",resets);
                        if (injectedPlayer!=null) evidence.add("assigned_at_guard",spawn(injectedPlayer.getSpawnPointsData()));
                    }
                    return false;
                }); } catch (RuntimeException | Error rejected) { refusal=rejected; }
                row.add("start_refusal",diagnostic(refusal)); row.add("position_after_start",position());
                if (requested==Mode.NORMAL) {
                    require(refusal==null && session!=null && session.state()==MinecraftRuntimeTests.State.RUNNING
                            && map.isStart() && map.checkGameHasPlayer(actor), "formal_CS_start_not_RUNNING");
                    row.add("RUNNING_observation",snapshot()); row.add("RUNNING_position",position());
                    require(tests.stop(session)==MinecraftRuntimeTests.State.STOPPED, "normal_stop_not_STOPPED");
                    int resetAfterFirst=resets; long generationAfterFirst=map.resetGeneration();
                    require(tests.stop(session)==MinecraftRuntimeTests.State.STOPPED
                            && resets==resetAfterFirst && map.resetGeneration()==generationAfterFirst, "repeated_stop_repeated_reset");
                    row.addProperty("repeated_stop_zero_additional_reset",true);
                } else {
                    require(session==null, "rejected_start_returned_RUNNING");
                    boolean unsafe=requested==Mode.ASSIGNED_OUTSIDE || requested==Mode.POOL_REPLACED && !skippedPool;
                    require(code(refusal,unsafe?"blockoffensive:test/spawn_unsafe":"editor:runtime_test/start_rejected"),
                            "wrong_formal_start_refusal");
                    require(beforePosition.equals(position()) && positionAtReset!=null && beforePosition.equals(positionAtReset),
                            "actor_teleported_before_rejection_or_cleanup");
                    require(injections==(skippedPool?0:1), "exactly_one_injection_required");
                    if (requested==Mode.START_CANCEL || requested==Mode.CLEAR_CANCEL) {
                        require(inventoryAtEvent!=null && inventoryAtEvent.equals(inventoryAtReset), "equipment_delivered_before_cancel_cleanup");
                        evidence.addProperty("inventory_unchanged_before_cleanup",true);
                    }
                    if (requested==Mode.CLEAR_CANCEL) {
                        JsonObject oracle=new JsonObject(); row.add("inventory_delivery_oracle",oracle);
                        boolean covered=emptyAtEvent && nonemptyKit;
                        oracle.addProperty("status",covered?"PASS":"NOT_COVERED");
                        oracle.addProperty("precondition","Empty inventory at initial ClearEvent and real nonempty kit of exact actor team");
                        oracle.addProperty("actual",covered?"Unchanged empty inventory at ResetEvent before cleanup":"Real nonempty kit or empty inventory unavailable; no equipment fabricated");
                        oracle.addProperty("expected","No kit delivery after cancelled ClearEvent");
                    }
                    if (requested==Mode.ASSIGNED_OUTSIDE || requested==Mode.POOL_REPLACED && !skippedPool) {
                        require(evidence.has("position_at_next_supplier_before_guard")
                                && positionAtEvent.equals(evidence.get("position_at_next_supplier_before_guard"))
                                && evidence.get("supplier_call_at_observation").getAsInt()==evidence.get("supplier_calls_at_event").getAsInt()+1,
                                "injection_must_be_observed_at_next_guard_before_cleanup");
                        if (requested==Mode.ASSIGNED_OUTSIDE) require(poolsOriginal(), "assigned_fault_changed_spawn_pool");
                        else require(injectedPlayer.getSpawnPointsData()==assignedA, "assigned_A_changed_by_pool_fault");
                    }
                }
                require(joins==1 && starts==1 && resets==1 && map.resetGeneration()-generation==1, "native_join_start_reset_counts_mismatch");
                require(initialClears==(requested==Mode.START_CANCEL?0:1), "initial_ClearEvent_count_mismatch");
                idle(); passed=true;
            } catch (RuntimeException | Error failure) {
                row.add("failure",diagnostic(failure));
                // A successful RUNNING receipt is the only cleanup authority. FAILED is never retried.
                if (session!=null && session.state()==MinecraftRuntimeTests.State.RUNNING) {
                    try { exact(); tests.stop(session); row.addProperty("owned_token_fallback_stop",true); }
                    catch (RuntimeException | Error cleanup) { row.add("owned_token_cleanup_failure",diagnostic(cleanup)); }
                }
            } finally {
                mode=null;
                try { restored=restore(); } catch (RuntimeException | Error failure) { restored=false; evidence.add("restore_failure",diagnostic(failure)); }
            }
            passed=passed && restored;
            row.addProperty("status",passed?(skippedPool?"NOT_COVERED":"PASS"):"FAIL");
            row.addProperty("actual_native_actions_requested",attempt!=null);
            row.add("actual",snapshot()); row.add("restoration_and_identity_proof",proof());
            row.addProperty("safe_to_continue",passed && (attempt==null || attempt.retainedSession()==null)
                    && !map.isStart() && map.getMapTeams().getJoinedPlayersWithSpec().isEmpty());
            row.addProperty("normal_host_shutdown_required",!passed || attempt!=null && attempt.retainedSession()!=null);
            return row;
        }

        boolean restore() {
            evidence.addProperty("runtime_list_restore_requested",injectedValues!=null);
            if (injectedValues!=null) {
                exact();
                boolean exactTarget=injectedTeam.getMap()==map && pools.get(injectedTeam)==injectedPool
                        && injectedTeam.getCapabilityMap().get(SpawnPointCapability.class).orElse(null)==injectedPool.cap
                        && injectedPool.cap.getSpawnPointsData()==injectedPool.list;
                boolean exactInjection=identityList(injectedPool.list,injectedValues);
                evidence.addProperty("restore_target_exact_original_map_capability_list",exactTarget);
                evidence.addProperty("runtime_list_still_exact_injected_values",exactInjection);
                evidence.addProperty("external_list_change_refused",!exactTarget || !exactInjection);
                if (!exactTarget || !exactInjection) return false;
                injectedPool.list.clear(); injectedPool.list.addAll(injectedPool.original);
                evidence.addProperty("runtime_list_restored_original_identity_order",identityList(injectedPool.list,injectedPool.original));
            }
            if (injectedAssigned!=null) {
                exact();
                var current=injectedTeam.getPlayerData(actor.getUUID()).orElse(null);
                if (current==null) {
                    evidence.addProperty("assigned_runtime_data_removed_by_original_map_cleanup",true);
                } else {
                    boolean ours=current==injectedPlayer && current.getSpawnPointsData()==injectedAssigned;
                    evidence.addProperty("external_assigned_change_refused",!ours);
                    if (!ours) return false;
                    current.setSpawnPointsData(assignedA);
                    evidence.addProperty("assigned_original_restored",current.getSpawnPointsData()==assignedA);
                }
            }
            require(poolsOriginal(), "runtime_spawn_pool_restoration_incomplete");
            return true;
        }
        boolean poolOriginal(ServerTeam team,Pool pool) {
            return team.getMap()==map && team.getCapabilityMap().get(SpawnPointCapability.class).orElse(null)==pool.cap
                    && pool.cap.getSpawnPointsData()==pool.list && identityList(pool.list,pool.original);
        }
        boolean poolsOriginal() { for (var entry:pools.entrySet()) if (!poolOriginal(entry.getKey(),entry.getValue())) return false; return true; }
        JsonObject proof() {
            JsonObject value=new JsonObject();
            value.addProperty("all_records_equal",records.equals(core.runtimeInstallations().records()));
            boolean exact=false;
            try { exact=core.getMapByTypeWithName(KEY.type(),KEY.name()).orElse(null)==map
                    && core.runtimeInstallations().captureInstalled(record)==installed; }
            catch (RuntimeException | Error failure) { value.add("exact_identity_failure",diagnostic(failure)); }
            value.addProperty("exact_map_and_handle_identical",exact);
            value.addProperty("all_spawn_pools_original_by_identity",poolsOriginal());
            value.addProperty("map_identity",System.identityHashCode(map));
            value.addProperty("installed_handle_identity",System.identityHashCode(installed)); return value;
        }
        JsonObject snapshot() {
            JsonObject value=new JsonObject(); value.addProperty("map_started",map.isStart());
            value.addProperty("participants",map.getMapTeams().getJoinedPlayersWithSpec().size());
            value.addProperty("session_state",session==null?"none":session.state().name());
            var retained=attempt==null?null:attempt.retainedSession();
            value.addProperty("retained_own_FAILED",retained!=null);
            value.addProperty("retained_own_session_state",retained==null?"none":retained.state().name());
            value.add("retained_diagnostic",diagnostic(retained==null?null:retained.diagnostic()));
            value.addProperty("join_events",joins); value.addProperty("start_events",starts);
            value.addProperty("clear_events",clears); value.addProperty("initial_clear_events",initialClears);
            value.addProperty("reset_events",resets); value.addProperty("reset_generation_delta",map.resetGeneration()-generation);
            value.addProperty("injections",injections); value.addProperty("supplier_calls",suppliers); return value;
        }
        JsonObject position() {
            JsonObject value=new JsonObject(); value.addProperty("x",actor.getX()); value.addProperty("y",actor.getY());
            value.addProperty("z",actor.getZ()); value.addProperty("yaw",actor.getYRot()); value.addProperty("pitch",actor.getXRot());
            value.addProperty("dimension",actor.level().dimension().location().toString()); return value;
        }
        JsonArray inventory() {
            JsonArray value=new JsonArray();
            for (int slot=0;slot<actor.getInventory().getContainerSize();slot++) {
                JsonObject stack=new JsonObject(); stack.addProperty("slot",slot);
                stack.addProperty("empty",actor.getInventory().getItem(slot).isEmpty());
                stack.addProperty("nbt",actor.getInventory().getItem(slot).save(new CompoundTag()).toString()); value.add(stack);
            }
            return value;
        }
        boolean hasKit() {
            var team=map.getMapTeams().getTeamByPlayer(actor.getUUID()).orElse(null);
            if (team==null) return false;
            var cap=team.getCapabilityMap().get(StartKitsCapability.class).orElse(null);
            if (cap==null) return false;
            var runtime=cap.runtimeKits();
            if (runtime.isPresent()) {
                var templates=runtime.orElseThrow();
                for (int i=0;i<templates.templates().size();i++) if (!templates.isEmpty(i)) return true;
                return false;
            }
            return cap.getTeamKits().stream().anyMatch(stack->!stack.isEmpty());
        }
    }

    private record Pool(SpawnPointCapability cap,List<SpawnPointData> list,List<SpawnPointData> original) {}
    private static boolean identityList(List<SpawnPointData> a,List<SpawnPointData> b) {
        if (a.size()!=b.size()) return false;
        for (int i=0;i<a.size();i++) if (a.get(i)!=b.get(i)) return false;
        return true;
    }
    private static JsonObject spawn(SpawnPointData point) {
        JsonObject value=new JsonObject(); value.addProperty("present",point!=null);
        if (point!=null) {
            value.addProperty("class",point.getClass().getName()); value.addProperty("identity",System.identityHashCode(point));
            value.addProperty("dimension",point.getDimension().location().toString()); value.addProperty("x",point.getX());
            value.addProperty("y",point.getY()); value.addProperty("z",point.getZ());
            value.addProperty("yaw",point.getYaw()); value.addProperty("pitch",point.getPitch());
        }
        return value;
    }
    private static JsonArray spawnList(List<SpawnPointData> points) { JsonArray value=new JsonArray(); points.forEach(p->value.add(spawn(p))); return value; }
    private static void bind(Context c) throws Exception {
        var request=owner(c.server,()-> { c.idle(); return c.core.getWorldBindings().begin(c.actor.getUUID(),c.installed.plan().dimensions()); });
        var read=request.read(new WindowsFileIdentityProvider(),()->false);
        owner(c.server,()-> { c.exact(); c.binding=c.core.getWorldBindings().accept(read); return null; });
    }
    private static boolean code(Throwable failure,String code) {
        return failure instanceof DocumentCodecException codec && codec.problems().stream().anyMatch(p->p.code().equals(EditorId.parse(code)));
    }
    private static JsonObject diagnostic(Throwable failure) {
        JsonObject value=new JsonObject(); value.addProperty("thrown",failure!=null);
        if (failure!=null) {
            value.addProperty("class",failure.getClass().getName()); value.addProperty("message",failure.getMessage());
            if (failure instanceof DocumentCodecException codec) {
                JsonArray problems=new JsonArray(); codec.problems().forEach(p->{
                    JsonObject problem=new JsonObject(); problem.addProperty("code",p.code().toString());
                    problem.addProperty("detail",p.toString()); problems.add(problem);
                }); value.add("problems",problems);
            }
            JsonArray suppressed=new JsonArray(); for (Throwable other:failure.getSuppressed()) suppressed.add(other.toString());
            value.add("suppressed",suppressed);
        }
        return value;
    }
    private static JsonObject uncovered(Mode mode,String reason) {
        JsonObject value=new JsonObject(); value.addProperty("id",mode.name()); value.addProperty("status","NOT_COVERED");
        value.addProperty("precondition",reason); value.addProperty("operation",mode.operation);
        value.addProperty("expected",mode.expected); value.addProperty("actual","Not executed; no native action requested");
        value.addProperty("safe_to_continue",false); return value;
    }
    private static Map<String,String> fingerprints(Path root) throws Exception {
        TreeMap<String,String> value=new TreeMap<>();
        for (Path top : new Path[]{root.resolve(".editor-platform/world.identity"),root.resolve(".editor-platform/installations.owner"),
                root.resolve(".editor-platform/installations"),PROJECT}) {
            require(Files.exists(top,LinkOption.NOFOLLOW_LINKS),"persistent_proof_node_missing");
            try (var paths=Files.walk(top)) {
                for (Path path:paths.sorted().toList()) {
                    require(!Files.isSymbolicLink(path),"persistent_proof_symlink_refused");
                    String key=path.toAbsolutePath().normalize().toString().replace('\\','/');
                    if (Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) value.put(key,"directory");
                    // LocalProjectStore holds an exclusive Windows byte-range lock here.
                    // This process-lock file is not project content; keep its existence in
                    // the proof while hashing every actual persistent byte as before.
                    else if (path.equals(root.resolve(".editor-platform/installations/.editor.lock"))
                            || path.equals(PROJECT.resolve(".editor.lock"))) {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS),"persistent_proof_lock_type");
                        value.put(key,"process_lock_existence_only_not_content_evidence");
                    }
                    else {
                        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Files.size(path)<=512L*1024*1024,"persistent_proof_file_budget");
                        MessageDigest digest=MessageDigest.getInstance("SHA-256"); long size=0;
                        try (var stream=Files.newInputStream(path)) {
                            byte[] bytes=new byte[65536]; int count;
                            while ((count=stream.read(bytes))!=-1) { size+=count; require(size<=512L*1024*1024,"persistent_proof_file_budget"); digest.update(bytes,0,count); }
                        }
                        value.put(key,size+":"+HexFormat.of().formatHex(digest.digest()));
                    }
                }
            }
        }
        return value;
    }
    private static JsonObject jsonMap(Map<String,String> values) { JsonObject result=new JsonObject(); values.forEach(result::addProperty); return result; }
    private static void require(boolean condition,String reason) { if (!condition) throw new IllegalStateException(reason); }
    private static <T> T owner(MinecraftServer server,Callable<T> action) throws Exception {
        require(!server.isSameThread(),"module_worker_required"); CompletableFuture<T> result=new CompletableFuture<>();
        server.execute(()-> { try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); } });
        boolean interrupted=false;
        try {
            for (;;) try { return result.get(); } catch (InterruptedException waiting) { interrupted=true; }
        } catch (ExecutionException failure) {
            Throwable cause=failure.getCause(); if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error; throw new IllegalStateException(cause);
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
