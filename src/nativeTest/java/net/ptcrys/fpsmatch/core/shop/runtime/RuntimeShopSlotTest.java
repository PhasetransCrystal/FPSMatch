package net.ptcrys.fpsmatch.core.shop.runtime;

import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.ptcrys.fpsmatch.common.shop.functional.ChangeShopItemModule;
import net.ptcrys.fpsmatch.core.shop.event.ShopSlotChangeEvent;
import net.ptcrys.fpsmatch.core.shop.functional.ListenerModule;
import net.ptcrys.fpsmatch.core.shop.slot.ShopSlot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Uses real Minecraft stacks and the Forge capability attachment/invalidation path. */
class RuntimeShopSlotTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftForge.EVENT_BUS.start();
    }

    @Test
    void thousandsOfPlayerCopiesAndResetsShareTemplatesWithoutNativeCopy() {
        try (NativeProbe probe = new NativeProbe(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            var normal = capture(owner, Items.STONE);
            var changed = capture(owner, Items.DIAMOND);
            RuntimeShopSlot root = new RuntimeShopSlot(normal, 100, 3, 9);
            root.setIndex(4);
            root.addListener(new RuntimeChangeItemModule(normal, 100, changed, 50, "editor_change_1"));
            owner.seal();
            int before = probe.serializations.get();
            for (int i = 0; i < 2000; i++) {
                RuntimeShopSlot player = root.copy();
                player.lock(2);
                player.setCost(123);
                player.reset();
                assertSame(normal, player.currentTemplate());
                assertEquals(100, player.getCost());
                assertEquals(0, player.getBoughtCount());
                assertFalse(player.isLocked());
                assertEquals(4, player.getIndex());
                assertEquals(3, player.getMaxBuyCount());
                assertEquals(9, player.getGroupId());
            }
            assertEquals(2, owner.size());
            assertEquals(before, probe.serializations.get(), "real ItemStack.copy serializes attached caps");
        }
    }

    @Test
    void processProducesCallerOwnedIndependentTaggedStacks() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            ItemStack borrowed = new ItemStack(Items.STONE, 3);
            borrowed.getOrCreateTag().putString("test", "original");
            try {
                var template = owner.capture(borrowed, item -> {}, () -> false);
                RuntimeShopSlot slot = new RuntimeShopSlot(template, 100, 1, -1);
                ItemStack first = slot.process();
                ItemStack second = slot.process();
                try {
                    assertNotSame(first, second);
                    assertNotSame(borrowed, first);
                    assertNotSame(first.getTag(), second.getTag());
                    first.getOrCreateTag().putString("test", "changed");
                    assertEquals("original", second.getTag().getString("test"));
                    assertEquals("original", borrowed.getTag().getString("test"));
                    owner.close();
                    assertEquals(Items.STONE, second.getItem());
                } finally {
                    first.invalidateCaps();
                    second.invalidateCaps();
                }
            } finally {
                borrowed.invalidateCaps();
            }
        }
    }

    @Test
    void changeAndResetKeepTwoPlayerSlotsIndependentAndCheckersFollowCurrentTemplate() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            var normal = capture(owner, Items.STONE);
            var changed = capture(owner, Items.DIAMOND);
            RuntimeShopSlot first = new RuntimeShopSlot(normal, 100, 1, 1);
            var change = new RuntimeChangeItemModule(normal, 100, changed, 25, "editor_change_uuid");
            first.addListener(change);
            RuntimeShopSlot second = first.copy();
            change.onChange(new ShopSlotChangeEvent(first, null, 80, 1));
            assertSame(changed, first.currentTemplate());
            assertSame(normal, second.currentTemplate());
            ItemStack diamond = new ItemStack(Items.DIAMOND);
            ItemStack stone = new ItemStack(Items.STONE);
            try {
                assertTrue(first.returningChecker.test(diamond));
                assertFalse(second.returningChecker.test(diamond));
                assertTrue(second.returningChecker.test(stone));
                first.reset();
                assertTrue(first.returningChecker.test(stone));
                assertSame(normal, first.currentTemplate());
                assertEquals(100, first.getCost());
            } finally {
                diamond.invalidateCaps();
                stone.invalidateCaps();
            }
        }
    }

    @Test
    void boughtCountCheckUsesIndependentTemporaryWithoutOwnerBusyReentry() {
        try (NativeProbe probe = new NativeProbe(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            var normal = capture(owner, Items.STONE);
            var changed = capture(owner, Items.DIAMOND);
            var change = new RuntimeChangeItemModule(normal, 100, changed, 25, "editor_change_uuid");
            RuntimeShopSlot slot = new RuntimeShopSlot(changed, 100, 2, 1);
            slot.lock(1);
            int before = probe.invalidated.size();
            assertDoesNotThrow(() -> change.onChange(new ShopSlotChangeEvent(slot, null, 80, 1)));
            assertEquals(25, slot.getCost());
            assertEquals(before + 2, probe.invalidated.size(), "checker observation and changed comparison both cleaned");
            slot.setItemSupplier(normal);
            slot.setCost(100);
            change.onChange(new ShopSlotChangeEvent(slot, null, 80, 1));
            assertSame(normal, slot.currentTemplate());
            assertEquals(100, slot.getCost(), "bought mismatching item retains old state");
        }
    }

    @Test
    void copyPreservesActualListenerOrderDuplicatesIdentityWithoutPriorityCalls() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeShopSlot slot = new RuntimeShopSlot(capture(owner, Items.STONE), 1, 1, -1);
            AtomicInteger priorities = new AtomicInteger();
            List<String> called = new ArrayList<>();
            ListenerModule first = listener("same", priorities, called, "first");
            ListenerModule second = listener("same", priorities, called, "second");
            slot.addListener(first);
            slot.addListener(second);
            slot.addListener(first);
            int before = priorities.get();
            RuntimeShopSlot copy = slot.copy();
            assertEquals(before, priorities.get());
            assertEquals(List.of(first, second, first), copy.getListenerModules());
            copy.reset();
            assertEquals(List.of("first", "second", "first"), called);
        }
    }

    @Test
    void setterAndPublicFieldTamperingRejectUnmanagedAndForeignBeforeSupplierExecution() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(2); RuntimeItemTemplates other = new RuntimeItemTemplates(1)) {
            var first = capture(owner, Items.STONE);
            var second = capture(owner, Items.DIAMOND);
            var foreign = capture(other, Items.STONE);
            RuntimeShopSlot slot = new RuntimeShopSlot(first, 1, 1, -1);
            AtomicInteger callbacks = new AtomicInteger();
            java.util.function.Supplier<ItemStack> unmanaged = () -> { callbacks.incrementAndGet(); return ItemStack.EMPTY; };
            assertThrows(RuntimeItemException.class, () -> slot.setItemSupplier(unmanaged));
            assertThrows(RuntimeItemException.class, () -> slot.setItemSupplier(foreign));
            assertSame(first, slot.currentTemplate());
            slot.setItemSupplier(second);
            assertSame(second, slot.currentTemplate());
            slot.itemSupplier = unmanaged;
            assertThrows(RuntimeItemException.class, slot::currentTemplate);
            assertThrows(RuntimeItemException.class, slot::process);
            assertThrows(RuntimeItemException.class, slot::copy);
            assertThrows(RuntimeItemException.class, slot::isEmptyItem);
            assertThrows(RuntimeItemException.class, slot::toString);
            assertEquals(0, callbacks.get());
            slot.itemSupplier = foreign;
            assertThrows(RuntimeItemException.class, slot::process);
        }
    }

    @Test
    void retiredSlotRejectsAllNativeEntryPointsAndWrongThreadIsRejected() throws InterruptedException {
        RuntimeItemTemplates owner = new RuntimeItemTemplates(1);
        RuntimeShopSlot slot = new RuntimeShopSlot(capture(owner, Items.STONE), 1, 1, -1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { slot.copy(); } catch (Throwable thrown) { failure.set(thrown); }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "wrong-thread copy must terminate within ten seconds");
        } finally {
            worker.interrupt();
            worker.join(1_000);
            assertFalse(worker.isAlive(), "worker must terminate after cleanup");
        }
        assertInstanceOf(RuntimeItemException.class, failure.get());
        owner.close();
        assertThrows(RuntimeItemException.class, slot::process);
        assertThrows(RuntimeItemException.class, slot::copy);
        assertThrows(RuntimeItemException.class, slot::isEmptyItem);
        assertThrows(RuntimeItemException.class, slot::getAmmoCount);
        assertThrows(RuntimeItemException.class, slot::getDummyAmmoCount);
        assertThrows(RuntimeItemException.class, slot::toString);
    }

    @Test
    void typedGuardRecognizesGeneratedNameAndOldRecordKeepsItsComponents() {
        assertTrue(ChangeShopItemModule.class.isRecord());
        assertEquals(List.of("defaultItem", "defaultCost", "changedItem", "changedCost", "moduleName"),
                java.util.Arrays.stream(ChangeShopItemModule.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList());
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            var change = new RuntimeChangeItemModule(template, 100, template, 50, "editor_generated_uuid");
            RuntimeShopSlot slot = new RuntimeShopSlot(template, 100, 1, 1);
            assertFalse(slot.requiresGroupReturn());
            slot.addListener(change);
            assertTrue(slot.requiresGroupReturn());
            assertEquals("editor_generated_uuid", change.getName());
            assertEquals(1, change.getPriority());
            ShopSlot legacy = new ShopSlot(() -> ItemStack.EMPTY, 1, 1, 1, item -> false);
            legacy.addListener(listener("changeItem_old", new AtomicInteger(), new ArrayList<>(), "old"));
            assertTrue(legacy.requiresGroupReturn());
            assertTrue(legacy.copy().requiresGroupReturn());
        }
    }

    @Test
    void observationsInvalidateTheirTemporaryStacksAndEmptyTemplatesStayPrivate() {
        try (NativeProbe probe = new NativeProbe(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            RuntimeShopSlot slot = new RuntimeShopSlot(capture(owner, Items.STONE), 100, 1, 1);
            int before = probe.invalidated.size();
            assertFalse(slot.isEmptyItem());
            slot.getAmmoCount();
            slot.getDummyAmmoCount();
            assertTrue(slot.toString().contains("defaultCost=100"));
            assertEquals(before + 4, probe.invalidated.size());
            Error expected = new AssertionError("observer failure");
            slot.addListener(new ListenerModule() {
                public String getName() { return "throwing_description"; }
                public int getPriority() { return 1; }
                public String toString() { throw expected; }
            });
            assertSame(expected, assertThrows(AssertionError.class, slot::toString));
            // Description callbacks now finish before inspect: failure creates no temporary stack.
            assertEquals(before + 4, probe.invalidated.size());
            var empty = owner.capture(ItemStack.EMPTY, ignored -> {}, () -> false);
            RuntimeShopSlot emptySlot = new RuntimeShopSlot(empty, 0, 1, -1);
            assertTrue(emptySlot.isEmptyItem());
            ItemStack delivery = emptySlot.process();
            try {
                assertNotSame(ItemStack.EMPTY, delivery);
                assertTrue(delivery.isEmpty());
            } finally {
                delivery.invalidateCaps();
            }
        }
    }

    @Test
    void oldRecordConstructorsAndLegacySupplierSlotStillOperate() {
        ChangeShopItemModule old = new ChangeShopItemModule(ItemStack.EMPTY, 100, ItemStack.EMPTY, 25, "explicit_old");
        ChangeShopItemModule oldUnnamed = new ChangeShopItemModule(ItemStack.EMPTY, 100, ItemStack.EMPTY, 25);
        assertSame(ItemStack.EMPTY, old.defaultItem());
        assertSame(ItemStack.EMPTY, oldUnnamed.changedItem());
        ShopSlot legacy = new ShopSlot(() -> ItemStack.EMPTY, 100, 1, 1, item -> false);
        legacy.addListener(old);
        old.onChange(new ShopSlotChangeEvent(legacy, null, 800, 1));
        assertEquals(25, legacy.getCost());
        assertTrue(legacy.isEmptyItem());
        legacy.reset();
        assertEquals(100, legacy.getCost());
        assertTrue(legacy.requiresGroupReturn());
        assertSame(old, legacy.copy().getListenerModules().get(0));
    }

    @Test
    void changeRejectsLegacyForeignAndTamperedSlotsBeforeAnyMutation() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1); RuntimeItemTemplates foreign = new RuntimeItemTemplates(1)) {
            var template = capture(owner, Items.STONE);
            var change = new RuntimeChangeItemModule(template, 100, template, 50, "private");
            RuntimeShopSlot slot = new RuntimeShopSlot(capture(foreign, Items.DIAMOND), 90, 1, 1);
            ShopSlotChangeEvent event = new ShopSlotChangeEvent(slot, null, 80, -1);
            assertThrows(RuntimeItemException.class, () -> change.onChange(event));
            assertEquals(80, event.getMoney());
            assertEquals(90, slot.getCost());
            ShopSlot legacy = new ShopSlot(() -> ItemStack.EMPTY, 90, 1, 1, item -> false);
            assertThrows(RuntimeItemException.class, () -> change.onReset(legacy));
            assertEquals(90, legacy.getCost());
            assertThrows(RuntimeItemException.class, () -> new RuntimeChangeItemModule(template, 1, slot.currentTemplate(), 2, "foreign"));
            assertThrows(RuntimeItemException.class, () -> new RuntimeChangeItemModule(template, 1, template, 2, ""));
        }
    }

    private static RuntimeItemTemplates.Template capture(RuntimeItemTemplates owner, net.minecraft.world.item.Item item) {
        ItemStack borrowed = new ItemStack(item);
        try { return owner.capture(borrowed, ignored -> {}, () -> false); }
        finally { borrowed.invalidateCaps(); }
    }

    private static ListenerModule listener(String name, AtomicInteger priorities, List<String> called, String label) {
        return new ListenerModule() {
            public String getName() { return name; }
            public int getPriority() { priorities.incrementAndGet(); return 1; }
            public void onReset(ShopSlot slot) { called.add(label); }
        };
    }

    private static final class NativeProbe implements AutoCloseable {
        final AtomicInteger serializations = new AtomicInteger();
        final List<ItemStack> invalidated = new ArrayList<>();
        final Consumer<AttachCapabilitiesEvent<ItemStack>> attachment = event -> {
            event.addCapability(new ResourceLocation("fpsmatch", "runtime_shop_test"), new ICapabilitySerializable<CompoundTag>() {
                public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) { return LazyOptional.empty(); }
                public CompoundTag serializeNBT() { serializations.incrementAndGet(); return new CompoundTag(); }
                public void deserializeNBT(CompoundTag tag) {}
            });
            event.addListener(() -> invalidated.add(event.getObject()));
        };
        NativeProbe() { MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, attachment); }
        public void close() { MinecraftForge.EVENT_BUS.unregister(attachment); }
    }
}
