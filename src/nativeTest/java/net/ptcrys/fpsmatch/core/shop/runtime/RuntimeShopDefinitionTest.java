package net.ptcrys.fpsmatch.core.shop.runtime;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.ptcrys.fpsmatch.core.shop.event.CheckCostEvent;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.GroupReturnDependent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Fixed slot definitions exercised with real Minecraft stacks and Forge serialization. */
class RuntimeShopDefinitionTest {
    @BeforeAll static void bootstrapNativeRuntime() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftForge.EVENT_BUS.start();
    }

    @Test void frozenTemplateAllowlistRejectsThirdForeignAndUnmanagedWithoutCallingSupplier() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(4);
             RuntimeItemTemplates other = new RuntimeItemTemplates(1)) {
            var initial = capture(owner, Items.STONE);
            var normal = capture(owner, Items.EMERALD);
            var changed = capture(owner, Items.DIAMOND);
            var third = capture(owner, Items.DIRT);
            var foreign = capture(other, Items.STONE);
            RuntimeShopSlot slot = slot(initial);
            slot.addListener(new RuntimeChangeItemModule(normal, 100, changed, 25, "declared"));
            slot.freezeDefinition();
            slot.setItemSupplier(normal);
            assertSame(normal, slot.currentTemplate());
            slot.setItemSupplier(changed);
            assertSame(changed, slot.currentTemplate());
            RuntimeShopSlot copy = slot.copy();
            assertTrue(copy.isDefinitionFrozen());
            copy.setItemSupplier(initial);
            copy.setItemSupplier(normal);
            assertSame(normal, copy.currentTemplate(), "copy must retain the original three-template definition");
            slot.setItemSupplier(initial);
            AtomicInteger calls = new AtomicInteger();
            Supplier<ItemStack> unmanaged = () -> { calls.incrementAndGet(); return ItemStack.EMPTY; };
            for (Supplier<ItemStack> rejected : List.of(third, foreign, unmanaged)) {
                String reason = rejected == third ? "invalid_slot" : rejected == foreign ? "foreign_template" : "unmanaged_supplier";
                problem(reason, () -> slot.setItemSupplier(rejected));
                assertSame(initial, slot.currentTemplate());
                slot.itemSupplier = rejected;
                problem(reason, slot::currentTemplate);
                problem(reason, slot::process);
                problem(reason, slot::copy);
                problem(reason, slot::freezeDefinition);
                slot.itemSupplier = initial;
            }
            assertEquals(0, calls.get());
            assertEquals(4, owner.size());
        }
    }

    @Test void frozenDefinitionSettersRejectNullEmptyAndSameValuesBeforeModuleCallbacks() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
            AtomicInteger names = new AtomicInteger();
            AtomicInteger priorities = new AtomicInteger();
            ListenerModule malicious = new ListenerModule() {
                public String getName() { names.incrementAndGet(); throw new AssertionError("name"); }
                public int getPriority() { priorities.incrementAndGet(); throw new AssertionError("priority"); }
            };
            slot.freezeDefinition();
            for (Executable write : List.<Executable>of(
                    () -> slot.setDefaultCost(100), () -> slot.setGroupId(9),
                    () -> slot.setMaxBuyCount(3), () -> slot.addListener(null),
                    () -> slot.addListener(malicious), () -> slot.replaceListener(null),
                    () -> slot.replaceListener(malicious), () -> slot.removeListenerModule(null),
                    () -> slot.removeListenerModule(""))) problem("invalid_slot", write);
            slot.setIndex(4);
            problem("invalid_slot", () -> slot.setIndex(5));
            problem("invalid_slot", () -> slot.setIndex(-1));
            assertEquals(4, slot.getIndex());
            assertEquals(0, names.get());
            assertEquals(0, priorities.get());
        }
    }

    @Test void declaredChangeAndResetPreserveMutablePriceCountsAndLocks() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            var initial = capture(owner, Items.STONE);
            var changed = capture(owner, Items.DIAMOND);
            RuntimeShopSlot slot = slot(initial);
            RuntimeChangeItemModule change = new RuntimeChangeItemModule(initial, 100, changed, 25, "declared");
            slot.addListener(change);
            slot.freezeDefinition();
            change.onChange(new ShopSlotChangeEvent(slot, null, 80, 1));
            assertSame(changed, slot.currentTemplate());
            assertEquals(25, slot.getCost());
            slot.setCost(77);
            slot.lock(2);
            slot.lockPickedUp(1);
            assertEquals(2, slot.getBoughtCount());
            assertEquals(3, slot.getCountAgainstLimit());
            assertTrue(slot.isLocked());
            slot.unlock(1);
            assertEquals(1, slot.getBoughtCount());
            assertEquals(2, slot.getCountAgainstLimit());
            assertFalse(slot.isLocked());
            slot.reconcileCarriedItems(2);
            assertEquals(0, slot.getBoughtCount());
            assertEquals(2, slot.getCountAgainstLimit());
            slot.lock();
            slot.unlock();
            slot.resetCost();
            assertEquals(100, slot.getCost());
            slot.reset();
            assertSame(initial, slot.currentTemplate());
            assertEquals(100, slot.getCost());
            assertEquals(0, slot.getCountAgainstLimit());
            assertFalse(slot.isLocked());
            ItemStack delivery = slot.process();
            try { assertEquals(Items.STONE, delivery.getItem()); }
            finally { if (delivery != ItemStack.EMPTY) delivery.invalidateCaps(); }
            assertTrue(slot.isDefinitionFrozen());
            assertEquals(2, owner.size());
        }
    }

    @Test void frozenCopiesShareExactOrderedDuplicateListenersAndKeepIndependentGameplayState() {
        try (NativeProbe probe = new NativeProbe(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            RuntimeShopSlot source = slot(template);
            AtomicInteger priorities = new AtomicInteger();
            List<String> calls = new ArrayList<>();
            ListenerModule first = ordered("first", priorities, calls);
            ListenerModule second = ordered("second", priorities, calls);
            source.addListener(first);
            source.addListener(second);
            source.addListener(first);
            int priorityBefore = priorities.get();
            int serializationBefore = probe.serializations.get();
            source.freezeDefinition();
            source.setCost(7);
            source.lock(2);
            source.lockPickedUp(1);
            RuntimeShopSlot copy = source.copy();
            assertTrue(copy.isDefinitionFrozen());
            assertSame(template, copy.currentTemplate());
            assertEquals(List.of(first, second, first), copy.getListenerModules());
            assertEquals(priorityBefore, priorities.get());
            assertEquals(serializationBefore, probe.serializations.get(), "freeze/copy must not serialize native stacks");
            assertEquals(4, copy.getIndex());
            assertEquals(100, copy.getCost());
            assertEquals(0, copy.getCountAgainstLimit());
            assertFalse(copy.isLocked());
            copy.reset();
            assertEquals(List.of("first", "second", "first"), calls);
            assertEquals(7, source.getCost());
            assertEquals(3, source.getCountAgainstLimit());
            assertTrue(source.isLocked());
            problem("invalid_slot", () -> copy.removeListenerModule("first"));
            assertEquals(1, owner.size());
        }
    }

    @Test void undeclaredChangeWithIdenticalTemplatesRejectsBeforeComparisonOrStateWrites() {
        try (NativeProbe probe = new NativeProbe(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            RuntimeShopSlot slot = slot(template);
            RuntimeChangeItemModule declared = new RuntimeChangeItemModule(template, 100, template, 25, "declared");
            RuntimeChangeItemModule undeclared = new RuntimeChangeItemModule(template, 100, template, 25, "declared");
            slot.addListener(declared);
            slot.freezeDefinition();
            slot.lock(1);
            int before = probe.serializations.get();
            ShopSlotChangeEvent event = new ShopSlotChangeEvent(slot, null, 80, -1);
            problem("invalid_slot", () -> undeclared.onChange(event));
            problem("invalid_slot", () -> undeclared.onReset(slot));
            assertEquals(before, probe.serializations.get());
            assertEquals(80, event.getMoney());
            assertEquals(100, slot.getCost());
            assertEquals(1, slot.getBoughtCount());
        }
    }

    @Test void publicDefaultCostDriftRejectsAllStateAndNullPlayerEntrypointsBeforeEffects() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
            AtomicInteger callbacks = new AtomicInteger();
            slot.addListener(callbacks(callbacks, () -> { }));
            slot.freezeDefinition();
            slot.lock(2);
            slot.lockPickedUp(1);
            slot.setCost(77);
            slot.defaultCost = 101;
            for (Executable use : List.<Executable>of(
                    slot::currentTemplate, slot::process, slot::copy, slot::isEmptyItem,
                    slot::getAmmoCount, slot::getDummyAmmoCount, slot::toString,
                    () -> slot.returningChecker.test(null), () -> slot.setCost(2), slot::resetCost,
                    slot::lock, () -> slot.lock(0), () -> slot.lockPickedUp(0), slot::unlock,
                    () -> slot.unlock(1), () -> slot.reconcileCarriedItems(0),
                    () -> slot.canBuy(100), () -> slot.canReturn(null), slot::reset,
                    () -> slot.onGroupSlotChanged(null),
                    () -> slot.onGroupSlotChanged(new ShopSlotChangeEvent(slot, null, 80, 1)),
                    () -> slot.handleCheckCostEvent(null), slot::requiresGroupReturn, slot::getListenerNames,
                    () -> slot.buy(null, 100), () -> slot.returnItem(null), () -> slot.returnItem(null, 1),
                    slot::freezeDefinition)) problem("invalid_slot", use);
            assertEquals(0, callbacks.get());
            assertEquals(77, slot.getCost());
            assertEquals(2, slot.getBoughtCount());
            assertEquals(3, slot.getCountAgainstLimit());
            assertTrue(slot.isLocked());
        }
    }

    @Test void dispatchStopsAfterListenerDriftOrOwnerCloseAcrossResetGroupCostAndNames() {
        for (boolean closeOwner : List.of(false, true)) {
            for (int entry = 0; entry < 5; entry++) {
                try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
                    RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
                    AtomicBoolean armed = new AtomicBoolean();
                    AtomicInteger first = new AtomicInteger();
                    AtomicInteger second = new AtomicInteger();
                    slot.addListener(callbacks(first, () -> {
                        if (armed.get()) {
                            if (closeOwner) owner.close(); else slot.defaultCost++;
                        }
                    }));
                    slot.addListener(callbacks(second, () -> { }));
                    slot.freezeDefinition();
                    first.set(0);
                    second.set(0);
                    armed.set(true);
                    Executable use = switch (entry) {
                        case 0 -> slot::reset;
                        case 1 -> () -> slot.onGroupSlotChanged(new ShopSlotChangeEvent(slot, null, 0, 0));
                        case 2 -> () -> slot.handleCheckCostEvent(new CheckCostEvent(null, 1));
                        case 3 -> slot::requiresGroupReturn;
                        default -> slot::getListenerNames;
                    };
                    problem(closeOwner ? "closed" : "invalid_slot", use);
                    assertEquals(1, first.get(), "entry " + entry);
                    assertEquals(0, second.get(), "next callback must not run at entry " + entry);
                }
            }
        }
    }

    @Test void listenerFailureKeepsIdentityAndTypedGroupMarkerSkipsNamesAndLaterListeners() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            RuntimeShopSlot failing = slot(template);
            AtomicBoolean armed = new AtomicBoolean();
            Error expected = new AssertionError("callback identity");
            failing.addListener(callbacks(new AtomicInteger(), () -> { if (armed.get()) throw expected; }));
            failing.freezeDefinition();
            armed.set(true);
            assertSame(expected, assertThrows(AssertionError.class, failing::getListenerNames));
            assertSame(expected, assertThrows(AssertionError.class, failing::reset));
            RuntimeShopSlot typed = slot(template);
            class Marker implements ListenerModule, GroupReturnDependent {
                public String getName() { throw new AssertionError("typed marker name"); }
                public int getPriority() { return 2; }
            }
            typed.addListener(new Marker());
            typed.addListener(new ListenerModule() {
                public String getName() { throw new AssertionError("short-circuited name"); }
                public int getPriority() { return 1; }
            });
            typed.freezeDefinition();
            assertTrue(typed.requiresGroupReturn());
            RuntimeShopSlot named = slot(template);
            named.addListener(new ListenerModule() {
                public String getName() { return "changeItem_legacy"; }
                public int getPriority() { return 2; }
            });
            named.addListener(new ListenerModule() {
                public String getName() { throw new AssertionError("legacy name short circuit"); }
                public int getPriority() { return 1; }
            });
            named.freezeDefinition();
            assertTrue(named.requiresGroupReturn());
        }
    }

    @Test void freezeFailuresForMissingIndexAndForeignChangeRemainMutableAndRetryable() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1);
             RuntimeItemTemplates other = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            var foreign = capture(other, Items.DIRT);
            RuntimeShopSlot missing = new RuntimeShopSlot(template, 100, 3, 9);
            problem("invalid_slot", missing::freezeDefinition);
            assertFalse(missing.isDefinitionFrozen());
            missing.setDefaultCost(123);
            missing.setIndex(2);
            missing.freezeDefinition();
            assertEquals(123, missing.getDefaultCost());
            RuntimeShopSlot slot = slot(template);
            slot.addListener(new RuntimeChangeItemModule(foreign, 100, foreign, 25, "foreign"));
            problem("foreign_template", slot::freezeDefinition);
            assertFalse(slot.isDefinitionFrozen());
            slot.removeListenerModule("foreign");
            slot.setGroupId(8);
            slot.freezeDefinition();
            assertTrue(slot.isDefinitionFrozen());
            assertEquals(8, slot.getGroupId());
        }
    }

    @Test void excessiveListenerBudgetDoesNotPartiallyFreezeAndCanBeRepaired() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
            LegacyBridge bridge = new LegacyBridge();
            bridge.addListener(ordered("bulk", new AtomicInteger(), new ArrayList<>()));
            for (int i = 0; i < 13; i++) bridge.appendTo(bridge);
            bridge.addListener(ordered("bulk", new AtomicInteger(), new ArrayList<>()));
            bridge.appendTo(slot);
            assertEquals(8193, slot.getListenerModules().size());
            problem("budget", slot::freezeDefinition);
            assertFalse(slot.isDefinitionFrozen());
            slot.removeListenerModule("bulk");
            slot.setMaxBuyCount(5);
            slot.freezeDefinition();
            assertTrue(slot.isDefinitionFrozen());
            assertEquals(5, slot.getMaxBuyCount());
        }
    }

    @Test void repeatedFreezeNeverRecapturesDriftOrExpandsTemplatePermissions() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            var initial = capture(owner, Items.STONE);
            var third = capture(owner, Items.DIRT);
            RuntimeShopSlot slot = slot(initial);
            slot.freezeDefinition();
            slot.freezeDefinition();
            slot.itemSupplier = third;
            problem("invalid_slot", slot::freezeDefinition);
            assertTrue(slot.isDefinitionFrozen());
            slot.itemSupplier = initial;
            problem("invalid_slot", () -> slot.setItemSupplier(third));
            slot.defaultCost = 101;
            problem("invalid_slot", slot::freezeDefinition);
            slot.defaultCost = 100;
            assertSame(initial, slot.currentTemplate());
            slot.freezeDefinition();
        }
    }

    @Test void priorityAndNameReentrantFreezeAreBusyAndMutationFailureRestoresScope() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
            AtomicBoolean reenter = new AtomicBoolean(true);
            AtomicInteger priorities = new AtomicInteger();
            AtomicInteger names = new AtomicInteger();
            ListenerModule module = new ListenerModule() {
                public int getPriority() {
                    priorities.incrementAndGet();
                    if (reenter.get()) problem("busy", slot::freezeDefinition);
                    return 1;
                }
                public String getName() {
                    names.incrementAndGet();
                    if (reenter.get()) problem("busy", slot::freezeDefinition);
                    return "module";
                }
            };
            slot.addListener(module);
            slot.addListener(module);
            assertTrue(priorities.get() > 0);
            slot.removeListenerModule("absent");
            slot.replaceListener(module);
            assertTrue(names.get() > 0);
            assertFalse(slot.isDefinitionFrozen());
            Error expected = new AssertionError("failed mutation");
            ListenerModule throwing = new ListenerModule() {
                public int getPriority() { throw expected; }
                public String getName() { return "throwing"; }
            };
            assertSame(expected, assertThrows(AssertionError.class, () -> slot.addListener(throwing)));
            reenter.set(false);
            slot.removeListenerModule("throwing");
            AtomicBoolean throwName = new AtomicBoolean(true);
            slot.addListener(new ListenerModule() {
                public int getPriority() { return 1; }
                public String getName() {
                    if (throwName.get()) {
                        problem("busy", slot::freezeDefinition);
                        throw expected;
                    }
                    return "name_failure";
                }
            });
            assertSame(expected, assertThrows(AssertionError.class, () -> slot.removeListenerModule("absent")));
            assertFalse(slot.isDefinitionFrozen());
            throwName.set(false);
            slot.removeListenerModule("name_failure");
            slot.freezeDefinition();
            assertTrue(slot.isDefinitionFrozen(), "failed mutation must leave no active mutation scope");
        }
    }

    @Test void protectedLegacyCopyBridgeCannotWriteFrozenTarget() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = slot(capture(owner, Items.STONE));
            ListenerModule original = ordered("original", new AtomicInteger(), new ArrayList<>());
            slot.addListener(original);
            slot.freezeDefinition();
            LegacyBridge bridge = new LegacyBridge();
            bridge.addListener(ordered("injected", new AtomicInteger(), new ArrayList<>()));
            problem("invalid_slot", () -> bridge.appendTo(slot));
            assertEquals(List.of(original), slot.getListenerModules());
            assertDoesNotThrow(slot::reset);
            assertEquals(1, bridge.copy().getListenerModules().size());
        }
    }

    @Test void ownerBusyClosedAndWrongThreadPrecedeFrozenDefinitionAndBadArguments() throws InterruptedException {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            RuntimeShopSlot slot = slot(template);
            slot.freezeDefinition();
            slot.defaultCost = 101;
            template.inspect(stack -> {
                problem("busy", slot::freezeDefinition);
                problem("busy", () -> slot.addListener(null));
                problem("busy", () -> slot.setItemSupplier(null));
                problem("busy", slot::getListenerNames);
                return null;
            });
            AtomicReference<Throwable> failure = new AtomicReference<>();
            runWorker(() -> {
                problem("wrong_thread", slot::freezeDefinition);
                problem("wrong_thread", () -> slot.replaceListener(null));
                problem("wrong_thread", () -> slot.buy(null, 0));
            }, failure);
            owner.close();
            problem("closed", slot::freezeDefinition);
            problem("closed", () -> slot.removeListenerModule(null));
            problem("closed", () -> slot.returnItem(null));
            assertTrue(slot.isDefinitionFrozen());
            runWorker(() -> {
                problem("wrong_thread", slot::freezeDefinition);
                problem("wrong_thread", () -> slot.setItemSupplier(null));
            }, failure);
        }
    }

    private static RuntimeShopSlot slot(RuntimeItemTemplates.Template template) {
        RuntimeShopSlot slot = new RuntimeShopSlot(template, 100, 3, 9);
        slot.setIndex(4);
        return slot;
    }

    private static RuntimeItemTemplates.Template capture(RuntimeItemTemplates owner, Item item) {
        ItemStack borrowed = new ItemStack(item);
        try { return owner.capture(borrowed, ignored -> { }, () -> false); }
        finally { borrowed.invalidateCaps(); }
    }

    private static ListenerModule ordered(String label, AtomicInteger priorities, List<String> calls) {
        return new ListenerModule() {
            public String getName() { return label; }
            public int getPriority() { priorities.incrementAndGet(); return 1; }
            public void onReset(ShopSlot slot) { calls.add(label); }
        };
    }

    private static ListenerModule callbacks(AtomicInteger calls, Runnable action) {
        return new ListenerModule() {
            private void call() { calls.incrementAndGet(); action.run(); }
            public String getName() { call(); return "ordinary"; }
            public int getPriority() { return 1; }
            public void onReset(ShopSlot slot) { call(); }
            public void onChange(ShopSlotChangeEvent event) { call(); }
            public void onCostCheck(CheckCostEvent event, ShopSlot slot) { call(); }
        };
    }

    private static void problem(String reason, Executable action) {
        RuntimeItemException failure = assertThrows(RuntimeItemException.class, action);
        assertEquals("editor:runtime_items/" + reason, failure.getMessage());
        assertEquals("runtime_items", failure.problem().target());
    }

    private static void runWorker(Runnable action, AtomicReference<Throwable> failure) throws InterruptedException {
        failure.set(null);
        Thread worker = new Thread(() -> {
            try { action.run(); } catch (Throwable thrown) { failure.set(thrown); }
        });
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

    private static final class LegacyBridge extends ShopSlot {
        LegacyBridge() { super(() -> ItemStack.EMPTY, 1, 1, -1, stack -> false); }
        void appendTo(ShopSlot target) { copyListenerReferencesTo(target); }
    }

    private static final class NativeProbe implements AutoCloseable {
        final AtomicInteger serializations = new AtomicInteger();
        final Consumer<AttachCapabilitiesEvent<ItemStack>> attachment = event -> {
            event.addCapability(new ResourceLocation("fpsmatch", "runtime_definition_test"), new ICapabilitySerializable<CompoundTag>() {
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) { return LazyOptional.empty(); }
                public CompoundTag serializeNBT() { serializations.incrementAndGet(); return new CompoundTag(); }
                public void deserializeNBT(CompoundTag tag) { }
            });
        };
        NativeProbe() { MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, attachment); }
        public void close() { MinecraftForge.EVENT_BUS.unregister(attachment); }
    }
}
