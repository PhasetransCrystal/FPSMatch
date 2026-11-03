package net.ptcrys.fpsmatch.editor.core.documents;

import java.util.Objects;
import java.util.UUID;

/**
 * An optimistic concurrency token for one exact state of one document branch.
 *
 * <p>
 * {@code revision} counts every successful content change on the branch, including changes
 * produced by undo and redo. It is not a dirty flag: dirtiness is derived from the content
 * encoding, never from the revision.
 * </p>
 */
public record DocumentToken(UUID documentId, UUID branchId, long revision) {

    public DocumentToken {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(branchId, "branchId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
