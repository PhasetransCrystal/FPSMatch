package net.ptcrys.fpsmatch.core.capability;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import net.ptcrys.fpsmatch.core.capability.map.MapCapability;
import net.ptcrys.fpsmatch.core.capability.team.TeamCapability;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.core.team.BaseTeam;
import net.ptcrys.fpsmatch.core.team.TeamData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.ModLauncherFactory;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Real production restoration and Forge lifecycle; no BaseMap/world is constructed. */
public class ControlledCapabilityRestorationTest {
    private static final ThreadLocal<Fixture> ACTIVE = new ThreadLocal<>();
    private static final List<Class<? extends FPSMCapability<?>>> OWN_REGISTRATIONS = new ArrayList<>();
    private static String eventWrapper;
    private static String registrationWrapper;
    private static String registrationErrorWrapper;
    private static ClassLoader originalContextLoader;

    @BeforeAll
    static void installOwnRegistrationsAndRealForgeEnvironment() throws ReflectiveOperationException {
        eventWrapper = new ModLauncherFactory().getUniqueName(
                Tracked.class.getMethod("onRestorationEvent", RestorationEvent.class));
        registrationWrapper = new ModLauncherFactory().getUniqueName(
                Q66RestoreRegisterProbe.class.getMethod("onRegistrationEvent", RegistrationEvent.class));
        registrationErrorWrapper = new ModLauncherFactory().getUniqueName(
                Q66RestoreRegisterErrorProbe.class.getMethod("onRegistrationErrorEvent", RegistrationEvent.class));
        registerMap(Q66RestoreProbe.class, Q66RestoreProbe::new);
        registerMap(Q66RestoreEarlier.class, Q66RestoreEarlier::new);
        registerMap(Q66RestoreNullableProbe.class, Q66RestoreNullableProbe::new);
        registerMap(Q66RestoreCancellingProbe.class, map -> {
            Q66RestoreCancellingProbe candidate = new Q66RestoreCancellingProbe(map);
            if (ACTIVE.get().cancellation == Cancellation.FACTORY) {
                MinecraftForge.EVENT_BUS.register(candidate);
                ACTIVE.get().post();
                assertTrue(ACTIVE.get().map.remove(Q66RestoreCancellingProbe.class));
            }
            return candidate;
        });
        registerMap(Q66RestoreRegisterProbe.class, Q66RestoreRegisterProbe::new);
        registerMap(Q66RestoreRegisterErrorProbe.class, Q66RestoreRegisterErrorProbe::new);
        registerMap(Q66RestorePlain.class, Q66RestorePlain::new);
        registerMap(Q66RestoreEmptyFactory.class, map -> null);
        registerMap(Q66RestoreThrowingFactory.class, map -> {
            throwUnchecked(ACTIVE.get().factoryFailure);
            throw new AssertionError("factory failure must be configured");
        });
        registerMap(First.SameName.class, First.SameName::new);
        registerMap(Second.SameName.class, Second.SameName::new);
        registerMap(MapNames.CrossName.class, MapNames.CrossName::new);
        registerTeam(TeamNames.CrossName.class, TeamNames.CrossName::new);
        registerTeam(Q66RestoreWrongHolder.class, Q66RestoreWrongHolder::new);
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, Q66RestoreRequiredOriginal.class,
                new FPSMCapability.Factory<BaseTeam, Q66RestoreRequiredOriginal>() {
                    @Override public Q66RestoreRequiredOriginal create(BaseTeam holder) { return new Q66RestoreRequiredOriginal(holder); }
                    @Override public boolean isOriginal() { return true; }
                });
        OWN_REGISTRATIONS.add(Q66RestoreRequiredOriginal.class);
        originalContextLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(new FixtureWrapperLoader(originalContextLoader));
        MinecraftForge.EVENT_BUS.start();
    }

    @AfterAll
    @SuppressWarnings("unchecked")
    static void removeOnlyOwnRegistrationsAndStopForge() throws ReflectiveOperationException {
        try {
            MinecraftForge.EVENT_BUS.shutdown();
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextLoader);
            // Manager has no unregister API. Reflection is limited to fixture ownership;
            // pending additions and all production lifecycle fields remain untouched.
            Field factoriesField = FPSMCapabilityManager.class.getDeclaredField("CAPABILITY_FACTORIES");
            factoriesField.setAccessible(true);
            Map<?, Map<Class<?>, ?>> groups = (Map<?, Map<Class<?>, ?>>) factoriesField.get(null);
            groups.values().forEach(group -> OWN_REGISTRATIONS.forEach(group::remove));
            Field originalsField = FPSMCapabilityManager.class.getDeclaredField("ORIGINAL_CAPABILITIES");
            originalsField.setAccessible(true);
            Map<?, List<Class<?>>> originals = (Map<?, List<Class<?>>>) originalsField.get(null);
            originals.values().forEach(list -> list.removeIf(OWN_REGISTRATIONS::contains));
            OWN_REGISTRATIONS.clear();
        }
    }

    private static <C extends MapCapability> void registerMap(
            Class<C> type, FPSMCapability.Factory<BaseMap, C> factory) {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.MAP, type, factory);
        OWN_REGISTRATIONS.add(type);
    }

    private static <C extends TeamCapability> void registerTeam(
            Class<C> type, FPSMCapability.Factory<BaseTeam, C> factory) {
        FPSMCapabilityManager.register(FPSMCapabilityManager.CapabilityType.TEAM, type, factory);
        OWN_REGISTRATIONS.add(type);
    }

    @Test
    void unscopedUnknownWrongHolderInvalidAndMissingTypedWritesRemainPermissive() {
        try (Fixture fixture = new Fixture()) {
            CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
            assertDoesNotThrow(() -> map.write("Q66UnknownCapability", number(1)));
            assertDoesNotThrow(() -> map.write("Q66RestoreWrongHolder", number(1)));
            assertDoesNotThrow(() -> map.write("Q66RestoreProbe", new JsonPrimitive("bad integer")));
            assertDoesNotThrow(() -> map.write("Q66RestoreProbe", new JsonPrimitive("")));
            assertDoesNotThrow(() -> map.write(Q66RestoreProbe.class, 3));
            assertTrue(map.values().isEmpty());
            assertDoesNotThrow(() -> map.write("Q66RestorePlain", number(123)));
            assertTrue(map.contains(Q66RestorePlain.class), "legacy non-Savable payload is ignored");
            map.write("Q66RestoreProbe", number(9));
            Q66RestoreProbe published = map.get(Q66RestoreProbe.class).orElseThrow();
            fixture.onDecode = (probe, json) -> { probe.value = 12; throw new IllegalArgumentException("legacy partial"); };
            assertDoesNotThrow(() -> map.write("Q66RestoreProbe", number(99)));
            assertEquals(12, published.value);
            assertFalse(map.isClosed());
        }
    }

    @Test
    void scopedUnknownWrongHolderAndBothKindsOfSimpleNameAmbiguityRejectBeforeFactory() {
        for (String name : List.of("Q66UnknownCapability", "Q66RestoreWrongHolder", "SameName", "CrossName")) {
            try (Fixture fixture = new Fixture()) {
                assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    map.write(name, number(1));
                    return map;
                }), name);
                assertTrue(fixture.created.isEmpty(), "resolver must reject before invoking a factory: " + name);
                fixture.assertClosedAndUnsubscribed();
                assertFalse(MapConstructionScope.isConstructing());
            }
        }
    }

    @Test
    void teamDataRejectsUnknownWrongHolderAndDuplicateOriginalOnlyInsideScope() {
        TeamData duplicate = new TeamData("q66", 1, List.of("Q66RestoreRequiredOriginal", "Q66RestoreRequiredOriginal"));
        assertEquals(List.of(Q66RestoreRequiredOriginal.class, Q66RestoreRequiredOriginal.class), duplicate.getCapabilities());
        assertTrue(new TeamData("q66", 1, List.of("Q66UnknownCapability", "Q66RestoreProbe")).getCapabilities().isEmpty());
        for (TeamData data : List.of(duplicate,
                new TeamData("q66", 1, List.of("Q66UnknownCapability")),
                new TeamData("q66", 1, List.of("Q66RestoreProbe")),
                new TeamData("q66", 1, List.of("CrossName")))) {
            assertThrows(IllegalStateException.class,
                    () -> MapConstructionScope.construct(data::getCapabilities));
            assertFalse(MapConstructionScope.isConstructing());
        }
        assertEquals(List.of(Q66RestoreRequiredOriginal.class), MapConstructionScope.construct(
                () -> new TeamData("q66", 1, List.of("Q66RestoreRequiredOriginal")).getCapabilities()));
    }

    @Test
    void publishedPartialWriteRuntimeAndErrorPreserveIdentityAndScopeClosesEveryPublishedObject() {
        for (Throwable primary : failures("published partial")) {
            try (Fixture fixture = new Fixture()) {
                fixture.onDecode = (probe, json) -> { probe.value = 37; throwUnchecked(primary); };
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(Q66RestoreEarlier.class));
                    assertTrue(map.add(Q66RestoreProbe.class));
                    fixture.post();
                    map.write("Q66RestoreProbe", number(99));
                    return map;
                })));
                Q66RestoreProbe probe = fixture.only(Q66RestoreProbe.class);
                assertEquals(37, probe.value, "this gate propagates failure; it is not data rollback");
                assertEquals(1, probe.decodes);
                assertEquals(1, probe.inits);
                fixture.assertDestroyCounts(1, 1);
                fixture.assertClosedAndUnsubscribed();
                assertEquals(0, primary.getSuppressed().length);
            }
        }
    }

    @Test
    void unpublishedDecodeFailureUnsubscribesAndDestroysOnceAndContinuesEarlierScopeCleanup() {
        for (Throwable primary : failures("unpublished decode")) {
            try (Fixture fixture = new Fixture()) {
                Throwable laterCleanup = new AssertionError("earlier container cleanup");
                fixture.onDecode = (probe, json) -> {
                    MinecraftForge.EVENT_BUS.register(probe);
                    fixture.post();
                    throwUnchecked(primary);
                };
                fixture.onDestroy = cap -> {
                    if (cap instanceof Q66RestoreProbe) throwUnchecked(primary); // No self-suppression.
                    if (cap instanceof Q66RestoreEarlier) throwUnchecked(laterCleanup);
                };
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(Q66RestoreEarlier.class));
                    map.write("Q66RestoreProbe", number(2));
                    return map;
                })));
                Q66RestoreProbe probe = fixture.only(Q66RestoreProbe.class);
                assertEquals(0, probe.inits);
                assertEquals(1, probe.decodes);
                assertEquals(1, probe.events, "decode really registered the returned candidate on Forge");
                assertArrayEquals(new Throwable[] {laterCleanup}, primary.getSuppressed());
                assertEquals(List.of("Q66RestoreProbe", "Q66RestoreEarlier"), fixture.destroyOrder);
                fixture.assertDestroyCounts(1, 1);
                fixture.assertClosedAndUnsubscribed();
                fixture.onDecode = null;
                fixture.onDestroy = null;
                CapabilityMap<BaseMap, MapCapability> fresh = MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    map.write("Q66RestoreProbe", number(4));
                    return map;
                });
                Q66RestoreProbe recovered = fresh.get(Q66RestoreProbe.class).orElseThrow();
                assertEquals(4, recovered.value);
                assertEquals(1, recovered.inits);
                fresh.close();
                fresh.close();
                assertEquals(1, recovered.destroys);
            }
        }
    }

    @Test
    void realCodecFailureAndReadFailureCleanUnpublishedCandidatesWithoutInitializingThem() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(RuntimeException.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                map.write("Q66RestoreProbe", new JsonPrimitive("not an integer"));
                return map;
            }));
            Q66RestoreProbe rejected = fixture.only(Q66RestoreProbe.class);
            assertEquals(1, rejected.codecCalls);
            assertEquals(0, rejected.inits);
            assertEquals(1, rejected.destroys);
            fixture.assertClosedAndUnsubscribed();
        }
        for (Throwable primary : failures("read sentinel")) {
            try (Fixture fixture = new Fixture()) {
                fixture.onRead = probe -> { throwUnchecked(primary); return null; };
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    map.write("Q66RestoreNullableProbe", new JsonPrimitive(""));
                    return map;
                })));
                Q66RestoreNullableProbe rejected = fixture.only(Q66RestoreNullableProbe.class);
                assertEquals(0, rejected.decodes);
                assertEquals(0, rejected.inits);
                assertEquals(1, rejected.destroys);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    @Test
    void factoryNullAndFactoryFailureHaveNoInventedCleanupHandleAndCloseEarlierObject() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                assertTrue(map.add(Q66RestoreEarlier.class));
                map.write("Q66RestoreEmptyFactory", number(2));
                return map;
            }));
            assertEquals(1, fixture.created.size());
            fixture.assertDestroyCounts(1);
            fixture.assertClosedAndUnsubscribed();
        }
        for (Throwable primary : failures("factory before return")) {
            try (Fixture fixture = new Fixture()) {
                fixture.factoryFailure = primary;
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(Q66RestoreEarlier.class));
                    map.write("Q66RestoreThrowingFactory", number(2));
                    return map;
                })));
                assertEquals(1, fixture.created.size());
                fixture.assertDestroyCounts(1);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    @Test
    void strictSentinelAcceptsOnlyNonSavableEmptyOrSavableActuallyNullInitialState() {
        try (Fixture fixture = new Fixture()) {
            CapabilityMap<BaseMap, MapCapability> map = MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> candidate = fixture.newMap();
                candidate.write("Q66RestorePlain", new JsonPrimitive(""));
                candidate.write("Q66RestoreNullableProbe", new JsonPrimitive(""));
                return candidate;
            });
            Q66RestoreNullableProbe probe = map.get(Q66RestoreNullableProbe.class).orElseThrow();
            assertNull(probe.value);
            assertEquals(1, probe.reads);
            assertEquals(0, probe.decodes);
            assertEquals(0, probe.codecCalls);
            assertEquals(1, probe.inits);
            map.close();
            map.close();
            fixture.assertDestroyCounts(1, 1);
        }
        for (String name : List.of("Q66RestorePlain", "Q66RestoreProbe", "Q66RestoreNullableProbe")) {
            for (JsonElement payload : new JsonElement[] {null, JsonNull.INSTANCE, number(8), new JsonPrimitive("")}) {
                boolean rejected = payload == null || payload == JsonNull.INSTANCE
                        || (name.equals("Q66RestorePlain") && !payload.equals(new JsonPrimitive("")))
                        || (name.equals("Q66RestoreProbe") && payload.equals(new JsonPrimitive("")));
                if (!rejected) continue;
                try (Fixture fixture = new Fixture()) {
                    Class<? extends Throwable> type = payload == null ? NullPointerException.class : IllegalStateException.class;
                    assertThrows(type, () -> MapConstructionScope.construct(() -> {
                        CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                        map.write(name, payload);
                        return map;
                    }), name + " / " + payload);
                    assertEquals(1, fixture.created.size());
                    assertEquals(0, fixture.created.get(0).inits);
                    fixture.assertDestroyCounts(1);
                    fixture.assertClosedAndUnsubscribed();
                }
            }
        }
    }

    @Test
    void sentinelCannotClearAnAlreadyPublishedNonNullState() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                map.write("Q66RestoreNullableProbe", number(18));
                map.write("Q66RestoreNullableProbe", new JsonPrimitive(""));
                return map;
            }));
            Q66RestoreNullableProbe probe = fixture.only(Q66RestoreNullableProbe.class);
            assertEquals(18, probe.value);
            assertEquals(1, probe.decodes);
            assertEquals(1, probe.reads);
            assertEquals(1, probe.inits);
            fixture.assertDestroyCounts(1);
            fixture.assertClosedAndUnsubscribed();
        }
    }

    @Test
    void validCustomDecodeRunsOnceBeforeInitAndSuccessTransfersExactlyOneCleanupToCaller() {
        try (Fixture fixture = new Fixture()) {
            fixture.onDecode = (probe, json) -> {
                assertEquals(0, probe.inits);
                assertTrue(fixture.map.get(Q66RestoreProbe.class).isEmpty());
                assertEquals("custom payload", json.getAsString());
                probe.value = 71;
            };
            CapabilityMap<BaseMap, MapCapability> map = MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> result = fixture.newMap();
                result.write("Q66RestoreProbe", new JsonPrimitive("custom payload"));
                return result;
            });
            Q66RestoreProbe probe = map.get(Q66RestoreProbe.class).orElseThrow();
            assertEquals(71, probe.value);
            assertEquals(1, probe.decodes);
            assertEquals(0, probe.codecCalls, "a direct codec route would bypass this custom decoder");
            assertEquals(1, probe.inits);
            assertEquals(0, probe.destroys);
            fixture.post();
            assertEquals(1, probe.events);
            map.close();
            map.close();
            fixture.assertDestroyCounts(1);
            fixture.assertClosedAndUnsubscribed();
        }
    }

    @Test
    void missingTypedObjectAndPublishedRemovalOrSameClassReplacementCannotReportSuccess() {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                map.write(Q66RestoreProbe.class, 4);
                return map;
            }));
            assertTrue(fixture.created.isEmpty());
            fixture.assertClosedAndUnsubscribed();
        }
        for (boolean typed : new boolean[] {false, true}) {
            for (boolean replace : new boolean[] {false, true}) {
                try (Fixture fixture = new Fixture()) {
                    fixture.onWrite = (probe, value) -> {
                        assertTrue(fixture.map.remove(Q66RestoreProbe.class));
                        if (replace) assertTrue(fixture.map.add(Q66RestoreProbe.class));
                    };
                    assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                        CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                        assertTrue(map.add(Q66RestoreProbe.class));
                        if (typed) map.write(Q66RestoreProbe.class, 12);
                        else map.write("Q66RestoreProbe", number(12));
                        return map;
                    }), "typed=" + typed + ", replacement=" + replace);
                    assertEquals(replace ? 2 : 1, fixture.created.size());
                    for (Tracked cap : fixture.created) assertEquals(1, cap.destroys);
                    if (replace) assertNotSame(fixture.created.get(0), fixture.created.get(1));
                    fixture.assertClosedAndUnsubscribed();
                }
            }
        }
    }

    @Test
    void typedWriteOriginalFailureWinsOverPostWriteIdentityFailure() {
        for (Throwable primary : failures("typed removal then throw")) {
            try (Fixture fixture = new Fixture()) {
                fixture.onWrite = (probe, value) -> {
                    assertTrue(fixture.map.remove(Q66RestoreProbe.class));
                    throwUnchecked(primary);
                };
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(Q66RestoreProbe.class));
                    map.write(Q66RestoreProbe.class, 12);
                    return map;
                })));
                fixture.assertDestroyCounts(1);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    @Test
    void mapAndWrapperSequentialRestoreCloseEarlierPublishedObjectsOnLaterFailure() {
        for (boolean wrapper : new boolean[] {false, true}) {
            try (Fixture fixture = new Fixture()) {
                Map<String, JsonElement> payload = new LinkedHashMap<>();
                payload.put("Q66RestoreEarlier", new JsonPrimitive(""));
                payload.put("Q66RestoreProbe", number(5));
                payload.put("Q66UnknownCapability", number(6));
                assertThrows(IllegalStateException.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    if (wrapper) map.write(new CapabilityMap.Wrapper(payload));
                    else map.write(payload);
                    return map;
                }));
                assertEquals(5, fixture.only(Q66RestoreProbe.class).value);
                fixture.assertDestroyCounts(1, 1);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    @Test
    void factoryDecodeInitAndRealRegisterCancellationKeepIsePrimaryAndCleanupFailureSuppressed() {
        for (Cancellation cancellation : Cancellation.values()) {
            for (Throwable cleanup : failures("cancel cleanup " + cancellation)) {
                try (Fixture fixture = new Fixture()) {
                    fixture.cancellation = cancellation;
                    fixture.onDestroy = cap -> {
                        if (cap instanceof Q66RestoreProbe) throwUnchecked(cleanup);
                    };
                    IllegalStateException primary = assertThrows(IllegalStateException.class,
                            () -> MapConstructionScope.construct(() -> {
                                CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                                assertTrue(map.add(Q66RestoreEarlier.class));
                                String name = cancellation == Cancellation.REGISTER
                                        ? (cleanup instanceof Error ? "Q66RestoreRegisterErrorProbe" : "Q66RestoreRegisterProbe") : "Q66RestoreCancellingProbe";
                                map.write(name, number(5));
                                return map;
                            }), cancellation.toString());
                    assertNotSame(cleanup, primary);
                    assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed(), cancellation.toString());
                    Q66RestoreProbe candidate = (Q66RestoreProbe) fixture.created.get(1);
                    assertEquals(cancellation == Cancellation.FACTORY || cancellation == Cancellation.DECODE ? 0 : 1,
                            candidate.inits, cancellation.toString());
                    if (cancellation == Cancellation.REGISTER) {
                        assertEquals(1, fixture.registrationHooks, "real Forge registration must load the hooked wrapper");
                    }
                    fixture.assertDestroyCounts(1, 1);
                    fixture.assertClosedAndUnsubscribed();
                    assertFalse(MapConstructionScope.isConstructing());
                }
            }
        }
    }

    @Test
    void addFactoryCancellationIsOwnedByInitializationEntryAndKeepsCancellationPrimary() {
        for (Throwable cleanup : failures("add entry cleanup")) {
            try (Fixture fixture = new Fixture()) {
                fixture.cancellation = Cancellation.FACTORY;
                fixture.onDestroy = cap -> { if (cap instanceof Q66RestoreProbe) throwUnchecked(cleanup); };
                IllegalStateException primary = assertThrows(IllegalStateException.class,
                        () -> MapConstructionScope.construct(() -> {
                            CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                            assertTrue(map.add(Q66RestoreEarlier.class));
                            map.add(Q66RestoreCancellingProbe.class);
                            return map;
                        }));
                assertArrayEquals(new Throwable[] {cleanup}, primary.getSuppressed());
                Q66RestoreCancellingProbe candidate = fixture.only(Q66RestoreCancellingProbe.class);
                assertEquals(0, candidate.inits);
                assertEquals(0, candidate.decodes);
                assertEquals(1, candidate.events, "factory self-registration must also be cleaned before init");
                fixture.assertDestroyCounts(1, 1);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    @Test
    void initOriginalFailureUnregistersSelfRegisteredCandidateAndDoesNotSelfSuppress() {
        for (Throwable primary : failures("init self registration")) {
            try (Fixture fixture = new Fixture()) {
                fixture.onInit = cap -> {
                    if (cap instanceof Q66RestoreProbe) {
                        MinecraftForge.EVENT_BUS.register(cap);
                        fixture.post();
                        throwUnchecked(primary);
                    }
                };
                fixture.onDestroy = cap -> { if (cap instanceof Q66RestoreProbe) throwUnchecked(primary); };
                assertSame(primary, assertThrows(Throwable.class, () -> MapConstructionScope.construct(() -> {
                    CapabilityMap<BaseMap, MapCapability> map = fixture.newMap();
                    assertTrue(map.add(Q66RestoreEarlier.class));
                    map.write("Q66RestoreProbe", number(3));
                    return map;
                })));
                assertEquals(1, fixture.only(Q66RestoreProbe.class).inits);
                assertEquals(1, fixture.only(Q66RestoreProbe.class).events);
                assertEquals(0, primary.getSuppressed().length);
                fixture.assertDestroyCounts(1, 1);
                fixture.assertClosedAndUnsubscribed();
            }
        }
    }

    private static JsonPrimitive number(int value) { return new JsonPrimitive(value); }
    private static Throwable[] failures(String message) {
        return new Throwable[] {new IllegalArgumentException(message), new AssertionError(message)};
    }
    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private enum Cancellation { FACTORY, DECODE, INIT, REGISTER }

    private static final class Fixture implements AutoCloseable {
        final List<Tracked> created = new ArrayList<>();
        final List<CapabilityMap<BaseMap, MapCapability>> maps = new ArrayList<>();
        final List<String> destroyOrder = new ArrayList<>();
        CapabilityMap<BaseMap, MapCapability> map;
        BiConsumer<Q66RestoreProbe, JsonElement> onDecode;
        BiConsumer<Q66RestoreProbe, Integer> onWrite;
        Function<Q66RestoreProbe, Integer> onRead;
        Consumer<Tracked> onInit;
        Consumer<Tracked> onDestroy;
        Throwable factoryFailure;
        Cancellation cancellation;
        int registrationHooks;

        Fixture() {
            assertNull(ACTIVE.get());
            ACTIVE.set(this);
        }

        CapabilityMap<BaseMap, MapCapability> newMap() {
            map = CapabilityMap.ofMapCapability(null);
            maps.add(map);
            return map;
        }

        <C extends Tracked> C only(Class<C> type) {
            List<C> matches = created.stream().filter(cap -> cap.getClass() == type).map(type::cast).toList();
            assertEquals(1, matches.size());
            return matches.get(0);
        }

        void post() {
            MinecraftForge.EVENT_BUS.post(new RestorationEvent());
            MinecraftForge.EVENT_BUS.post(new RegistrationEvent());
        }

        void assertDestroyCounts(Integer... counts) {
            assertEquals(List.of(counts), created.stream().map(cap -> cap.destroys).toList());
        }

        void assertClosedAndUnsubscribed() {
            assertTrue(maps.stream().allMatch(CapabilityMap::isClosed));
            assertTrue(maps.stream().allMatch(candidate -> candidate.values().isEmpty()));
            List<Integer> before = created.stream().map(cap -> cap.events).toList();
            int registerEvents = created.stream().filter(Q66RestoreProbe.class::isInstance)
                    .map(Q66RestoreProbe.class::cast).mapToInt(cap -> cap.registrationEvents).sum();
            post();
            assertEquals(before, created.stream().map(cap -> cap.events).toList(), "real Forge listeners must be gone");
            assertEquals(registerEvents, created.stream().filter(Q66RestoreProbe.class::isInstance)
                    .map(Q66RestoreProbe.class::cast).mapToInt(cap -> cap.registrationEvents).sum());
        }

        @Override public void close() {
            try {
                for (CapabilityMap<BaseMap, MapCapability> candidate : maps) {
                    try { candidate.close(); } catch (RuntimeException | Error ignored) { /* asserted by test */ }
                }
            } finally {
                try { created.forEach(MinecraftForge.EVENT_BUS::unregister); }
                finally {
                    ACTIVE.remove();
                }
            }
        }
    }

    public static class RestorationEvent extends Event { }
    public static class RegistrationEvent extends Event { }

    public abstract static class Tracked extends MapCapability {
        final Fixture fixture;
        int inits;
        int destroys;
        int events;

        Tracked(BaseMap holder) {
            super(holder);
            fixture = ACTIVE.get();
            assertNotNull(fixture);
            fixture.created.add(this);
        }

        @Override public void init() {
            inits++;
            if (fixture.onInit != null) fixture.onInit.accept(this);
            if (this instanceof Q66RestoreCancellingProbe && fixture.cancellation == Cancellation.INIT) {
                MinecraftForge.EVENT_BUS.register(this);
                fixture.post();
                assertTrue(fixture.map.remove(Q66RestoreCancellingProbe.class));
            }
        }

        @Override public void destroy() {
            destroys++;
            fixture.destroyOrder.add(getClass().getSimpleName());
            if (fixture.onDestroy != null) fixture.onDestroy.accept(this);
        }

        @SubscribeEvent public void onRestorationEvent(RestorationEvent event) { events++; }
    }

    public static class Q66RestoreProbe extends Tracked implements FPSMCapability.Savable<Integer> {
        Integer value = 0;
        int codecCalls;
        int decodes;
        int reads;
        int registrationEvents;

        Q66RestoreProbe(BaseMap holder) { super(holder); }
        @Override public Codec<Integer> codec() { codecCalls++; return Codec.INT; }
        @Override public Integer read() {
            reads++;
            return fixture.onRead == null ? value : fixture.onRead.apply(this);
        }
        @Override public Integer write(Integer input) {
            value = input;
            if (fixture.onWrite != null) fixture.onWrite.accept(this, input);
            return value;
        }
        @Override public void decode(JsonElement json) {
            decodes++;
            if (fixture.onDecode != null) fixture.onDecode.accept(this, json);
            else FPSMCapability.Savable.super.decode(json);
            if (this instanceof Q66RestoreCancellingProbe && fixture.cancellation == Cancellation.DECODE) {
                assertTrue(fixture.map.remove(Q66RestoreCancellingProbe.class));
            }
        }
    }

    public static final class Q66RestoreNullableProbe extends Q66RestoreProbe {
        Q66RestoreNullableProbe(BaseMap holder) { super(holder); value = null; }
    }
    public static final class Q66RestoreCancellingProbe extends Q66RestoreProbe { Q66RestoreCancellingProbe(BaseMap holder) { super(holder); } }
    public static final class Q66RestoreRegisterProbe extends Q66RestoreProbe {
        Q66RestoreRegisterProbe(BaseMap holder) { super(holder); }
        @SubscribeEvent public void onRegistrationEvent(RegistrationEvent event) { registrationEvents++; }
    }
    public static final class Q66RestoreRegisterErrorProbe extends Q66RestoreProbe {
        Q66RestoreRegisterErrorProbe(BaseMap holder) { super(holder); }
        @SubscribeEvent public void onRegistrationErrorEvent(RegistrationEvent event) { registrationEvents++; }
    }
    public static final class Q66RestoreEarlier extends Tracked { Q66RestoreEarlier(BaseMap holder) { super(holder); } }
    public static final class Q66RestorePlain extends Tracked { Q66RestorePlain(BaseMap holder) { super(holder); } }
    public static final class Q66RestoreEmptyFactory extends Tracked { Q66RestoreEmptyFactory(BaseMap holder) { super(holder); } }
    public static final class Q66RestoreThrowingFactory extends Tracked { Q66RestoreThrowingFactory(BaseMap holder) { super(holder); } }
    public static final class Q66RestoreWrongHolder extends TeamCapability { Q66RestoreWrongHolder(BaseTeam holder) { super(holder); } }
    public static final class Q66RestoreRequiredOriginal extends TeamCapability { Q66RestoreRequiredOriginal(BaseTeam holder) { super(holder); } }
    public static final class First {
        public static final class SameName extends Tracked { SameName(BaseMap holder) { super(holder); } }
    }
    public static final class Second {
        public static final class SameName extends Tracked { SameName(BaseMap holder) { super(holder); } }
    }
    public static final class MapNames {
        public static final class CrossName extends Tracked { CrossName(BaseMap holder) { super(holder); } }
    }
    public static final class TeamNames {
        public static final class CrossName extends TeamCapability { CrossName(BaseTeam holder) { super(holder); } }
    }

    private static final class FixtureWrapperLoader extends ClassLoader {
        FixtureWrapperLoader(ClassLoader parent) { super(parent); }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if ((!name.equals(eventWrapper) && !name.equals(registrationWrapper) && !name.equals(registrationErrorWrapper))
                    || !ModLauncherFactory.hasPendingWrapperClass(name)) throw new ClassNotFoundException(name);
            ClassNode node = new ClassNode();
            ModLauncherFactory.processWrapperClass(name, node);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            byte[] bytes = writer.toByteArray();
            Class<?> wrapper = defineClass(name, bytes, 0, bytes.length);
            Fixture fixture = ACTIVE.get();
            if ((name.equals(registrationWrapper) || name.equals(registrationErrorWrapper))
                    && fixture.cancellation == Cancellation.REGISTER) {
                fixture.registrationHooks++;
                if (name.equals(registrationWrapper)) assertTrue(fixture.map.remove(Q66RestoreRegisterProbe.class));
                else assertTrue(fixture.map.remove(Q66RestoreRegisterErrorProbe.class));
            }
            return wrapper;
        }
    }
}
