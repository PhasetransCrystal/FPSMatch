package net.ptcrys.fpsmatch.editor.core.session;

import java.util.Objects;
import java.util.UUID;

/** Identifies one revision of the resource scope of an open project session. */
public record ResourceToken(UUID sessionId, long revision) {

    public ResourceToken {
        Objects.requireNonNull(sessionId, "sessionId");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }
}
