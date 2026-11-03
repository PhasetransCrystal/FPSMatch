package net.ptcrys.fpsmatch.editor.common.backend.tacz.script;

import java.io.IOException;
import java.util.Objects;

/** Structured content failure; technical detail is intended for diagnostics only. */
public final class ScriptValidationException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        SOURCE_LIMIT,
        INVALID_SOURCE,
        INVALID_NAME,
        UNKNOWN_MODULE,
        CYCLIC_REQUIRE,
        INVALID_RESULT,
        SCRIPT_ERROR,
        INSTRUCTION_LIMIT,
        CANCELLED
    }

    private final Reason reason;
    private final String moduleName;

    public ScriptValidationException(Reason reason, String moduleName, String technicalDetail) {
        super(Objects.requireNonNull(technicalDetail, "technicalDetail"));
        this.reason = Objects.requireNonNull(reason, "reason");
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName");
    }

    public Reason reason() {
        return reason;
    }

    public String moduleName() {
        return moduleName;
    }
}
