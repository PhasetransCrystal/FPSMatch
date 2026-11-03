package net.ptcrys.fpsmatch.core.shop.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraftforge.common.MinecraftForge;
import net.ptcrys.fpsmatch.common.capability.map.GameEndTeleportCapability;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.capability.team.SpawnPointCapability;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.common.event.FPSMapEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLease;
import net.ptcrys.fpsmatch.core.RuntimeMapLeaseException;
import net.ptcrys.fpsmatch.core.ShutdownCleanup;
import net.ptcrys.fpsmatch.core.capability.CapabilityMap;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.data.Setting;
import net.ptcrys.fpsmatch.core.data.SpawnPointData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.core.persistence.ISavePort;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.core.team.TeamData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real Forge server holders; private temporary files never point into the live world. */
class RuntimeMapPersistenceTest {
    @Test void constructionNameRemainsOccupiedThroughReleaseAndIsFreedEvenWhenReleaseFails() throws Exception {
        try (Fixture f = new Fixture()) {
            String type = f.map.getGameType(), name = f.map.mapName;
            AssertionError marker = new AssertionError("q80 release failure");
            f.lease = f.core.ownRuntimeMap(f.map, () -> {
                f.releases.incrementAndGet();
                f.assertConsumersClosed();
                problem("duplicate", () -> f.core.requireUnoccupiedMapName(type, name));
                f.owner.close();
                throw marker;
            });
            assertSame(marker, assertThrows(AssertionError.class, f.lease::close));
            f.core.requireUnoccupiedMapName(type, name);
            f.lease.close();
            assertEquals(1, f.releases.get());
        }
    }

    @Test void liveConstructionNamesUseFixedTypeNameAndRetirementReleasesOnlyTheirLease() throws Exception {
        FPSMCore core = FPSMCore.getInstance();
        String sharedName = "q80_" + UUID.randomUUID().toString().replace("-", "");
        ProbeMap duplicate = MapConstructionScope.construct(() -> new ProbeMap(core.getServer().overworld(), sharedName));
        // Remove this unclaimed fixture's spectator before constructing the real holder.
        // Two live same-name legacy constructors are intentionally not a safe product path.
        duplicate.getMapTeams().shutdown(core.getServer().getScoreboard());
        try (Fixture f = new Fixture(sharedName)) {
            String type = f.map.getGameType(), name = f.map.mapName;
            f.core.requireUnoccupiedMapName(type, name);
            f.adopt();
            problem("duplicate", () -> core.ownRuntimeMap(duplicate, () -> fail("duplicate adoption transferred cleanup")));
            assertFalse(duplicate.hasRuntimeOwnership());
            assertFalse(duplicate.getCapabilityMap().isClosed());
            f.map.denyNames = true;
            problem("duplicate", () -> f.core.requireUnoccupiedMapName(type, name));
            assertEquals(0, f.map.nameCalls, "occupancy must use the captured key, never overridable getters");
            f.core.requireUnoccupiedMapName(type + "_other", name);
            f.core.requireUnoccupiedMapName(type, name + "_other");
            onWorker(() -> problem("wrong_thread", () -> f.core.requireUnoccupiedMapName(null, null)));
            f.lease.close();
            f.core.requireUnoccupiedMapName(type, name);
            f.lease.close();
            f.core.requireUnoccupiedMapName(type, name);
        } finally {
            duplicate.getCapabilityMap().close();
            duplicate.getMapTeams().shutdown(core.getServer().getScoreboard());
        }
    }

    @Test void failedAdoptionDoesNotReserveNamesAndGamesOccupancyAllowsAdoptingTheExactExistingMap() throws Exception {
        try (Fixture f = new Fixture()) {
            String type = f.map.getGameType(), name = f.map.mapName;
            try (BaseMap.LegacyPersistence ignored = f.map.openLegacyPersistence()) {
                problem("busy", () -> f.core.ownRuntimeMap(f.map, () -> fail("failed adoption ran release")));
                f.core.requireUnoccupiedMapName(type, name);
            }
            Map<String, List<BaseMap>> games = f.core.getAllMaps();
            boolean had = games.containsKey(type);
            List<BaseMap> previous = games.get(type);
            List<BaseMap> fixtureMaps = new ArrayList<>(had ? previous : List.of());
            fixtureMaps.add(f.map);
            games.put(type, fixtureMaps);
            try {
                f.map.denyNames = true;
                problem("duplicate", () -> f.core.requireUnoccupiedMapName(type, name));
                assertEquals(0, f.map.nameCalls);
                f.map.clearObservations();
                f.adopt();
                f.lease.close();
                problem("duplicate", () -> f.core.requireUnoccupiedMapName(type, name));
            } finally {
                if (had) games.put(type, previous); else games.remove(type);
            }
            f.core.requireUnoccupiedMapName(type, name);
        }
    }

    @Test void unclaimedConfigurationAndPlainSavePortsKeepLegacyBehavior() throws Exception {
        try (Fixture f = new Fixture()) {
            assertDoesNotThrow(f.map::requireLegacyPersistence);
            Setting<Integer> setting = f.map.addSetting("q76_value", 17);
            f.map.saveConfig();
            assertTrue(Files.isRegularFile(f.cfg));
            assertEquals(17, com.google.gson.JsonParser.parseString(Files.readString(f.cfg))
                    .getAsJsonObject().get("q76_value").getAsInt());
            setting.set(29);
            f.map.loadConfig();
            assertEquals(17, setting.get());
            assertNotNull(f.map.getCapabilityMap().getData());
            assertNotNull(f.team.getCapabilityMap().getData());
            f.map.getMapTeams().writeData(Map.of());
            Port<ProbeMap> port = mapPort(f);
            assertEquals(1, port.encodeToJson(f.map).getAsJsonObject().get("version").getAsInt());
            assertTrue(port.createNewDataFile(f.temp.toFile(), "legacy", f.map));
            port.getWriter(f.map, "legacy", true).accept(f.temp.toFile());
            assertTrue(Files.size(f.temp.resolve("legacy.json")) > 0);
            Port<String> ordinary = new Port<>(Codec.STRING);
            assertTrue(ordinary.createNewDataFile(f.temp.toFile(), "plain", "ordinary"));
            assertEquals("ordinary", ordinary.encodeToJson("ordinary").getAsJsonObject().get("data").getAsString());
            assertFalse(f.map.hasRuntimeOwnership());
            f.adopt(); // all successful nested legacy scopes have ended.
        }
    }

    @Test void ownedCfgRejectsBeforeObservingExistingOrMissingPrivateFiles() throws Exception {
        for (boolean exists : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                byte[] sentinel = "untouched CFG sentinel\n".getBytes(StandardCharsets.UTF_8);
                if (exists) Files.write(f.cfg, sentinel);
                f.adopt();
                f.map.denyConfigFile = true;
                owned(f.map::saveConfig);
                owned(f.map::loadConfig);
                assertEquals(0, f.map.configFileCalls);
                assertEquals(exists, Files.exists(f.cfg));
                if (exists) assertArrayEquals(sentinel, Files.readAllBytes(f.cfg));
            }
        }
    }

    @Test void ownedConfigurationRejectsPoisonAndNullBeforeSettingsOrJson() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger settings = new AtomicInteger(), json = new AtomicInteger();
            f.map.addSetting(new PoisonSetting(settings));
            f.adopt();
            for (boolean poisonCollection : List.of(false, true)) {
                f.map.denySettings = poisonCollection;
                owned(f.map::configToJson);
                for (JsonElement input : new JsonElement[]{null, JsonNull.INSTANCE, new JsonPrimitive(""), new PoisonJson(json)})
                    owned(() -> f.map.configFromJson(input));
            }
            assertEquals(0, settings.get());
            assertEquals(0, json.get());
            assertEquals(0, f.map.settingsCalls);
        }
    }

    @Test void ownedReloadLoadAndRegisterRejectBeforeEventsNamesOrGamesMutation() throws Exception {
        try (Fixture f = new Fixture(); Events events = new Events(f.map)) {
            f.adopt();
            f.map.denyNames = true;
            f.map.denyConfigFile = true;
            owned(f.map::reload);
            owned(f.map::load);
            owned(() -> f.core.registerMap(null, f.map));
            owned(() -> f.core.registerMap("unregistered-q76", f.map));
            assertEquals(0, events.calls.get());
            assertEquals(0, f.map.nameCalls);
            assertEquals(0, f.map.configFileCalls);
            f.baseline.assertGamesAndLeases(f.lease);
        }
    }

    @Test void mapTeamAndSpectatorSerializationPropagatesOwnedInsteadOfEmptySentinels() throws Exception {
        try (Fixture f = new Fixture()) {
            assertNotNull(f.map.getCapabilityMap().getData());
            assertNotNull(f.team.getCapabilityMap().getData());
            f.adopt();
            owned(f.map.getCapabilityMap()::getData);
            for (ServerTeam team : f.teams) owned(team.getCapabilityMap()::getData);
            owned(f.map.getMapTeams()::getData);
            assertTrue(f.teams.stream().anyMatch(team -> team == f.map.getMapTeams().getSpectatorTeam()));
            assertFalse(f.map.getCapabilityMap().isClosed());
            assertFalse(f.team.getCapabilityMap().isClosed());
        }
    }

    @Test void everyCapabilityWriteOverloadRejectsBeforePoisonNullOrEmptyInputs() throws Exception {
        try (Fixture f = new Fixture()) {
            f.adopt();
            AtomicInteger touched = new AtomicInteger();
            JsonElement json = new PoisonJson(touched);
            for (CapabilityMap<?, ?> container : f.containers) {
                owned(() -> container.write((String) null, null));
                owned(() -> container.write("unregistered", json));
                owned(() -> container.write((CapabilityMap.Wrapper) null));
                owned(() -> container.write(new CapabilityMap.Wrapper(Map.of())));
                owned(() -> container.write(new CapabilityMap.Wrapper(new ObservingMap<>(() -> poison(touched)))));
                owned(() -> container.write((Map<String, JsonElement>) null));
                owned(() -> container.write(Map.<String, JsonElement>of()));
                owned(() -> container.write(new ObservingMap<String, JsonElement>(() -> poison(touched))));
            }
            owned(() -> f.map.getCapabilityMap().write(GameEndTeleportCapability.class, (SpawnPointData) null));
            owned(() -> f.map.getCapabilityMap().write(null, (SpawnPointData) null));
            for (ServerTeam team : f.teams) {
                owned(() -> team.getCapabilityMap().write(SpawnPointCapability.class, List.<SpawnPointData>of()));
                owned(() -> team.getCapabilityMap().write(SpawnPointCapability.class, null));
                owned(() -> team.getCapabilityMap().write(SpawnPointCapability.class,
                        new java.util.AbstractList<SpawnPointData>() {
                            public int size() { poison(touched); return 0; }
                            public SpawnPointData get(int index) { poison(touched); return null; }
                        }));
            }
            assertEquals(0, touched.get());
        }
    }

    @Test void mapTeamsRestoreHoldsScopeAndOwnedRejectsEvenEmptyOrPoisonMaps() throws Exception {
        try (Fixture f = new Fixture()) {
            AtomicInteger observed = new AtomicInteger();
            f.map.getMapTeams().writeData(new ObservingMap<>(() -> {
                observed.incrementAndGet(); f.assertBusyWithoutTransfer();
            }));
            assertEquals(1, observed.get());
            f.adopt();
            owned(f.map.getMapTeams()::getData);
            owned(() -> f.map.getMapTeams().writeData(null));
            owned(() -> f.map.getMapTeams().writeData(Map.of()));
            owned(() -> f.map.getMapTeams().writeData(new ObservingMap<>(() -> poison(observed))));
            assertEquals(1, observed.get());
        }
        // No nested CapabilityMap remains to mask a missing MapTeams.getData front gate.
        try (Fixture f = new Fixture()) {
            for (ServerTeam team : f.teams) f.map.getMapTeams().delTeam(team.getPlayerTeam());
            assertTrue(f.map.getMapTeams().getTeamsWithSpectator().isEmpty());
            assertTrue(f.map.getMapTeams().getData().isEmpty());
            f.teams.forEach(team -> assertTrue(team.getCapabilityMap().isClosed()));
            f.adopt();
            owned(f.map.getMapTeams()::getData);
        }
    }

    @Test void savePortOwnedGatesPrecedeCodecVersionDirectoryAndFileCreation() throws Exception {
        try (Fixture f = new Fixture()) {
            Port<ProbeMap> port = mapPort(f);
            f.adopt();
            port.poison = true;
            AtomicInteger touched = new AtomicInteger();
            File directory = new PoisonFile(f.temp.resolve("absent-directory"), touched);
            owned(() -> port.encodeToJson(f.map));
            owned(() -> port.getWriter(f.map, null, true));
            owned(() -> port.getWriter(f.map, "", false));
            owned(() -> port.createNewDataFile(null, null, f.map));
            owned(() -> port.createNewDataFile(directory, "", f.map));
            assertEquals(0, port.calls.get());
            assertEquals(0, touched.get());
            assertFalse(Files.exists(f.temp.resolve("absent-directory")));
        }
    }

    @Test void writersAcquiredBeforeClaimRecheckEveryExecutionAndPreserveJsonFiles() throws Exception {
        try (Fixture f = new Fixture()) {
            byte[] sentinel = "{\"original\":true}\n".getBytes(StandardCharsets.UTF_8);
            Path existing = f.temp.resolve("existing.json");
            Files.write(existing, sentinel);
            Port<ProbeMap> port = mapPort(f);
            List<Consumer<File>> writers = List.of(port.getWriter(f.map, "existing", false),
                    port.getWriter(f.map, "missing", true), port.getWriter(f.map, null, false));
            f.adopt(); // obtaining writers must not reserve an executing-operation scope.
            port.poison = true;
            AtomicInteger touched = new AtomicInteger();
            File poison = new PoisonFile(f.temp.resolve("never-observed"), touched);
            for (Consumer<File> writer : writers) {
                owned(() -> writer.accept(f.temp.toFile()));
                owned(() -> writer.accept(poison));
                owned(() -> writer.accept(null));
            }
            assertArrayEquals(sentinel, Files.readAllBytes(existing));
            assertFalse(Files.exists(f.temp.resolve("missing.json")));
            assertEquals(0, port.calls.get());
            assertEquals(0, touched.get());
        }
    }

    @Test void managerSyncAtomicAndExactAsyncFutureRejectBeforeRegistryOrPaths() throws Exception {
        try (Fixture f = new Fixture()) {
            var manager = f.core.getFPSMDataManager();
            assertDoesNotThrow(() -> manager.saveData(f.map, null, true)); // private fixture type is unregistered.
            f.adopt();
            owned(() -> manager.saveData(f.map, null, false));
            owned(() -> manager.saveDataAtomic(f.map, null));
            CompletableFuture<Void> actual = manager.saveDataAsync(f.map, null, true);
            assertThrows(java.util.concurrent.ExecutionException.class, () -> actual.get(10, TimeUnit.SECONDS));
            CompletionException failure = assertThrows(CompletionException.class, actual::join);
            assertOwnedFailure(failure.getCause());
            assertTrue(actual.isCompletedExceptionally());
            f.baseline.assertGamesAndLeases(f.lease);
            assertEquals(0, f.map.configFileCalls);
        }
    }

    @Test void nestedAndExceptionalScopesKeepClaimsBusyThenReleaseForRecovery() throws Exception {
        try (Fixture f = new Fixture()) {
            try (BaseMap.LegacyPersistence outer = f.map.openLegacyPersistence()) {
                try (BaseMap.LegacyPersistence inner = f.map.openLegacyPersistence()) {
                    f.assertBusyWithoutTransfer();
                }
                f.assertBusyWithoutTransfer();
            }
            for (Throwable marker : List.of(new IllegalArgumentException("scope"), new AssertionError("scope"))) {
                assertSame(marker, assertThrows(marker.getClass(), () -> {
                    try (BaseMap.LegacyPersistence ignored = f.map.openLegacyPersistence()) { throwUnchecked(marker); }
                }));
                assertDoesNotThrow(f.map::requireLegacyPersistence);
            }
            AtomicInteger callbacks = new AtomicInteger();
            JsonElement configuration = f.map.configToJson();
            f.map.settingsObservation = () -> { callbacks.incrementAndGet(); f.assertBusyWithoutTransfer(); };
            assertNotNull(f.map.configToJson());
            f.map.configFromJson(configuration);
            f.map.settingsObservation = null;
            assertEquals(2, callbacks.get());
            for (CapabilityMap<?, ?> container : f.containers) {
                Runnable duringTraversal = () -> { callbacks.incrementAndGet(); f.assertBusyWithoutTransfer(); };
                container.write(new ObservingMap<String, JsonElement>(duringTraversal));
                container.write(new CapabilityMap.Wrapper(new ObservingMap<>(duringTraversal)));
            }
            assertEquals(2 + 2 * f.containers.size(), callbacks.get());
            Port<ProbeMap> port = mapPort(f);
            port.observation = f::assertBusyWithoutTransfer;
            assertNotNull(port.encodeToJson(f.map));
            assertEquals(2, port.calls.get());
            f.adopt();
        }
    }

    @Test void wrongThreadAndIdempotentScopeCloseCannotReleaseAnotherActivity() throws Exception {
        try (Fixture f = new Fixture()) {
            BaseMap.LegacyPersistence outer = f.map.openLegacyPersistence();
            BaseMap.LegacyPersistence inner = f.map.openLegacyPersistence();
            try {
                onWorker(() -> problem("wrong_thread", outer::close));
                f.assertBusyWithoutTransfer();
                outer.close();
                outer.close();
                f.assertBusyWithoutTransfer(); // repeated outer close must not decrement inner.
                onWorker(() -> problem("wrong_thread", outer::close));
            } finally { inner.close(); outer.close(); }
            f.adopt();
        }
    }

    @Test void activeWorkerScopeAndRealWriterMakeClaimNonBlockingBusyUntilJoined() throws Exception {
        for (boolean writer : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                HeldWorker worker = new HeldWorker();
                RuntimeException marker = new IllegalArgumentException("worker operation");
                Consumer<File> late = mapPort(f).getWriter(f.map, "unused", true);
                worker.start(() -> {
                    if (writer) late.accept(new BlockingFile(f.temp.resolve("not-a-real-io-target"), worker, marker));
                    else try (BaseMap.LegacyPersistence ignored = f.map.openLegacyPersistence()) {
                        worker.hold(); throw marker;
                    }
                });
                try {
                    assertTrue(worker.entered.await(10, TimeUnit.SECONDS), "worker must enter the actual operation");
                    assertEquals(1, worker.release.getCount());
                    f.assertBusyWithoutTransfer();
                    assertEquals(1, worker.release.getCount(), "claim returned without releasing or waiting for the operation");
                    assertTrue(worker.thread.isAlive());
                } finally { worker.close(); }
                assertSame(marker, worker.failure.get(), "operation failure must escape after the held scope closes");
                assertTrue(worker.finished.getCount() == 0 && !worker.thread.isAlive());
                f.assertUnclaimedAndOpen();
                f.adopt();
                assertSame(f.lease, leases(f.core).get(f.map));
            }
        }
    }

    @Test void retiredMapPermanentlyRejectsLegacyAndLeaseClosesConsumersBeforeTemplates() throws Exception {
        try (Fixture f = new Fixture()) {
            StartKitsCapability cap = f.team.getCapabilityMap().get(StartKitsCapability.class).orElseThrow();
            RuntimeKitTemplates kits = new RuntimeKitTemplates(f.owner, List.of(f.template));
            cap.useRuntimeKits(kits);
            f.adopt();
            assertSame(kits, cap.runtimeKits().orElseThrow());
            f.lease.close();
            assertEquals(1, f.releases.get());
            assertTrue(f.map.hasRuntimeOwnership());
            for (Executable action : List.<Executable>of(f.map::requireLegacyPersistence, f.map::openLegacyPersistence,
                    f.map::configToJson, () -> f.map.configFromJson(null), f.map::saveConfig, f.map::loadConfig,
                    f.map::reload, f.map::load, f.map.getCapabilityMap()::getData, f.team.getCapabilityMap()::getData,
                    f.map.getMapTeams()::getData, () -> f.map.getMapTeams().writeData(Map.of()),
                    () -> f.core.registerMap(null, f.map))) owned(action);
            problem("duplicate", () -> f.core.ownRuntimeMap(f.map, () -> fail("must not transfer again")));
            problem("closed", f.lease::requireOpen);
            RuntimeItemException closed = assertThrows(RuntimeItemException.class, cap::runtimeKits);
            assertEquals("editor:runtime_items/closed", closed.getMessage());
            assertDoesNotThrow(f.lease::close);
            assertEquals(1, f.releases.get());
            f.baseline.assertUnchanged();
        }
    }

    private static final class ProbeMap extends BaseMap {
        File configFile;
        boolean denyConfigFile, denySettings, denyNames;
        int configFileCalls, settingsCalls, nameCalls;
        Runnable settingsObservation;
        ProbeMap(ServerLevel level, String name) { super(level, name, new AreaData(BlockPos.ZERO, new BlockPos(2, 2, 2))); }
        @Override public boolean victoryGoal() { return false; }
        @Override public String getGameType() {
            if (denyNames) { nameCalls++; throw new AssertionError("game type read before persistence gate"); }
            return "q76_native";
        }
        @Override public String getMapName() {
            if (denyNames) { nameCalls++; throw new AssertionError("map name read before persistence gate"); }
            return super.getMapName();
        }
        @Override public File getConfigFile() {
            configFileCalls++;
            if (denyConfigFile) throw new AssertionError("CFG path observed before persistence gate");
            return configFile;
        }
        @Override public Collection<Setting<?>> settings() {
            settingsCalls++;
            if (denySettings) throw new AssertionError("settings observed before persistence gate");
            if (settingsObservation != null) settingsObservation.run();
            return super.settings();
        }
        void clearObservations() {
            denyConfigFile = false; denySettings = false; denyNames = false; settingsObservation = null;
            configFileCalls = 0; settingsCalls = 0; nameCalls = 0;
        }
    }

    private record Parts(ProbeMap map, ServerTeam team, RuntimeItemTemplates owner, RuntimeItemTemplates.Template template) { }

    private static final class Fixture implements AutoCloseable {
        final FPSMCore core;
        final ServerLevel level;
        final Baseline baseline;
        final ProbeMap map;
        final ServerTeam team;
        final RuntimeItemTemplates owner;
        final RuntimeItemTemplates.Template template;
        final List<ServerTeam> teams;
        final List<CapabilityMap<?, ?>> containers = new ArrayList<>();
        final AtomicInteger releases = new AtomicInteger();
        final Path temp, cfg;
        RuntimeMapLease lease;
        Fixture() throws IOException {
            this("q76_" + UUID.randomUUID().toString().replace("-", ""));
        }
        Fixture(String runtimeName) throws IOException {
            core = FPSMCore.getInstance();
            assertTrue(core.isActive() && core.getServer().isSameThread(), "actual active server owner required");
            level = core.getServer().overworld();
            assertNotNull(level);
            assertSame(core.getServer(), level.getServer());
            assertSame(level, core.getServer().getLevel(level.dimension()));
            baseline = new Baseline(core);
            temp = Files.createTempDirectory("fpsmatch-q76-native-").toRealPath();
            cfg = temp.resolve("private.cfg");
            Parts parts;
            try {
                parts = MapConstructionScope.construct(() -> {
                    RuntimeItemTemplates owner = MapConstructionScope.acquire(() -> new RuntimeItemTemplates(1), RuntimeItemTemplates::close);
                    ItemStack borrowed = new ItemStack(Items.STONE);
                    RuntimeItemTemplates.Template template;
                    try { template = owner.capture(borrowed, stack -> {}, () -> false); }
                    finally { borrowed.invalidateCaps(); }
                    owner.seal();
                    ProbeMap map = new ProbeMap(level, runtimeName);
                    assertTrue(map.getCapabilityMap().add(GameEndTeleportCapability.class));
                    ServerTeam team = map.getMapTeams().addTeam(TeamData.of("test", 1,
                            List.of(ShopCapability.class, StartKitsCapability.class)));
                    return new Parts(map, team, owner, template);
                });
            } catch (RuntimeException | Error failure) {
                try { deletePrivateTree(temp); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            map = parts.map(); team = parts.team(); owner = parts.owner(); template = parts.template();
            map.configFile = cfg.toFile();
            map.clearObservations();
            teams = List.copyOf(map.getMapTeams().getTeamsWithSpectator());
            containers.add(map.getCapabilityMap());
            teams.forEach(value -> containers.add(value.getCapabilityMap()));
            try { assertUnclaimedAndOpen(); }
            catch (RuntimeException | Error failure) {
                try { close(); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void adopt() {
            assertNull(lease);
            lease = core.ownRuntimeMap(map, () -> ShutdownCleanup.run(() -> {
                releases.incrementAndGet();
                owner.requireAvailable(); // release begins only after consumers were closed.
                assertConsumersClosed();
            }, List.of(owner::close)));
            assertTrue(map.hasRuntimeOwnership());
            assertSame(lease, leases(core).get(map));
            map.clearObservations();
        }
        void assertBusyWithoutTransfer() {
            problem("busy", () -> core.ownRuntimeMap(map, () -> fail("failed claim must not transfer cleanup")));
            assertUnclaimedAndOpen();
        }
        void assertUnclaimedAndOpen() {
            assertFalse(map.hasRuntimeOwnership());
            baseline.assertGamesAndLeases(null);
            assertEquals(0, releases.get());
            containers.forEach(container -> assertFalse(container.isClosed()));
            teams.forEach(value -> assertSame(value.getPlayerTeam(), level.getScoreboard().getPlayerTeam(value.getFixedName())));
            assertDoesNotThrow(owner::requireAvailable);
        }
        void assertConsumersClosed() {
            containers.forEach(container -> { assertTrue(container.isClosed()); assertTrue(container.values().isEmpty()); });
            teams.forEach(value -> assertNull(level.getScoreboard().getPlayerTeam(value.getFixedName())));
        }
        @Override public void close() {
            map.clearObservations();
            List<Runnable> cleanup = new ArrayList<>();
            if (lease == null) {
                cleanup.add(map.getCapabilityMap()::close);
                cleanup.add(() -> map.getMapTeams().shutdown(level.getScoreboard()));
                cleanup.add(owner::close);
            } else cleanup.add(lease::close); // never repair a claimed consumer or owner behind its lease.
            cleanup.add(this::assertConsumersClosed);
            cleanup.add(() -> {
                RuntimeItemException failure = assertThrows(RuntimeItemException.class, owner::requireAvailable);
                assertEquals("editor:runtime_items/closed", failure.getMessage());
                if (lease != null) assertEquals(1, releases.get());
            });
            cleanup.add(baseline::assertUnchanged);
            cleanup.add(() -> deletePrivateTree(temp));
            ShutdownCleanup.run(() -> {}, cleanup);
        }
    }

    private static final class Baseline {
        final FPSMCore core;
        final Map<String, PlayerTeam> scoreboard = new LinkedHashMap<>();
        final Map<String, Set<String>> members = new LinkedHashMap<>();
        final Map<String, List<BaseMap>> gameLists = new LinkedHashMap<>(), contents = new LinkedHashMap<>();
        final Map<BaseMap, RuntimeMapLease> originalLeases;
        Baseline(FPSMCore core) {
            this.core = core;
            core.getServer().getScoreboard().getPlayerTeams().forEach(team -> {
                scoreboard.put(team.getName(), team); members.put(team.getName(), new HashSet<>(team.getPlayers()));
            });
            core.getAllMaps().forEach((type, maps) -> { gameLists.put(type, maps); contents.put(type, List.copyOf(maps)); });
            originalLeases = leases(core);
        }
        void assertGamesAndLeases(RuntimeMapLease added) {
            assertEquals(gameLists.keySet(), core.getAllMaps().keySet());
            contents.forEach((type, maps) -> {
                List<BaseMap> actual = core.getAllMaps().get(type);
                assertSame(gameLists.get(type), actual); assertEquals(maps.size(), actual.size());
                for (int i = 0; i < maps.size(); i++) assertSame(maps.get(i), actual.get(i));
            });
            Map<BaseMap, RuntimeMapLease> current = leases(core);
            assertEquals(originalLeases.size() + (added == null ? 0 : 1), current.size());
            originalLeases.forEach((map, lease) -> assertSame(lease, current.get(map)));
            if (added != null) assertTrue(current.values().stream().anyMatch(value -> value == added));
        }
        void assertUnchanged() {
            assertGamesAndLeases(null);
            Map<String, PlayerTeam> current = new LinkedHashMap<>();
            core.getServer().getScoreboard().getPlayerTeams().forEach(team -> current.put(team.getName(), team));
            assertEquals(scoreboard.keySet(), current.keySet());
            scoreboard.forEach((name, team) -> { assertSame(team, current.get(name)); assertEquals(members.get(name), new HashSet<>(team.getPlayers())); });
        }
    }

    /** Read-only identity evidence for an unexposed registry; never changes core fields. */
    @SuppressWarnings("unchecked")
    private static Map<BaseMap, RuntimeMapLease> leases(FPSMCore core) {
        try {
            Field field = FPSMCore.class.getDeclaredField("runtimeMapLeases");
            field.setAccessible(true);
            return new IdentityHashMap<>((Map<BaseMap, RuntimeMapLease>) field.get(core));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static final class Port<T> implements ISavePort<T> {
        final Codec<T> codec;
        final AtomicInteger calls = new AtomicInteger();
        boolean poison;
        Runnable observation;
        Port(Codec<T> codec) { this.codec = codec; }
        private void touch() {
            calls.incrementAndGet();
            if (poison) throw new AssertionError("port callback before persistence gate");
            if (observation != null) observation.run();
        }
        @Override public Codec<T> codec() { touch(); return codec; }
        @Override public int getVersion() { touch(); return 1; }
        @Override public Class<?> getHolderClass() { touch(); return Object.class; }
        @Override public String getFileType() { touch(); return "json"; }
    }

    private static Port<ProbeMap> mapPort(Fixture f) { return new Port<>(Codec.STRING.xmap(ignored -> f.map, ignored -> "legacy")); }

    private static final class PoisonSetting extends Setting<Integer> {
        final AtomicInteger calls;
        PoisonSetting(AtomicInteger calls) { super("q76_poison", Codec.INT, 1); this.calls = calls; }
        @Override public String getConfigName() { poison(calls); return "unreachable"; }
        @Override public JsonElement toJson() { poison(calls); return JsonNull.INSTANCE; }
        @Override public void fromJson(JsonElement input) { poison(calls); }
    }

    private static final class PoisonJson extends JsonElement {
        final AtomicInteger calls;
        PoisonJson(AtomicInteger calls) { this.calls = calls; }
        @Override public JsonElement deepCopy() { poison(calls); return this; }
        @Override public JsonObject getAsJsonObject() { poison(calls); return null; }
        @Override public boolean isJsonNull() { poison(calls); return false; }
        @Override public boolean isJsonPrimitive() { poison(calls); return false; }
        @Override public String toString() { poison(calls); return ""; }
    }

    private static final class ObservingMap<K, V> extends AbstractMap<K, V> {
        final Runnable observe;
        ObservingMap(Runnable observe) { this.observe = observe; }
        @Override public void forEach(BiConsumer<? super K, ? super V> action) { observe.run(); }
        @Override public Set<Entry<K, V>> entrySet() { observe.run(); return Set.of(); }
    }

    private static final class PoisonFile extends File {
        private static final long serialVersionUID = 1L;
        final AtomicInteger calls;
        PoisonFile(Path path, AtomicInteger calls) { super(path.toString()); this.calls = calls; }
        @Override public boolean exists() { poison(calls); return false; }
        @Override public boolean isDirectory() { poison(calls); return false; }
        @Override public boolean mkdirs() { poison(calls); return false; }
    }

    private static final class BlockingFile extends File {
        private static final long serialVersionUID = 1L;
        final HeldWorker worker;
        final RuntimeException marker;
        BlockingFile(Path path, HeldWorker worker, RuntimeException marker) { super(path.toString()); this.worker = worker; this.marker = marker; }
        @Override public boolean exists() { worker.hold(); throw marker; }
    }

    private static final class HeldWorker implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread;
        void start(Runnable action) {
            thread = new Thread(() -> {
                try { action.run(); } catch (Throwable thrown) { failure.set(thrown); }
                finally { finished.countDown(); }
            }, "q76-held-legacy-operation");
            thread.setDaemon(true); thread.start();
        }
        void hold() {
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS), "owner must release held worker in finally"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        }
        @Override public void close() throws InterruptedException {
            release.countDown();
            try { thread.join(10_000); }
            finally { if (thread.isAlive()) { thread.interrupt(); thread.join(1_000); } }
            assertFalse(thread.isAlive(), "exact worker must terminate before fixture cleanup");
            assertEquals(0, finished.getCount());
        }
    }

    private static final class Events implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final Consumer<FPSMapEvent.ReloadEvent> reload;
        final Consumer<FPSMapEvent.LoadEvent> load;
        Events(BaseMap map) {
            reload = event -> { if (event.getMap() == map) calls.incrementAndGet(); };
            load = event -> { if (event.getMap() == map) calls.incrementAndGet(); };
            MinecraftForge.EVENT_BUS.addListener(reload);
            MinecraftForge.EVENT_BUS.addListener(load);
        }
        @Override public void close() { MinecraftForge.EVENT_BUS.unregister(reload); MinecraftForge.EVENT_BUS.unregister(load); }
    }

    private static void onWorker(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } }, "q76-scope-close");
        thread.setDaemon(true); thread.start();
        try { thread.join(10_000); }
        finally { if (thread.isAlive()) { thread.interrupt(); thread.join(1_000); } }
        assertFalse(thread.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void deletePrivateTree(Path root) {
        try {
            assertTrue(root.isAbsolute() && root.toRealPath().equals(root));
            try (var paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    assertTrue(path.toAbsolutePath().normalize().startsWith(root));
                    try { Files.delete(path); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
            }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    private static void poison(AtomicInteger calls) { calls.incrementAndGet(); throw new AssertionError("input observed before persistence gate"); }
    private static void owned(Executable action) { problem("owned_definition", action); }
    private static RuntimeMapLeaseException problem(String reason, Executable action) {
        RuntimeMapLeaseException failure = assertThrows(RuntimeMapLeaseException.class, action);
        assertEquals("editor:runtime_map/" + reason, failure.getMessage());
        assertEquals("runtime_map", failure.problem().target());
        return failure;
    }
    private static void assertOwnedFailure(Throwable failure) {
        assertInstanceOf(RuntimeMapLeaseException.class, failure);
        assertEquals("editor:runtime_map/owned_definition", failure.getMessage());
        assertEquals("runtime_map", ((RuntimeMapLeaseException) failure).problem().target());
    }
    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }
}
