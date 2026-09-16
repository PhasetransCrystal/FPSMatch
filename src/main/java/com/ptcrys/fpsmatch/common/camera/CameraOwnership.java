package com.ptcrys.fpsmatch.common.camera;

import java.util.function.Consumer;

/** Single-threaded exclusive ownership. A stale lease can never release its successor. */
public final class CameraOwnership<T> {
    private Lease<T> active;

    public Lease<T> acquire(String owner, int priority, T value, Consumer<CameraEndReason> onEnd) {
        if (active != null && priority < active.priority) return null;
        Lease<T> previous = active;
        Lease<T> next = new Lease<>(owner, priority, value, onEnd);
        active = next;
        if (previous != null) previous.end(CameraEndReason.REPLACED);
        return next;
    }

    public Lease<T> active() { return active; }

    public boolean release(Lease<T> lease, CameraEndReason reason) {
        if (lease == null || active != lease) return false;
        active = null;
        lease.end(reason);
        return true;
    }

    public void clear(CameraEndReason reason) { release(active, reason); }

    public static final class Lease<T> {
        private final String owner;
        private final int priority;
        private final T value;
        private final Consumer<CameraEndReason> onEnd;
        private boolean ended;

        private Lease(String owner, int priority, T value, Consumer<CameraEndReason> onEnd) {
            this.owner = owner;
            this.priority = priority;
            this.value = value;
            this.onEnd = onEnd;
        }

        private void end(CameraEndReason reason) {
            if (ended) return;
            ended = true;
            onEnd.accept(reason);
        }

        public String owner() { return owner; }
        public int priority() { return priority; }
        public T value() { return value; }
    }
}
