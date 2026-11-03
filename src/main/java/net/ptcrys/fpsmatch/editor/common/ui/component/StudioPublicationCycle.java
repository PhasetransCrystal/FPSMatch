package net.ptcrys.fpsmatch.editor.common.ui.component;

/** Shared screen publication gate. Reentrant invalidation is never erased by a completed build. */
public final class StudioPublicationCycle {

    private boolean open, building, failed;
    private long requested, published = -1, epoch;

    public void open() {
        epoch++;
        open = true;
        failed = false;
        invalidate();
    }

    public void close() {
        epoch++;
        open = false;
        invalidate();
    }

    public long epoch() {
        return epoch;
    }

    public boolean current(long candidate) {
        return open && epoch == candidate;
    }

    public void invalidate() {
        requested++;
    }

    public boolean building() {
        return building;
    }

    public boolean failed() {
        return failed;
    }

    public void fail() {
        failed = true;
    }

    public void retry() {
        failed = false;
        invalidate();
    }

    /** False means no event may use the previous frame, including during publication callbacks. */
    public boolean refresh(Runnable publication) {
        if (!open || failed || building) return false;
        long lifecycle = epoch;
        int attempts = 0;
        while (current(lifecycle) && published != requested && attempts++ < 8) {
            long version = requested;
            building = true;
            try {
                publication.run();
                if (current(lifecycle)) published = version;
            } catch (RuntimeException failure) {
                if (current(lifecycle)) failed = true;
                throw failure;
            } finally {
                building = false;
            }
        }
        return current(lifecycle) && !failed && published == requested;
    }
}
