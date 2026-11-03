package net.ptcrys.fpsmatch.core;

import java.util.List;

import static net.ptcrys.fpsmatch.core.RuntimeMapLeaseException.Reason.*;

/** One owner-thread retirement right, including candidates never installed in GAMES. */
public final class RuntimeMapLease implements AutoCloseable {

    private final Thread owner = Thread.currentThread();
    private boolean closed;
    private boolean checking;
    private List<Runnable> consumers;
    private Runnable release;
    private Runnable detached;
    private Runnable beforeClose;

    RuntimeMapLease(List<Runnable> consumers, Runnable release, Runnable detached, Runnable beforeClose) {
        if (consumers == null || release == null || detached == null || beforeClose == null) {
            throw RuntimeMapLeaseException.error(INVALID_INPUT);
        }
        List<Runnable> captured;
        try {
            captured = List.copyOf(consumers);
        } catch (NullPointerException invalid) {
            throw RuntimeMapLeaseException.error(INVALID_INPUT);
        }
        this.consumers = captured;
        this.release = release;
        this.detached = detached;
        this.beforeClose = beforeClose;
    }

    public void requireOpen() {
        requireOwner();
        if (closed) throw RuntimeMapLeaseException.error(CLOSED);
    }

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        if (checking) throw RuntimeMapLeaseException.error(BUSY);
        checking = true;
        try {
            beforeClose.run();
        } finally {
            checking = false;
        }
        closed = true;
        List<Runnable> capturedConsumers = consumers;
        Runnable capturedRelease = release;
        Runnable capturedDetached = detached;
        consumers = null;
        release = null;
        detached = null;
        beforeClose = null;

        Throwable failure = attempt(capturedDetached, null);
        for (Runnable consumer : capturedConsumers) failure = attempt(consumer, failure);
        failure = attempt(capturedRelease, failure);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw RuntimeMapLeaseException.error(WRONG_THREAD);
    }

    private static Throwable attempt(Runnable action, Throwable failure) {
        try {
            action.run();
        } catch (RuntimeException | Error next) {
            if (failure == null) return next;
            if (failure != next) failure.addSuppressed(next);
        }
        return failure;
    }
}
