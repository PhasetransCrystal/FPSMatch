package net.ptcrys.fpsmatch.editor.common.domain.weapon;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceRef;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Pure gun state; resource references describe selections, not authenticated asset bytes. */
public record TaczGun(EditorId gunId, ResourceRef gunResource,
                      Optional<EditorId> displayId, Optional<ResourceRef> displayResource,
                      int count, FireMode fireMode, boolean bulletInBarrel, int ammoCount,
                      OptionalInt dummyAmmo, OptionalInt maxDummyAmmo) {

    public enum FireMode {
        AUTO,
        SEMI,
        BURST
    }

    public TaczGun {
        Objects.requireNonNull(gunId, "gunId");
        Objects.requireNonNull(gunResource, "gunResource");
        Objects.requireNonNull(displayId, "displayId");
        Objects.requireNonNull(displayResource, "displayResource");
        Objects.requireNonNull(fireMode, "fireMode");
        Objects.requireNonNull(dummyAmmo, "dummyAmmo");
        Objects.requireNonNull(maxDummyAmmo, "maxDummyAmmo");
        if (gunId.equals(EditorId.parse("tacz:empty"))) throw new IllegalArgumentException("gunId");
        if (count < 1 || count > 127) throw new IllegalArgumentException("count");
        if (ammoCount < 0 || (dummyAmmo.isPresent() && dummyAmmo.getAsInt() < 0) || (maxDummyAmmo.isPresent() && maxDummyAmmo.getAsInt() < 0)) {
            throw new IllegalArgumentException("nonnegative ammunition required");
        }
        boolean explicitDisplay = displayId.isPresent() && !displayId.get().equals(EditorId.parse("tacz:default"));
        if (explicitDisplay != displayResource.isPresent()) {
            throw new IllegalArgumentException("display resource must match display identity");
        }
    }
}
