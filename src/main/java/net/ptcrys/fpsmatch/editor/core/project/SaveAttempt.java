package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.core.storage.ProjectState;

import java.util.Objects;
import java.util.UUID;

/**
 * One dispatched publish intent, frozen before any I/O. The three identities and the candidate are
 * exactly what the storage layer receives and exactly what a result must match to be accepted.
 *
 * @param sessionId        queue generation; a result from another queue or an earlier generation is
 *                         refused
 * @param requestId        stable identity of this request; it becomes the candidate commit id and
 *                         must be reused unchanged for a retry or a lookup
 * @param expectedCommitId the confirmed commit the candidate was assembled from; the storage layer
 *                         publishes only while {@code CURRENT} still matches it
 * @param candidate        the complete manifest to publish, never a partial state
 */
public record SaveAttempt(UUID sessionId, UUID requestId, UUID expectedCommitId, ProjectState candidate) {

    public SaveAttempt {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(expectedCommitId, "expectedCommitId");
        Objects.requireNonNull(candidate, "candidate");
    }
}
