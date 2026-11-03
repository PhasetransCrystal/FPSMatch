package net.ptcrys.fpsmatch.editor.core.storage;

import java.util.Objects;

/**
 * One fixed published snapshot that a live {@link LocalProjectStore} certified through its own entry.
 *
 * <p>
 * An instance is issued only by {@link LocalProjectStore#captureSnapshot()}, and it is not a
 * general authentication of arbitrary saves: the type has no public constructor and
 * {@link LocalProjectStore#load(ProjectSnapshot)} and
 * {@link LocalProjectStore#exportSnapshot(java.nio.file.Path, ProjectSnapshot)} accept only a
 * capability the same live instance issued. A capture records the manifest digest the published
 * entry vouched for at capture time, so a later snapshot of the same project is never substituted
 * for the captured one and this store never adopts an entry changed by another process.
 *
 * <p>
 * Holding a capability pins nothing: it neither blocks later saves nor extends the lifetime of
 * any stored version. Reading or exporting again verifies the same fixed content; missing or
 * damaged content is refused rather than replaced by a later version.
 */
public final class ProjectSnapshot {

    private final Object issuer;
    private final SavedProject saved;
    private final String manifestHash;

    ProjectSnapshot(Object issuer, SavedProject saved, String manifestHash) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.saved = Objects.requireNonNull(saved, "saved");
        this.manifestHash = Objects.requireNonNull(manifestHash, "manifestHash");
    }

    /** The published snapshot this capability certifies; immutable and safe to query repeatedly. */
    public SavedProject saved() {
        return saved;
    }

    boolean issuedBy(Object candidate) {
        return issuer == candidate;
    }

    String manifestHash() {
        return manifestHash;
    }
}
