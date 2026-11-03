package net.ptcrys.fpsmatch.editor.core.storage;

import java.util.Objects;
import java.util.UUID;

/** One immutable published snapshot. A null parent denotes the first local commit. */
public record SavedProject(UUID commitId, UUID parentCommitId, ProjectState state) {

    public SavedProject {
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(state, "state");
        if (commitId.equals(parentCommitId)) {
            throw new IllegalArgumentException("commit cannot be its own parent");
        }
    }
}
