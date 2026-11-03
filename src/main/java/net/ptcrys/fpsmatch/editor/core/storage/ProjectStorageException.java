package net.ptcrys.fpsmatch.editor.core.storage;

import java.io.IOException;

/** Stable diagnostic codes; presentation and translation belong to the calling application. */
public final class ProjectStorageException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        INVALID_FORMAT,
        UNSUPPORTED_FORMAT,
        LIMIT_EXCEEDED,
        CORRUPT_CONTENT,
        CONFLICT,
        BUSY,
        INVALID_PATH,
        MISSING_PROJECT,
        ATOMIC_MOVE_UNSUPPORTED,
        RESULT_UNKNOWN,
        REQUEST_MISMATCH,
        CLOSED
    }

    private final Code code;

    public ProjectStorageException(Code code, String diagnostic) {
        super(diagnostic);
        this.code = code;
    }

    public ProjectStorageException(Code code, String diagnostic, Throwable cause) {
        super(diagnostic, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
