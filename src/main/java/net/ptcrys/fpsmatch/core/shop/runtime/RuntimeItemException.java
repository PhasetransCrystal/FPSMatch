package net.ptcrys.fpsmatch.core.shop.runtime;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.Objects;

/** Unresolved runtime-item diagnostic; the message contains only the technical code. */
public final class RuntimeItemException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private final transient Problem problem;

    public RuntimeItemException(Problem problem) {
        super(requireError(problem).code().toString());
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }

    private static Problem requireError(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        if (problem.severity() != Problem.Severity.ERROR) {
            throw new IllegalArgumentException("a runtime item failure needs an ERROR problem");
        }
        return problem;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized runtime item failure carries no diagnostics");
    }
}
