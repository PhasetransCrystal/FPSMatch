package net.ptcrys.fpsmatch.editor.core.types;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Objects;

/**
 * Signals that a codec could not decode the bytes it was given.
 *
 * <p>
 * A third-party codec uses this exception to report damaged content, an unreadable schema version
 * or any other condition a caller can translate, without inventing English sentences that a
 * presentation layer would have to parse. The reason travels as a non-empty, immutable list of
 * {@link Problem}s in the producer's original order, each with its own code, target and unresolved
 * message. The codes and language keys belong to the reporting module; this module fabricates no
 * unified business wording of its own.
 * </p>
 *
 * <p>
 * {@link #getMessage()} repeats the first code as a technical locator only and must neither be
 * parsed nor shown as product text.
 * </p>
 *
 * <p>
 * {@link Problem} is not serializable, so this exception refuses deserialization instead of
 * producing an object whose {@link #problems()} could not honour its contract.
 * </p>
 */
public final class DocumentCodecException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * The complete diagnostics of the failed decode, in the order the producer reported them.
     *
     * <p>
     * Transient because {@link Problem} is not serializable: a serialized instance would have lost
     * its reason, which is why deserialization is rejected.
     * </p>
     */
    private final transient List<Problem> problems;

    /**
     * Creates the exception from {@code problems}.
     *
     * @param problems the diagnostics of the failed decode, copied and kept in their original order
     * @throws NullPointerException     if {@code problems} or any element is null
     * @throws IllegalArgumentException if {@code problems} is empty or holds no ERROR
     */
    public DocumentCodecException(List<Problem> problems) {
        super(firstCode(problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the complete diagnostics of this failure, in the producer's original order.
     *
     * <p>
     * The list is immutable, non-empty and always contains at least one ERROR; it was copied on
     * construction, so changing whatever list the producer used has no effect here.
     * </p>
     */
    public List<Problem> problems() {
        return problems;
    }

    private static String firstCode(List<Problem> problems) {
        requireDiagnostics(problems);
        return problems.get(0).code().toString();
    }

    private static void requireDiagnostics(List<Problem> problems) {
        Objects.requireNonNull(problems, "problems");
        if (problems.isEmpty()) {
            throw new IllegalArgumentException("a codec failure needs at least one problem");
        }
        boolean hasError = false;
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
            hasError |= problem.severity() == Problem.Severity.ERROR;
        }
        if (!hasError) {
            throw new IllegalArgumentException("a codec failure needs at least one ERROR problem");
        }
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized codec failure carries no diagnostics");
    }
}
