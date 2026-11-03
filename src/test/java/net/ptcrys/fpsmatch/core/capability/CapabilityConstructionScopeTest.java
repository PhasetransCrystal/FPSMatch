package net.ptcrys.fpsmatch.core.capability;

import net.ptcrys.fpsmatch.core.capability.map.MapCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.ModLauncherFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CapabilityConstructionScopeTest {
    private static final ThreadLocal<Fixture> ACTIVE = new ThreadLocal<>();
    private static ClassLoader originalContextLoader;
    private static boolean eventEnvironmentInstalled;
    private static List<Class<? extends MapCapability>> originalMapCapabilities;

    @BeforeAll
    static void registerNonOriginalFixturesAndStartRealEventBus() throws ReflectiveOperationException {
        originalMapCapabilities = FPSMCapabilityManager.getOriginalMapCapabilities();
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                ScopeA.class, (BaseMap map) -> new ScopeA(map));
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                ScopeB.class, (BaseMap map) -> new ScopeB(map));
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP,
                ScopeC.class, (BaseMap map) -> {
                    throwUnchecked(ACTIVE.get().factoryFailure);
                    return new ScopeC(map);
                });
        assertEquals(originalMapCapabilities, FPSMCapabilityManager.getOriginalMapCapabilities(),
                "fixture registration must not change required original capabilities");

        originalContextLoader = Thread.currentThread().getContextClassLoader();
        String wrapperName = new ModLauncherFactory().getUniqueName(
                TrackedMapCapability.class.getMethod("onScopeEvent", ScopeEvent.class));
        // Standalone JUnit lacks ModLauncher transformation. Forge creates the real
        // subscriber wrapper for this fixture; registration and event dispatch stay real.
        try {
            Thread.currentThread().setContextClassLoader(new FixtureWrapperLoader(originalContextLoader, wrapperName));
            MinecraftForge.EVENT_BUS.start();
            eventEnvironmentInstalled = true;
        } catch (RuntimeException | Error failure) {
            try {
                MinecraftForge.EVENT_BUS.shutdown();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
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
            ACTIVE.remove();
        }
        assertEquals(originalMapCapabilities, FPSMCapabilityManager.getOriginalMapCapabilities());
    }

    @Test
    void successLeavesRealCapabilitiesLiveUntilCallerClosesReturnedContainer() {
        try (Fixture fixture = new Fixture()) {
            CapabilityMap<BaseMap, MapCapability> map = MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> acquired = fixture.newMap();
                assertTrue(acquired.add(ScopeA.class));
                assertTrue(acquired.add(ScopeB.class));
                MinecraftForge.EVENT_BUS.post(new ScopeEvent());
                return acquired;
            });
            assertFalse(MapConstructionScope.isConstructing());
            assertFalse(map.isClosed());
            assertEquals(2, map.values().size());
            assertCounts(fixture, List.of(1, 1), List.of(0, 0));
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            assertCounts(fixture, List.of(2, 2), List.of(0, 0));

            map.close();
            assertTrue(map.isClosed());
            assertTrue(map.values().isEmpty());
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            map.close();
            assertCounts(fixture, List.of(2, 2), List.of(1, 1));
            assertEquals(List.of(1, 1), fixture.created.stream().map(cap -> cap.inits).toList());
        }
    }

    @Test
    void laterInitRuntimeAndErrorClosePreviouslyPublishedContainerAndDestroyEachCapabilityOnce() {
        for (Throwable primary : new Throwable[] {new IllegalArgumentException("later init"), new AssertionError("later init")}) {
            try (Fixture fixture = new Fixture()) {
                fixture.initFailure = primary;
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(ScopeA.class));
                    assertTrue(map.add(ScopeB.class));
                    MinecraftForge.EVENT_BUS.post(new ScopeEvent());
                    map.add(ScopeC.class);
                    return map;
                })));
                CapabilityMap<BaseMap, MapCapability> map = fixture.maps.get(0);
                assertTrue(map.isClosed());
                assertTrue(map.values().isEmpty());
                assertTrue(map.get(ScopeA.class).isEmpty());
                assertTrue(map.get(ScopeB.class).isEmpty());
                MinecraftForge.EVENT_BUS.post(new ScopeEvent());
                map.close();
                assertCounts(fixture, List.of(1, 1, 0), List.of(1, 1, 1));
                assertEquals(List.of(1, 1, 1), fixture.created.stream().map(cap -> cap.inits).toList());
                assertEquals(0, primary.getSuppressed().length);
                assertFalse(MapConstructionScope.isConstructing());
                assertEquals("fresh", MapConstructionScope.construct(() -> "fresh"));
            }
        }
    }

    @Test
    void laterFactoryFailurePreservesItsIdentityAndClosesAlreadyPublishedCapabilities() {
        try (Fixture fixture = new Fixture()) {
            AssertionError primary = new AssertionError("factory never returned a capability");
            fixture.factoryFailure = primary;
            assertSame(primary, assertThrows(AssertionError.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                assertTrue(map.add(ScopeA.class));
                MinecraftForge.EVENT_BUS.post(new ScopeEvent());
                map.add(ScopeC.class);
                return map;
            })));
            CapabilityMap<BaseMap, MapCapability> map = fixture.maps.get(0);
            assertTrue(map.isClosed());
            assertTrue(map.values().isEmpty());
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            assertEquals(1, fixture.created.size(), "a throwing factory has no returned cleanup handle");
            assertCounts(fixture, List.of(1), List.of(1));
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void failedContainerCleanupIsSuppressedAndEarlierContainerStillClosesAndUnsubscribes() {
        try (Fixture fixture = new Fixture()) {
            RuntimeException primary = new IllegalArgumentException("constructor after publication");
            AssertionError cleanupFailure = new AssertionError("second container destroy");
            fixture.destroyFailure = cleanupFailure;
            assertSame(primary, assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> first = fixture.newMap();
                assertTrue(first.add(ScopeA.class));
                CapabilityMap<BaseMap, MapCapability> second = fixture.newMap();
                assertTrue(second.add(ScopeB.class));
                MinecraftForge.EVENT_BUS.post(new ScopeEvent());
                throw primary;
            })));
            assertArrayEquals(new Throwable[] {cleanupFailure}, primary.getSuppressed());
            assertEquals(List.of("ScopeB", "ScopeA"), fixture.destroyOrder);
            assertTrue(fixture.maps.stream().allMatch(CapabilityMap::isClosed));
            assertTrue(fixture.maps.stream().allMatch(map -> map.values().isEmpty()));
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            assertCounts(fixture, List.of(1, 1), List.of(1, 1));
            assertSame(cleanupFailure, assertThrows(AssertionError.class, fixture.maps.get(1)::close));
            assertCounts(fixture, List.of(1, 1), List.of(1, 1));
            assertFalse(MapConstructionScope.isConstructing());
        }
    }

    @Test
    void ordinaryUnscopedFactoryDoesNotClosePreviouslyPublishedContainerOnLaterFailure() {
        try (Fixture fixture = new Fixture()) {
            CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
            assertTrue(map.add(ScopeA.class));
            RuntimeException failure = new IllegalArgumentException("unscoped later init");
            fixture.initFailure = failure;
            assertSame(failure, assertThrows(RuntimeException.class, () -> map.add(ScopeC.class)));
            assertFalse(map.isClosed());
            assertTrue(map.contains(ScopeA.class));
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            assertCounts(fixture, List.of(1, 0), List.of(0, 1));
            map.close();
            MinecraftForge.EVENT_BUS.post(new ScopeEvent());
            assertCounts(fixture, List.of(1, 0), List.of(1, 1));
        }
    }

    private static void assertCounts(Fixture fixture, List<Integer> events, List<Integer> destroys) {
        assertEquals(events, fixture.created.stream().map(cap -> cap.events).toList());
        assertEquals(destroys, fixture.created.stream().map(cap -> cap.destroys).toList());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private static final class Fixture implements AutoCloseable {
        final List<CapabilityMap<BaseMap, MapCapability>> maps = new ArrayList<>();
        final List<TrackedMapCapability> created = new ArrayList<>();
        final List<String> destroyOrder = new ArrayList<>();
        Throwable initFailure;
        Throwable factoryFailure;
        Throwable destroyFailure;

        Fixture() {
            assertNull(ACTIVE.get());
            ACTIVE.set(this);
        }

        CapabilityMap<BaseMap, MapCapability> newMap() {
            // This exercises the actual public factory's scope acquisition, without
            // constructing a BaseMap or claiming ServerLevel/MapTeams host coverage.
            CapabilityMap<BaseMap, MapCapability> map = CapabilityMap.ofMapCapability(null);
            maps.add(map);
            return map;
        }

        @Override
        public void close() {
            try {
                for (CapabilityMap<BaseMap, MapCapability> map : maps) {
                    try {
                        map.close();
                    } catch (RuntimeException | Error ignored) {
                        // The test asserts failure identity; release all other fixtures too.
                    }
                }
            } finally {
                try {
                    created.forEach(MinecraftForge.EVENT_BUS::unregister);
                } finally {
                    ACTIVE.remove();
                }
            }
        }
    }

    public static class ScopeEvent extends Event {}

    public abstract static class TrackedMapCapability extends MapCapability {
        final Fixture fixture;
        int inits;
        int destroys;
        int events;

        TrackedMapCapability(BaseMap map) {
            super(map);
            fixture = ACTIVE.get();
            assertNotNull(fixture);
            fixture.created.add(this);
        }

        @Override public void init() { inits++; }
        @Override public void destroy() {
            destroys++;
            fixture.destroyOrder.add(getClass().getSimpleName());
        }
        @SubscribeEvent public void onScopeEvent(ScopeEvent event) { events++; }
    }

    public static final class ScopeA extends TrackedMapCapability {
        ScopeA(BaseMap map) { super(map); }
    }

    public static final class ScopeB extends TrackedMapCapability {
        ScopeB(BaseMap map) { super(map); }
        @Override public void destroy() {
            super.destroy();
            throwUnchecked(fixture.destroyFailure);
        }
    }

    public static final class ScopeC extends TrackedMapCapability {
        ScopeC(BaseMap map) { super(map); }
        @Override public void init() {
            super.init();
            throwUnchecked(fixture.initFailure);
        }
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
}
