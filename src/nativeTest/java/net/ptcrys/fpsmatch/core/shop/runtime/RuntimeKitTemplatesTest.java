package net.ptcrys.fpsmatch.core.shop.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.ptcrys.fpsmatch.common.capability.team.StartKitsCapability;
import net.ptcrys.fpsmatch.core.persistence.RuntimeItemStackCodec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real Forge invalidation and Minecraft codec; no simulated stacks or player substitutes. */
class RuntimeKitTemplatesTest {
    @BeforeAll static void bootstrapNativeRuntime() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftForge.EVENT_BUS.start();
    }

    @Test void orderedEmptyDuplicateHandlesAndCompleteCodecRemainFixed() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(3)) {
            ItemStack source = new ItemStack(Items.STONE, 3);
            source.getOrCreateTag().putString("kit_payload", "A");
            var a = owner.capture(source, ignored -> { }, () -> false);
            var empty = owner.capture(ItemStack.EMPTY, ignored -> { }, () -> false);
            var b = owner.capture(new ItemStack(Items.DIRT, 2), ignored -> { }, () -> false);
            ArrayList<RuntimeItemTemplates.Template> input = new ArrayList<>(List.of(a, empty, b, empty, a));
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, input);
            input.clear();
            assertEquals(List.of(a, empty, b, empty, a), kits.templates());
            assertThrows(UnsupportedOperationException.class, () -> kits.templates().clear());
            assertFalse(kits.isEmpty(0));
            assertTrue(kits.isEmpty(1));
            assertFalse(kits.isEmpty(2));
            assertTrue(kits.isEmpty(3));
            JsonArray encoded = kits.toJson();
            assertEquals(5, encoded.size());
            for (int i = 0; i < encoded.size(); i++) {
                assertEquals(1, encoded.get(i).getAsJsonObject().get("fpsmatch:item_format").getAsInt());
                ItemStack decoded = RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, encoded.get(i))
                        .getOrThrow(false, message -> fail(message));
                try {
                    assertEquals(i == 1 || i == 3, decoded.isEmpty());
                    if (i == 0 || i == 4) {
                        assertEquals(Items.STONE, decoded.getItem());
                        assertEquals(3, decoded.getCount());
                        assertEquals("A", decoded.getTag().getString("kit_payload"));
                    } else if (i == 2) {
                        assertEquals(Items.DIRT, decoded.getItem());
                        assertEquals(2, decoded.getCount());
                    }
                } finally {
                    if (decoded != ItemStack.EMPTY) decoded.invalidateCaps();
                }
            }
            JsonArray original = encoded.deepCopy();
            encoded.get(0).getAsJsonObject().getAsJsonArray("nbt").set(0, new JsonPrimitive("tampered"));
            assertNotEquals(original, encoded);
            assertEquals(original.get(4), encoded.get(4));
            assertEquals(original, kits.toJson());

            // Legacy compatibility is a separate input path, not the format emitted by kits.
            var legacy = ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, source)
                    .getOrThrow(false, message -> fail(message));
            assertFalse(legacy.getAsJsonObject().has("fpsmatch:item_format"));
            ItemStack legacyDecoded = RuntimeItemStackCodec.CODEC.parse(JsonOps.INSTANCE, legacy)
                    .getOrThrow(false, message -> fail(message));
            try {
                assertNotSame(source, legacyDecoded);
                assertEquals(Items.STONE, legacyDecoded.getItem());
                assertEquals(3, legacyDecoded.getCount());
                assertEquals(source.getTag(), legacyDecoded.getTag());
            } finally {
                if (legacyDecoded != ItemStack.EMPTY) legacyDecoded.invalidateCaps();
            }
            assertEquals(3, owner.size());
            assertFalse(fixture.invalidated.contains(source));
            assertFalse(fixture.invalidated.contains(ItemStack.EMPTY));
        }
    }

    @Test void observationCopiesAreInvalidatedAndNamesAreDetached() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            var template = owner.capture(new ItemStack(Items.STONE), stack -> {
                stack.setHoverName(Component.literal("kit name"));
                raw.set(stack);
            }, () -> false);
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of(template, template));
            String originalName = raw.get().getDisplayName().getString();
            List<Component> names = kits.displayNames();
            assertEquals(2, fixture.invalidated.size());
            assertThrows(UnsupportedOperationException.class, () -> names.clear());
            names.get(0).getSiblings().add(Component.literal(" changed"));
            assertEquals(originalName, kits.displayNames().get(0).getString());
            assertEquals(4, fixture.invalidated.size());
            kits.toJson();
            assertEquals(6, fixture.invalidated.size());
            kits.isEmpty(0);
            assertEquals(7, fixture.invalidated.size());
            assertFalse(fixture.invalidated.contains(raw.get()));
            assertEquals(1, owner.size());
        }
    }

    @Test void observationCleanupFailurePropagatesAndOwnerRemainsAvailable() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = owner.capture(new ItemStack(Items.STONE), ignored -> { }, () -> false);
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of(template));
            RuntimeException failure = new IllegalStateException("scoped cleanup");
            fixture.onInvalidate = ignored -> { throw failure; };
            assertSame(failure, assertThrows(RuntimeException.class, kits::toJson));
            assertEquals(1, fixture.invalidated.size());
            fixture.onInvalidate = ignored -> { };
            assertEquals(1, kits.toJson().size());
            assertEquals(1, owner.size());
        }
    }

    @Test void deliveriesAreIndependentAndNeverRetainedByOwner() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = owner.capture(new ItemStack(Items.STONE, 2), stack -> stack.getOrCreateTag().putInt("value", 7), () -> false);
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of(template, template));
            ItemStack first = kits.createDelivery(0);
            ItemStack second = kits.createDelivery(1);
            try {
                assertNotSame(first, second);
                assertNotSame(first.getTag(), second.getTag());
                first.getTag().putInt("value", -1);
                assertEquals(7, second.getTag().getInt("value"));
                for (int i = 0; i < 100; i++) kits.createDelivery(0).invalidateCaps();
                assertEquals(1, owner.size());
                owner.close();
                assertFalse(fixture.invalidated.contains(first));
                assertFalse(fixture.invalidated.contains(second));
                assertEquals(7, second.getTag().getInt("value"));
            } finally {
                first.invalidateCaps();
                second.invalidateCaps();
            }
        }
    }

    @Test void invalidInputsForeignOwnerBudgetAndLyingListAreRejected() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1); RuntimeItemTemplates foreign = new RuntimeItemTemplates(1)) {
            var template = owner.capture(new ItemStack(Items.STONE), ignored -> { }, () -> false);
            var other = foreign.capture(new ItemStack(Items.DIRT), ignored -> { }, () -> false);
            assertTrue(owner.owns(template));
            assertFalse(owner.owns(other));
            assertFalse(owner.owns(null));
            problem("invalid_input", () -> new RuntimeKitTemplates(null, List.of()));
            problem("invalid_input", () -> new RuntimeKitTemplates(owner, null));
            problem("invalid_input", () -> new RuntimeKitTemplates(owner, Arrays.asList(template, null)));
            problem("foreign_template", () -> new RuntimeKitTemplates(owner, List.of(template, other)));
            problem("budget", () -> new RuntimeKitTemplates(owner, Collections.nCopies(8193, template)));
            AtomicInteger reads = new AtomicInteger();
            List<RuntimeItemTemplates.Template> lying = new AbstractList<>() {
                @Override public int size() { return 0; }
                @Override public RuntimeItemTemplates.Template get(int index) { throw new AssertionError("iterator only"); }
                @Override public Iterator<RuntimeItemTemplates.Template> iterator() {
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return true; }
                        @Override public RuntimeItemTemplates.Template next() { reads.incrementAndGet(); return template; }
                    };
                }
            };
            problem("budget", () -> new RuntimeKitTemplates(owner, lying));
            assertEquals(8193, reads.get());
            assertEquals(8192, new RuntimeKitTemplates(owner, Collections.nCopies(8192, template)).templates().size());
            assertEquals(1, owner.size());
        }
    }

    @Test void emptyCollectionAndAllObservationsRejectAfterClose() {
        RuntimeItemTemplates owner = new RuntimeItemTemplates(1);
        RuntimeKitTemplates empty = new RuntimeKitTemplates(owner, List.of());
        assertTrue(empty.templates().isEmpty());
        assertTrue(empty.displayNames().isEmpty());
        assertEquals(0, empty.toJson().size());
        problem("invalid_slot", () -> empty.isEmpty(0));
        problem("invalid_slot", () -> empty.createDelivery(-1));
        RuntimeKitTemplates populated = new RuntimeKitTemplates(owner,
                List.of(owner.capture(new ItemStack(Items.STONE), ignored -> { }, () -> false)));
        owner.close();
        problem("closed", owner::requireAvailable);
        problem("closed", () -> owner.owns(null));
        problem("closed", empty::requireAvailable);
        problem("closed", empty::templates);
        problem("closed", empty::displayNames);
        problem("closed", empty::toJson);
        problem("closed", () -> empty.isEmpty(0));
        problem("closed", () -> empty.createDelivery(0));
        problem("closed", () -> new RuntimeKitTemplates(owner, List.of()));
        problem("closed", populated::templates);
        problem("closed", populated::displayNames);
        problem("closed", populated::toJson);
        problem("closed", () -> populated.isEmpty(0));
        problem("closed", () -> populated.createDelivery(0));
    }

    @Test void ownerQueriesAndKitObservationsKeepBusyAndWrongThreadGates() throws InterruptedException {
        RuntimeItemTemplates owner = new RuntimeItemTemplates(1);
        var template = owner.capture(new ItemStack(Items.STONE), ignored -> { }, () -> false);
        RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of(template));
        template.inspect(stack -> {
            problem("busy", owner::requireAvailable);
            problem("busy", () -> owner.owns(template));
            problem("busy", kits::templates);
            return null;
        });
        owner.close();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                problem("wrong_thread", owner::requireAvailable);
                problem("wrong_thread", () -> owner.owns(null));
                problem("wrong_thread", kits::templates);
                problem("wrong_thread", kits::toJson);
            } catch (Throwable thrown) { failure.set(thrown); }
        });
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(10_000);
            assertFalse(worker.isAlive());
        } finally {
            worker.interrupt();
            worker.join(1_000);
            assertFalse(worker.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    @Test void managedCapabilityRejectsAllLegacyEntrypointsBeforeInputCallbacks() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            var template = owner.capture(new ItemStack(Items.STONE), ignored -> { }, () -> false);
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of(template));
            StartKitsCapability capability = new StartKitsCapability(null);
            capability.useRuntimeKits(kits);
            assertSame(kits, capability.runtimeKits().orElseThrow());
            problem("invalid_slot", () -> capability.useRuntimeKits(kits));
            problem("invalid_slot", capability::getTeamKits);
            problem("invalid_slot", capability::read);
            problem("invalid_slot", () -> capability.addKit(null));
            problem("invalid_slot", () -> capability.addKits(List.of()));
            problem("invalid_slot", () -> capability.addKits(null));
            problem("invalid_slot", () -> capability.setTeamKits(null));
            problem("invalid_slot", capability::clearTeamKits);
            problem("invalid_slot", () -> capability.removeItem(null));
            problem("invalid_slot", () -> capability.write(List.of()));
            problem("invalid_slot", () -> capability.write(null));
            problem("invalid_slot", () -> capability.toJson(List.of()));
            problem("invalid_slot", () -> capability.toJson(null));
            problem("invalid_slot", () -> capability.decode(null));
            problem("invalid_slot", () -> capability.decode(new JsonPrimitive("")));
            problem("invalid_slot", () -> capability.decode(new JsonArray()));
            assertEquals(kits.toJson(), capability.toJson());
            assertEquals(1, owner.size());
            owner.close();
            problem("closed", capability::runtimeKits);
            problem("closed", capability::toJson);
        }
    }

    @Test void capabilityAdoptionRejectsLegacyAndClosedInputsAndDestroyDoesNotCloseSharedOwner() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeKitTemplates kits = new RuntimeKitTemplates(owner, List.of());
            StartKitsCapability legacy = new StartKitsCapability(null);
            legacy.addKit(new ItemStack(Items.STONE));
            problem("invalid_slot", () -> legacy.useRuntimeKits(kits));
            StartKitsCapability capability = new StartKitsCapability(null);
            problem("invalid_slot", () -> capability.useRuntimeKits(null));
            capability.useRuntimeKits(kits);
            capability.destroy();
            capability.destroy();
            problem("closed", capability::runtimeKits);
            owner.requireAvailable();
            problem("closed", () -> capability.useRuntimeKits(kits));
            problem("closed", () -> capability.givePlayerKits(null));
            problem("closed", capability::giveAllPlayersKits);
            problem("invalid_slot", () -> capability.addKits(List.of()));
            problem("invalid_slot", capability::read);
            problem("invalid_slot", capability::getTeamKits);
            problem("invalid_slot", () -> capability.write(List.of()));
            problem("invalid_slot", () -> capability.decode(null));
            problem("invalid_slot", capability::toJson);
            problem("invalid_slot", () -> capability.toJson(List.of()));
            StartKitsCapability terminatedLegacy = new StartKitsCapability(null);
            terminatedLegacy.destroy();
            problem("closed", () -> terminatedLegacy.useRuntimeKits(kits));
            terminatedLegacy.addKit(new ItemStack(Items.STONE));
            assertEquals(1, terminatedLegacy.getTeamKits().size());
            owner.close();
            StartKitsCapability fresh = new StartKitsCapability(null);
            problem("closed", () -> fresh.useRuntimeKits(kits));
            assertTrue(fresh.runtimeKits().isEmpty());
        }
    }

    private static void problem(String reason, org.junit.jupiter.api.function.Executable action) {
        RuntimeItemException failure = assertThrows(RuntimeItemException.class, action);
        assertEquals("editor:runtime_items/" + reason, failure.getMessage());
        assertEquals("runtime_items", failure.problem().target());
    }

    private static final class NativeFixture implements AutoCloseable {
        final List<ItemStack> invalidated = new ArrayList<>();
        Consumer<ItemStack> onInvalidate = ignored -> { };
        final Consumer<AttachCapabilitiesEvent<ItemStack>> attachment = event -> {
            ItemStack stack = event.getObject();
            event.addCapability(new ResourceLocation("fpsmatch", "runtime_kits_test"), new ICapabilitySerializable<CompoundTag>() {
                @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return LazyOptional.empty();
                }
                @Override public CompoundTag serializeNBT() { return new CompoundTag(); }
                @Override public void deserializeNBT(CompoundTag tag) { }
            });
            event.addListener(() -> {
                invalidated.add(stack);
                onInvalidate.accept(stack);
            });
        };

        NativeFixture() { MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, attachment); }
        @Override public void close() { MinecraftForge.EVENT_BUS.unregister(attachment); }
    }
}
