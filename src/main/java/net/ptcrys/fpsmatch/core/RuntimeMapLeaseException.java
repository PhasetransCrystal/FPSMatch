package net.ptcrys.fpsmatch.core;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Locale;

/** Unresolved runtime-map diagnostic; the message is only its technical code. */
public final class RuntimeMapLeaseException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        WRONG_THREAD,
        CLOSED,
        INVALID_INPUT,
        INACTIVE,
        DUPLICATE,
        BUDGET,
        BUSY,
        OWNED_DEFINITION,
        PERSISTENT_OWNERSHIP,
        RECOVERY_REQUIRED
    }

    private final transient Problem problem;

    public RuntimeMapLeaseException(Reason reason) {
        this(problem(reason));
    }

    private RuntimeMapLeaseException(Problem problem) {
        super(problem.code().toString());
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }

    static RuntimeMapLeaseException error(Reason reason) {
        return new RuntimeMapLeaseException(reason);
    }

    private static Problem problem(Reason reason) {
        String key = reason.name().toLowerCase(Locale.ROOT);
        return new Problem(Problem.Severity.ERROR, new EditorId("editor", "runtime_map/" + key),
                "runtime_map", new TextContent.Message("editor.runtime_map." + key, List.of()));
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized runtime map failure carries no diagnostics");
    }
}
