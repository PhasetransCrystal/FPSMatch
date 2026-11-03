package net.ptcrys.fpsmatch.editor.core.worldaccess;

import java.util.Objects;

/** Stable local binding failure classifications for caller translation. */
public final class WorldBindingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        WRONG_THREAD,
        CLOSED,
        BUSY,
        INACTIVE,
        INVALID_TARGET,
        DISCONNECTED,
        PERMISSION_DENIED,
        DIMENSION_UNAVAILABLE,
        STALE,
        FOREIGN,
        LIMIT
    }

    private final Code code;

    public WorldBindingException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
