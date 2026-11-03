package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.core.storage.LocalProjectStore;
import net.ptcrys.fpsmatch.editor.core.storage.ProjectStorageException;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Publishes one frozen {@link SaveAttempt} through {@link LocalProjectStore} and reports the fact it
 * observed. It blocks on real file I/O and therefore runs on whatever thread the caller chooses; it
 * starts no thread and never touches user interface state.
 *
 * <p>
 * The runner adds no policy: it does not retry, does not look the request up, does not reopen the
 * project and never decides that an unresolved publication was rejected. A receipt that does not
 * commit the requested id is a conflict, not a new baseline. Storage codes map as follows:
 * {@code RESULT_UNKNOWN} is unknown, {@code CONFLICT} is a conflict, other explicit storage errors
 * and plain {@code IOException} are rejections, and an unexpected runtime failure after publication
 * was attempted is conservatively unknown, because a manifest may already exist on disk.
 */
public final class LocalCommitRunner {

    private LocalCommitRunner() {}

    /** Publishes the attempt and returns the observed outcome; never throws for a failed publish. */
    public static SaveResult run(LocalProjectStore store, SaveAttempt attempt) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(attempt, "attempt");
        LocalProjectStore.CommitResult committed;
        try {
            committed = store.commit(attempt.expectedCommitId(), attempt.requestId(), attempt.candidate());
        } catch (ProjectStorageException failure) {
            return storageFailure(attempt, failure);
        } catch (IOException failure) {
            return rejected(attempt, "IO", Messages.technical(failure));
        } catch (RuntimeException failure) {
            return unknown(attempt, Messages.technical(failure));
        }
        SavedProject saved = committed.saved();
        UUID current = committed.currentCommitId();
        if (current == null) {
            return new SaveResult(SaveResult.Outcome.CONFLICT, null, null,
                    List.of(Messages.conflictUnobserved(attempt.requestId(), attempt.expectedCommitId())));
        }
        if (!attempt.requestId().equals(current) || !attempt.requestId().equals(saved.commitId())) {
            return new SaveResult(SaveResult.Outcome.CONFLICT, null, current,
                    List.of(Messages.conflict(attempt.requestId(), attempt.expectedCommitId(), current)));
        }
        return new SaveResult(SaveResult.Outcome.CONFIRMED, saved, current, List.of());
    }

    private static SaveResult storageFailure(SaveAttempt attempt, ProjectStorageException failure) {
        return switch (failure.code()) {
            case RESULT_UNKNOWN -> unknown(attempt, Messages.technical(failure));
            case CONFLICT -> new SaveResult(SaveResult.Outcome.CONFLICT, null, null,
                    List.of(Messages.conflictUnobserved(attempt.requestId(), attempt.expectedCommitId())));
            default -> rejected(attempt, failure.code().name(), Messages.technical(failure));
        };
    }

    private static SaveResult rejected(SaveAttempt attempt, String code, String detail) {
        return new SaveResult(SaveResult.Outcome.REJECTED, null, null,
                List.of(Messages.rejected(attempt.requestId(), code, detail)));
    }

    private static SaveResult unknown(SaveAttempt attempt, String detail) {
        return new SaveResult(SaveResult.Outcome.UNKNOWN, null, null,
                List.of(Messages.unknown(attempt.requestId(), detail)));
    }
}
