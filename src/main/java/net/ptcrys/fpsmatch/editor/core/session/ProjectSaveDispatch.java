package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.Problem;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A queued intent either produced an I/O job or was rejected with its original diagnostics. */
public record ProjectSaveDispatch(UUID requestId, Optional<ProjectSaveJob> job, List<Problem> problems) {

    public ProjectSaveDispatch {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(job, "job");
        problems = List.copyOf(problems);
        boolean errors = problems.stream().anyMatch(p -> p.severity() == Problem.Severity.ERROR);
        if (job.isPresent() ? errors || !requestId.equals(job.get().attempt().requestId()) : !errors) {
            throw new IllegalArgumentException("inconsistent save dispatch");
        }
    }
}
