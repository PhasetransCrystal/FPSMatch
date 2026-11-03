package net.ptcrys.fpsmatch.core.shop.runtime;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.common.MinecraftForge;
import net.ptcrys.fpsmatch.common.capability.team.ShopCapability;
import net.ptcrys.fpsmatch.common.event.FPSMShopEvent;
import net.ptcrys.fpsmatch.common.event.FPSMTeamEvent;
import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.ShutdownCleanup;
import net.ptcrys.fpsmatch.core.capability.CapabilityMap;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.data.PlayerData;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.core.shop.FPSMShop;
import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.ShopData;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import net.ptcrys.fpsmatch.core.team.ClientTeam;
import net.ptcrys.fpsmatch.core.team.ServerTeam;
import net.ptcrys.fpsmatch.core.team.TeamData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real GameTest-server holders. No FakePlayer, inventory operation, packet send, or startup simulation. */
class RuntimeShopCapabilityTest {
    private static final String TYPE_ID = "fpsmatch:q75_native_capability_primary";
    private static final String OTHER_TYPE_ID = "fpsmatch:q75_native_capability_other";
    private static final AtomicInteger LEGACY_SUPPLIER_CALLS = new AtomicInteger();

    @BeforeAll static void registerOnlyTwoIsolatedTestTypesOnActualServerOwner() {
        activeCore();
        assertFalse(FPSMShop.isShopTypeRegistered(TYPE_ID), "never overwrite an existing registration");
        assertFalse(FPSMShop.isShopTypeRegistered(OTHER_TYPE_ID), "never overwrite an existing registration");
        FPSMShop.registerShopType(TYPE_ID, Types.class);
        FPSMShop.registerShopType(OTHER_TYPE_ID, OtherTypes.class);
    }

    @Test void realFixtureConstructionRollbackAndScoreboardCollisionPreserveExactBaselines() {
        try (Fixture f = new Fixture()) {
            Baseline baseline = new Baseline(f.core);
            for (Throwable expected : List.of(new IllegalArgumentException("construction"), new AssertionError("construction"))) {
                AtomicReference<ProbeMap> escaped = new AtomicReference<>();
                Throwable actual = assertThrows(expected.getClass(), () -> MapConstructionScope.construct(() -> {
                    ProbeMap map = new ProbeMap(f.level, uniqueName());
                    escaped.set(map);
                    map.getMapTeams().addTeam(TeamData.of("test", 1, List.of(ShopCapability.class)));
                    throwUnchecked(expected);
                    return map;
                }));
                assertSame(expected, actual);
                assertEquals(0, actual.getSuppressed().length);
                assertTrue(escaped.get().getCapabilityMap().isClosed());
                escaped.get().getMapTeams().getTeamsWithSpectator().forEach(team -> assertTrue(team.getCapabilityMap().isClosed()));
                assertFalse(MapConstructionScope.isConstructing());
                baseline.assertUnchanged();
            }
            assertThrows(IllegalStateException.class,
                    () -> MapConstructionScope.construct(() -> new ProbeMap(f.level, f.map.getMapName())));
            assertSame(f.team.getPlayerTeam(), f.level.getScoreboard().getPlayerTeam(f.team.getPlayerTeam().getName()));
            baseline.assertUnchanged();
        }
    }

    @Test void uninitializedAndEmptyLegacyCapabilitiesAdoptExactNonBoRuntimeWithoutDefaultsConsumption() {
        for (boolean initialized : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                assertTrue(f.cap.runtimeShop().isEmpty());
                if (initialized) assertTrue(f.cap.initialize(TYPE_ID, 475));
                int supplierCalls = LEGACY_SUPPLIER_CALLS.get();
                RuntimeShop<Types> root = f.runtime(f.team.name, 1234);
                AtomicInteger events = new AtomicInteger();
                try (Hook ignored = new Hook(root, event -> events.incrementAndGet())) {
                    f.cap.useRuntimeShop(TYPE_ID, root);
                }
                assertEquals(0, events.get());
                assertEquals(supplierCalls, LEGACY_SUPPLIER_CALLS.get());
                assertSame(root, f.cap.getShop());
                assertSame(root, f.cap.getShopSafe().orElseThrow());
                assertSame(root, f.cap.runtimeShop().orElseThrow());
                assertSame(f.cap, f.team.getCapabilityMap().get(ShopCapability.class).orElseThrow());
                assertTrue(f.cap.isInitialized());
                assertEquals(TYPE_ID, f.cap.getShopTypeId());
                assertEquals(1234, f.cap.getStartMoney());
                assertTrue(root.hasRuntimeOwnership());
                assertTrue(root.playersData.isEmpty());
                ShopData<Types> detached = root.getDefaultShopData();
                assertEquals(2, detached.getShopSlotsByType(Types.PRIMARY).size());
                assertEquals(1, detached.getShopSlotsByType(Types.EXTRA).size());
                assertTrue(f.team.players.isEmpty());
            }
        }
    }

    @Test void inputValidationFailuresKeepLegacyIdentityMetadataAndUnclaimedCandidates() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.cap.initialize(TYPE_ID, 415));
            FPSMShop<?> old = f.cap.getShop();
            RuntimeShop<Types> good = f.runtime(f.team.name, 925);
            RuntimeShop<Types> wrongName = f.runtime("other_name", 925);
            RuntimeShop<OtherTypes> wrongClass = f.otherRuntime(f.team.name);
            problem("invalid_input", () -> f.cap.useRuntimeShop(null, good));
            problem("invalid_input", () -> f.cap.useRuntimeShop("", good));
            problem("invalid_input", () -> f.cap.useRuntimeShop(TYPE_ID, null));
            problem("invalid_slot", () -> f.cap.useRuntimeShop("fpsmatch:q75_unregistered", good));
            problem("invalid_slot", () -> f.cap.useRuntimeShop(OTHER_TYPE_ID, good));
            problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, wrongClass));
            problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, wrongName));
            RuntimeShop<Types> closed = f.runtime(f.team.name, 800);
            closed.close();
            problem("closed", () -> f.cap.useRuntimeShop(TYPE_ID, closed));
            assertFalse(closed.hasRuntimeOwnership());
            f.template.inspect(stack -> {
                problem("busy", () -> f.cap.useRuntimeShop(null, good));
                return null;
            });
            assertSame(old, f.cap.getShop());
            assertEquals(TYPE_ID, f.cap.getShopTypeId());
            assertEquals(415, f.cap.getStartMoney());
            assertTrue(f.cap.runtimeShop().isEmpty());
            for (RuntimeShop<?> root : List.of(good, wrongName, wrongClass)) {
                assertFalse(root.hasRuntimeOwnership());
                assertDoesNotThrow(root::requireAvailable);
            }
            assertTrue(old.playersData.isEmpty());
            assertDoesNotThrow(f.owner::requireAvailable);
            f.cap.useRuntimeShop(TYPE_ID, good);
            assertSame(good, f.cap.getShop());
        }
    }

    @Test void existingOldNewOrTeamPlayersRejectAdoptionWithoutClearingAnyMembership() {
        for (int occupied = 0; occupied < 3; occupied++) {
            try (Fixture f = new Fixture()) {
                assertTrue(f.cap.initialize(TYPE_ID, 612));
                FPSMShop<?> old = f.cap.getShop();
                RuntimeShop<Types> root = f.runtime(f.team.name, 975);
                UUID uuid = UUID.randomUUID();
                int supplierCalls = LEGACY_SUPPLIER_CALLS.get();
                if (occupied == 0) seedLegacyPlayer(old, uuid, 333);
                if (occupied == 1) f.team.players.put(uuid, new PlayerData(uuid, Component.literal("offline fixture"), false));
                if (occupied == 2) root.getPlayerShopData(uuid).setMoney(444);
                Map<UUID, ?> oldPlayers = new LinkedHashMap<>(old.playersData);
                Map<UUID, ?> newPlayers = new LinkedHashMap<>(root.playersData);
                Map<UUID, PlayerData> teamPlayers = new LinkedHashMap<>(f.team.players);
                assertEquals(supplierCalls, LEGACY_SUPPLIER_CALLS.get());
                problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, root));
                assertSame(old, f.cap.getShop());
                assertEquals(612, f.cap.getStartMoney());
                assertEquals(oldPlayers, old.playersData);
                assertEquals(newPlayers, root.playersData);
                assertEquals(teamPlayers, f.team.players);
                if (occupied == 0) assertEquals(333, old.getPlayerShopData(uuid).getMoney());
                if (occupied == 2) assertEquals(444, root.getPlayerShopData(uuid).getMoney());
                assertFalse(root.hasRuntimeOwnership());
                assertEquals(supplierCalls, LEGACY_SUPPLIER_CALLS.get());
            }
        }
    }

    @Test void adoptionRequiresRealServerTeamExactOpenMemberAndNeverDestroyedCapability() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            ShopCapability orphan = new ShopCapability(f.team);
            try { problem("invalid_slot", () -> orphan.useRuntimeShop(TYPE_ID, root)); }
            finally { orphan.destroy(); }
            ClientTeam client = new ClientTeam("q75", "unregistered", new TeamData("client", -1));
            ShopCapability clientCap = new ShopCapability(client);
            try { problem("invalid_slot", () -> clientCap.useRuntimeShop(TYPE_ID, root)); }
            finally { ShutdownCleanup.run(clientCap::destroy, List.of(client.getCapabilityMap()::close)); }
            assertFalse(root.hasRuntimeOwnership());
            f.cap.destroy();
            problem("closed", () -> f.cap.useRuntimeShop(TYPE_ID, root));
            assertTrue(f.cap.initialize(TYPE_ID, 350), "never-managed legacy initialize keeps its old compatibility");
            problem("closed", () -> f.cap.useRuntimeShop(TYPE_ID, root));
        }
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.team.getCapabilityMap().close();
            problem("closed", () -> f.cap.useRuntimeShop(TYPE_ID, root));
            assertFalse(root.hasRuntimeOwnership());
        }
    }

    @Test void repeatedAndCrossCapabilityAdoptionCannotTransferClaimedRuntime() {
        try (Fixture f = new Fixture(); Fixture other = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            RuntimeShop<Types> unused = f.runtime(f.team.name, 900);
            f.cap.useRuntimeShop(TYPE_ID, root);
            assertTrue(other.cap.initialize(TYPE_ID, 250));
            FPSMShop<?> otherOld = other.cap.getShop();
            problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, root));
            problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, unused));
            problem("invalid_slot", () -> f.cap.useRuntimeShop(null, null));
            problem("invalid_slot", () -> other.cap.useRuntimeShop(TYPE_ID, root));
            assertSame(root, f.cap.runtimeShop().orElseThrow());
            assertSame(otherOld, other.cap.getShop());
            assertEquals(250, other.cap.getStartMoney());
            assertTrue(root.hasRuntimeOwnership());
            assertFalse(unused.hasRuntimeOwnership());
            assertDoesNotThrow(f.owner::requireAvailable);
        }
    }

    @Test void adoptingReentryAndHolderCallbackFailuresKeepOldStateAndRestoreFinally() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.cap.initialize(TYPE_ID, 535));
            FPSMShop<?> old = f.cap.getShop();
            for (Throwable expected : List.of(new IllegalArgumentException("holder"), new AssertionError("holder"))) {
                RuntimeShop<Types> root = f.runtime(f.team.name, 800);
                f.map.levelObservation = () -> {
                    problem("busy", f.cap::runtimeShop);
                    problem("busy", f.cap::getShop);
                    problem("busy", f.cap::destroy);
                    problem("busy", () -> f.cap.useRuntimeShop(null, null));
                    throwUnchecked(expected);
                };
                try { assertSame(expected, assertThrows(expected.getClass(), () -> f.cap.useRuntimeShop(TYPE_ID, root))); }
                finally { f.map.levelObservation = null; }
                assertSame(old, f.cap.getShop());
                assertEquals(535, f.cap.getStartMoney());
                assertEquals(TYPE_ID, f.cap.getShopTypeId());
                assertTrue(f.cap.runtimeShop().isEmpty());
                assertFalse(root.hasRuntimeOwnership());
                assertDoesNotThrow(root::requireAvailable);
            }
            RuntimeShop<Types> accepted = f.runtime(f.team.name, 850);
            f.cap.useRuntimeShop(TYPE_ID, accepted);
            assertSame(accepted, f.cap.getShop());
        }
        try (Fixture f = new Fixture()) {
            assertTrue(f.cap.initialize(TYPE_ID, 535));
            FPSMShop<?> old = f.cap.getShop();
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.detachedConsumers.add(f.cap);
            f.map.levelObservation = () -> problem("busy", () -> f.team.getCapabilityMap().remove(ShopCapability.class));
            try { problem("invalid_slot", () -> f.cap.useRuntimeShop(TYPE_ID, root)); }
            finally { f.map.levelObservation = null; }
            assertTrue(f.team.getCapabilityMap().get(ShopCapability.class).isEmpty());
            assertSame(old, f.cap.getShop());
            assertEquals(535, f.cap.getStartMoney());
            assertFalse(root.hasRuntimeOwnership(), "final member recheck precedes the one-way claim");
            assertDoesNotThrow(root::requireAvailable);
            f.cap.destroy();
        }
    }

    @Test void directClaimFailuresDoNotInstallGuardsAndCloseDropsGuardWithoutClearingMarker() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime("raw_claim", 800);
            AtomicInteger rejectedGuard = new AtomicInteger(), acceptedGuard = new AtomicInteger();
            root.getPlayerShopData(UUID.randomUUID());
            problem("invalid_slot", () -> root.claimRuntimeOwnership(rejectedGuard::incrementAndGet));
            assertFalse(root.hasRuntimeOwnership());
            root.requireAvailable();
            assertEquals(0, rejectedGuard.get());
            root.clearPlayerShopData();
            problem("invalid_input", () -> root.claimRuntimeOwnership(null));
            assertFalse(root.hasRuntimeOwnership());
            AtomicBoolean poison = new AtomicBoolean();
            root.claimRuntimeOwnership(() -> {
                acceptedGuard.incrementAndGet();
                if (poison.get()) throw new AssertionError("guard must not be called by close");
            });
            assertEquals(0, acceptedGuard.get(), "claim stores the prepared guard without calling it");
            assertTrue(root.hasRuntimeOwnership());
            root.requireAvailable();
            problem("invalid_slot", () -> root.claimRuntimeOwnership(rejectedGuard::incrementAndGet));
            assertEquals(0, rejectedGuard.get());
            int beforeClose = acceptedGuard.get();
            poison.set(true);
            root.close();
            root.close();
            problem("closed", root::requireAvailable);
            problem("closed", () -> root.claimRuntimeOwnership(null));
            assertEquals(beforeClose, acceptedGuard.get());
            assertTrue(root.hasRuntimeOwnership());
            assertTrue(root.playersData.isEmpty());
        }
    }

    @Test void claimedGuardErrorsKeepIdentityAcrossRootDetachedDataSlotsAndCopies() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime("raw_guard", 800);
            AtomicReference<Throwable> guardFailure = new AtomicReference<>();
            root.claimRuntimeOwnership(() -> { if (guardFailure.get() != null) throwUnchecked(guardFailure.get()); });
            try {
                ShopData<Types> data = root.getPlayerShopData(UUID.randomUUID());
                ShopData<Types> detached = root.getDefaultShopData();
                RuntimeShopSlot slot = slot(data), copy = slot.copy();
                for (Throwable expected : List.of(new IllegalStateException("guard"), new AssertionError("guard"))) {
                    guardFailure.set(expected);
                    for (Executable call : List.<Executable>of(root::requireAvailable, data::getMoney,
                            detached::getMoney, slot::getCost, copy::copy,
                            () -> root.resetPlayerData(List.of()), root::getDefaultShopData)) {
                        assertSame(expected, assertThrows(expected.getClass(), call));
                    }
                }
            } finally { root.close(); }
            assertTrue(root.hasRuntimeOwnership());
            assertDoesNotThrow(f.owner::requireAvailable);
        }
    }

    @Test void managedLegacyAndSerializationEntrypointsRejectBeforeJsonShopProviderOrPlayerReads() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            AtomicInteger calls = new AtomicInteger();
            PoisonShop source = new PoisonShop(calls);
            JsonElement json = new PoisonJson(calls);
            for (Executable action : List.<Executable>of(
                    () -> f.cap.initialize(null, 10), () -> f.cap.setShop(null), () -> f.cap.setShop(source),
                    () -> f.cap.importConfigurationFrom(null), () -> f.cap.importConfigurationFrom(source),
                    () -> f.cap.canImportConfigurationFrom(null), () -> f.cap.canImportConfigurationFrom(source),
                    () -> f.cap.addArea(null), f.cap::clearAreas,
                    () -> f.cap.addListenerModule(null, null, -1), () -> f.cap.removeListenerModule(null, null, -1),
                    () -> f.cap.setShopGroupID(1, null, -1), () -> f.cap.setShopCost(1, null, -1),
                    () -> f.cap.setShopItem(null, null, -1), () -> f.cap.setShopItemFromPlayer(null, null, -1),
                    () -> f.cap.setGunAmmoAmount(1, null, -1), f.cap::codec, f.cap::read,
                    () -> f.cap.write(null), () -> f.cap.write(source), f.cap::toJson,
                    () -> f.cap.toJson(null), () -> f.cap.toJson(source), () -> f.cap.toJson(root),
                    () -> f.cap.decode(null), () -> f.cap.decode(JsonNull.INSTANCE),
                    () -> f.cap.decode(new JsonPrimitive("")), () -> f.cap.decode(json))) {
                problem("invalid_slot", action);
            }
            assertEquals(0, calls.get());
            assertSame(root, f.cap.getShop());
            assertEquals(800, f.cap.getStartMoney());
        }
    }

    @Test void neverManagedLegacyRejectsDirectRuntimeArgumentsWithoutInspectingThem() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.cap.initialize(TYPE_ID, 275));
            FPSMShop<?> old = f.cap.getShop();
            assertSame(old, f.cap.read());
            assertNotNull(f.cap.codec());
            assertTrue(f.cap.toJson((FPSMShop<?>) null).isJsonPrimitive());
            f.cap.decode(null);
            f.cap.decode(JsonNull.INSTANCE);
            f.cap.decode(new JsonPrimitive(""));
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            root.close();
            for (Executable action : List.<Executable>of(() -> f.cap.setShop(root), () -> f.cap.write(root),
                    () -> f.cap.importConfigurationFrom(root), () -> f.cap.canImportConfigurationFrom(root),
                    () -> f.cap.toJson(root))) problem("invalid_slot", action);
            assertSame(old, f.cap.getShop());
            assertEquals(275, f.cap.getStartMoney());
            assertFalse(root.hasRuntimeOwnership());
            assertTrue(f.cap.runtimeShop().isEmpty());
        }
    }

    @Test void publicationBusyBlocksCapabilityDestroyAndLegacyCallsButStageAndFinallyRecover() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            UUID uuid = UUID.randomUUID();
            ShopData<Types> old = root.getPlayerShopData(uuid);
            try (Hook ignored = new Hook(root, event -> {
                problem("busy", f.cap::runtimeShop);
                problem("busy", f.cap::getShop);
                problem("busy", f.cap::isInitialized);
                problem("busy", f.cap::destroy);
                problem("busy", () -> f.cap.initialize(null, 0));
                problem("busy", () -> f.cap.decode(null));
                problem("busy", () -> f.cap.useRuntimeShop(null, null));
                RuntimeShopSlot stage = (RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0);
                stage.setCost(725);
                assertEquals(725, stage.getCost());
                assertDoesNotThrow(stage::toString);
            })) {
                assertEquals(725, slot(root.getDefaultAndPutData(uuid, false)).getCost());
            }
            assertSame(root, f.cap.runtimeShop().orElseThrow());
            assertTrue(f.cap.isInitialized());
            problem("closed", old::getMoney);
            assertDoesNotThrow(f.owner::requireAvailable);
        }
    }

    @Test void removalDuringDataInitClosesEveryEscapeAbortsCommitAndAllowsExplicitDestroyAfterBusy() {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            UUID oldId = UUID.randomUUID();
            ShopData<Types> old = root.getPlayerShopData(oldId);
            old.setMoney(321);
            RuntimeShopSlot oldSlot = slot(old), oldCopy = oldSlot.copy();
            ShopData<Types> detached = root.getDefaultShopData();
            AtomicReference<RuntimeShopSlot> stage = new AtomicReference<>(), stageCopy = new AtomicReference<>();
            AtomicReference<RuntimeItemException> destroyBusy = new AtomicReference<>();
            f.detachedConsumers.add(f.cap); // remove has already detached it even when destroy throws.
            try (Hook ignored = new Hook(root, event -> {
                stage.set((RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0));
                stageCopy.set(stage.get().copy());
                destroyBusy.set(problem("busy", () -> f.team.getCapabilityMap().remove(ShopCapability.class)));
                assertTrue(f.team.getCapabilityMap().get(ShopCapability.class).isEmpty());
                assertFalse(f.team.getCapabilityMap().isClosed());
                for (Executable call : List.<Executable>of(f.cap::runtimeShop, f.cap::getShop, root::requireAvailable,
                        old::getMoney, detached::getMoney, oldSlot::getCost, oldCopy::copy,
                        stage.get()::getCost, stageCopy.get()::copy)) problem("closed", call);
            })) {
                problem("closed", () -> root.getDefaultAndPutData(UUID.randomUUID(), true));
            }
            assertNotNull(destroyBusy.get());
            assertEquals(1, root.playersData.size());
            assertSame(old, root.playersData.get(oldId), "failed publication preserves the sole current map identity");
            problem("closed", old::getMoney); // money is deliberately unreadable after membership loss.
            problem("closed", stage.get()::currentTemplate);
            problem("closed", stageCopy.get()::copy);
            assertDoesNotThrow(f.cap::destroy, "explicit cleanup is allowed after remove and publication busy exit");
            assertTrue(root.playersData.isEmpty(), "destroy must actually close the accurate root before fixture cleanup");
            assertTrue(root.hasRuntimeOwnership());
            assertDoesNotThrow(f.owner::requireAvailable);
            assertTrue(f.team.getCapabilityMap().add(ShopCapability.class));
            ShopCapability replacement = f.team.getCapabilityMap().get(ShopCapability.class).orElseThrow();
            assertNotSame(f.cap, replacement);
            problem("closed", f.cap::getShop);
            problem("closed", root::requireAvailable);
        }
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            UUID oldId = UUID.randomUUID();
            ShopData<Types> old = root.getPlayerShopData(oldId);
            AtomicBoolean removed = new AtomicBoolean();
            f.detachedConsumers.add(f.cap);
            List<UUID> emptyRemovingList = new java.util.AbstractList<>() {
                public int size() {
                    if (removed.compareAndSet(false, true))
                        problem("busy", () -> f.team.getCapabilityMap().remove(ShopCapability.class));
                    return 0;
                }
                public UUID get(int index) { throw new AssertionError("empty input"); }
            };
            problem("closed", () -> root.resetPlayerData(emptyRemovingList));
            assertTrue(removed.get());
            assertSame(old, root.playersData.get(oldId), "empty-batch final guard must reject before clearing membership");
            assertEquals(1, root.playersData.size());
            f.cap.destroy();
            assertTrue(root.playersData.isEmpty());
            assertDoesNotThrow(f.owner::requireAvailable);
        }
    }

    @Test void managedEconomyAndOfflineZeroRecipientResetAndSyncRemainGameplayOperations() {
        try (Fixture f = new Fixture()) {
            assertTrue(f.server.getPlayerList().getPlayers().isEmpty(), "this suite must never send a real player packet");
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            f.cap.setStartMoney(-1);
            assertEquals(-1, f.cap.getStartMoney());
            assertEquals(-1, root.getStartMoney());
            UUID offline = UUID.randomUUID();
            assertTrue(f.core.getPlayerByUUID(offline).isEmpty());
            ShopData<Types> data = root.getPlayerShopData(offline);
            assertTrue(data.unlimited());
            data.addMoney(200);
            data.reduceMoney(200);
            assertEquals(-1, data.getMoney());
            f.cap.resetPlayerData();
            f.cap.reset();
            f.cap.syncShopData();
            f.cap.syncShopMoneyData();
            f.cap.sync();
            f.cap.sync(null); // instanceof-null branch; this is not a player/network assertion.
            f.cap.onJoin(new FPSMTeamEvent.JoinEvent(f.team, null));
            assertTrue(f.cap.isInArea(null), "empty area list has no entity read");
            assertEquals(-1, root.getPlayerShopData(offline).getMoney());
            assertSame(root, f.cap.getShop());
        }
    }

    @Test void normalRemovalAndContainerCloseRetireRootDataSlotCopiesAndKeepSharedOwner() {
        for (boolean remove : List.of(false, true)) {
            try (Fixture f = new Fixture()) {
                RuntimeShop<Types> root = f.runtime(f.team.name, 800);
                f.cap.useRuntimeShop(TYPE_ID, root);
                ShopData<Types> data = root.getPlayerShopData(UUID.randomUUID());
                RuntimeShopSlot slot = slot(data), copy = slot.copy();
                if (remove) assertTrue(f.team.getCapabilityMap().remove(ShopCapability.class));
                else f.team.getCapabilityMap().close();
                for (Executable call : List.<Executable>of(f.cap::runtimeShop, f.cap::getShopSafe, root::requireAvailable,
                        data::getData, slot::getCost, copy::copy)) problem("closed", call);
                assertTrue(root.playersData.isEmpty());
                assertTrue(root.hasRuntimeOwnership());
                assertDoesNotThrow(f.cap::destroy);
                assertDoesNotThrow(f.owner::requireAvailable);
            }
        }
    }

    @Test void destroyIsPermanentAndIdempotentAfterExternalRootAndThenOwnerClose() throws InterruptedException {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            root.getPlayerShopData(UUID.randomUUID());
            root.close();
            f.cap.destroy();
            f.cap.destroy();
            assertTrue(root.hasRuntimeOwnership());
            assertTrue(root.playersData.isEmpty());
            assertDoesNotThrow(f.owner::requireAvailable);
            for (Executable call : List.<Executable>of(f.cap::runtimeShop, f.cap::isInitialized, f.cap::getShop,
                    f.cap::getShopSafe, f.cap::getShopTypeId, f.cap::getStartMoney,
                    () -> f.cap.initialize(TYPE_ID, 100), () -> f.cap.setShop(null), () -> f.cap.decode(null),
                    () -> f.cap.useRuntimeShop(null, null), () -> f.cap.onJoin(null), () -> f.cap.onLeave(null),
                    f.cap::reset, f.cap::resetPlayerData, f.cap::sync, f.cap::syncShopData, f.cap::syncShopMoneyData,
                    () -> f.cap.sync(null), () -> f.cap.isInArea(null), () -> f.cap.displayAreas(null),
                    () -> f.cap.setStartMoney(1))) {
                problem("closed", call);
            }
            f.owner.close();
            assertDoesNotThrow(f.cap::destroy);
            runWorker(() -> problem("wrong_thread", f.cap::destroy));
        }
    }

    @Test void ownerBusyWrongThreadAndOwnerClosedPrecedeInputsAndDoNotPartiallyChangeEconomy() throws InterruptedException {
        try (Fixture f = new Fixture()) {
            RuntimeShop<Types> root = f.runtime(f.team.name, 800);
            f.cap.useRuntimeShop(TYPE_ID, root);
            f.cap.setStartMoney(905);
            ShopData<Types> old = root.getPlayerShopData(UUID.randomUUID());
            f.template.inspect(stack -> {
                problem("busy", () -> f.cap.setStartMoney(123));
                problem("busy", () -> f.cap.initialize(null, 0));
                problem("busy", () -> f.cap.decode(null));
                problem("busy", f.cap::runtimeShop);
                problem("busy", f.cap::destroy);
                return null;
            });
            assertEquals(905, f.cap.getStartMoney());
            assertEquals(905, root.getStartMoney());
            runWorker(() -> {
                problem("wrong_thread", f.cap::runtimeShop);
                problem("wrong_thread", () -> f.cap.useRuntimeShop(null, null));
                problem("wrong_thread", () -> f.cap.decode(null));
                problem("wrong_thread", () -> f.cap.setStartMoney(1));
                problem("wrong_thread", f.cap::destroy);
            });
            f.expectOwnerClosedCleanup = true;
            f.owner.close();
            problem("closed", f.cap::getShop);
            problem("closed", () -> f.cap.setStartMoney(1));
            problem("closed", () -> f.cap.decode(null));
            problem("closed", f.cap::destroy);
            assertSame(old, root.playersData.values().iterator().next(), "failed destroy must not clear the accurate root");
            assertTrue(root.hasRuntimeOwnership());
        }
    }

    @Test void aTemplateOwnerWorkerStillCannotAdoptIntoTheRealServerTeam() throws InterruptedException {
        try (Fixture f = new Fixture()) {
            runWorker(() -> {
                try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
                    RuntimeItemTemplates.Template template = capture(owner);
                    owner.seal();
                    try (RuntimeShop<Types> root = new RuntimeShop<>(Types.class, f.team.name,
                            runtimeDefaults(template, Types.PRIMARY, Types.EXTRA), 800, List.of(), owner)) {
                        problem("wrong_thread", () -> f.cap.useRuntimeShop(TYPE_ID, root));
                        assertFalse(root.hasRuntimeOwnership());
                        assertDoesNotThrow(root::requireAvailable);
                    }
                }
            });
            assertFalse(f.cap.isInitialized());
            assertTrue(f.cap.runtimeShop().isEmpty());
        }
    }

    private enum Types implements INamedType {
        PRIMARY(2), EXTRA(1);
        private final int slots;
        Types(int slots) { this.slots = slots; }
        public int slotCount() { return slots; }
        public boolean dorpUnlock() { return false; }
        public ArrayList<ShopSlot> defaultSlots() { return legacySlots(slots); }
    }

    private enum OtherTypes implements INamedType {
        PRIMARY(2), EXTRA(1);
        private final int slots;
        OtherTypes(int slots) { this.slots = slots; }
        public int slotCount() { return slots; }
        public boolean dorpUnlock() { return false; }
        public ArrayList<ShopSlot> defaultSlots() { return legacySlots(slots); }
    }

    private static ArrayList<ShopSlot> legacySlots(int size) {
        ArrayList<ShopSlot> slots = new ArrayList<>();
        for (int i = 0; i < size; i++) slots.add(new ShopSlot(() -> {
            LEGACY_SUPPLIER_CALLS.incrementAndGet();
            throw new AssertionError("legacy default supplier must not be consumed by adoption");
        }, 100, 3, -1, stack -> false));
        return slots;
    }

    private static final class ProbeMap extends BaseMap {
        Runnable levelObservation;
        ProbeMap(ServerLevel level, String name) { super(level, name, new AreaData(BlockPos.ZERO, new BlockPos(2, 2, 2))); }
        public boolean victoryGoal() { return false; }
        public String getGameType() { return "q75_native"; }
        @Override public ServerLevel getServerLevel() {
            if (levelObservation != null) levelObservation.run();
            return super.getServerLevel();
        }
        @Override public void saveConfig() { throw new AssertionError("fixture must not save"); }
        @Override public void loadConfig() { throw new AssertionError("fixture must not load"); }
    }

    private record Parts(ProbeMap map, ServerTeam team, ShopCapability cap,
                         RuntimeItemTemplates owner, RuntimeItemTemplates.Template template) {}

    private static final class Fixture implements AutoCloseable {
        final FPSMCore core;
        final MinecraftServer server;
        final ServerLevel level;
        final ProbeMap map;
        final ServerTeam team;
        final ShopCapability cap;
        final RuntimeItemTemplates owner;
        final RuntimeItemTemplates.Template template;
        final Baseline baseline;
        final List<CapabilityMap<?, ?>> containers = new ArrayList<>();
        final List<RuntimeShop<?>> roots = new ArrayList<>();
        final List<ShopCapability> detachedConsumers = new ArrayList<>();
        boolean expectOwnerClosedCleanup;
        boolean closed;
        Fixture() {
            core = activeCore();
            server = core.getServer();
            level = server.overworld();
            assertNotNull(level);
            assertSame(server, level.getServer());
            assertSame(level, server.getLevel(level.dimension()));
            baseline = new Baseline(core);
            Parts parts = MapConstructionScope.construct(() -> {
                RuntimeItemTemplates owner = MapConstructionScope.acquire(() -> new RuntimeItemTemplates(1), RuntimeItemTemplates::close);
                RuntimeItemTemplates.Template template = capture(owner);
                owner.seal();
                ProbeMap map = new ProbeMap(level, uniqueName());
                ServerTeam team = map.getMapTeams().addTeam(TeamData.of("test", 1, List.of(ShopCapability.class)));
                ShopCapability cap = team.getCapabilityMap().get(ShopCapability.class).orElseThrow();
                assertSame(team, cap.getHolder());
                assertTrue(map.getMapTeams().getTeamsWithSpectator().contains(team));
                assertFalse(core.getAllMaps().values().stream().flatMap(List::stream).anyMatch(candidate -> candidate == map));
                return new Parts(map, team, cap, owner, template);
            });
            map = parts.map(); team = parts.team(); cap = parts.cap(); owner = parts.owner(); template = parts.template();
            containers.add(map.getCapabilityMap());
            map.getMapTeams().getTeamsWithSpectator().forEach(value -> containers.add(value.getCapabilityMap()));
        }
        RuntimeShop<Types> runtime(String name, int money) {
            RuntimeShop<Types> root = new RuntimeShop<>(Types.class, name,
                    runtimeDefaults(template, Types.PRIMARY, Types.EXTRA), money, List.of(), owner);
            roots.add(root);
            return root;
        }
        RuntimeShop<OtherTypes> otherRuntime(String name) {
            RuntimeShop<OtherTypes> root = new RuntimeShop<>(OtherTypes.class, name,
                    runtimeDefaults(template, OtherTypes.PRIMARY, OtherTypes.EXTRA), 800, List.of(), owner);
            roots.add(root);
            return root;
        }
        public void close() {
            if (closed) return;
            closed = true;
            map.levelObservation = null;
            List<Runnable> cleanup = new ArrayList<>();
            cleanup.add(map.getCapabilityMap()::close);
            cleanup.add(() -> map.getMapTeams().shutdown(level.getScoreboard()));
            detachedConsumers.forEach(consumer -> cleanup.add(consumer::destroy));
            for (RuntimeShop<?> root : roots) {
                if (!root.hasRuntimeOwnership()) cleanup.add(root::close);
                else if (!expectOwnerClosedCleanup) cleanup.add(() -> assertTrue(root.playersData.isEmpty(),
                        "claimed consumer must be closed by its capability; fixture must not repair it with root.close"));
            }
            cleanup.add(() -> containers.forEach(container -> assertTrue(container.isClosed())));
            cleanup.add(baseline::assertUnchanged);
            cleanup.add(owner::close); // always after every accurate consumer cleanup attempt.
            if (expectOwnerClosedCleanup) {
                RuntimeItemException failure = assertThrows(RuntimeItemException.class, () -> ShutdownCleanup.run(() -> {}, cleanup));
                assertEquals("editor:runtime_items/closed", failure.getMessage());
                assertEquals("runtime_items", failure.problem().target());
                assertEquals(0, failure.getSuppressed().length, "accept only the intentional owner-closed consumer failure");
            } else ShutdownCleanup.run(() -> {}, cleanup);
        }
    }

    private static final class Baseline {
        final FPSMCore core;
        final Scoreboard scoreboard;
        final Map<String, PlayerTeam> scoreboardTeams = new LinkedHashMap<>();
        final Map<String, List<BaseMap>> gameLists = new LinkedHashMap<>();
        final Map<String, List<BaseMap>> games = new LinkedHashMap<>();
        Baseline(FPSMCore core) {
            this.core = core;
            scoreboard = core.getServer().getScoreboard();
            scoreboard.getPlayerTeams().forEach(team -> scoreboardTeams.put(team.getName(), team));
            core.getAllMaps().forEach((type, maps) -> {
                gameLists.put(type, maps);
                games.put(type, List.copyOf(maps));
            });
        }
        void assertUnchanged() {
            Map<String, PlayerTeam> current = new LinkedHashMap<>();
            scoreboard.getPlayerTeams().forEach(team -> current.put(team.getName(), team));
            assertEquals(scoreboardTeams.keySet(), current.keySet());
            scoreboardTeams.forEach((name, owned) -> assertSame(owned, current.get(name)));
            assertEquals(games.keySet(), core.getAllMaps().keySet());
            games.forEach((type, maps) -> {
                List<BaseMap> actual = core.getAllMaps().get(type);
                assertSame(gameLists.get(type), actual);
                assertEquals(maps.size(), actual.size());
                for (int i = 0; i < maps.size(); i++) assertSame(maps.get(i), actual.get(i));
            });
        }
    }

    private static final class PoisonJson extends JsonElement {
        final AtomicInteger calls;
        PoisonJson(AtomicInteger calls) { this.calls = calls; }
        private AssertionError touched() { calls.incrementAndGet(); return new AssertionError("JSON inspected before legacy gate"); }
        @Override public JsonElement deepCopy() { throw touched(); }
        @Override public boolean isJsonNull() { throw touched(); }
        @Override public boolean isJsonPrimitive() { throw touched(); }
        @Override public JsonObject getAsJsonObject() { throw touched(); }
        @Override public String toString() { throw touched(); }
    }

    private static final class PoisonShop extends FPSMShop<Types> {
        final AtomicInteger calls;
        PoisonShop(AtomicInteger calls) { super(Types.class, "poison", Map.of(), 0); this.calls = calls; }
        private AssertionError touched() { calls.incrementAndGet(); return new AssertionError("foreign shop inspected before legacy gate"); }
        @Override public FPSMShop<Types> withName(String name) { throw touched(); }
        @Override public String getName() { throw touched(); }
        @Override public int getStartMoney() { throw touched(); }
        @Override public List<ShopSlot> getDefaultShopSlotListByType(String type) { throw touched(); }
        @Override public Codec<FPSMShop<Types>> getCodec() { throw touched(); }
    }

    private static final class Hook implements AutoCloseable {
        final Consumer<FPSMShopEvent.DataInit> callback;
        @SuppressWarnings("unchecked")
        Hook(RuntimeShop<Types> shop, Consumer<FPSMShopEvent.DataInit<Types>> action) {
            callback = event -> { if (event.getShop() == shop) action.accept((FPSMShopEvent.DataInit<Types>) event); };
            MinecraftForge.EVENT_BUS.addListener(callback);
        }
        public void close() { MinecraftForge.EVENT_BUS.unregister(callback); }
    }

    private static FPSMCore activeCore() {
        FPSMCore core = FPSMCore.getInstance(); // absent core is a failure; never simulate server startup.
        assertTrue(core.isActive());
        assertTrue(core.getServer().isSameThread(), "real server owner required");
        assertSame(core, FPSMCore.getInstance());
        return core;
    }

    private static String uniqueName() { return "q75_" + UUID.randomUUID().toString().replace("-", ""); }

    /** Seed only an existing-player precondition; this does not simulate a player joining. */
    private static <E extends Enum<E> & INamedType> void seedLegacyPlayer(FPSMShop<E> shop, UUID id, int money) {
        Map<E, ImmutableList<ShopSlot>> graph = new LinkedHashMap<>();
        shop.getDefaultShopDataMap().forEach((type, slots) -> graph.put(type, ImmutableList.copyOf(slots)));
        shop.playersData.put(id, new ShopData<>(graph, shop.typeCount, money));
    }

    private static <T extends Enum<T> & INamedType> Map<T, List<ShopSlot>> runtimeDefaults(
            RuntimeItemTemplates.Template template, T primary, T extra) {
        Map<T, List<ShopSlot>> result = new LinkedHashMap<>();
        result.put(primary, List.of(frozen(template, 0), frozen(template, 1)));
        result.put(extra, List.of(frozen(template, 0)));
        return result;
    }

    private static RuntimeShopSlot frozen(RuntimeItemTemplates.Template template, int index) {
        RuntimeShopSlot slot = new RuntimeShopSlot(template, 100, 3, -1);
        slot.setIndex(index);
        slot.freezeDefinition();
        return slot;
    }

    private static RuntimeItemTemplates.Template capture(RuntimeItemTemplates owner) {
        ItemStack borrowed = new ItemStack(Items.STONE);
        try { return owner.capture(borrowed, ignored -> {}, () -> false); }
        finally { borrowed.invalidateCaps(); }
    }

    private static RuntimeShopSlot slot(ShopData<Types> data) {
        return (RuntimeShopSlot) data.getShopSlotsByType(Types.PRIMARY).get(0);
    }

    private static RuntimeItemException problem(String reason, Executable action) {
        RuntimeItemException failure = assertThrows(RuntimeItemException.class, action);
        assertEquals("editor:runtime_items/" + reason, failure.getMessage());
        assertEquals("runtime_items", failure.problem().target());
        return failure;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw (Error) failure;
    }

    private static void runWorker(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } });
        worker.setDaemon(true);
        worker.start();
        try { worker.join(10_000); assertFalse(worker.isAlive(), "worker must finish within ten seconds"); }
        finally { worker.interrupt(); worker.join(1_000); assertFalse(worker.isAlive(), "worker must terminate during cleanup"); }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
