package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.world.MinecraftWorldBindings.ConfirmedTarget;

import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Trusted target-specific construction from one immutable saved preparation. */
public record NativeMapFactory<P>(EditorId adapterId, Class<P> preparationType,
                                  Function<P, Set<EditorId>> requiredDimensions,
                                  Constructor<P> constructor) {

    public NativeMapFactory {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(preparationType, "preparationType");
        Objects.requireNonNull(requiredDimensions, "requiredDimensions");
        Objects.requireNonNull(constructor, "constructor");
        if (preparationType.isPrimitive()) throw new IllegalArgumentException("Reference preparation type required");
    }

    @FunctionalInterface
    public interface Constructor<P> {

        /**
         * Runs inside the caller's construction scope. Acquire shared resources before consumers,
         * and return their final retirement action. Never retain the target, write files or publish
         * into GAMES. Arbitrary Java side effects are not transactional or sandboxed.
         */
        NativeMapBuild construct(P prepared, ConfirmedTarget target, String runtimeName,
                                 BooleanSupplier cancelled);
    }
}
