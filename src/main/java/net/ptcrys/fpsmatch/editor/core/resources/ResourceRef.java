package net.ptcrys.fpsmatch.editor.core.resources;

import java.util.Objects;
import java.util.UUID;

/** A stable resource identity locked to the exact version a caller depends on. */
public record ResourceRef(UUID resourceId, UUID versionId) {

    public ResourceRef {
        Objects.requireNonNull(resourceId, "resourceId");
        Objects.requireNonNull(versionId, "versionId");
    }
}
