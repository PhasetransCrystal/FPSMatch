package net.ptcrys.fpsmatch.editor.common.host.minecraft.v1201.item;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.Objects;

/** An unresolved native item diagnostic; the exception message is only its technical code. */
public final class NativeItemException extends IllegalStateException {

    private static final long serialVersionUID = 1L;
    private final transient Problem problem;

    public NativeItemException(Problem problem) {
        super(requireError(problem).code().toString());
        this.problem = problem;
    }

    public Problem problem() {
        return problem;
    }

    private static Problem requireError(Problem problem) {
        Objects.requireNonNull(problem, "problem");
        if (problem.severity() != Problem.Severity.ERROR) {
            throw new IllegalArgumentException("a native item failure needs an ERROR problem");
        }
        return problem;
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized native item failure carries no diagnostics");
    }
}
