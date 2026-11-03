package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.core.project.SaveAttempt;

/** One dispatched immutable attempt; retries and reconciliation use this exact original job. */
public final class ProjectSaveJob {

    final LocalProjectAccess access;
    final Object lease;
    private final SaveAttempt attempt;
    // Accessed only under the issuing LocalProjectAccess monitor.
    boolean revoked;
    boolean running;
    boolean publishable = true;

    ProjectSaveJob(LocalProjectAccess access, Object lease, SaveAttempt attempt) {
        this.access = access;
        this.lease = lease;
        this.attempt = attempt;
    }

    public SaveAttempt attempt() {
        return attempt;
    }
}
