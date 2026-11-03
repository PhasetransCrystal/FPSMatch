package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.api.Problem;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The observed outcome of one publish attempt, as reported to {@link ProjectSaveQueue#complete}.
 *
 * <p>
 * The outcome is a fact, not a decision: the queue still verifies identity, parent and
 * {@code CURRENT} before it accepts a success. {@code saved} is present only for
 * {@link Outcome#CONFIRMED}; for every other outcome it is absent, because no new manifest was
 * published. {@code currentCommitId} carries only a commit id the reporter actually observed and is
 * therefore {@code null} when no authoritative {@code CURRENT} was read; it never repeats the
 * request id or the expected baseline to look like progress.
 */
public record SaveResult(Outcome outcome, SavedProject saved, UUID currentCommitId, List<Problem> problems) {

    public enum Outcome {
        CONFIRMED,
        REJECTED,
        CONFLICT,
        UNKNOWN
    }

    public SaveResult {
        Objects.requireNonNull(outcome, "outcome");
        problems = List.copyOf(Objects.requireNonNull(problems, "problems"));
        for (Problem problem : problems) {
            Objects.requireNonNull(problem, "problem");
        }
        if (outcome == Outcome.CONFIRMED) {
            Objects.requireNonNull(saved, "saved");
            if (!saved.commitId().equals(currentCommitId)) {
                throw new IllegalArgumentException("a confirmed result must report its own commit as CURRENT");
            }
        } else if (saved != null) {
            throw new IllegalArgumentException("only a confirmed result carries a published snapshot");
        }
    }
}
