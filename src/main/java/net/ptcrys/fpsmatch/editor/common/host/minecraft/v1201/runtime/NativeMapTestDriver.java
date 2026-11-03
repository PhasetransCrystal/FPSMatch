package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.core.map.BaseMap;
import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionKind;
import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Trusted native lifecycle for testing one installed, fixed version. Not a sandbox. */
public record NativeMapTestDriver<M extends BaseMap>(Class<M> mapType,
                                                     BiFunction<M, ServerPlayer, List<Problem>> preflight,
                                                     BiPredicate<M, ServerPlayer> join, GuardedStart<M> start, Consumer<M> stop) {

    /** Invoke each guard once: after start events before spawn, then after clear events before delivery. */
    @FunctionalInterface
    public interface GuardedStart<M extends BaseMap> {

        boolean start(M map, ServerPlayer actor, Runnable ready, Runnable beforeDelivery);
    }

    public static final ExtensionKind<NativeMapTestDriver<?>> KIND = new ExtensionKind<>(
            EditorId.parse("editor:native_map_test_driver"), driverClass());

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Class<NativeMapTestDriver<?>> driverClass() {
        return (Class) NativeMapTestDriver.class;
    }

    public NativeMapTestDriver {
        Objects.requireNonNull(mapType);
        Objects.requireNonNull(preflight);
        Objects.requireNonNull(join);
        Objects.requireNonNull(start);
        Objects.requireNonNull(stop);
    }

    /** The id is exactly the associated factory id; registration must share its owner. */
    public static void register(ExtensionRegistryBuilder builder, EditorId factoryId, EditorId owner,
                                NativeMapTestDriver<?> driver) {
        builder.define(KIND);
        builder.add(KIND, new Contribution(new ContributionKey(KIND.id(), factoryId), owner,
                Contribution.Side.COMMON, Set.of(new ContributionKey(NativeMapFactories.KIND.id(), factoryId))), driver);
    }

    List<Problem> inspect(BaseMap map, ServerPlayer actor) {
        return List.copyOf(preflight.apply(mapType.cast(map), actor));
    }

    boolean begin(BaseMap map, ServerPlayer actor, Runnable ready, Runnable beforeDelivery) {
        return start.start(mapType.cast(map), actor, ready, beforeDelivery);
    }

    boolean admit(BaseMap map, ServerPlayer actor) {
        return join.test(mapType.cast(map), actor);
    }

    void end(BaseMap map) {
        stop.accept(mapType.cast(map));
    }
}
