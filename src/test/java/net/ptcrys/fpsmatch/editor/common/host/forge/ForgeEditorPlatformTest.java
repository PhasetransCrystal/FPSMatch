package net.ptcrys.fpsmatch.editor.common.host.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.IModBusEvent;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceProducers;
import net.ptcrys.fpsmatch.editor.core.types.DocumentTypes;
import net.ptcrys.fpsmatch.editor.core.types.values.ValueTypes;
import net.ptcrys.fpsmatch.editor.common.domain.map.MapDomainValues;
import net.ptcrys.fpsmatch.editor.common.domain.spatial.SpatialValues;
import org.junit.jupiter.api.Test;

class ForgeEditorPlatformTest {
    private static final ApiVersion API = new ApiVersion(1, 0);
    private static final Contribution.Side COMMON = Contribution.Side.COMMON;
    private static final Contribution.Side CLIENT = Contribution.Side.CLIENT;
    private static final EditorId OWNER = EditorId.parse("thirdparty:module");
    private static final EditorId INCREMENT = EditorId.parse("thirdparty:increment");
    private static final EditorId CLIENT_ONLY = EditorId.parse("thirdparty:client_only");
    private static final ExtensionKind<IntUnaryOperator> KIND = new ExtensionKind<>(
            EditorId.parse("thirdparty:operation"), IntUnaryOperator.class);

    @Test
    void threeIndependentModBusesShareOneEventBuilderAndActualFpsContributions() {
        List<EditorRegistrationEvent> events = new ArrayList<>();
        List<ExtensionRegistryBuilder> builders = new ArrayList<>();
        IEventBus fps = bus(event -> {
            events.add(event);
            builders.add(event.registry());
            SpatialValues.register(event.registry(), "1.2026.10.15-test-build");
            MapDomainValues.register(event.registry(), "1.2026.10.15-test-build");
        });
        IEventBus addon = bus(event -> {
            events.add(event);
            builders.add(event.registry());
            contribute(event.registry());
        });
        IEventBus dependent = bus(event -> {
            events.add(event);
            builders.add(event.registry());
            event.registry().add(KIND, new Contribution(new ContributionKey(KIND.id(), CLIENT_ONLY),
                    OWNER, CLIENT, Set.of(new ContributionKey(KIND.id(), INCREMENT))), operand -> operand * 2);
        });
        AtomicInteger cleanup = new AtomicInteger();
        ForgeEditorPlatform platform = platform(CLIENT, event -> {
            fps.post(event);
            addon.post(event);
            dependent.post(event);
        }, cleanup::incrementAndGet);

        platform.collect();

        assertEquals(ForgeEditorPlatform.State.READY, platform.state());
        assertEquals(1, cleanup.get());
        assertEquals(3, events.size());
        assertSame(events.get(0), events.get(1));
        assertSame(events.get(0), events.get(2));
        assertSame(builders.get(0), builders.get(1));
        assertSame(builders.get(0), builders.get(2));
        ExtensionRegistry registry = platform.registry();
        assertEquals("1.2026.10.15-test-build", registry.metadata().modules().get(SpatialValues.MODULE).buildVersion());
        assertEquals("1.2026.10.15-test-build", registry.metadata().modules().get(MapDomainValues.MODULE).buildVersion());
        assertTrue(registry.find(ValueTypes.KIND, SpatialValues.POINT).isPresent());
        assertTrue(registry.find(ValueTypes.KIND, SpatialValues.AREA).isPresent());
        assertTrue(registry.find(ValueTypes.KIND, MapDomainValues.TEAM).isPresent());
        assertTrue(registry.find(ValueTypes.KIND, MapDomainValues.SHOP).isPresent());
        assertEquals(42, registry.find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
        assertEquals(42, registry.find(KIND, CLIENT_ONLY).orElseThrow().applyAsInt(21));
        assertTrue(registry.metadata().problems().isEmpty());
        assertClosed(events.get(0), builders.get(0));
    }

    @Test
    void commonEnvironmentFiltersClientImplementationAndItsCommonDependent() {
        EditorId dependent = EditorId.parse("thirdparty:requires_client");
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            assertEquals(COMMON, event.environment());
            contribute(event.registry());
            event.registry().add(KIND, contribution(CLIENT_ONLY, CLIENT), operand -> operand * 2);
            event.registry().add(KIND, new Contribution(new ContributionKey(KIND.id(), dependent),
                    OWNER, COMMON, Set.of(new ContributionKey(KIND.id(), CLIENT_ONLY))), operand -> operand * 3);
        }, () -> { });
        platform.collect();
        assertTrue(platform.registry().find(KIND, INCREMENT).isPresent());
        assertTrue(platform.registry().find(KIND, CLIENT_ONLY).isEmpty());
        assertTrue(platform.registry().find(KIND, dependent).isEmpty());
        assertFalse(platform.registry().metadata().problems().isEmpty());
    }

    @Test
    void emptyCollectionDefinesOnlyGenericKindsWithoutInventingDomainModules() {
        ForgeEditorPlatform platform = platform(COMMON, event -> { }, () -> { });
        platform.collect();
        ExtensionRegistry registry = platform.registry();
        assertTrue(registry.entries(DocumentTypes.KIND).isEmpty());
        assertTrue(registry.entries(ValueTypes.KIND).isEmpty());
        assertTrue(registry.entries(ResourceProducers.KIND).isEmpty());
        assertTrue(registry.metadata().modules().isEmpty());
        assertTrue(registry.metadata().contributions().isEmpty());
    }

    @Test
    void readyDoesNotGrantAvailabilityToConflictingOrMissingDependencyContributions() {
        EditorId dependent = EditorId.parse("thirdparty:dependent");
        EditorId missing = EditorId.parse("thirdparty:missing");
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            contribute(event.registry());
            event.registry().add(KIND, contribution(INCREMENT, COMMON), operand -> operand + 100);
            event.registry().add(KIND, new Contribution(new ContributionKey(KIND.id(), dependent),
                    OWNER, COMMON, Set.of(new ContributionKey(KIND.id(), missing))), operand -> operand * 2);
        }, () -> { });
        platform.collect();
        assertEquals(ForgeEditorPlatform.State.READY, platform.state());
        assertTrue(platform.registry().find(KIND, INCREMENT).isEmpty());
        assertTrue(platform.registry().find(KIND, dependent).isEmpty());
        assertTrue(platform.registry().metadata().problems().stream()
                .anyMatch(problem -> problem.code().toString().equals("editor:registry/duplicate_contribution")));
        assertTrue(platform.registry().metadata().problems().stream()
                .anyMatch(problem -> problem.code().toString().equals("editor:registry/missing_contribution")));
    }

    @Test
    void candidateCannotEscapeThroughRegistryDuringBroadcastOrCleanup() {
        AtomicReference<ForgeEditorPlatform> reference = new AtomicReference<>();
        AtomicInteger checks = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            assertEquals(ForgeEditorPlatform.State.COLLECTING, reference.get().state());
            assertThrows(IllegalStateException.class, reference.get()::registry);
            checks.incrementAndGet();
            contribute(event.registry());
        }, () -> {
            assertThrows(IllegalStateException.class, reference.get()::registry);
            checks.incrementAndGet();
        });
        reference.set(platform);
        assertThrows(IllegalStateException.class, platform::registry);
        platform.collect();
        assertEquals(2, checks.get());
        assertEquals(42, platform.registry().find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
    }

    @Test
    void firstThrowingBusStopsLaterBusesAndPreservesRuntimeException() {
        assertDispatchFailure(new IllegalArgumentException("extension rejected"));
    }

    @Test
    void firstThrowingBusStopsLaterBusesAndPreservesError() {
        assertDispatchFailure(new AssertionError("extension error"));
    }

    @Test
    void cleanupFailurePreventsPublishingAnOtherwiseCompleteCandidate() {
        IllegalStateException failure = new IllegalStateException("context cleanup");
        AtomicReference<EditorRegistrationEvent> event = new AtomicReference<>();
        AtomicReference<ExtensionRegistryBuilder> builder = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, current -> {
            event.set(current);
            builder.set(current.registry());
            contribute(current.registry());
        }, () -> {
            cleanups.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, platform::collect));
        assertFailed(platform);
        assertEquals(1, cleanups.get());
        assertClosed(event.get(), builder.get());
    }

    @Test
    void originalFailureKeepsDifferentCleanupFailureSuppressed() {
        RuntimeException primary = new IllegalArgumentException("dispatch");
        Error secondary = new AssertionError("cleanup");
        ForgeEditorPlatform platform = platform(COMMON, event -> { throw primary; }, () -> { throw secondary; });
        assertSame(primary, assertThrows(IllegalArgumentException.class, platform::collect));
        assertEquals(1, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
        assertFailed(platform);
    }

    @Test
    void standaloneCleanupErrorCannotPublishCandidate() {
        Error failure = new AssertionError("cleanup error");
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> contribute(event.registry()), () -> {
            cleanups.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(AssertionError.class, platform::collect));
        assertEquals(1, cleanups.get());
        assertFailed(platform);
    }

    @Test
    void primaryErrorKeepsCleanupRuntimeExceptionSuppressed() {
        Error primary = new AssertionError("dispatch error");
        RuntimeException secondary = new IllegalStateException("cleanup");
        ForgeEditorPlatform platform = platform(COMMON, event -> { throw primary; }, () -> { throw secondary; });
        assertSame(primary, assertThrows(AssertionError.class, platform::collect));
        assertEquals(1, primary.getSuppressed().length);
        assertSame(secondary, primary.getSuppressed()[0]);
        assertFailed(platform);
    }

    @Test
    void sameThrowableFromDispatchAndCleanupDoesNotMaskPrimaryWithSelfSuppression() {
        RuntimeException failure = new IllegalArgumentException("same object");
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> { throw failure; }, () -> {
            cleanups.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalArgumentException.class, platform::collect));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, cleanups.get());
        assertFailed(platform);
    }

    @Test
    void invalidLoaderBeforeBroadcastFailsWithoutCallingSubscribersAndCleansOnce() {
        AtomicInteger broadcasts = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = new ForgeEditorPlatform(API, COMMON,
                event -> broadcasts.incrementAndGet(), cleanups::incrementAndGet, () -> false);
        assertThrows(IllegalStateException.class, platform::collect);
        assertEquals(0, broadcasts.get());
        assertEquals(1, cleanups.get());
        assertFailed(platform);
    }

    @Test
    void loaderInvalidatedByBroadcastCannotTurnSilentReturnIntoSuccess() {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicInteger cleanups = new AtomicInteger();
        AtomicReference<EditorRegistrationEvent> retained = new AtomicReference<>();
        AtomicReference<ExtensionRegistryBuilder> builder = new AtomicReference<>();
        ForgeEditorPlatform platform = new ForgeEditorPlatform(API, COMMON, event -> {
            retained.set(event);
            builder.set(event.registry());
            contribute(event.registry());
            valid.set(false);
        }, cleanups::incrementAndGet, valid::get);
        assertThrows(IllegalStateException.class, platform::collect);
        assertFailed(platform);
        assertEquals(1, cleanups.get());
        assertClosed(retained.get(), builder.get());
    }

    @Test
    void loaderValidityExceptionStillEntersCleanupAndFailureBoundary() {
        AssertionError failure = new AssertionError("loader unavailable");
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = new ForgeEditorPlatform(API, COMMON, event -> {
            throw new AssertionError("broadcast must not happen");
        }, cleanups::incrementAndGet, () -> { throw failure; });
        assertSame(failure, assertThrows(AssertionError.class, platform::collect));
        assertEquals(1, cleanups.get());
        assertFailed(platform);
    }

    @Test
    void subscriberPrematureFreezeCannotBePublishedByHost() {
        AtomicReference<ExtensionRegistryBuilder> builder = new AtomicReference<>();
        AtomicReference<EditorRegistrationEvent> retained = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            retained.set(event);
            builder.set(event.registry());
            contribute(event.registry());
            event.registry().freeze();
        }, cleanups::incrementAndGet);
        assertThrows(IllegalStateException.class, platform::collect);
        assertFailed(platform);
        assertEquals(1, cleanups.get());
        assertClosed(retained.get(), builder.get());
    }

    @Test
    void subscriberDiscardCannotBePublishedAsSuccessfulEmptyRegistration() {
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            contribute(event.registry());
            event.registry().discard();
        }, cleanups::incrementAndGet);
        assertThrows(IllegalStateException.class, platform::collect);
        assertEquals(1, cleanups.get());
        assertFailed(platform);
    }

    @Test
    void repeatedCollectDoesNotBroadcastCleanOrOverwriteReadyRegistry() {
        AtomicInteger broadcasts = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            broadcasts.incrementAndGet();
            contribute(event.registry());
        }, cleanups::incrementAndGet);
        platform.collect();
        ExtensionRegistry registry = platform.registry();
        assertThrows(IllegalStateException.class, platform::collect);
        assertSame(registry, platform.registry());
        assertThrows(IllegalStateException.class, () -> platform.attach(bus(event -> { })));
        assertEquals(1, broadcasts.get());
        assertEquals(1, cleanups.get());
        assertEquals(ForgeEditorPlatform.State.READY, platform.state());
    }

    @Test
    void failedCollectionCannotBeRetried() {
        AtomicInteger broadcasts = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            broadcasts.incrementAndGet();
            throw new IllegalArgumentException("first attempt");
        }, cleanups::incrementAndGet);
        assertThrows(IllegalArgumentException.class, platform::collect);
        assertThrows(IllegalStateException.class, platform::collect);
        assertFailed(platform);
        assertEquals(1, broadcasts.get());
        assertEquals(1, cleanups.get());
    }

    @Test
    void reentrantCollectCannotBroadcastOrCleanTheOuterScope() {
        AtomicReference<ForgeEditorPlatform> reference = new AtomicReference<>();
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            assertThrows(IllegalStateException.class, reference.get()::collect);
            assertEquals(0, cleanups.get());
            contribute(event.registry());
        }, cleanups::incrementAndGet);
        reference.set(platform);
        platform.collect();
        assertEquals(1, cleanups.get());
        assertEquals(42, platform.registry().find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
    }

    @Test
    void nonOwnerEventAccessFailsWhileOwnerMayContinueCollecting() {
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, event::registry);
                assertThrows(IllegalStateException.class, event::environment);
            }).join();
            assertEquals(COMMON, event.environment());
            contribute(event.registry());
        }, () -> { });
        platform.collect();
        assertEquals(42, platform.registry().find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
    }

    @Test
    void eventAndRetainedBuilderAreAlreadyClosedWhenContextCleanupRuns() {
        AtomicReference<EditorRegistrationEvent> event = new AtomicReference<>();
        AtomicReference<ExtensionRegistryBuilder> builder = new AtomicReference<>();
        ForgeEditorPlatform platform = platform(COMMON, current -> {
            event.set(current);
            builder.set(current.registry());
            contribute(current.registry());
        }, () -> assertClosed(event.get(), builder.get()));
        platform.collect();
        assertClosed(event.get(), builder.get());
        assertEquals(42, platform.registry().find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
    }

    @Test
    void readyResultCanBeReadAndUsedFromAnotherThread() {
        ForgeEditorPlatform platform = platform(COMMON, event -> contribute(event.registry()), () -> { });
        platform.collect();
        ExtensionRegistry registry = platform.registry();
        CompletableFuture.runAsync(() -> {
            assertEquals(ForgeEditorPlatform.State.READY, platform.state());
            assertSame(registry, platform.registry());
            assertEquals(42, platform.registry().find(KIND, INCREMENT).orElseThrow().applyAsInt(41));
        }).join();
    }

    @Test
    void actualLifecycleSubscriptionFailureRevokesHostAfterNullRejection() {
        ForgeEditorPlatform platform = platform(COMMON, event -> { }, () -> { });
        assertThrows(NullPointerException.class, () -> platform.attach(null));
        assertEquals(ForgeEditorPlatform.State.NEW, platform.state());
        IEventBus ownerBus = bus(event -> { });
        // This JVM has no ModLauncher transformation for Forge's own lifecycle event.
        RuntimeException failure = assertThrows(RuntimeException.class, () -> platform.attach(ownerBus));
        assertTrue(failure.getMessage().contains("FMLLoadCompleteEvent"));
        assertTrue(failure.getCause() instanceof NoSuchMethodException);
        assertFailed(platform);
        assertThrows(IllegalStateException.class, () -> platform.attach(ownerBus));
        assertThrows(IllegalStateException.class, platform::collect);
    }

    @Test
    void frameworkPlaceholderNeverGrantsRegistrationAccess() {
        EditorRegistrationEvent placeholder = new EditorRegistrationEvent();
        assertThrows(IllegalStateException.class, placeholder::registry);
        assertThrows(IllegalStateException.class, placeholder::environment);
        assertFalse(placeholder.isCancelable());
        assertFalse(placeholder.hasResult());
        placeholder.invalidate();
        assertThrows(IllegalStateException.class, placeholder::registry);
    }

    private static void assertDispatchFailure(Throwable failure) {
        List<String> reached = new ArrayList<>();
        AtomicReference<EditorRegistrationEvent> retained = new AtomicReference<>();
        AtomicReference<ExtensionRegistryBuilder> builder = new AtomicReference<>();
        IEventBus first = bus(event -> {
            reached.add("first");
            retained.set(event);
            builder.set(event.registry());
            contribute(event.registry());
        });
        IEventBus failing = bus(event -> {
            reached.add("failing");
            rethrow(failure);
        });
        listen(failing, event -> reached.add("same-bus-late"));
        IEventBus later = bus(event -> reached.add("later-bus"));
        AtomicInteger cleanups = new AtomicInteger();
        ForgeEditorPlatform platform = platform(COMMON, event -> {
            first.post(event);
            failing.post(event);
            later.post(event);
        }, cleanups::incrementAndGet);
        assertSame(failure, assertThrows(failure.getClass(), platform::collect));
        assertEquals(List.of("first", "failing"), reached);
        assertEquals(1, cleanups.get());
        assertFailed(platform);
        assertClosed(retained.get(), builder.get());
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("invalid test fixture", failure);
    }

    private static void assertFailed(ForgeEditorPlatform platform) {
        assertEquals(ForgeEditorPlatform.State.FAILED, platform.state());
        assertThrows(IllegalStateException.class, platform::registry);
    }

    private static void assertClosed(EditorRegistrationEvent event, ExtensionRegistryBuilder builder) {
        assertThrows(IllegalStateException.class, event::registry);
        assertThrows(IllegalStateException.class, event::environment);
        assertThrows(IllegalStateException.class, () -> builder.define(KIND));
        assertThrows(IllegalStateException.class, () -> builder.addModule(module()));
        assertThrows(IllegalStateException.class, () -> builder.add(KIND, contribution(INCREMENT, COMMON), operand -> operand));
        assertThrows(IllegalStateException.class, builder::freeze);
    }

    private static ForgeEditorPlatform platform(Contribution.Side side,
            Consumer<EditorRegistrationEvent> broadcast, Runnable cleanup) {
        return new ForgeEditorPlatform(API, side, broadcast, cleanup, () -> true);
    }

    private static IEventBus bus(Consumer<EditorRegistrationEvent> listener) {
        IEventBus bus = BusBuilder.builder().setTrackPhases(false).markerType(IModBusEvent.class)
                .setExceptionHandler((eventBus, event, listeners, index, throwable) -> { }).build();
        listen(bus, listener);
        return bus;
    }

    private static void listen(IEventBus bus, Consumer<EditorRegistrationEvent> listener) {
        bus.addListener(EventPriority.NORMAL, false, EditorRegistrationEvent.class, listener);
    }

    private static void contribute(ExtensionRegistryBuilder builder) {
        builder.define(KIND);
        builder.addModule(module());
        builder.add(KIND, contribution(INCREMENT, COMMON), operand -> operand + 1);
    }

    private static ModuleSpec module() {
        return new ModuleSpec(OWNER, "7.11.13-build-test", API, Set.of(), Set.of());
    }

    private static Contribution contribution(EditorId id, Contribution.Side side) {
        return new Contribution(new ContributionKey(KIND.id(), id), OWNER, side, Set.of());
    }
}
