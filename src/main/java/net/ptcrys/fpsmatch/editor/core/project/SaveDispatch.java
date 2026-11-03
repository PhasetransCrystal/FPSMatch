package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The queue's answer to {@link ProjectSaveQueue#dispatch()}.
 *
 * <p>
 * Exactly one of two shapes is possible. With an attempt, the request left the queue and the
 * caller must publish it; problems may accompany it but must never contain an
 * {@link Problem.Severity#ERROR}, and the attempt's request id is the dispatch request id. Without
 * an attempt, the head was rejected before any I/O and at least one {@code ERROR} problem explains
 * why; the request id still identifies the request that was dropped.
 */
public record SaveDispatch(UUID requestId, Optional<SaveAttempt> attempt, List<Problem> problems) {

    public SaveDispatch {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(attempt, "attempt");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
        }
        boolean rejected = problems.stream().anyMatch(SaveDispatch::isError);
        if (attempt.isPresent()) {
            if (rejected) {
                throw new IllegalArgumentException("a dispatchable attempt cannot carry an error problem");
            }
            if (!requestId.equals(attempt.get().requestId())) {
                throw new IllegalArgumentException("dispatch request id does not match its attempt");
            }
        } else if (!rejected) {
            throw new IllegalArgumentException("a dispatch without an attempt must report an error problem");
        }
    }

    private static boolean isError(Problem problem) {
        return problem.severity() == Problem.Severity.ERROR;
    }
}
