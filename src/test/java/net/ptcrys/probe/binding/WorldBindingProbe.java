package net.ptcrys.probe.binding;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLLoader;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.LocalWorldBindings;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheck;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingRead;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingRequest;
import net.ptcrys.fpsmatch.editor.core.worldio.FileIdentityProvider;
import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentityException;
import net.ptcrys.fpsmatch.editor.common.backend.windows.WindowsFileIdentityProvider;
import net.ptcrys.fpsmatch.core.FPSMCore;

/** Explicit opt-in observer; all binding authority comes from the actual active core. */
public final class WorldBindingProbe {
    /** Explicit test-source bootstrap attached to the existing BO mod container. */
    @Mod.EventBusSubscriber(modid = "blockoffensive", bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Bootstrap {
        private static boolean initialized;

        private Bootstrap() { }

        @SubscribeEvent
        public static synchronized void commonSetup(FMLCommonSetupEvent event) {
            if (initialized) return;
            initialized = true;
            new WorldBindingProbe();
        }
    }

    private static final Path ROOT = Path.of("F:/BlockOffensive/.local-artifacts/world-editor/rewrite/platform-batch07/Q58/host-runs");
    private static final String[] WORLDS = {"Q58-A", "Q58-B"};
    private static final int MANIFEST_LIMIT = 1024 * 1024;
    private static final long WORK_LIMIT = TimeUnit.SECONDS.toNanos(120);
    private static final EditorId OVERWORLD = EditorId.parse("minecraft:overworld");
    private static final Map<EditorId, EditorId> DIMENSIONS = Map.of(OVERWORLD, OVERWORLD);
    private static final String SCOPE = "Actual core bindings, reselection, unavailable dimension, cancellation, normal stop and foreign issuer on two isolated integrated servers. No permission mutation, respawn, reconnect, level replacement, dedicated server, installation, restart recovery, full BO or RC claim.";
    private final long processId = ProcessHandle.current().pid();
    private final String processStartedAt = ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unavailable");
    private Path game;
    private Path attempt;
    private byte[] manifestBytes;
    private String manifestSha = "unavailable";
    private UUID worldId;
    private String identitySha;
    private boolean failed;
    private int completedSessions;
    private Session current;
    // These three opaque pure objects are the only references retained across real servers.
    private WorldBindingRead oldRead;
    private WorldBinding oldBinding;
    private WorldBindingCheck oldHeldCheck;

    public WorldBindingProbe() {
        try {
            initialize();
            MinecraftForge.EVENT_BUS.register(this);
        } catch (Exception | Error failure) { fail(failure); }
    }

    private void initialize() throws IOException {
        Path supplied = FMLLoader.getGamePath();
        for (Path part : supplied) require(!part.toString().equals(".."), "Parent traversal refused");
        game = supplied.toAbsolutePath().normalize();
        require(game.startsWith(ROOT) && game.getNameCount() == ROOT.getNameCount() + 2
                && game.getFileName().toString().equals("game"), "Wrong Q58 game boundary");
        require(game.toRealPath().equals(game), "Game path must be canonical");
        attempt = game.getParent();
        require("true".equals(System.getProperty("q58.probe.enabled")), "Explicit q58.probe.enabled=true required");
        require(!processStartedAt.equals("unavailable"), "Process start identity unavailable");
        manifestBytes = readManifest();
        manifestSha = sha(manifestBytes);
        JsonObject input = JsonParser.parseString(new String(manifestBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        require(input.keySet().equals(Set.of("schema", "gameDirectory", "worldFolders", "levelName", "worldId",
                "identitySha256", "files", "dirs", "sourceManifestSha256")), "Wrong manifest fields");
        require(string(input, "schema").equals("Q58_LOCAL_WORLD_BINDING_INPUT_V1"), "Wrong manifest schema");
        Path declared = Path.of(string(input, "gameDirectory"));
        require(declared.isAbsolute() && declared.equals(declared.normalize()) && declared.equals(game), "Manifest gameDirectory mismatch");
        require(string(input, "levelName").equals("testWolrd"), "Wrong LevelName");
        JsonArray worlds = input.getAsJsonArray("worldFolders");
        require(worlds.size() == 2 && worlds.get(0).isJsonPrimitive() && worlds.get(0).getAsJsonPrimitive().isString()
                && worlds.get(1).isJsonPrimitive() && worlds.get(1).getAsJsonPrimitive().isString()
                && worlds.get(0).getAsString().equals(WORLDS[0]) && worlds.get(1).getAsString().equals(WORLDS[1]), "Wrong world sequence");
        worldId = UUID.fromString(string(input, "worldId"));
        require(!worldId.equals(new UUID(0, 0)), "Zero world identity");
        identitySha = string(input, "identitySha256");
        require(identitySha.matches("[0-9a-f]{64}") && string(input, "sourceManifestSha256").matches("[0-9a-f]{64}"), "Invalid identity/source SHA");
        Set<Path> paths = new HashSet<>();
        JsonArray files = input.getAsJsonArray("files");
        require(!files.isEmpty(), "Empty input inventory");
        for (JsonElement item : files) {
            JsonObject entry = item.getAsJsonObject();
            require(entry.keySet().equals(Set.of("path", "bytes", "sha256")), "Wrong file inventory fields");
            require(paths.add(relative(string(entry, "path"))), "Duplicate inventory path");
            require(entry.get("bytes").isJsonPrimitive() && entry.get("bytes").getAsJsonPrimitive().isNumber()
                    && new BigDecimal(entry.get("bytes").getAsString()).longValueExact() >= 0, "Invalid inventory byte count");
            require(string(entry, "sha256").matches("[0-9a-f]{64}"), "Invalid inventory SHA");
        }
        JsonArray dirs = input.getAsJsonArray("dirs");
        require(!dirs.isEmpty(), "Empty directory inventory");
        for (JsonElement item : dirs) {
            require(item.isJsonPrimitive() && item.getAsJsonPrimitive().isString(), "Invalid directory inventory entry");
            require(paths.add(relative(item.getAsString())), "Duplicate file/directory path");
        }
        // Preparation authenticates the actual copies. Mutable world files are never rehashed here.
        verifyManifest();
    }

    private static Path relative(String value) {
        Path path = Path.of(value);
        require(!path.isAbsolute() && !value.isEmpty() && !path.startsWith("..")
                && path.equals(path.normalize()), "Invalid inventory path");
        return path;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void started(ServerStartedEvent event) {
        if (failed) return;
        try {
            MinecraftServer server = event.getServer();
            requireOwner(server);
            verifyManifest();
            require(current == null && completedSessions < 2, "Unexpected Started order");
            int number = completedSessions + 1;
            Path actualRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(actualRoot.toRealPath().equals(actualRoot)
                    && actualRoot.equals(game.resolve("saves").resolve(WORLDS[number - 1])), "Wrong actual root/order");
            FPSMCore core = FPSMCore.getInstance();
            require(!server.isStopped() && core.getServer() == server && core.isActive(), "Started core/server mismatch");
            require(core.archiveName.equals("testWolrd") && server.getWorldData().getLevelName().equals("testWolrd"), "Unexpected LevelName");
            if (number == 1) require(oldRead == null && oldBinding == null && oldHeldCheck == null, "Unexpected old tokens");
            else require(oldRead != null && oldBinding != null && oldHeldCheck != null, "A stop tokens absent");
            current = new Session(number, server, core, core.getWorldBindings(), actualRoot);
            JsonObject evidence = snapshot(current, "STARTED");
            evidence.addProperty("coreServerReferenceEqual", core.getServer() == server);
            evidence.addProperty("coreServiceReferenceEqual", current.service == core.getWorldBindings());
            evidence.addProperty("awaitingActualPlayerLogin", true);
            write("session-" + number + "-started.json", evidence);
        } catch (Exception | Error failure) { fail(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (failed || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            Session s = current;
            require(s != null && player.server == s.server, "Login without matching Started");
            requireOwner(s.server);
            if (s.player != null) return; // Only the first actual login is the diagnostic actor.
            require(player.hasPermissions(2), "Actual logged-in player lacks permission level 2");
            require(s.server.getPlayerList().getPlayer(player.getUUID()) == player, "Logged-in player reference mismatch");
            s.player = player;
            s.actorId = player.getUUID();
            s.steps.add("actualPlayerLoginPermission2");
        } catch (Exception | Error failure) { fail(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void tick(TickEvent.ServerTickEvent event) {
        if (failed || event.phase != TickEvent.Phase.END || current == null) return;
        try {
            Session s = current;
            require(event.getServer() == s.server, "Tick from unknown server");
            requireOwner(s.server);
            if (s.ready || s.player == null) return;
            verifyManifest();
            require(s.core == FPSMCore.getInstance() && s.core.getServer() == s.server && s.core.isActive()
                    && s.service == s.core.getWorldBindings(), "Tick core/service changed");
            if (s.stage == Stage.INITIAL) {
                s.request = begin(s, 0);
                submitRead(s, Stage.READ1);
                return;
            }
            require(s.future != null, "Missing pending worker");
            require(System.nanoTime() - s.submittedAt <= WORK_LIMIT, "Worker exceeded 120 seconds");
            if (!s.future.isDone()) return;
            Outcome outcome = s.future.get(1, TimeUnit.SECONDS);
            s.future = null;
            long elapsed = System.nanoTime() - s.submittedAt;
            require(elapsed <= WORK_LIMIT, "Worker completion observation exceeded 120 seconds");
            require(outcome.threadId() != Thread.currentThread().getId(), "I/O ran on server owner");
            s.workers.add(outcome.threadName() + "#" + outcome.threadId());
            JsonObject action = new JsonObject();
            action.addProperty("stage", s.stage.name());
            action.addProperty("workerThreadId", outcome.threadId());
            action.addProperty("workerThreadName", outcome.threadName());
            action.addProperty("observedElapsedNanos", elapsed);
            action.addProperty("failureType", outcome.failure() == null ? null : outcome.failure().getClass().getName());
            if (outcome.failure() instanceof WorldIdentityException failure) action.addProperty("identityFailureCode", failure.code().name());
            s.workerActions.add(action);
            advance(s, outcome);
        } catch (Exception | Error failure) { fail(failure); }
    }

    private WorldBindingRequest begin(Session s, int index) {
        WorldBindingRequest request = s.service.begin(s.actorId, DIMENSIONS);
        require(request.actorId().equals(s.actorId) && request.dimensions().equals(DIMENSIONS), "Request metadata mismatch");
        require(!request.requestId().equals(new UUID(0, 0)), "Zero request ID");
        for (UUID id : s.requestIds) require(!request.requestId().equals(id), "Request ID reused");
        s.requestIds[index] = request.requestId();
        s.steps.add("beginR" + (index + 1));
        return request;
    }

    private void advance(Session s, Outcome outcome) throws Exception {
        if (s.stage == Stage.CANCEL4 || s.stage == Stage.FOREIGN_A) {
            require(outcome.failure() instanceof WorldIdentityException identityFailure
                    && identityFailure.code() == WorldIdentityException.Code.CANCELLED, "Expected worker CANCELLED");
            s.service.verify(s.binding);
            if (s.stage == Stage.CANCEL4) {
                s.steps.add("cancelledCheckWorkerCANCELLEDBindingStillValid");
                s.heldCheck = s.service.check(s.binding);
                s.steps.add("lastCurrentHeldCheckCreatedUnexecuted");
                if (s.number == 2) {
                    expect(WorldBindingException.Code.FOREIGN, () -> s.service.accept(oldRead));
                    expect(WorldBindingException.Code.FOREIGN, () -> s.service.verify(oldBinding));
                    s.service.verify(s.binding);
                    s.steps.add("AReadAndBindingFOREIGNB4StillValid");
                    submit(s, checkTask(oldHeldCheck), Stage.FOREIGN_A);
                } else ready(s);
            } else {
                s.steps.add("AHeldCheckWorkerCANCELLEDAfterRealStopB4StillValid");
                releaseOld();
                ready(s);
            }
            return;
        }
        if (outcome.failure() instanceof Exception failure) throw failure;
        if (outcome.failure() instanceof Error failure) throw failure;
        require(outcome.failure() == null, "Unexpected worker throwable type");
        switch (s.stage) {
            case READ1, READ3, READ4 -> {
                require(outcome.value() instanceof WorldBindingRead, "Expected successful binding read");
                s.read = (WorldBindingRead) outcome.value();
                s.binding = s.service.accept(s.read);
                require(s.binding.actorId().equals(s.actorId) && s.binding.requestId().equals(s.request.requestId())
                        && s.binding.dimensions().equals(DIMENSIONS) && s.binding.worldId().equals(worldId), "Accepted binding metadata/worldId mismatch");
                s.steps.add("acceptR" + (s.stage == Stage.READ1 ? 1 : s.stage == Stage.READ3 ? 3 : 4));
                Stage next = s.stage == Stage.READ1 ? Stage.CHECK1 : s.stage == Stage.READ3 ? Stage.CHECK3 : Stage.CHECK4;
                submitCheck(s, next);
            }
            case CHECK1, CHECK3, CHECK4 -> {
                require(outcome.value() instanceof WorldBindingCheckResult, "Expected successful binding check");
                s.service.confirm((WorldBindingCheckResult) outcome.value());
                s.service.verify(s.binding);
                s.steps.add("confirmAndVerifyR" + (s.stage == Stage.CHECK1 ? 1 : s.stage == Stage.CHECK3 ? 3 : 4));
                if (s.stage == Stage.CHECK1) {
                    s.previousBinding = s.binding;
                    s.request = begin(s, 1);
                    submitRead(s, Stage.READ2);
                } else if (s.stage == Stage.CHECK3) {
                    expect(WorldBindingException.Code.DIMENSION_UNAVAILABLE, () -> s.service.begin(s.actorId,
                            Map.of(OVERWORLD, EditorId.parse("editor_probe:missing_dimension"))));
                    expect(WorldBindingException.Code.STALE, () -> s.service.verify(s.binding));
                    s.steps.add("missingDimensionDIMENSION_UNAVAILABLEB3STALE");
                    s.request = begin(s, 3);
                    submitRead(s, Stage.READ4);
                } else {
                    s.check = s.service.check(s.binding);
                    s.check.cancel();
                    submit(s, checkTask(s.check), Stage.CANCEL4);
                }
            }
            case READ2 -> {
                require(outcome.value() instanceof WorldBindingRead, "Expected successful R2 read");
                WorldBindingRead lateRead = (WorldBindingRead) outcome.value();
                s.request = begin(s, 2);
                expect(WorldBindingException.Code.STALE, () -> s.service.accept(lateRead));
                expect(WorldBindingException.Code.STALE, () -> s.service.verify(s.previousBinding));
                s.previousBinding = null;
                s.steps.add("R2ReadSTALEAfterR3B1STALE");
                submitRead(s, Stage.READ3);
            }
            default -> throw new IllegalStateException("Unexpected completed stage " + s.stage);
        }
    }

    private void ready(Session s) throws IOException {
        s.service.verify(s.binding);
        require(s.heldCheck != null && s.future == null, "Ready without final held check or with pending I/O");
        require(s.workers.size() == 1, "Session did not use exactly one I/O worker thread");
        JsonObject evidence = snapshot(s, "READY");
        evidence.add("implementation", implementations());
        evidence.addProperty("allWorkerActionsOffOwner", true);
        evidence.addProperty("heldCheckUnexecutedAndLastCurrent", true);
        evidence.addProperty("oldTokensReleased", oldRead == null && oldBinding == null && oldHeldCheck == null);
        write("session-" + s.number + "-ready.json", evidence);
        s.ready = true;
        s.stage = Stage.READY;
    }

    private static void submitRead(Session s, Stage next) {
        submit(s, readTask(s.request, new WindowsFileIdentityProvider()), next);
    }

    private static void submitCheck(Session s, Stage next) {
        s.check = s.service.check(s.binding);
        submit(s, checkTask(s.check), next);
    }

    private static void submit(Session s, Callable<Outcome> task, Stage next) {
        require(s.future == null && !s.worker.isShutdown(), "Worker submission overlap/closed");
        s.stage = next;
        s.submittedAt = System.nanoTime();
        s.future = s.worker.submit(task);
    }

    // Static factories ensure worker closures capture only pure work tokens and explicit provider.
    private static Callable<Outcome> readTask(WorldBindingRequest request, FileIdentityProvider provider) {
        return () -> {
            try { return outcome(request.read(provider, () -> false), null); }
            catch (Exception | Error failure) { return outcome(null, failure); }
        };
    }

    private static Callable<Outcome> checkTask(WorldBindingCheck check) {
        return () -> {
            try { return outcome(check.verify(() -> false), null); }
            catch (Exception | Error failure) { return outcome(null, failure); }
        };
    }

    private static Outcome outcome(Object value, Throwable failure) {
        return new Outcome(value, failure, Thread.currentThread().getId(), Thread.currentThread().getName());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void stopping(ServerStoppingEvent event) {
        Session s = current;
        if (s == null || event.getServer() != s.server) {
            if (!failed) fail(new IllegalStateException("Stopping without matching session"));
            return;
        }
        try {
            requireOwner(s.server);
            require(!s.stoppingObserved, "Repeated real Stopping");
            s.stoppingObserved = true;
            if (!failed) {
                verifyManifest();
                require(s.ready, "Stopped before READY");
                require(FPSMCore.getInstance() == s.core && s.core.getServer() == s.server && !s.core.isActive(), "Stopping core still active/replaced");
                expect(WorldBindingException.Code.CLOSED, () -> s.service.verify(s.binding));
                s.steps.add("realStoppingServiceCLOSED");
            }
        } catch (Exception | Error failure) { fail(failure); }
        finally {
            try {
                s.worker.shutdown();
                s.workerTerminated = s.worker.awaitTermination(2, TimeUnit.SECONDS);
                require(s.workerTerminated, "I/O worker did not terminate within 2 seconds");
                if (!failed && s.number == 1) {
                    oldRead = s.read;
                    oldBinding = s.binding;
                    oldHeldCheck = s.heldCheck;
                }
            } catch (Exception | Error failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                fail(failure);
            } finally {
                s.releaseTokens();
                if (failed) releaseOld();
            }
        }
        if (!failed) {
            try {
                JsonObject evidence = snapshot(s, "STOPPING");
                evidence.addProperty("serviceClosed", true);
                evidence.addProperty("playerFutureAndCurrentTokensReleased", s.tokensReleased());
                evidence.addProperty("onlyAPureStopTokensRetained", s.number == 1);
                write("session-" + s.number + "-stopping.json", evidence);
            } catch (Exception | Error failure) { fail(failure); }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void stopped(ServerStoppedEvent event) {
        Session s = current;
        if (s == null || event.getServer() != s.server) {
            if (!failed) fail(new IllegalStateException("Stopped without matching session"));
            return;
        }
        try {
            requireOwner(s.server);
            require(s.stoppingObserved && s.ready && s.workerTerminated, "Stopped missing Stopping/ready/worker termination");
            require(!FPSMCore.initialized() && !s.core.isActive() && s.core.getServer() == s.server, "Stopped core still initialized/active/rebound");
            if (!failed) {
                verifyManifest();
                completedSessions++;
                JsonObject evidence = snapshot(s, "STOPPED");
                s.releaseNative();
                current = null;
                evidence.addProperty("observerNativeReferencesReleased", true);
                evidence.addProperty("observerAllTokensReleased", oldRead == null && oldBinding == null && oldHeldCheck == null && s.tokensReleased());
                write("session-" + s.number + "-stopped.json", evidence);
                if (completedSessions == 2) {
                    require(oldRead == null && oldBinding == null && oldHeldCheck == null, "Final A tokens retained");
                    JsonObject result = base();
                    result.addProperty("status", "PASS");
                    result.addProperty("observerAllNativeAndPureTokenReferencesReleased", true);
                    write("host-result.json", result);
                }
            }
        } catch (Exception | Error failure) { fail(failure); }
        finally {
            // Missing Stopping is failure; still request orderly worker termination without pretending it succeeded.
            if (!s.worker.isShutdown()) {
                s.cancelWork();
                s.worker.shutdown();
                try { s.workerTerminated = s.worker.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); fail(failure); }
                if (!s.workerTerminated) fail(new IllegalStateException("Stopped worker remains alive"));
            }
            s.releaseTokens();
            s.releaseNative();
            current = null;
            if (failed) releaseOld();
        }
    }

    private static void expect(WorldBindingException.Code expected, Runnable operation) {
        try { operation.run(); }
        catch (WorldBindingException failure) {
            require(failure.code() == expected, "Expected " + expected + " but got " + failure.code());
            return;
        }
        throw new IllegalStateException("Expected binding failure " + expected);
    }

    private static void requireOwner(MinecraftServer server) {
        require(server.isSameThread() && !Thread.currentThread().isInterrupted(), "Event requires uninterrupted actual server owner");
    }

    private JsonObject snapshot(Session s, String stage) {
        JsonObject evidence = base();
        evidence.addProperty("stage", stage);
        evidence.addProperty("session", s.number);
        evidence.addProperty("worldFolder", WORLDS[s.number - 1]);
        evidence.addProperty("actualWorldRoot", s.root.toString());
        evidence.addProperty("actorId", s.actorId == null ? null : s.actorId.toString());
        evidence.addProperty("worldId", worldId.toString());
        evidence.addProperty("identitySha256", identitySha);
        evidence.addProperty("eventOwnerThreadName", Thread.currentThread().getName());
        evidence.addProperty("eventOwnerThreadId", Thread.currentThread().getId());
        evidence.addProperty("serverIdentityDisplayOnly", identity(s.server));
        evidence.addProperty("coreIdentityDisplayOnly", identity(s.core));
        evidence.addProperty("serviceIdentityDisplayOnly", identity(s.service));
        evidence.addProperty("eventServerOwner", s.server.isSameThread());
        evidence.addProperty("coreActive", s.core.isActive());
        evidence.addProperty("coreInitialized", FPSMCore.initialized());
        evidence.addProperty("referenceAuthentication", "All core/server/player/service reference assertions use ==; hashes are display only");
        evidence.addProperty("ready", s.ready || stage.equals("READY"));
        evidence.addProperty("stoppingObserved", s.stoppingObserved);
        evidence.addProperty("workerTerminated", s.workerTerminated);
        JsonArray ids = new JsonArray();
        for (UUID id : s.requestIds) ids.add(id == null ? null : id.toString());
        evidence.add("requestIdsR1R2R3R4", ids);
        evidence.add("orderedSteps", s.steps.deepCopy());
        JsonArray threads = new JsonArray();
        for (String thread : s.workers) threads.add(thread);
        evidence.add("workerThreads", threads);
        evidence.add("workerActions", s.workerActions.deepCopy());
        evidence.addProperty("dimensionMapping", "minecraft:overworld->minecraft:overworld");
        evidence.addProperty("coordinateSemantics", "unchanged");
        return evidence;
    }

    private JsonObject base() {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", "Q58_LOCAL_WORLD_BINDING_EVIDENCE_V1");
        evidence.addProperty("recordedAt", Instant.now().toString());
        evidence.addProperty("processId", processId);
        evidence.addProperty("processStartedAt", processStartedAt);
        evidence.addProperty("inputManifestSha256", manifestSha);
        evidence.addProperty("completedSessions", completedSessions);
        evidence.addProperty("scope", SCOPE);
        return evidence;
    }

    private static JsonObject implementations() throws IOException {
        JsonObject result = new JsonObject();
        result.add("FPSMCore", implementation(FPSMCore.class));
        result.add("MinecraftWorldBindings", implementation(MinecraftWorldBindings.class));
        result.add("LocalWorldBindings", implementation(LocalWorldBindings.class));
        result.add("WorldBindingProbe", implementation(WorldBindingProbe.class));
        return result;
    }

    private static JsonObject implementation(Class<?> type) throws IOException {
        String resource = type.getSimpleName() + ".class";
        var url = type.getResource(resource);
        require(url != null, "Runtime class resource unavailable: " + resource);
        JsonObject result = new JsonObject();
        result.addProperty("classResource", url.toExternalForm());
        result.addProperty("anchorCodeSource", type.getProtectionDomain().getCodeSource().getLocation().toExternalForm());
        try (InputStream stream = type.getResourceAsStream(resource)) {
            require(stream != null, "Runtime class bytes unavailable: " + resource);
            result.addProperty("classSha256", sha(stream.readAllBytes()));
        }
        return result;
    }

    private byte[] readManifest() throws IOException {
        Path input = attempt.resolve("input-manifest.json");
        require(Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) && Files.size(input) <= MANIFEST_LIMIT, "Manifest missing/nonregular/over 1 MiB");
        try (InputStream stream = Files.newInputStream(input)) {
            byte[] bytes = stream.readNBytes(MANIFEST_LIMIT + 1);
            require(bytes.length > 0 && bytes.length <= MANIFEST_LIMIT, "Manifest empty/over 1 MiB");
            return bytes;
        }
    }

    private void verifyManifest() throws IOException {
        require(game.toRealPath().equals(game) && attempt.toRealPath().equals(attempt), "Canonical destination changed");
        require(manifestBytes != null && Arrays.equals(manifestBytes, readManifest()), "Manifest bytes changed");
    }

    private void write(String filename, JsonObject evidence) throws IOException {
        verifyManifest();
        Files.writeString(attempt.resolve(filename), new GsonBuilder().setPrettyPrinting().create().toJson(evidence) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private void fail(Throwable failure) {
        if (failed) return;
        failed = true;
        if (current != null) current.cancelWork();
        releaseOld();
        System.err.println("Q58 binding diagnostic FAIL: " + failure.getClass().getName() + ": " + failure.getMessage());
        if (attempt == null) return;
        try {
            JsonObject evidence = base();
            evidence.addProperty("status", "FAIL");
            evidence.addProperty("failureType", failure.getClass().getName());
            evidence.addProperty("failureMessage", failure.getMessage());
            if (current != null) {
                evidence.addProperty("session", current.number);
                evidence.addProperty("stage", current.stage.name());
                evidence.addProperty("workerTerminatedAtFailure", current.workerTerminated);
            }
            boolean authenticated = false;
            try { verifyManifest(); authenticated = true; }
            catch (Exception verificationFailure) { evidence.addProperty("manifestVerificationFailureType", verificationFailure.getClass().getName()); }
            evidence.addProperty("manifestAuthenticatedAtFailure", authenticated);
            require(Files.isDirectory(attempt, LinkOption.NOFOLLOW_LINKS) && attempt.toRealPath().equals(attempt), "Failure destination changed");
            Files.writeString(attempt.resolve("host-failure.json"), new GsonBuilder().setPrettyPrinting().create().toJson(evidence) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (Exception | Error writeFailure) {
            System.err.println("Q58 failure evidence unavailable: " + writeFailure.getClass().getName() + ": " + writeFailure.getMessage());
        }
    }

    private void releaseOld() { oldRead = null; oldBinding = null; oldHeldCheck = null; }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected string: " + key);
        return value.getAsString();
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private static String identity(Object value) {
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private enum Stage { INITIAL, READ1, CHECK1, READ2, READ3, CHECK3, READ4, CHECK4, CANCEL4, FOREIGN_A, READY }
    private record Outcome(Object value, Throwable failure, long threadId, String threadName) { }

    private static final class Session {
        final int number;
        final Path root;
        final ExecutorService worker;
        final UUID[] requestIds = new UUID[4];
        final JsonArray steps = new JsonArray();
        final JsonArray workerActions = new JsonArray();
        final Set<String> workers = new java.util.LinkedHashSet<>();
        MinecraftServer server;
        FPSMCore core;
        LocalWorldBindings service;
        ServerPlayer player;
        UUID actorId;
        WorldBindingRequest request;
        WorldBindingRead read;
        WorldBinding binding;
        WorldBinding previousBinding;
        WorldBindingCheck check;
        WorldBindingCheck heldCheck;
        Future<Outcome> future;
        Stage stage = Stage.INITIAL;
        long submittedAt;
        boolean ready;
        boolean stoppingObserved;
        boolean workerTerminated;

        Session(int number, MinecraftServer server, FPSMCore core, LocalWorldBindings service, Path root) {
            this.number = number;
            this.server = server;
            this.core = core;
            this.service = service;
            this.root = root;
            String workerName = "q58-binding-io-" + number;
            worker = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, workerName);
                thread.setDaemon(true);
                return thread;
            });
        }

        void cancelWork() {
            if (request != null) request.cancel();
            if (check != null) check.cancel();
            if (heldCheck != null) heldCheck.cancel();
        }

        void releaseTokens() {
            player = null;
            future = null;
            request = null;
            read = null;
            binding = null;
            previousBinding = null;
            check = null;
            heldCheck = null;
        }

        boolean tokensReleased() {
            return player == null && future == null && request == null && read == null && binding == null
                    && previousBinding == null && check == null && heldCheck == null;
        }

        void releaseNative() { server = null; core = null; service = null; }
    }
}
