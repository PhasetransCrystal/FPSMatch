package net.ptcrys.fpsmatch.core.capability;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.ModLauncherFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Constructor;
import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class CapabilityMapCloseTest {
    private static ClassLoader originalContextLoader;
    private static boolean eventEnvironmentInstalled;

    @BeforeAll
    static void registerIndependentFixtureTypesOnce() throws ReflectiveOperationException {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                AFirst.class, AFirst::new);
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                BSecond.class, BSecond::new);
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                CLast.class, CLast::new);
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                Probe.class, Probe::new);

        originalContextLoader = Thread.currentThread().getContextClassLoader();
        String wrapperName = new ModLauncherFactory().getUniqueName(
                Tracked.class.getMethod("onCleanupEvent", CleanupEvent.class));
        // Plain JUnit has no ModLauncher class transformer. Generate only this fixture's
        // queued wrapper through Forge's own API, preserving the production register path.
        try {
            Thread.currentThread().setContextClassLoader(
                    new FixtureWrapperLoader(originalContextLoader, wrapperName));
            // This local MinecraftForge creates EVENT_BUS with startShutdown(), and no
            // other test in the suite starts it. Restore that default after this class.
            MinecraftForge.EVENT_BUS.start();
            eventEnvironmentInstalled = true;
        } catch (RuntimeException | Error failure) {
            try {
                MinecraftForge.EVENT_BUS.shutdown();
            } catch (RuntimeException | Error rollbackFailure) {
                if (failure != rollbackFailure) failure.addSuppressed(rollbackFailure);
            } finally {
                Thread.currentThread().setContextClassLoader(originalContextLoader);
            }
            throw failure;
        }
    }

    @AfterAll
    static void restoreStandaloneEventEnvironment() {
        if (!eventEnvironmentInstalled) return;
        try {
            MinecraftForge.EVENT_BUS.shutdown();
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextLoader);
            eventEnvironmentInstalled = false;
        }
    }

    @Test
    void closeUnsubscribesRealEventsAndDestroysEvenImmutableCapabilitiesExactlyOnce() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        probe.immutable = true;
        probe.onDestroy = () -> {
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(1, probe.events, "the real listener is removed before destroy is invoked");
        };
        try {
            assertTrue(fixture.map.add(probe));
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(1, probe.events);
            assertFalse(fixture.map.remove(Probe.class));

            fixture.map.close();

            assertTrue(fixture.map.isClosed());
            assertEquals(1, probe.destroys);
            assertTrue(fixture.map.get(Probe.class).isEmpty());
            assertFalse(fixture.map.contains(Probe.class));
            assertTrue(fixture.map.values().isEmpty());
            assertEquals(0, fixture.map.stream().count());
            assertTrue(fixture.map.capabilitiesString().isEmpty());
            assertTrue(fixture.map.synchronizableCapabilitiesString().isEmpty());
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            fixture.map.close();
            fixture.map.clear();
            assertFalse(fixture.map.remove(Probe.class));
            assertEquals(1, probe.events);
            assertEquals(1, probe.destroys);
        } finally {
            fixture.release();
        }
    }

    @Test
    void runtimeAndErrorCleanupFailuresRetainIdentityAndAttemptEveryLaterCapability() throws Exception {
        assertAllCleanupAttempted(new IllegalStateException("first destroy"));
        assertAllCleanupAttempted(new AssertionError("first destroy"));
    }

    private static void assertAllCleanupAttempted(Throwable primary) throws Exception {
        Fixture fixture = new Fixture();
        AFirst first = new AFirst(fixture);
        BSecond second = new BSecond(fixture);
        CLast last = new CLast(fixture);
        Throwable later = new AssertionError("second destroy");
        first.onDestroy = () -> throwUnchecked(primary);
        second.onDestroy = () -> throwUnchecked(later);
        try {
            // Reverse publication order makes the public class-name cleanup order observable.
            assertTrue(fixture.map.add(last));
            assertTrue(fixture.map.add(second));
            assertTrue(fixture.map.add(first));
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(List.of(1, 1, 1), List.of(first.events, second.events, last.events));

            assertSame(primary, assertThrows(Throwable.class, fixture.map::close));
            assertEquals(List.of("AFirst", "BSecond", "CLast"), fixture.destroyOrder);
            assertArrayEquals(new Throwable[]{later}, primary.getSuppressed());
            assertTrue(fixture.map.isClosed());
            assertTrue(fixture.map.values().isEmpty());
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(List.of(1, 1, 1), List.of(first.events, second.events, last.events));

            assertSame(primary, assertThrows(Throwable.class, fixture.map::close));
            assertEquals(List.of(1, 1, 1), List.of(first.destroys, second.destroys, last.destroys));
            assertArrayEquals(new Throwable[]{later}, primary.getSuppressed());
        } finally {
            fixture.release();
        }
    }

    @Test
    void theSameFailureObjectDoesNotSelfSuppressOrSkipTheLastCapability() throws Exception {
        Fixture fixture = new Fixture();
        AssertionError shared = new AssertionError("shared");
        AFirst first = new AFirst(fixture);
        BSecond second = new BSecond(fixture);
        CLast last = new CLast(fixture);
        first.onDestroy = () -> { throw shared; };
        second.onDestroy = () -> { throw shared; };
        try {
            fixture.map.add(first);
            fixture.map.add(second);
            fixture.map.add(last);
            assertSame(shared, assertThrows(AssertionError.class, fixture.map::close));
            assertEquals(0, shared.getSuppressed().length);
            assertEquals(1, last.destroys);
        } finally {
            fixture.release();
        }
    }

    @Test
    void everyTerminalMutationAndSerializationEntryRejectsBeforeUserCallbacks() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            fixture.map.add(probe);
            fixture.map.close();
            int constructed = fixture.created.size();
            buf.writeInt(91);
            int reader = buf.readerIndex();
            int writer = buf.writerIndex();
            Collection<Class<Probe>> hostileClasses = new AbstractCollection<>() {
                @Override public Iterator<Class<Probe>> iterator() {
                    throw new AssertionError("closed addAll must not iterate caller collection");
                }
                @Override public int size() { return 0; }
            };
            Map<String, JsonElement> hostileData = new AbstractMap<>() {
                @Override public Set<Entry<String, JsonElement>> entrySet() {
                    throw new AssertionError("closed write must not iterate caller map");
                }
            };

            assertThrows(IllegalStateException.class, () -> fixture.map.add(Probe.class));
            assertThrows(IllegalStateException.class, () -> fixture.map.add(probe));
            assertThrows(IllegalStateException.class, () -> fixture.map.addAll(List.<Class<Probe>>of()));
            assertThrows(IllegalStateException.class, () -> fixture.map.addAll(hostileClasses));
            assertThrows(IllegalStateException.class, () -> fixture.map.getOrCreate(Probe.class));
            assertThrows(IllegalStateException.class,
                    () -> fixture.map.write(Probe.class.getSimpleName(), new JsonPrimitive(7)));
            assertThrows(IllegalStateException.class, () -> fixture.map.write(Probe.class, 7));
            assertThrows(IllegalStateException.class, () -> fixture.map.write(Map.of()));
            assertThrows(IllegalStateException.class, () -> fixture.map.write(hostileData));
            assertThrows(IllegalStateException.class,
                    () -> fixture.map.write(new CapabilityMap.Wrapper(Map.of())));
            assertThrows(IllegalStateException.class,
                    () -> fixture.map.write(new CapabilityMap.Wrapper(hostileData)));
            assertThrows(IllegalStateException.class,
                    () -> fixture.map.deserializeCapability(Probe.class.getSimpleName(), buf));
            assertThrows(IllegalStateException.class,
                    () -> fixture.map.deserializeCapability("unregistered-after-close", buf));
            assertThrows(IllegalStateException.class, () -> fixture.map.serializeCapability(Probe.class, buf));
            assertThrows(IllegalStateException.class, fixture.map::getData);

            fixture.map.tick();
            fixture.map.reset(Probe.class);
            fixture.map.resetAll();
            fixture.map.ifPresent(Probe.class, ignored -> fail("closed query invoked callback"));
            assertEquals(constructed, fixture.created.size(), "no terminal factory invocation");
            assertEquals(0, probe.codecCalls + probe.reads + probe.writes + probe.networkReads + probe.networkWrites);
            assertEquals(0, probe.ticks + probe.resets);
            assertEquals(reader, buf.readerIndex());
            assertEquals(writer, buf.writerIndex());
        } finally {
            buf.release();
            fixture.release();
        }
    }

    @Test
    void closeWaitsForInflightInitPreservesInterruptAndCannotPublishAfterReturning() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        CountDownLatch enteredInit = new CountDownLatch(1);
        CountDownLatch releaseInit = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        probe.onInit = () -> {
            enteredInit.countDown();
            await(releaseInit);
        };
        try {
            Future<Boolean> addition = workers.submit(() -> fixture.map.add(probe));
            assertTrue(enteredInit.await(5, TimeUnit.SECONDS));
            Future<?> closing = workers.submit(() -> {
                Thread.currentThread().interrupt();
                closeStarted.countDown();
                try {
                    fixture.map.close();
                    interruptPreserved.set(Thread.currentThread().isInterrupted());
                } finally {
                    closeReturned.countDown();
                }
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS), "close must wait for init");
            assertFalse(fixture.map.isClosed(), "terminal transition waits for the lifecycle callback");
            releaseInit.countDown();
            assertTrue(addition.get(5, TimeUnit.SECONDS));
            closing.get(5, TimeUnit.SECONDS);

            assertTrue(interruptPreserved.get());
            assertTrue(fixture.map.isClosed());
            assertTrue(fixture.map.values().isEmpty());
            assertEquals(1, probe.destroys);
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(0, probe.events, "init must not register after close returns");
            assertThrows(IllegalStateException.class, () -> fixture.map.add(Probe.class));
        } finally {
            releaseInit.countDown();
            workers.shutdownNow();
            try {
                assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "workers must not survive the test");
            } finally {
                fixture.release();
            }
        }
    }

    @Test
    void closeInsideInitIsRejectedBeforeTerminalStateAndTheFailedAdditionRollsBack() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        probe.onInit = fixture.map::close;
        try {
            assertThrows(IllegalStateException.class, () -> fixture.map.add(probe));
            assertFalse(fixture.map.isClosed());
            assertTrue(fixture.map.get(Probe.class).isEmpty());
            assertEquals(1, probe.destroys);
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(0, probe.events);
            Probe replacement = new Probe(fixture);
            assertTrue(fixture.map.add(replacement), "a rejected recursive close must not terminate the map");
            fixture.map.close();
            assertEquals(1, replacement.destroys);
        } finally {
            fixture.release();
        }
    }

    @Test
    void cleanupCanObserveTerminalStateAndReenterCloseButCannotAddOrWrite() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        probe.onDestroy = () -> {
            assertTrue(fixture.map.isClosed());
            assertTrue(fixture.map.values().isEmpty());
            assertDoesNotThrow(fixture.map::close);
            assertThrows(IllegalStateException.class, () -> fixture.map.add(Probe.class));
            assertThrows(IllegalStateException.class, () -> fixture.map.write(Probe.class, 1));
            assertThrows(IllegalStateException.class, () -> fixture.map.write(Map.of()));
        };
        try {
            fixture.map.add(probe);
            fixture.map.close();
            fixture.map.close();
            assertEquals(1, probe.destroys);
            assertEquals(0, probe.writes);
            assertEquals(1, fixture.created.size());
        } finally {
            fixture.release();
        }
    }

    @Test
    void ordinaryTickAndResetCallbacksCannotClaimAReentrantTerminalClose() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        probe.onTick = () -> assertThrows(IllegalStateException.class, fixture.map::close);
        probe.onReset = () -> assertThrows(IllegalStateException.class, fixture.map::close);
        try {
            fixture.map.add(probe);
            fixture.map.tick();
            fixture.map.reset(Probe.class);
            fixture.map.resetAll();
            assertFalse(fixture.map.isClosed());
            assertEquals(1, probe.ticks);
            assertEquals(2, probe.resets);
            assertSame(probe, fixture.map.get(Probe.class).orElseThrow());
        } finally {
            fixture.release();
        }
    }

    @Test
    void unclosedAddRemoveClearPersistenceAndTickResetPoliciesRemainUsable() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        AFirst immutable = new AFirst(fixture);
        immutable.immutable = true;
        try {
            assertFalse(fixture.map.isClosed());
            assertTrue(fixture.map.add(Probe.class));
            Probe factoryProbe = fixture.map.get(Probe.class).orElseThrow();
            assertFalse(fixture.map.add(Probe.class));
            fixture.map.write(Probe.class, 12);
            assertEquals(12, factoryProbe.value);
            fixture.map.write(Probe.class.getSimpleName(), new JsonPrimitive(15));
            assertEquals(15, factoryProbe.value);
            assertEquals(15, fixture.map.getData().data().get("Probe").getAsInt());
            assertTrue(fixture.map.remove(Probe.class));
            assertEquals(1, factoryProbe.destroys);

            assertTrue(fixture.map.add(probe));
            assertTrue(fixture.map.add(immutable));
            probe.onTick = () -> { throw new IllegalStateException("ordinary tick is logged"); };
            assertDoesNotThrow(fixture.map::tick);
            assertEquals(1, probe.ticks);
            assertEquals(1, immutable.ticks);
            probe.onReset = () -> { throw new IllegalStateException("ordinary reset propagates"); };
            assertThrows(IllegalStateException.class, () -> fixture.map.reset(Probe.class));
            assertThrows(IllegalStateException.class, fixture.map::resetAll);
            fixture.map.clear();
            assertEquals(1, probe.destroys);
            assertSame(immutable, fixture.map.get(AFirst.class).orElseThrow());
            assertEquals(0, immutable.destroys, "ordinary clear keeps immutable capabilities");
            fixture.map.close();
            assertEquals(1, immutable.destroys);
        } finally {
            fixture.release();
        }
    }

    @Test
    void unclosedRestoreStillToleratesInvalidDataAndSupportsValidPersistenceAndNetworkCallbacks() throws Exception {
        Fixture fixture = new Fixture();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            assertDoesNotThrow(() -> fixture.map.write("Probe", new JsonPrimitive("not-an-integer")));
            assertTrue(fixture.map.get(Probe.class).isEmpty());
            assertDoesNotThrow(() -> fixture.map.write("Probe", new JsonPrimitive("")));
            assertTrue(fixture.map.get(Probe.class).isEmpty());
            fixture.map.write("Probe", new JsonPrimitive(23));
            Probe restored = fixture.map.get(Probe.class).orElseThrow();
            assertEquals(23, restored.value);
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(1, restored.events);

            fixture.map.serializeCapability(Probe.class, buf);
            assertEquals(23, buf.readInt());
            buf.clear();
            buf.writeInt(29);
            fixture.map.deserializeCapability("Probe", buf);
            assertEquals(29, restored.value);
            assertEquals(1, restored.networkWrites);
            assertEquals(1, restored.networkReads);
            assertFalse(fixture.map.isClosed());
        } finally {
            buf.release();
            fixture.release();
        }
    }

    @Test
    void legacyUnregisterOnlyRemovesListenersAndStillAllowsTheLaterTerminalDestroy() throws Exception {
        Fixture fixture = new Fixture();
        Probe probe = new Probe(fixture);
        try {
            fixture.map.add(probe);
            fixture.map.unregisterAllFromBus();
            MinecraftForge.EVENT_BUS.post(new CleanupEvent());
            assertEquals(0, probe.events);
            assertEquals(0, probe.destroys);
            assertFalse(fixture.map.isClosed());
            assertSame(probe, fixture.map.get(Probe.class).orElseThrow());
            fixture.map.close();
            assertEquals(1, probe.destroys);
        } finally {
            fixture.release();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "test callback release timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test callback interrupted", failure);
        }
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }

    private static final class FixtureWrapperLoader extends ClassLoader {
        private final String wrapperName;

        FixtureWrapperLoader(ClassLoader parent, String wrapperName) {
            super(parent);
            this.wrapperName = wrapperName;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (!wrapperName.equals(name) || !ModLauncherFactory.hasPendingWrapperClass(name)) {
                throw new ClassNotFoundException(name);
            }
            ClassNode node = new ClassNode();
            ModLauncherFactory.processWrapperClass(name, node);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static final class Fixture {
        final CapabilityMap<Object, FPSMCapability<Object>> map;
        final List<Tracked> created = new ArrayList<>();
        final List<String> destroyOrder = new ArrayList<>();

        @SuppressWarnings("unchecked")
        Fixture() throws ReflectiveOperationException {
            Constructor<?> constructor = CapabilityMap.class.getDeclaredConstructor(Object.class, Class.class);
            constructor.setAccessible(true);
            map = (CapabilityMap<Object, FPSMCapability<Object>>) constructor.newInstance(this, FPSMCapability.class);
        }

        void release() {
            try {
                map.close();
            } catch (RuntimeException | Error ignored) {
                // Failure identity is asserted by the test; always release real global listeners.
            } finally {
                created.forEach(MinecraftForge.EVENT_BUS::unregister);
            }
        }
    }

    public static class CleanupEvent extends Event {}

    public abstract static class Tracked extends FPSMCapability<Object> {
        final Fixture fixture;
        int events;
        int destroys;
        int ticks;
        int resets;
        boolean immutable;
        Runnable onInit = () -> {};
        Runnable onDestroy = () -> {};
        Runnable onTick = () -> {};
        Runnable onReset = () -> {};

        Tracked(Object holder) {
            fixture = (Fixture) holder;
            fixture.created.add(this);
        }

        @Override public Object getHolder() { return fixture; }
        @Override public boolean isImmutable() { return immutable; }
        @Override public void init() { onInit.run(); }
        @Override public void destroy() {
            destroys++;
            fixture.destroyOrder.add(getClass().getSimpleName());
            onDestroy.run();
        }
        @Override public void tick() { ticks++; onTick.run(); }
        @Override public void reset() { resets++; onReset.run(); }
        @SubscribeEvent public void onCleanupEvent(CleanupEvent event) { events++; }
    }

    public static final class AFirst extends Tracked { AFirst(Object holder) { super(holder); } }
    public static final class BSecond extends Tracked { BSecond(Object holder) { super(holder); } }
    public static final class CLast extends Tracked { CLast(Object holder) { super(holder); } }

    public static final class Probe extends Tracked implements FPSMCapability.Savable<Integer>,
            FPSMCapability.CapabilitySynchronizable {
        int value;
        int codecCalls;
        int reads;
        int writes;
        int networkReads;
        int networkWrites;

        Probe(Object holder) { super(holder); }
        @Override public Codec<Integer> codec() { codecCalls++; return Codec.INT; }
        @Override public Integer write(Integer value) { writes++; this.value = value; return value; }
        @Override public Integer read() { reads++; return value; }
        @Override public boolean isDirty() { return true; }
        @Override public void readFromBuf(FriendlyByteBuf buf) { networkReads++; value = buf.readInt(); }
        @Override public void writeToBuf(FriendlyByteBuf buf) { networkWrites++; buf.writeInt(value); }
    }
}
