package net.ptcrys.fpsmatch.editor.core.documents;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.api.TextContent;

import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.util.List;
import java.util.Objects;

/**
 * Rejection of an operation that cannot be applied to the current session state.
 *
 * <p>
 * A rejection never changes the draft value, the token, the history or the dirty flag. It carries
 * the reason as a non-empty, immutable list of {@link Problem}s: a stable
 * {@code editor:document/...} code, a target that names the document, and an unresolved
 * {@link TextContent.Message} with its own translation key and typed arguments. Presentation layers
 * resolve those keys against this module's {@code lang} resources; {@link #getMessage()} repeats the
 * first code as a technical locator only and must neither be parsed nor shown as product text.
 * </p>
 *
 * <p>
 * A rejection produced by domain validation keeps the complete diagnostic list of that validation
 * run in its original order - including WARNING and INFO entries, with their own codes, targets,
 * messages and nested arguments - instead of replacing it with a single summary sentence.
 * </p>
 */
public final class DocumentRejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /**
     * The complete diagnostics of the rejected operation, in the order the producer reported them.
     *
     * <p>
     * Transient because {@link Problem} is not serializable. A serialized rejection would
     * therefore have lost its reason, so deserialization is refused instead of handing out an
     * exception whose {@link #problems()} could not honour its contract.
     * </p>
     */
    private final transient List<Problem> problems;

    /**
     * Creates a rejection from {@code problems}; not public on purpose, so that only this module
     * decides what a rejection says.
     *
     * @throws NullPointerException     if {@code problems}, or any element, is null
     * @throws IllegalArgumentException if {@code problems} is empty or holds no ERROR
     */
    DocumentRejection(List<Problem> problems) {
        super(firstCode(problems));
        this.problems = List.copyOf(problems);
    }

    /**
     * Returns the complete diagnostics of this rejection, in the producer's original order.
     *
     * <p>
     * The list is immutable, non-empty, and always contains at least one ERROR; it was copied on
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
            throw new IllegalArgumentException("a rejection needs at least one problem");
        }
        boolean hasError = false;
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
            hasError |= problem.severity() == Problem.Severity.ERROR;
        }
        if (!hasError) {
            throw new IllegalArgumentException("a rejection needs at least one ERROR problem");
        }
    }

    private void readObject(ObjectInputStream stream) throws InvalidObjectException {
        throw new InvalidObjectException("a serialized document rejection carries no diagnostics");
    }
}
