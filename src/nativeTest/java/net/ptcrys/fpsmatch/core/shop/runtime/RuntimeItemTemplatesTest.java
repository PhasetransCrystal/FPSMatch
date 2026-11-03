package net.ptcrys.fpsmatch.core.shop.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real Minecraft stacks and Forge capability lifecycle; bootstrap failures must remain failures. */
class RuntimeItemTemplatesTest {
    @BeforeAll static void bootstrapNativeRuntime() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        MinecraftForge.EVENT_BUS.start();
    }

    @Test void taggedCaptureInitializesPrivateCopyAndDeliveryIsIndependent() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            ItemStack borrowed = stone();
            borrowed.getOrCreateTag().putInt("source", 7);
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            RuntimeItemTemplates.Template template = owner.capture(borrowed, stack -> {
                raw.set(stack);
                stack.getOrCreateTag().putInt("initialized", 9);
            }, () -> false);
            assertEquals(1, owner.size());
            assertFalse(borrowed.getTag().contains("initialized"));
            ItemStack first = template.get();
            ItemStack second = template.get();
            assertNotSame(borrowed, raw.get());
            assertNotSame(raw.get(), first);
            assertNotSame(first, second);
            assertNotSame(raw.get().getTag(), first.getTag());
            first.getTag().putInt("initialized", -1);
            assertEquals(9, second.getTag().getInt("initialized"));
            owner.close();
            assertEquals(List.of(raw.get()), fixture.invalidated);
            assertEquals(9, second.getTag().getInt("initialized"));
            assertFalse(fixture.invalidated.contains(borrowed));
            first.invalidateCaps();
            second.invalidateCaps();
        }
    }

    @Test void emptyCaptureAndDeliveryNeverOwnGlobalEmpty() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            RuntimeItemTemplates.Template template = owner.capture(ItemStack.EMPTY, raw::set, () -> false);
            ItemStack delivery = template.get();
            assertTrue(raw.get().isEmpty());
            assertNotSame(ItemStack.EMPTY, raw.get());
            assertNotSame(ItemStack.EMPTY, delivery);
            assertNotSame(raw.get(), delivery);
            owner.close();
            assertFalse(fixture.invalidated.contains(ItemStack.EMPTY));
            delivery.invalidateCaps();
        }
    }

    @Test void initializerFailurePreservesIdentityAndCleansOnlyReturnedPrivateStack() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            ItemStack borrowed = stone();
            RuntimeException original = new IllegalArgumentException("initializer");
            RuntimeException cleanup = new IllegalStateException("initializer cleanup");
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            assertSame(original, assertThrows(RuntimeException.class, () -> owner.capture(borrowed, stack -> {
                raw.set(stack);
                fixture.cleanupFailures.put(stack, cleanup);
                throw original;
            }, () -> false)));
            assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
            assertEquals(List.of(raw.get()), fixture.invalidated);
            assertEquals(0, owner.size());
            assertFalse(fixture.invalidated.contains(borrowed));
        }
    }

    @Test void noncanonicalEmptyIsRejectedAndItsBorrowedTagIsPreserved() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            ItemStack borrowed = new ItemStack(Items.AIR, 0);
            borrowed.getOrCreateTag().putString("empty_payload", "preserve");
            problem("invalid_copy", () -> owner.capture(borrowed, ignored -> fail(), () -> false));
            assertEquals("preserve", borrowed.getTag().getString("empty_payload"));
            assertEquals(0, owner.size());
            assertFalse(fixture.invalidated.contains(borrowed));
        }
    }

    @Test void initializerFailureSuppressesCleanupFailureAndOwnerRemainsUsable() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            Error original = new AssertionError("initializer");
            RuntimeException cleanup = new IllegalStateException("invalidation");
            assertSame(original, assertThrows(Error.class, () -> owner.capture(stone(), stack -> {
                fixture.cleanupFailures.put(stack, cleanup);
                throw original;
            }, () -> false)));
            assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
            assertEquals(0, owner.size());
            owner.capture(stone(), ignored -> { }, () -> false);
            assertEquals(1, owner.size());
        }
    }

    @Test void initializerCannotPublishTaggedEmptyTemplateThatCannotBeDelivered() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            ItemStack borrowed = stone();
            borrowed.getOrCreateTag().putString("preserve", "source");
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            problem("invalid_copy", () -> owner.capture(borrowed, stack -> {
                raw.set(stack);
                stack.setCount(0);
            }, () -> false));
            assertEquals(0, owner.size());
            assertEquals(2, borrowed.getCount());
            assertEquals("source", borrowed.getTag().getString("preserve"));
            assertEquals(List.of(raw.get()), fixture.invalidated);
            assertFalse(fixture.invalidated.contains(borrowed));
        }
    }

    @Test void cancellationAfterCopyAndAfterInitializationCleansWithoutPublishing() {
        for (int cancelAt : List.of(2, 3)) {
            try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
                ItemStack borrowed = stone();
                AtomicInteger gates = new AtomicInteger();
                AtomicInteger initialized = new AtomicInteger();
                problem("cancelled", () -> owner.capture(borrowed, ignored -> initialized.incrementAndGet(),
                        () -> gates.incrementAndGet() == cancelAt));
                assertEquals(cancelAt == 3 ? 1 : 0, initialized.get());
                assertEquals(1, fixture.invalidated.size());
                assertNotSame(borrowed, fixture.invalidated.get(0));
                assertEquals(0, owner.size());
            }
        }
    }

    @Test void alreadyCancelledRequestDoesNotSerializeOrInitializeNativeInput() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            ItemStack borrowed = stone();
            AtomicInteger serializations = new AtomicInteger();
            fixture.onSerialize = ignored -> serializations.incrementAndGet();
            problem("cancelled", () -> owner.capture(borrowed, ignored -> fail(), () -> true));
            assertEquals(0, serializations.get());
            assertEquals(0, owner.size());
            assertTrue(fixture.invalidated.isEmpty());
        }
    }

    @Test void budgetSealAndInvalidParametersRejectBeforeCallbacksOrInputAccess() {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            AtomicInteger callbacks = new AtomicInteger();
            owner.capture(stone(), ignored -> { }, () -> false);
            problem("budget", () -> owner.capture(null, ignored -> callbacks.incrementAndGet(),
                    () -> { callbacks.incrementAndGet(); return false; }));
            owner.seal();
            owner.seal();
            problem("sealed", () -> owner.capture(null, null, null));
            assertEquals(0, callbacks.get());
        }
        problem("invalid_input", () -> new RuntimeItemTemplates(0));
        problem("invalid_input", () -> new RuntimeItemTemplates(8193));
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            problem("invalid_input", () -> owner.capture(null, ignored -> fail(), () -> false));
        }
    }

    @Test void finalCancellationCallbackCannotMutateInitializedCopyOrBorrowedSource() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            ItemStack borrowed = stone();
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            AtomicInteger gates = new AtomicInteger();
            problem("invalid_copy", () -> owner.capture(borrowed, raw::set, () -> {
                if (gates.incrementAndGet() == 3) raw.get().setCount(4);
                return false;
            }));
            assertEquals(List.of(raw.get()), fixture.invalidated);
            assertEquals(0, owner.size());
            AtomicInteger sourceGates = new AtomicInteger();
            problem("invalid_copy", () -> owner.capture(borrowed, ignored -> { }, () -> {
                if (sourceGates.incrementAndGet() == 2) borrowed.setCount(3);
                return false;
            }));
            assertFalse(fixture.invalidated.contains(borrowed));
        }
    }

    @Test void reentrantNativeOperationsRejectAndWrongThreadTakesPrecedence() throws InterruptedException {
        try (RuntimeItemTemplates owner = new RuntimeItemTemplates(3)) {
            RuntimeItemTemplates.Template first = owner.capture(stone(), ignored -> { }, () -> false);
            owner.capture(stone(), ignored -> {
                problem("busy", first::get);
                problem("busy", () -> first.inspect(ItemStack::getCount));
                problem("busy", first::requireAvailable);
                problem("busy", owner::seal);
                problem("busy", owner::close);
                problem("busy", () -> owner.capture(null, null, null));
            }, () -> false);
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { first.get(); } catch (Throwable failure) { workerFailure.set(failure); }
            });
            runWorker(worker);
            assertEquals("editor:runtime_items/wrong_thread", workerFailure.get().getMessage());
            owner.close();
            Thread retiredWorker = new Thread(() -> {
                try { owner.close(); } catch (Throwable failure) { workerFailure.set(failure); }
            });
            runWorker(retiredWorker);
            assertEquals("editor:runtime_items/wrong_thread", workerFailure.get().getMessage());
        }
    }

    @Test void inspectAlwaysInvalidatesAndDoesNotExposeRawTemplate() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            AtomicReference<ItemStack> raw = new AtomicReference<>();
            RuntimeItemTemplates.Template template = owner.capture(stone(), raw::set, () -> false);
            AtomicReference<ItemStack> observed = new AtomicReference<>();
            int observedCount = template.inspect(stack -> { observed.set(stack); return stack.getCount(); });
            assertEquals(2, observedCount);
            assertNotSame(raw.get(), observed.get());
            assertEquals(List.of(observed.get()), fixture.invalidated);
            Error original = new AssertionError("observer");
            RuntimeException cleanup = new IllegalStateException("observer cleanup");
            assertSame(original, assertThrows(Error.class, () -> template.inspect(stack -> {
                fixture.cleanupFailures.put(stack, cleanup);
                throw original;
            })));
            assertArrayEquals(new Throwable[] {cleanup}, original.getSuppressed());
            assertEquals(2, fixture.invalidated.size());
            assertEquals(1, owner.size());
        }
    }

    @Test void closeClearsReferencesBeforeReverseCleanupContinuesAndNeverRetries() throws Exception {
        try (NativeFixture fixture = new NativeFixture()) {
            RuntimeItemTemplates owner = new RuntimeItemTemplates(3);
            List<RuntimeItemTemplates.Template> templates = new ArrayList<>();
            List<ItemStack> raws = new ArrayList<>();
            for (int i = 0; i < 3; i++) templates.add(owner.capture(stone(), raws::add, () -> false));
            RuntimeException first = new IllegalStateException("first cleanup");
            Error second = new AssertionError("second cleanup");
            fixture.cleanupFailures.put(raws.get(2), first);
            fixture.cleanupFailures.put(raws.get(1), second);
            fixture.beforeInvalidation = () -> {
                assertEquals(0, owner.size());
                for (RuntimeItemTemplates.Template template : templates) {
                    assertNull(rawOf(template));
                    problem("closed", template::get);
                }
            };
            assertSame(first, assertThrows(RuntimeException.class, owner::close));
            assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
            assertEquals(List.of(raws.get(2), raws.get(1), raws.get(0)), fixture.invalidated);
            owner.close();
            assertEquals(3, fixture.invalidated.size());
            problem("closed", owner::seal);
            problem("closed", () -> owner.capture(null, null, null));
            problem("closed", templates.get(0)::requireAvailable);
            problem("closed", () -> templates.get(0).inspect(ItemStack::getCount));
        }
    }

    @Test void repeatedSameCleanupExceptionAvoidsSelfSuppression() {
        try (NativeFixture fixture = new NativeFixture()) {
            RuntimeItemTemplates owner = new RuntimeItemTemplates(2);
            RuntimeException original = new IllegalStateException("shared failure");
            for (int i = 0; i < 2; i++) owner.capture(stone(), stack -> fixture.cleanupFailures.put(stack, original), () -> false);
            assertSame(original, assertThrows(RuntimeException.class, owner::close));
            assertEquals(0, original.getSuppressed().length);
            assertEquals(2, fixture.invalidated.size());
        }
    }

    @Test void actualForgeSerializationFailureEscapesWithoutInvalidatingBorrowedStack() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            ItemStack borrowed = stone();
            borrowed.getCapability(ForgeCapabilities.ITEM_HANDLER);
            RuntimeException original = new IllegalArgumentException("copy serializer");
            AtomicInteger serializations = new AtomicInteger();
            fixture.onSerialize = stack -> {
                // Source save, source verification, then the actual ItemStack.copy serialization.
                if (stack == borrowed && serializations.incrementAndGet() == 3) throw original;
            };
            assertSame(original, assertThrows(RuntimeException.class,
                    () -> owner.capture(borrowed, ignored -> fail(), () -> false)));
            assertEquals(0, owner.size());
            assertFalse(fixture.invalidated.contains(borrowed));
        }
    }

    @Test void deliveryCopyFailurePreservesOriginalAndLeavesTemplateAvailable() {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(1)) {
            RuntimeItemTemplates.Template template = owner.capture(stone(), ignored -> { }, () -> false);
            ItemStack raw = rawOf(template);
            raw.getCapability(ForgeCapabilities.ITEM_HANDLER);
            Error original = new AssertionError("delivery copy serializer");
            fixture.onSerialize = stack -> { if (stack == raw) throw original; };
            assertSame(original, assertThrows(Error.class, template::get));
            assertTrue(fixture.invalidated.isEmpty());
            assertEquals(1, owner.size());
            fixture.onSerialize = ignored -> { };
            ItemStack delivery = template.get();
            assertNotSame(raw, delivery);
            delivery.invalidateCaps();
        }
    }

    @Test void independentIdentityGuardRejectsNullBorrowedEmptyAndAlreadyOwnedStack() throws Exception {
        try (NativeFixture fixture = new NativeFixture(); RuntimeItemTemplates owner = new RuntimeItemTemplates(2)) {
            ItemStack borrowed = stone();
            RuntimeItemTemplates.Template first = owner.capture(borrowed, ignored -> { }, () -> false);
            RuntimeItemTemplates.Template second = owner.capture(stone(), ignored -> { }, () -> false);
            Method guard = RuntimeItemTemplates.class.getDeclaredMethod("isIndependentIdentity", ItemStack.class, ItemStack.class);
            guard.setAccessible(true);
            // ItemStack is final: exercise the real bounded identity guard without a replacement stack class.
            for (ItemStack candidate : new ItemStack[] {null, borrowed, ItemStack.EMPTY, rawOf(first), rawOf(second)}) {
                assertEquals(false, guard.invoke(owner, borrowed, candidate));
            }
            assertTrue(fixture.invalidated.isEmpty());
            assertTrue(first.hasSameOwner(second));
            try (RuntimeItemTemplates foreign = new RuntimeItemTemplates(1)) {
                assertFalse(first.hasSameOwner(foreign.capture(stone(), ignored -> { }, () -> false)));
            }
        }
    }

    private static ItemStack rawOf(RuntimeItemTemplates.Template template) {
        try {
            Field entry = RuntimeItemTemplates.Template.class.getDeclaredField("entry");
            entry.setAccessible(true);
            Object value = entry.get(template);
            Field raw = value.getClass().getDeclaredField("raw");
            raw.setAccessible(true);
            return (ItemStack) raw.get(value);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static ItemStack stone() { return new ItemStack(Items.STONE, 2); }

    private static void runWorker(Thread worker) throws InterruptedException {
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(10_000);
            assertFalse(worker.isAlive(), "worker must finish within ten seconds");
        } finally {
            worker.interrupt();
            worker.join(1_000);
            assertFalse(worker.isAlive(), "worker must terminate after interruption");
        }
    }

    private static void problem(String reason, org.junit.jupiter.api.function.Executable action) {
        RuntimeItemException failure = assertThrows(RuntimeItemException.class, action);
        assertEquals("editor:runtime_items/" + reason, failure.getMessage());
        assertEquals("runtime_items", failure.problem().target());
    }

    private static final class NativeFixture implements AutoCloseable {
        final List<ItemStack> invalidated = new ArrayList<>();
        final Map<ItemStack, Throwable> cleanupFailures = new IdentityHashMap<>();
        Runnable beforeInvalidation = () -> { };
        Consumer<ItemStack> onSerialize = ignored -> { };
        final Consumer<AttachCapabilitiesEvent<ItemStack>> attachment = event -> {
            ItemStack stack = event.getObject();
            event.addCapability(new ResourceLocation("fpsmatch", "runtime_templates_test"), new ICapabilitySerializable<CompoundTag>() {
                @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return LazyOptional.empty();
                }
                @Override public CompoundTag serializeNBT() {
                    onSerialize.accept(stack);
                    return new CompoundTag();
                }
                @Override public void deserializeNBT(CompoundTag tag) { }
            });
            event.addListener(() -> {
                beforeInvalidation.run();
                invalidated.add(stack);
                Throwable failure = cleanupFailures.get(stack);
                if (failure instanceof RuntimeException thrown) throw thrown;
                if (failure instanceof Error thrown) throw thrown;
            });
        };

        NativeFixture() { MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, attachment); }
        @Override public void close() { MinecraftForge.EVENT_BUS.unregister(attachment); }
    }
}
