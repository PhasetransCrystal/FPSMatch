package net.ptcrys.fpsmatch.editor.core.packio;

import java.io.IOException;
import java.util.Objects;

public final class PackSnapshotException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_SOURCE,
        INVALID_PATH,
        DUPLICATE_PATH,
        BUDGET,
        SOURCE_CHANGED,
        INVALID_ARCHIVE,
        CONTENT_MISMATCH,
        CANCELLED
    }

    private final Code code;

    public PackSnapshotException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public PackSnapshotException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
