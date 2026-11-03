package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.runtime;

import net.ptcrys.fpsmatch.core.map.BaseMap;

import java.util.Objects;

/** Unpublished construction result; the host transfers it to a core retirement lease. */
public record NativeMapBuild(BaseMap map, Runnable afterConsumers, Runnable verify, Runnable beforeRetirement) {

    /** Factories without additional busy resources need no retirement readiness callback. */
    public NativeMapBuild(BaseMap map, Runnable afterConsumers, Runnable verify) {
        this(map, afterConsumers, verify, () -> {});
    }

    public NativeMapBuild {
        Objects.requireNonNull(map, "map");
        Objects.requireNonNull(afterConsumers, "afterConsumers");
        Objects.requireNonNull(verify, "verify");
        Objects.requireNonNull(beforeRetirement, "beforeRetirement");
    }
}
