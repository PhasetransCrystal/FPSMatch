package net.ptcrys.fpsmatch.editor.core.session;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** One owner thread and mutation boundary shared by every document of an open project. */
final class SessionOwner {

    private final Thread thread = Thread.currentThread();
    private final UUID id;
    private boolean mutating;
    private boolean closed;

    SessionOwner(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    UUID id() {
        requireReadable();
        return id;
    }

    void requireReadable() {
        requireThread();
        if (closed) {
            throw new IllegalStateException("project session is closed");
        }
    }

    <T> T mutate(Supplier<T> operation) {
        requireReadable();
        ResourceValidation.requireOutsideCallback();
        if (mutating) {
            throw RuntimeMessages.reentrant(id);
        }
        mutating = true;
        try {
            return operation.get();
        } finally {
            mutating = false;
        }
    }

    boolean isClosed() {
        requireThread();
        return closed;
    }

    void close(Runnable release) {
        requireThread();
        if (closed) {
            return;
        }
        mutate(() -> {
            release.run();
            closed = true;
            return null;
        });
    }

    private void requireThread() {
        if (Thread.currentThread() != thread) {
            throw new IllegalStateException("project session belongs to its owner thread");
        }
    }
}
