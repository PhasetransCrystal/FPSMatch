package net.ptcrys.fpsmatch.editor.core.worldio;

import java.io.IOException;

/** A stable classification of a local world identity operation failure. */
public final class WorldIdentityException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        MISSING,
        ALREADY_EXISTS,
        CORRUPT,
        INVALID_ROOT,
        CHANGED,
        CANCELLED,
        UNSUPPORTED,
        IO_FAILURE,
        UNKNOWN
    }

    private final Code code;

    WorldIdentityException(Code code, String message) {
        super(message);
        this.code = code;
    }

    WorldIdentityException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
