package net.ptcrys.fpsmatch.core.shop.runtime;

import com.google.common.collect.ImmutableList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.ptcrys.fpsmatch.common.event.FPSMShopEvent;
import net.ptcrys.fpsmatch.core.data.AreaData;
import net.ptcrys.fpsmatch.core.shop.INamedType;
import net.ptcrys.fpsmatch.core.shop.ShopAction;
import net.ptcrys.fpsmatch.core.shop.ShopData;
import net.ptcrys.fpsmatch.core.shop.event.CheckCostEvent;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real Forge publication tests; null players only exercise rejection before inventory access. */
class RuntimeShopPublicationTest {
    @BeforeAll static void bootstrapNativeRuntime() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftForge.EVENT_BUS.start();
    }

    @Test void nonBoShapeIsCapturedOnceAndNotRecalledDuringPublicationOrCheckData() {
        try (Fixture f = new Fixture()) {
            int captured = Types.calls.get();
            assertEquals(2, f.shop.typeCount);
            assertEquals(List.of(Types.PRIMARY, Types.EXTRA), f.shop.getEnums());
            Types.forbidden = true;
            try {
                ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
                assertEquals(2, data.getShopSlotsByType(Types.PRIMARY).size());
                assertEquals(1, data.getShopSlotsByType(Types.EXTRA).size());
                data.checkData(data.getData(), 2);
                f.shop.resetPlayerData(false);
                assertEquals(captured, Types.calls.get(), "fixed shape must avoid external enum callbacks");
            } finally { Types.forbidden = false; }
        }
    }

    @Test void defaultsAndAreaValuesAreDefensivelyCopiedBeforeCallerMutation() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            Map<Types, ArrayList<ShopSlot>> defaults = defaults(template, null);
            RuntimeShopSlot input = (RuntimeShopSlot) defaults.get(Types.PRIMARY).get(0);
            BlockPos.MutableBlockPos corner = new BlockPos.MutableBlockPos(1, 2, 3);
            AreaData derived = new AreaData(corner, new BlockPos(7, 8, 9)) {
                @Override public boolean isEntityInArea(net.minecraft.world.entity.Entity ignored) {
                    throw new AssertionError("must not retain dynamic area implementation");
                }
            };
            List<AreaData> areas = new ArrayList<>(List.of(derived));
            owner.seal();
            try (RuntimeShop<Types> shop = new RuntimeShop<>(Types.class, "defensive", defaults, 800, areas, owner)) {
                input.setCost(999);
                input.lock(2);
                defaults.clear();
                areas.clear();
                corner.set(40, 50, 60);
                ShopData<Types> first = shop.getDefaultShopData();
                ShopData<Types> second = shop.getDefaultShopData();
                assertEquals(100, slot(first).getCost());
                assertEquals(0, slot(first).getBoughtCount());
                assertNotSame(input, slot(first));
                assertNotSame(slot(first), slot(second));
                slot(first).setCost(250);
                assertEquals(100, slot(second).getCost());
                assertSame(template, slot(second).currentTemplate());
                AreaData copied = shop.getAreas().get(0);
                assertEquals(AreaData.class, copied.getClass());
                assertNotSame(derived, copied);
                assertEquals(new BlockPos(1, 2, 3), copied.pos1());
                assertThrows(UnsupportedOperationException.class, () -> shop.areas.clear());
                assertThrows(UnsupportedOperationException.class, () -> shop.getAreas().clear());
            }
        }
    }

    @Test void exactSlotsInNewEventContainersAcceptGameplayChangeAndIgnoreEventMoney() {
        try (Fixture f = new Fixture(); Hook ignored = new Hook(f.shop, event -> {
            RuntimeShopSlot candidate = (RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0);
            f.change.onChange(new ShopSlotChangeEvent(candidate, null, 800, 1));
            candidate.lock(2);
            candidate.setCost(333);
            event.setMoney(1);
            event.setData(mutable(event));
        })) {
            ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
            assertSame(f.changed, slot(data).currentTemplate());
            assertEquals(333, slot(data).getCost());
            assertEquals(2, slot(data).getBoughtCount());
            assertTrue(slot(data).isLocked());
            assertEquals(800, data.getMoney(), "DataInit.setMoney retains its legacy non-consumption semantics");
            assertEquals(0, slot(data).getIndex());
            assertSame(f.change, slot(data).getListenerModules().get(0));
        }
    }

    @Test void eventRejectsEqualCopiesLegacyForeignOldPlayerReorderAndAlias() {
        try (Fixture f = new Fixture(); Fixture foreign = new Fixture()) {
            UUID uuid = UUID.randomUUID();
            ShopData<Types> original = f.shop.getPlayerShopData(uuid);
            original.setMoney(425);
            RuntimeShopSlot originalSlot = slot(original);
            ShopSlot foreignSlot = foreign.shop.getPlayerShopData(UUID.randomUUID()).getShopSlotsByType(Types.PRIMARY).get(0);
            for (int attack = 0; attack < 6; attack++) {
                final int selected = attack;
                AtomicReference<RuntimeShopSlot> escaped = new AtomicReference<>();
                try (Hook ignored = new Hook(f.shop, event -> {
                    Map<Types, List<ShopSlot>> replacement = mutable(event);
                    List<ShopSlot> primary = replacement.get(Types.PRIMARY);
                    escaped.set(((RuntimeShopSlot) primary.get(0)).copy());
                    switch (selected) {
                        case 0 -> primary.set(0, ((RuntimeShopSlot) primary.get(0)).copy());
                        case 1 -> primary.set(0, new ShopSlot(() -> { throw new AssertionError("legacy supplier"); }, 100, 3, 9, stack -> false));
                        case 2 -> primary.set(0, foreignSlot);
                        case 3 -> primary.set(0, originalSlot);
                        case 4 -> Collections.swap(primary, 0, 1);
                        case 5 -> primary.set(1, primary.get(0));
                        default -> throw new AssertionError();
                    }
                    event.setData(replacement);
                })) {
                    problem("invalid_slot", () -> f.shop.getDefaultAndPutData(uuid, false));
                }
                assertSame(original, f.shop.getPlayerShopData(uuid));
                assertEquals(425, original.getMoney());
                retired(escaped.get());
            }
        }
    }

    @Test void lastCallbackTamperingEarlierCandidateAbortsWholeBatchAndRetiresEveryEscape() {
        try (Fixture f = new Fixture()) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            f.shop.resetPlayerData(List.of(first, second));
            ShopData<Types> oldFirst = f.shop.getPlayerShopData(first), oldSecond = f.shop.getPlayerShopData(second);
            oldFirst.setMoney(321);
            oldSecond.setMoney(654);
            List<RuntimeShopSlot> escaped = new ArrayList<>();
            try (Hook ignored = new Hook(f.shop, event -> {
                RuntimeShopSlot current = (RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0);
                if (!escaped.isEmpty()) escaped.get(0).defaultCost++;
                escaped.add(current);
                escaped.add(current.copy());
            })) {
                problem("invalid_slot", () -> f.shop.resetPlayerData(false));
            }
            assertSame(oldFirst, f.shop.getPlayerShopData(first));
            assertSame(oldSecond, f.shop.getPlayerShopData(second));
            assertEquals(321, oldFirst.getMoney());
            assertEquals(654, oldSecond.getMoney());
            escaped.forEach(RuntimeShopPublicationTest::retired);
            assertDoesNotThrow(() -> f.shop.resetPlayerData(false));
        }
    }

    @Test void runtimeExceptionAndErrorKeepIdentityAndFinallyRestorePublication() {
        try (Fixture f = new Fixture()) {
            UUID uuid = UUID.randomUUID();
            ShopData<Types> old = f.shop.getPlayerShopData(uuid);
            old.setMoney(765);
            for (Throwable expected : List.of(new IllegalArgumentException("callback"), new AssertionError("callback"))) {
                AtomicReference<RuntimeShopSlot> stage = new AtomicReference<>(), clone = new AtomicReference<>();
                try (Hook ignored = new Hook(f.shop, event -> {
                    RuntimeShopSlot candidate = (RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0);
                    stage.set(candidate);
                    clone.set(candidate.copy());
                    if (expected instanceof Error error) throw error;
                    throw (RuntimeException) expected;
                })) {
                    assertSame(expected, assertThrows(expected.getClass(), () -> f.shop.getDefaultAndPutData(uuid, true)));
                }
                assertSame(old, f.shop.getPlayerShopData(uuid));
                assertEquals(765, old.getMoney());
                retired(stage.get());
                retired(clone.get());
                assertDoesNotThrow(f.shop::requireAvailable);
            }
            assertNotSame(old, f.shop.getDefaultAndPutData(uuid, false));
        }
    }

    @Test void publicationBlocksShopOldDataOldCopiesAndCloseButAllowsItsStage() {
        try (Fixture f = new Fixture()) {
            UUID uuid = UUID.randomUUID();
            ShopData<Types> old = f.shop.getPlayerShopData(uuid);
            RuntimeShopSlot oldSlot = slot(old), oldCopy = oldSlot.copy();
            try (Hook ignored = new Hook(f.shop, event -> {
                problem("busy", f.shop::requireAvailable);
                problem("busy", f.shop::close);
                problem("busy", old::getMoney);
                problem("busy", oldSlot::getCost);
                problem("busy", oldCopy::copy);
                problem("busy", () -> f.shop.handleButton(null, null, -1, null));
                problem("busy", () -> f.shop.getPlayerShopData((UUID) null));
                problem("busy", () -> f.shop.requirePlayerSlot(null, null, null));
                RuntimeShopSlot stage = (RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0);
                stage.setCost(123);
                assertEquals(123, stage.getCost());
                assertDoesNotThrow(stage::toString);
            })) {
                assertEquals(123, slot(f.shop.getDefaultAndPutData(uuid, false)).getCost());
            }
            assertDoesNotThrow(f.shop::requireAvailable);
            problem("closed", old::getMoney);
            retired(oldCopy);
        }
    }

    @Test void singleReplacementPreservesUnlimitedMoneyAndRetiresPreviousDataSlotsAndCopies() {
        try (Fixture f = new Fixture()) {
            UUID uuid = UUID.randomUUID();
            ShopData<Types> old = f.shop.getPlayerShopData(uuid);
            old.setMoney(-1);
            RuntimeShopSlot oldSlot = slot(old), copy = oldSlot.copy();
            ShopData<Types> replacement = f.shop.getDefaultAndPutData(uuid, false);
            assertEquals(-1, replacement.getMoney());
            replacement.addMoney(500);
            replacement.reduceMoney(500);
            assertTrue(replacement.unlimited());
            problem("closed", old::getData);
            retired(oldSlot);
            retired(copy);
            f.shop.setStartMoney(1200);
            assertEquals(1200, f.shop.getDefaultAndPutData(uuid, true).getMoney());
            problem("closed", replacement::getMoney);
        }
    }

    @Test void listResetReplacesMembershipResetsMoneyAndEmptyListRetiresAll() {
        try (Fixture f = new Fixture()) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            ShopData<Types> removed = f.shop.getPlayerShopData(first);
            f.shop.setStartMoney(900);
            f.shop.resetPlayerData(List.of(second));
            assertTrue(f.shop.getPlayerShopDataSafe(first).isEmpty());
            assertEquals(Set.of(second), f.shop.playersData.keySet());
            ShopData<Types> current = f.shop.getPlayerShopData(second);
            assertEquals(900, current.getMoney());
            problem("closed", removed::getRevision);
            problem("invalid_input", () -> f.shop.resetPlayerData(List.of(second, second)));
            problem("invalid_input", () -> f.shop.resetPlayerData(java.util.Arrays.asList(second, null)));
            assertSame(current, f.shop.getPlayerShopData(second));
            f.shop.resetPlayerData(List.of());
            assertTrue(f.shop.playersData.isEmpty());
            problem("closed", current::getMoney);
        }
    }

    @Test void dynamicPlayerAndDataViewsRejectAllContainerWritePaths() {
        try (Fixture f = new Fixture()) {
            Map<UUID, ShopData<Types>> publicView = f.shop.playersData;
            Map<UUID, ShopData<Types>> getterView = f.shop.getPlayersData();
            Set<Map.Entry<UUID, ShopData<Types>>> entries = publicView.entrySet();
            Set<UUID> keys = publicView.keySet();
            Collection<ShopData<Types>> values = publicView.values();
            UUID uuid = UUID.randomUUID();
            ShopData<Types> data = f.shop.getPlayerShopData(uuid);
            assertSame(data, publicView.get(uuid));
            assertSame(data, getterView.get(uuid));
            AtomicInteger callbacks = new AtomicInteger();
            assertThrows(UnsupportedOperationException.class, () -> publicView.put(UUID.randomUUID(), data));
            assertThrows(UnsupportedOperationException.class, () -> publicView.compute(uuid, (key, value) -> { callbacks.incrementAndGet(); return null; }));
            assertThrows(UnsupportedOperationException.class, () -> publicView.entrySet().iterator().next().setValue(data));
            assertThrows(UnsupportedOperationException.class, () -> publicView.keySet().remove(uuid));
            assertThrows(UnsupportedOperationException.class, getterView::clear);
            assertEquals(0, callbacks.get());
            Map<Types, ImmutableList<ShopSlot>> dataView = data.getData();
            assertThrows(UnsupportedOperationException.class, dataView::clear);
            assertThrows(UnsupportedOperationException.class, () -> dataView.get(Types.PRIMARY).clear());
            long revision = data.getRevision();
            problem("invalid_slot", () -> data.setDoneData(null));
            assertEquals(revision, data.getRevision());
            Iterator<Map.Entry<UUID, ShopData<Types>>> historical = entries.iterator();
            UUID nextId = UUID.randomUUID();
            f.shop.resetPlayerData(List.of(nextId));
            ShopData<Types> next = f.shop.getPlayerShopData(nextId);
            assertEquals(Set.of(nextId), keys);
            assertEquals(Set.of(Map.entry(nextId, next)), entries);
            assertEquals(List.of(next), new ArrayList<>(values));
            assertSame(data, historical.next().getValue());
            problem("closed", data::getMoney);
            f.shop.clearPlayerShopData(nextId);
            assertTrue(publicView.isEmpty());
            assertTrue(getterView.isEmpty());
            assertTrue(entries.isEmpty());
            assertTrue(values.isEmpty());
            assertTrue(keys.isEmpty());
            problem("closed", data::getData);
        }
    }

    @Test void legacyDefinitionEntrypointsRejectBeforeReadingMaliciousInputsOrProviders() {
        try (Fixture f = new Fixture(); Fixture foreign = new Fixture()) {
            Map<Types, ArrayList<ShopSlot>> malicious = new AbstractMap<>() {
                @Override public Set<Entry<Types, ArrayList<ShopSlot>>> entrySet() { throw new AssertionError("input read"); }
            };
            List<Executable> denied = List.of(
                    f.shop::getDefaultShopDataMap, f.shop::getDefaultShopDataMapString,
                    () -> f.shop.getDefaultShopSlotListByType((String) null),
                    () -> f.shop.getDefaultShopSlotListByType((Types) null),
                    () -> f.shop.getDefaultShopDataItemStack(null, -1), f.shop::getCodec,
                    () -> f.shop.setDefaultShopData(malicious),
                    () -> f.shop.replaceDefaultShopData(null, -1, null),
                    () -> f.shop.setDefaultShopDataGroupId(null, -1, 0),
                    () -> f.shop.addDefaultShopDataListenerModule(null, -1, null),
                    () -> f.shop.removeDefaultShopDataListenerModule(null, -1, null),
                    () -> f.shop.setDefaultShopDataItemStack(null, -1, null),
                    () -> f.shop.setDefaultShopDataCost(null, -1, 0),
                    () -> f.shop.copyConfigurationFrom(null), () -> f.shop.isConfigurationCompatible(null),
                    () -> f.shop.addArea(null), f.shop::clearAreas, () -> f.shop.withName("other"));
            denied.forEach(action -> problem("invalid_slot", action));
            assertSame(f.shop, f.shop.withName(f.shop.getName()));

            UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
            RuntimeShopSlot first = slot(f.shop.getPlayerShopData(firstId));
            RuntimeShopSlot second = slot(f.shop.getPlayerShopData(secondId));
            RuntimeShopSlot sameValueCopy = first.copy();
            RuntimeShopSlot detached = slot(f.shop.getDefaultShopData());
            RuntimeShopSlot foreignSlot = slot(foreign.shop.getPlayerShopData(UUID.randomUUID()));
            AtomicInteger untrustedCalls = new AtomicInteger();
            ShopSlot maliciousSlot = new ShopSlot(() -> {
                untrustedCalls.incrementAndGet();
                throw new AssertionError("untrusted supplier");
            }, 100, 3, 9, stack -> false) {
                @Override public int getIndex() { untrustedCalls.incrementAndGet(); throw new AssertionError("untrusted index"); }
                @Override public int getCost() { untrustedCalls.incrementAndGet(); throw new AssertionError("untrusted cost"); }
                @Override public ItemStack process() { untrustedCalls.incrementAndGet(); throw new AssertionError("untrusted process"); }
            };
            AtomicInteger publicationEvents = new AtomicInteger();
            try (Hook ignored = new Hook(f.shop, event -> publicationEvents.incrementAndGet())) {
                // This helper proves slot authorization only; no packet or actual network host is exercised.
                assertSame(first, f.shop.requirePlayerSlot(firstId, "PRIMARY", first));
                assertSame(second, f.shop.requirePlayerSlot(secondId, "PRIMARY", second));
                for (ShopSlot supplied : List.of(second, sameValueCopy, detached, foreignSlot, maliciousSlot)) {
                    problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, "PRIMARY", supplied));
                }
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(UUID.randomUUID(), "PRIMARY", maliciousSlot));
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, "missing", maliciousSlot));
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, "EXTRA", first));
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(null, "PRIMARY", maliciousSlot));
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, null, maliciousSlot));
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, "PRIMARY", null));
                assertEquals(0, untrustedCalls.get(), "reject caller slots by identity before getter, process or supplier calls");
                assertEquals(0, publicationEvents.get(), "slot synchronization authorization must never create player membership");
                assertEquals(Set.of(firstId, secondId), f.shop.playersData.keySet());
            }
            RuntimeShopSlot replacement = slot(f.shop.getDefaultAndPutData(firstId, false));
            try (Hook ignored = new Hook(f.shop, event -> publicationEvents.incrementAndGet())) {
                problem("invalid_slot", () -> f.shop.requirePlayerSlot(firstId, "PRIMARY", first));
                assertSame(replacement, f.shop.requirePlayerSlot(firstId, "PRIMARY", replacement));
                assertEquals(0, publicationEvents.get(), "reject retired membership without publishing again");
            }
        }
    }

    @Test void ownerBusyAndWrongThreadPrecedeMalformedInputsAndClosedShopRemainsTerminal() throws InterruptedException {
        try (Fixture f = new Fixture()) {
            ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
            RuntimeShopSlot bound = slot(data);
            f.normal.inspect(stack -> {
                problem("busy", () -> f.shop.resetPlayerData((List<UUID>) null));
                problem("busy", () -> data.setDoneData(null));
                problem("busy", bound::getCost);
                problem("busy", () -> f.shop.requirePlayerSlot(null, null, null));
                problem("busy", () -> new RuntimeShop<>(Types.class, null, null, 0, null, f.owner));
                return null;
            });
            runWorker(() -> {
                problem("wrong_thread", () -> f.shop.getPlayerShopData((UUID) null));
                problem("wrong_thread", () -> data.setDoneData(null));
                problem("wrong_thread", () -> bound.buy(null, 0));
                problem("wrong_thread", f.shop::close);
                problem("wrong_thread", () -> f.shop.requirePlayerSlot(null, null, null));
            });
            f.shop.close();
            problem("closed", () -> f.shop.resetPlayerData((List<UUID>) null));
            problem("closed", () -> data.setDoneData(null));
            problem("closed", () -> f.shop.withName("publication"));
            problem("closed", () -> f.shop.requirePlayerSlot(null, null, null));
            f.owner.close();
            assertDoesNotThrow(f.shop::close);
            runWorker(() -> problem("wrong_thread", f.shop::close));
            assertEquals("publication", f.shop.getName());
            assertEquals(Types.PRIMARY, f.shop.valueOf("PRIMARY"));
        }
    }

    @Test void closedFrontDoorsRejectBeforeNullPlayerServerNetworkOrInventoryAccess() {
        try (Fixture f = new Fixture()) {
            ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
            RuntimeShopSlot bound = slot(data);
            f.shop.close();
            List<Executable> frontDoors = List.of(
                    f.shop::syncShopData, f.shop::syncShopMoneyData, f.shop::sync,
                    () -> f.shop.syncShopData((ServerPlayer) null),
                    () -> f.shop.syncShopData((List<ServerPlayer>) null),
                    () -> f.shop.syncShopMoneyData((ServerPlayer) null),
                    () -> f.shop.syncShopMoneyData((UUID) null),
                    () -> f.shop.syncShopMoneyData((java.util.Collection<ServerPlayer>) null),
                    () -> f.shop.syncShopData(null, (String) null, null),
                    () -> f.shop.syncShopData(null, (Types) null, -1),
                    () -> f.shop.sync(null), () -> f.shop.handleButton(null, null, -1, null),
                    () -> f.shop.getPlayerShopData((ServerPlayer) null),
                    () -> f.shop.getPlayerShopDataSafe((ServerPlayer) null),
                    () -> f.shop.addMoney((ServerPlayer) null, 1), () -> f.shop.reduceMoney(null, 1),
                    () -> f.shop.isInArea(null), () -> f.shop.displayAreas(null),
                    () -> data.handleButton(null, null, -1, null), () -> data.lockShopSlots(null),
                    () -> data.checkItemStackIsInData(null), () -> bound.buy(null, 0),
                    () -> bound.returnItem(null), () -> bound.canReturn(null));
            frontDoors.forEach(action -> problem("closed", action));
        }
    }

    @Test void detachedViewsSkipDataInitAndSurviveMembershipClearsUntilShopClose() {
        try (Fixture f = new Fixture()) {
            AtomicInteger events = new AtomicInteger();
            try (Hook ignored = new Hook(f.shop, event -> events.incrementAndGet())) {
                ShopData<Types> detached = f.shop.getDefaultShopData();
                RuntimeShopSlot raw = (RuntimeShopSlot) f.shop.getShopDataByRaw().get(Types.PRIMARY).get(0);
                RuntimeShopSlot copy = raw.copy();
                assertEquals(0, events.get());
                assertTrue(f.shop.playersData.isEmpty());
                assertTrue(f.shop.getPlayerShopDataSafe(UUID.randomUUID()).isEmpty());
                assertEquals(0, events.get(), "safe lookup must not publish");
                ShopData<Types> registered = f.shop.getPlayerShopData(UUID.randomUUID());
                assertEquals(1, events.get());
                f.shop.clearPlayerShopData();
                problem("closed", registered::getMoney);
                assertEquals(800, detached.getMoney());
                assertSame(f.normal, raw.currentTemplate());
                assertSame(f.normal, copy.currentTemplate());
                f.shop.close();
                problem("closed", detached::getMoney);
                retired(raw);
                retired(copy);
            }
        }
    }

    @Test void constructorRejectsUnfrozenWrongCoordinatesAliasesForeignAndAlreadyBoundSlots() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1); RuntimeItemTemplates other = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            var foreign = capture(other, Items.DIAMOND);
            Map<Types, ArrayList<ShopSlot>> valid = defaults(template, null);
            owner.seal();
            other.seal();
            problem("invalid_input", () -> new RuntimeShop<>(Types.class, "bad", null, 800, List.of(), owner));
            Map<Types, ArrayList<ShopSlot>> invalid = copyLists(valid);
            invalid.get(Types.PRIMARY).set(0, new RuntimeShopSlot(template, 100, 3, 9));
            problem("invalid_slot", () -> new RuntimeShop<>(Types.class, "bad", invalid, 800, List.of(), owner));
            Map<Types, ArrayList<ShopSlot>> reordered = copyLists(valid);
            Collections.swap(reordered.get(Types.PRIMARY), 0, 1);
            problem("invalid_slot", () -> new RuntimeShop<>(Types.class, "bad", reordered, 800, List.of(), owner));
            Map<Types, ArrayList<ShopSlot>> aliased = copyLists(valid);
            aliased.get(Types.PRIMARY).set(1, aliased.get(Types.PRIMARY).get(0));
            problem("invalid_slot", () -> new RuntimeShop<>(Types.class, "bad", aliased, 800, List.of(), owner));
            Map<Types, ArrayList<ShopSlot>> foreignDefaults = defaults(foreign, null);
            problem("foreign_template", () -> new RuntimeShop<>(Types.class, "bad", foreignDefaults, 800, List.of(), owner));
            try (RuntimeShop<Types> shop = new RuntimeShop<>(Types.class, "valid", valid, 800, List.of(), owner)) {
                Map<Types, ArrayList<ShopSlot>> bound = copyLists(valid);
                bound.get(Types.PRIMARY).set(0, slot(shop.getDefaultShopData()));
                problem("invalid_slot", () -> new RuntimeShop<>(Types.class, "bad", bound, 800, List.of(), owner));
            }
            assertDoesNotThrow(template::requireAvailable);
            assertEquals(100, ((RuntimeShopSlot) valid.get(Types.PRIMARY).get(0)).getCost());
        }
    }

    @Test void playerAndPublishedSlotBudgetsRejectBeforeAnyEventAndKeepExistingGraph() {
        try (Fixture f = new Fixture()) {
            UUID originalId = UUID.randomUUID();
            ShopData<Types> old = f.shop.getPlayerShopData(originalId);
            AtomicInteger calls = new AtomicInteger();
            try (Hook ignored = new Hook(f.shop, event -> calls.incrementAndGet())) {
                List<UUID> tooMany = new ArrayList<>();
                for (int i = 0; i < 1025; i++) tooMany.add(UUID.randomUUID());
                problem("budget", () -> f.shop.resetPlayerData(tooMany));
                assertEquals(0, calls.get());
                assertSame(old, f.shop.getPlayerShopData(originalId));
            }
        }
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            Map<Wide, ArrayList<ShopSlot>> defaults = new LinkedHashMap<>();
            ArrayList<ShopSlot> slots = new ArrayList<>();
            for (int i = 0; i < 65; i++) slots.add(frozen(template, i, null));
            defaults.put(Wide.ONLY, slots);
            owner.seal();
            try (RuntimeShop<Wide> shop = new RuntimeShop<>(Wide.class, "wide", defaults, 800, List.of(), owner)) {
                List<UUID> players = new ArrayList<>();
                for (int i = 0; i < 1009; i++) players.add(UUID.randomUUID());
                problem("budget", () -> shop.resetPlayerData(players));
                assertTrue(shop.playersData.isEmpty());
            }
            List<AreaData> areas = Collections.nCopies(8193, new AreaData(BlockPos.ZERO, BlockPos.ZERO));
            problem("budget", () -> new RuntimeShop<>(Wide.class, "areas", defaults, 800, areas, owner));
            problem("budget", () -> new RuntimeShop<>(Huge.class, "slots", Map.of(Huge.ONLY, List.of()), 800, List.of(), owner));
            Map<Types, List<ShopSlot>> excessiveCategories = new AbstractMap<>() {
                public int size() { return 8193; }
                public Set<Entry<Types, List<ShopSlot>>> entrySet() { throw new AssertionError("budget must precede input traversal"); }
            };
            problem("budget", () -> new RuntimeShop<>(Types.class, "categories", excessiveCategories, 800, List.of(), owner));
            AtomicInteger callbacks = new AtomicInteger();
            ListenerModule counted = new ListenerModule() {
                public String getName() { callbacks.incrementAndGet(); return "budget"; }
                public int getPriority() { callbacks.incrementAndGet(); return 1; }
            };
            List<ListenerModule> repeated = Collections.nCopies(2731, counted);
            Map<Types, ArrayList<ShopSlot>> tooManyListeners = new LinkedHashMap<>();
            tooManyListeners.put(Types.PRIMARY, new ArrayList<>(List.of(frozen(template, 0, repeated), frozen(template, 1, repeated))));
            tooManyListeners.put(Types.EXTRA, new ArrayList<>(List.of(frozen(template, 0, repeated))));
            int before = callbacks.get();
            problem("budget", () -> new RuntimeShop<>(Types.class, "listeners", tooManyListeners, 800, List.of(), owner));
            assertEquals(before, callbacks.get(), "definition preparation must not call listener names or priorities");
        }
    }

    @Test void twoPlayersKeepIndependentGameplayStateAndRepeatedResetDoesNotGrowTemplates() {
        try (Fixture f = new Fixture()) {
            UUID first = UUID.randomUUID(), second = UUID.randomUUID();
            ShopData<Types> a = f.shop.getPlayerShopData(first), b = f.shop.getPlayerShopData(second);
            f.change.onChange(new ShopSlotChangeEvent(slot(a), null, 800, 1));
            slot(a).lock(2);
            a.setMoney(123);
            assertSame(f.normal, slot(b).currentTemplate());
            assertEquals(0, slot(b).getBoughtCount());
            assertFalse(slot(b).isLocked());
            assertEquals(800, b.getMoney());
            ItemStack delivery = slot(a).process();
            try { assertEquals(Items.DIAMOND, delivery.getItem()); }
            finally { delivery.invalidateCaps(); }
            for (int i = 0; i < 100; i++) {
                f.shop.resetPlayerData(false);
                assertEquals(2, f.owner.size());
                assertEquals(123, f.shop.getPlayerShopData(first).getMoney());
                assertEquals(800, f.shop.getPlayerShopData(second).getMoney());
                assertSame(f.normal, slot(f.shop.getPlayerShopData(first)).currentTemplate());
            }
        }
    }

    @Test void graphChecksRejectUnusedSlotPublicFieldDriftBeforeNormalReturn() {
        try (Fixture f = new Fixture()) {
            ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
            RuntimeShopSlot unused = (RuntimeShopSlot) data.getShopSlotsByType(Types.EXTRA).get(0);
            unused.defaultCost++;
            problem("invalid_slot", () -> data.handleButton(null, Types.PRIMARY, -1, ShopAction.BUY));
            problem("invalid_slot", () -> data.checkItemStackIsInData(ItemStack.EMPTY));
            assertEquals(800, data.getMoney(), "O(1) economic reads do not run whole-graph checks");
            f.shop.getDefaultAndPutData(f.shop.playersData.keySet().iterator().next(), true);
            problem("closed", unused::getCost);
        }
    }

    @Test void callbackClosingShopStopsFollowingCostListenersBeforeInventoryAndMoneyCommit() {
        AtomicReference<RuntimeShop<Types>> target = new AtomicReference<>();
        AtomicInteger afterClose = new AtomicInteger();
        ListenerModule closing = new ListenerModule() {
            public String getName() { return "close"; }
            public int getPriority() { return 1; }
            public void onCostCheck(CheckCostEvent event, ShopSlot slot) { target.get().close(); }
        };
        ListenerModule later = new ListenerModule() {
            public String getName() { return "later"; }
            public int getPriority() { return 1; }
            public void onCostCheck(CheckCostEvent event, ShopSlot slot) { afterClose.incrementAndGet(); }
        };
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            Map<Types, ArrayList<ShopSlot>> defaults = defaults(template, List.of(closing, later));
            owner.seal();
            try (RuntimeShop<Types> shop = new RuntimeShop<>(Types.class, "callbacks", defaults, 800, List.of(), owner)) {
                target.set(shop);
                ShopData<Types> data = shop.getPlayerShopData(UUID.randomUUID());
                problem("closed", () -> data.handleButton(null, Types.PRIMARY, 1, ShopAction.BUY));
                assertEquals(0, afterClose.get());
                assertTrue(shop.playersData.isEmpty());
                assertDoesNotThrow(template::requireAvailable);
            }
        }
    }

    @Test void boundToStringWorksAndOwnerClosureDuringDataInitCannotPublish() {
        try (Fixture f = new Fixture()) {
            ShopData<Types> data = f.shop.getPlayerShopData(UUID.randomUUID());
            RuntimeShopSlot bound = slot(data);
            assertTrue(bound.toString().contains("defaultCost=100"));
            f.normal.inspect(stack -> {
                problem("busy", bound::getCost);
                problem("busy", bound::toString);
                return null;
            });
            AtomicReference<RuntimeShopSlot> escaped = new AtomicReference<>();
            try (Hook ignored = new Hook(f.shop, event -> {
                escaped.set((RuntimeShopSlot) event.getData().get(Types.PRIMARY).get(0));
                f.owner.close();
            })) {
                problem("closed", () -> f.shop.getDefaultAndPutData(UUID.randomUUID(), true));
            }
            assertEquals(1, f.shop.playersData.size(), "owner failure does not commit candidate membership");
            retired(escaped.get());
        }
        try (Fixture f = new Fixture()) {
            UUID uuid = UUID.randomUUID();
            ShopData<Types> old = f.shop.getPlayerShopData(uuid);
            List<UUID> closesOwner = new java.util.AbstractList<>() {
                public int size() { f.owner.close(); return 0; }
                public UUID get(int index) { throw new AssertionError("empty list"); }
            };
            problem("closed", () -> f.shop.resetPlayerData(closesOwner));
            assertSame(old, f.shop.playersData.get(uuid), "even an empty batch rechecks owner before commit");
        }
    }

    private enum Types implements INamedType {
        PRIMARY(2), EXTRA(1);
        private final int count;
        static final AtomicInteger calls = new AtomicInteger();
        static boolean forbidden;
        Types(int count) { this.count = count; }
        public int slotCount() {
            calls.incrementAndGet();
            if (forbidden) throw new AssertionError("slotCount called after shape capture");
            return count;
        }
        public boolean dorpUnlock() { return false; }
        public ArrayList<ShopSlot> defaultSlots() { throw new AssertionError("runtime must use explicit defaults"); }
    }

    private enum Wide implements INamedType {
        ONLY;
        public int slotCount() { return 65; }
        public boolean dorpUnlock() { return false; }
        public ArrayList<ShopSlot> defaultSlots() { throw new AssertionError("explicit defaults only"); }
    }

    private enum Huge implements INamedType {
        ONLY;
        public int slotCount() { return Integer.MAX_VALUE; }
        public boolean dorpUnlock() { return false; }
        public ArrayList<ShopSlot> defaultSlots() { throw new AssertionError("budget must precede slot allocation"); }
    }

    private static final class Fixture implements AutoCloseable {
        final RuntimeItemTemplates owner = new RuntimeItemTemplates(2);
        final RuntimeItemTemplates.Template normal = capture(owner, Items.STONE);
        final RuntimeItemTemplates.Template changed = capture(owner, Items.DIAMOND);
        final RuntimeChangeItemModule change = new RuntimeChangeItemModule(normal, 100, changed, 25, "publication_change");
        final RuntimeShop<Types> shop;
        Fixture() {
            Map<Types, ArrayList<ShopSlot>> defaults = defaults(normal, List.of(change));
            owner.seal();
            shop = new RuntimeShop<>(Types.class, "publication", defaults, 800, List.of(), owner);
        }
        public void close() {
            try {
                shop.close();
            } catch (RuntimeItemException failure) {
                // Owner-close negative cases already revoked every native handle; shop.close must reject.
                if (!failure.getMessage().equals("editor:runtime_items/closed")) throw failure;
            } finally { owner.close(); }
        }
    }

    /** Register the real bus, filter by exact shop identity, and always unregister in try-with-resources. */
    private static final class Hook implements AutoCloseable {
        final Consumer<FPSMShopEvent.DataInit> callback;
        @SuppressWarnings("unchecked")
        Hook(RuntimeShop<Types> shop, Consumer<FPSMShopEvent.DataInit<Types>> action) {
            callback = event -> { if (event.getShop() == shop) action.accept((FPSMShopEvent.DataInit<Types>) event); };
            MinecraftForge.EVENT_BUS.addListener(callback);
        }
        public void close() { MinecraftForge.EVENT_BUS.unregister(callback); }
    }

    private static Map<Types, ArrayList<ShopSlot>> defaults(RuntimeItemTemplates.Template template, List<ListenerModule> listeners) {
        Map<Types, ArrayList<ShopSlot>> defaults = new LinkedHashMap<>();
        defaults.put(Types.PRIMARY, new ArrayList<>(List.of(frozen(template, 0, listeners), frozen(template, 1, null))));
        defaults.put(Types.EXTRA, new ArrayList<>(List.of(frozen(template, 0, null))));
        return defaults;
    }

    private static RuntimeShopSlot frozen(RuntimeItemTemplates.Template template, int index, List<ListenerModule> listeners) {
        RuntimeShopSlot slot = new RuntimeShopSlot(template, 100, 3, 9);
        slot.setIndex(index);
        if (listeners != null) listeners.forEach(slot::addListener);
        slot.freezeDefinition();
        return slot;
    }

    private static Map<Types, ArrayList<ShopSlot>> copyLists(Map<Types, ArrayList<ShopSlot>> source) {
        Map<Types, ArrayList<ShopSlot>> result = new LinkedHashMap<>();
        source.forEach((type, slots) -> result.put(type, new ArrayList<>(slots)));
        return result;
    }

    private static Map<Types, List<ShopSlot>> mutable(FPSMShopEvent.DataInit<Types> event) {
        Map<Types, List<ShopSlot>> result = new LinkedHashMap<>();
        event.getData().forEach((type, slots) -> result.put(type, new ArrayList<>(slots)));
        return result;
    }

    private static RuntimeShopSlot slot(ShopData<Types> data) {
        return (RuntimeShopSlot) data.getShopSlotsByType(Types.PRIMARY).get(0);
    }

    private static RuntimeItemTemplates.Template capture(RuntimeItemTemplates owner, Item item) {
        ItemStack borrowed = new ItemStack(item);
        try { return owner.capture(borrowed, ignored -> { }, () -> false); }
        finally { borrowed.invalidateCaps(); }
    }

    private static void retired(RuntimeShopSlot slot) {
        assertNotNull(slot);
        problem("closed", slot::getCost);
        problem("closed", slot::currentTemplate);
        problem("closed", slot::copy);
        problem("closed", () -> slot.buy(null, 0));
    }

    private static void problem(String reason, Executable action) {
        RuntimeItemException failure = assertThrows(RuntimeItemException.class, action);
        assertEquals("editor:runtime_items/" + reason, failure.getMessage());
        assertEquals("runtime_items", failure.problem().target());
    }

    private static void runWorker(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { action.run(); } catch (Throwable thrown) { failure.set(thrown); } });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "worker must finish within ten seconds");
        } finally {
            worker.interrupt();
            worker.join(1_000);
            assertFalse(worker.isAlive(), "worker must terminate during cleanup");
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
