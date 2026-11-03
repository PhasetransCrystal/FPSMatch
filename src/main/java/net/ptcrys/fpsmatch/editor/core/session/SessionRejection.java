package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Objects;

/**
 * Rejection of an owner-domain operation that cannot be applied to the frozen registry state.
 *
 * <p>
 * The reason travels as a non-empty, immutable list of {@link Problem}s in the producer's original
 * order: every element keeps its own stable {@code editor:session/...} code, its target and its
 * unresolved message with typed arguments. A whole activation pass may therefore report several
 * unusable fields of one document type in one rejection instead of a summary sentence.
 * </p>
 *
 * <p>
 * {@link #getMessage()} repeats the first code as a technical locator only and must neither be
 * parsed nor shown as product text. It is <em>not</em> a substitute for the producer's own exception:
 * when a rejection was caused by a failed third-party call, that original throwable is handed to
 * {@code super} as the cause and stays readable through {@link Throwable#getCause()}, so the raw
 * failure is preserved for technical mapping while no diagnostic of it is turned into product
 * wording.
 * </p>
 *
 * <p>
 * This is a state rejection, not an argument check: a caller that was handed a document type which
 * cannot be used here has not passed a wrong argument, it has met a frozen registry it cannot work
 * with. A null or malformed {@code problems} list, by contrast, is a programming error of the producer
 * and stays an {@link IllegalArgumentException} from the constructor instead of being dressed up as a
 * rejection.
 * </p>
 *
 * <p>
 * {@link Problem} is not serializable, so this exception refuses deserialization instead of
 * producing an object whose {@link #problems()} could not honour its contract.
 * </p>
 */
public final class SessionRejection extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * The complete diagnostics of the rejected operation, in the order the producer reported them.
     *
     * <p>
     * Transient because {@link Problem} is not serializable: a serialized rejection would have
     * lost its reason, which is why deserialization is rejected.
     * </p>
     */
    private final transient List<Problem> problems;

    /**
     * Creates a rejection from a complete problem list without a technical cause.
     *
     * @throws NullPointerException     if {@code problems} or any element is null
     * @throws IllegalArgumentException if {@code problems} is empty or holds no ERROR
     */
    public SessionRejection(List<Problem> problems) {
        this(problems, null);
    }

    /**
     * Creates a rejection from {@code problems} and keeps {@code cause} as the technical origin.
     *
     * <p>
     * The cause is never inspected, rewritten or rendered here: a failed codec call is reported
     * through this diagnostics list and its own exception stays attached unchanged. A null cause is
     * the normal case for a rejection this module detected itself.
     * </p>
     *
     * @throws NullPointerException     if {@code problems} or any element is null
     * @throws IllegalArgumentException if {@code problems} is empty or holds no ERROR
     */
    public SessionRejection(List<Problem> problems, Throwable cause) {
        super(firstCode(problems), cause);
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the complete diagnostics of this rejection, in the producer's original order.
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
            throw new IllegalArgumentException("a session rejection needs at least one problem");
        }
        boolean hasError = false;
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
            hasError |= problem.severity() == Problem.Severity.ERROR;
        }
        if (!hasError) {
            throw new IllegalArgumentException("a session rejection needs at least one ERROR problem");
        }
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized session rejection carries no diagnostics");
    }
}
