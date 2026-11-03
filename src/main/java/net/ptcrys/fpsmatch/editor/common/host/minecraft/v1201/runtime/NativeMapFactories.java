package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.core.FPSMCore;
import net.ptcrys.fpsmatch.core.RuntimeMapLease;
import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.core.map.MapConstructionScope;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings.ConfirmedTarget;
import net.ptcrys.fpsmatch.editor.core.session.PreparedRuntime;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeAdapter;
import net.ptcrys.fpsmatch.editor.core.session.SavedRuntimeAdapters;
import net.ptcrys.fpsmatch.editor.core.types.DocumentCodecException;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBinding;
import net.ptcrys.fpsmatch.editor.core.worldaccess.WorldBindingCheckResult;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Shared registration and retirement handoff for this target. No domain-specific branches. */
public final class NativeMapFactories {

    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static final ExtensionKind<NativeMapFactory<?>> KIND = new ExtensionKind(
            EditorId.parse("editor:native_map_factory"), NativeMapFactory.class);

    private NativeMapFactories() {}

    public static void register(ExtensionRegistryBuilder builder, EditorId id, EditorId owner,
                                NativeMapFactory<?> factory) {
        Objects.requireNonNull(factory, "factory");
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), id), owner,
                Contribution.Side.COMMON, Set.of(new ContributionKey(
                        SavedRuntimeAdapters.KIND.id(), factory.adapterId()))),
                factory);
    }

    public static Candidate construct(ExtensionRegistry registry, EditorId id, PreparedRuntime<?> prepared,
                                      FPSMCore core, WorldBinding binding, WorldBindingCheckResult check,
                                      String runtimeName, BooleanSupplier cancelled) {
        return construct(registry, id, prepared, core, binding, check, runtimeName, cancelled, null);
    }

    static Candidate construct(ExtensionRegistry registry, EditorId id, PreparedRuntime<?> prepared,
                               FPSMCore core, WorldBinding binding, WorldBindingCheckResult check,
                               String runtimeName, BooleanSupplier cancelled,
                               MinecraftRuntimeInstallations.Attempt installation) {
        if (core == null) throw failure("invalid_input");
        if (!core.getServer().isSameThread()) throw failure("wrong_thread");
        if (registry == null || id == null || prepared == null || binding == null || check == null || cancelled == null || !validName(runtimeName)) throw failure("invalid_input");
        if (!prepared.preparedWith(registry)) throw failure("preparation_mismatch");
        NativeMapFactory<?> factory = registered(registry, KIND, id);
        if (factory == null) throw failure("unavailable");
        ContributionKey adapterKey = new ContributionKey(SavedRuntimeAdapters.KIND.id(), factory.adapterId());
        Contribution descriptor = registry.metadata().contributions().get(new ContributionKey(KIND.id(), id));
        Contribution adapterDescriptor = registry.metadata().contributions().get(adapterKey);
        SavedRuntimeAdapter<?, ?> adapter = registered(registry, SavedRuntimeAdapters.KIND, factory.adapterId());
        if (descriptor == null || adapterDescriptor == null || adapter == null || descriptor.side() != Contribution.Side.COMMON || !descriptor.owner().equals(adapterDescriptor.owner()) || !descriptor.requiredContributions().contains(adapterKey)) throw failure("invalid_registration");
        if (!prepared.adapterId().equals(factory.adapterId()) || !factory.preparationType().isInstance(prepared.value())) throw failure("preparation_mismatch");
        Cancellation cancellation = new Cancellation(cancelled);
        cancellation.check();
        Class<?> resultType = adapter.resultType();
        cancellation.check();
        if (resultType != factory.preparationType()) throw failure("invalid_registration");
        Set<EditorId> dimensions = dimensions(factory, prepared.value(), cancellation);
        var scoreboard = core.getServer().getScoreboard();
        Constructed constructed = MapConstructionScope.construct(() -> core.withConfirmedWorld(binding, check,
                dimensions, target -> {
                    cancellation.check();
                    if (installation != null) installation.begin(target);
                    cancellation.check();
                    NativeMapBuild result = call(factory, prepared.value(), target, runtimeName, cancellation);
                    cancellation.check();
                    if (result == null) throw failure("invalid_result");
                    BaseMap map = result.map();
                    checkMap(map, core, target, dimensions, runtimeName);
                    if (installation != null) installation.checkMap(map);
                    // Resolve overridable getters while scope rollback still owns all resources,
                    // before the last cancellation and complete domain verification.
                    var capabilities = map.getCapabilityMap();
                    var teams = map.getMapTeams();
                    if (capabilities == null || teams == null) throw failure("invalid_result");
                    Constructed captured = new Constructed(result, capabilities::close,
                            () -> teams.shutdown(scoreboard));
                    cancellation.check();
                    // Cancellation and native observation hooks may have changed earlier fields.
                    // The domain verifies after the final cancellation callback; repeat the host's
                    // own invariants afterwards rather than trusting that extension's assertion.
                    result.verify().run();
                    checkMap(map, core, target, dimensions, runtimeName);
                    if (installation != null) installation.checkMap(map);
                    target.verify();
                    return captured;
                }));
        NativeMapBuild build = constructed.build();
        RuntimeMapLease lease;
        try {
            lease = core.ownRuntimeMap(build.map(), build.afterConsumers(), build.beforeRetirement());
        } catch (RuntimeException | Error failure) {
            cleanup(constructed.closeCapabilities(), failure);
            cleanup(constructed.closeTeams(), failure);
            cleanup(build.afterConsumers(), failure);
            throw failure;
        }
        try {
            return new Candidate(build.map(), prepared, lease);
        } catch (RuntimeException | Error failure) {
            cleanup(lease::close, failure);
            throw failure;
        }
    }

    private record Constructed(NativeMapBuild build, Runnable closeCapabilities, Runnable closeTeams) {}

    private static <T> T registered(ExtensionRegistry registry, ExtensionKind<T> kind, EditorId id) {
        try {
            return registry.defines(kind) ? registry.find(kind, id).orElse(null) : null;
        } catch (IllegalArgumentException mismatch) {
            // Only frozen registry lookup is inside this guard; extension exceptions retain identity.
            throw failure("invalid_registration");
        }
    }

    private static void checkMap(BaseMap map, FPSMCore core, ConfirmedTarget target,
                                 Set<EditorId> dimensions, String runtimeName) {
        if (map.hasRuntimeOwnership() || map.getCapabilityMap().isClosed() || !runtimeName.equals(map.getMapName())) throw failure("invalid_result");
        var level = map.getServerLevel();
        boolean selected = false;
        for (EditorId dimension : dimensions) if (target.level(dimension) == level) selected = true;
        if (!selected) throw failure("invalid_result");
        for (List<BaseMap> maps : core.getAllMaps().values()) {
            for (BaseMap published : maps) if (published == map) throw failure("invalid_result");
        }
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= 128 && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static <P> Set<EditorId> dimensions(NativeMapFactory<P> factory, Object prepared,
                                                Cancellation cancellation) {
        cancellation.check();
        Set<EditorId> offered = factory.requiredDimensions().apply(factory.preparationType().cast(prepared));
        cancellation.check();
        if (offered == null) throw failure("invalid_result");
        Set<EditorId> result = new HashSet<>();
        for (EditorId dimension : offered) {
            if (dimension == null || result.size() == 64 || !result.add(dimension)) throw failure("invalid_result");
        }
        if (result.isEmpty()) throw failure("invalid_result");
        cancellation.check();
        return Set.copyOf(result);
    }

    private static <P> NativeMapBuild call(NativeMapFactory<P> factory, Object value,
                                           net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings.ConfirmedTarget target,
                                           String name, BooleanSupplier cancelled) {
        return factory.constructor().construct(factory.preparationType().cast(value), target, name, cancelled);
    }

    private static void cleanup(Runnable action, Throwable primary) {
        try {
            action.run();
        } catch (RuntimeException | Error cleanup) {
            if (cleanup != primary) primary.addSuppressed(cleanup);
        }
    }

    private static DocumentCodecException failure(String reason) {
        return new DocumentCodecException(List.of(new Problem(Problem.Severity.ERROR,
                EditorId.parse("editor:native_map/" + reason), "runtime_map",
                new TextContent.Message("fpsmatch.editor.native_map." + reason, List.of()))));
    }

    private static final class Cancellation implements BooleanSupplier {

        private final BooleanSupplier delegate;
        private boolean seen;
        private Throwable callbackFailure;

        Cancellation(BooleanSupplier delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean getAsBoolean() {
            if (callbackFailure instanceof RuntimeException failure) throw failure;
            if (callbackFailure instanceof Error failure) throw failure;
            if (!seen) {
                try {
                    seen = delegate.getAsBoolean();
                } catch (RuntimeException | Error failure) {
                    callbackFailure = failure;
                    throw failure;
                }
            }
            return seen;
        }

        void check() {
            if (getAsBoolean()) throw failure("cancelled");
        }
    }

    /** A constructed map handle. Construction alone does not install; close and core stop share retirement. */
    public static final class Candidate implements AutoCloseable {

        private final BaseMap map;
        private final PreparedRuntime<?> preparation;
        private final RuntimeMapLease lease;

        private Candidate(BaseMap map, PreparedRuntime<?> preparation, RuntimeMapLease lease) {
            this.map = map;
            this.preparation = preparation;
            this.lease = lease;
        }

        public BaseMap map() {
            lease.requireOpen();
            return map;
        }

        public PreparedRuntime<?> preparation() {
            lease.requireOpen();
            return preparation;
        }

        public RuntimeMapLease lease() {
            lease.requireOpen();
            return lease;
        }

        // Failure withdrawal must also work when a callback has already retired this handle.
        void withdrawFrom(FPSMCore core) {
            core.withdrawInstalledRuntimeMap(map, lease);
        }

        @Override
        public void close() {
            lease.close();
        }
    }
}
