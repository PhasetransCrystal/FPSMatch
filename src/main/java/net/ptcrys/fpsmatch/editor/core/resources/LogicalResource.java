package net.ptcrys.fpsmatch.editor.core.resources;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;
import java.util.UUID;

/**
 * The stable identity of a logical resource. The name is presentation data and may change
 * without changing this identity or any version identity.
 */
public record LogicalResource(UUID id, EditorId type, String name, UUID currentVersion) {

    public LogicalResource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(currentVersion, "currentVersion");
    }
}
