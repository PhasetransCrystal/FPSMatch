package net.ptcrys.fpsmatch.core;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Server ownership and terminal cleanup for one captured server instance. */
final class ServerLifetime<S> {

    private enum State {
        ACTIVE,
        STOPPING,
        STOPPED
    }

    private final S server;
    private final BooleanSupplier owner;
    private volatile State state = State.ACTIVE;
    private boolean shutdownRunning;
    private boolean closeAttempted;
    private boolean closeRunning;

    ServerLifetime(S server, BooleanSupplier owner) {
        this.server = Objects.requireNonNull(server, "server");
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    S server() {
        return server;
    }

    boolean matches(S candidate) {
        return server == candidate;
    }

    boolean active() {
        return state == State.ACTIVE;
    }

    /** Owner-only observation for releasing the containing core after callbacks finish. */
    boolean terminalCleanupFinished() {
        return state == State.STOPPED && closeAttempted && !shutdownRunning && !closeRunning;
    }

    void requireOwner() {
        if (!owner.getAsBoolean()) {
            throw new IllegalStateException("Server lifetime requires the server owner thread");
        }
    }

    void stopping(Runnable shutdown, Runnable close) {
        requireOwner();
        Objects.requireNonNull(shutdown, "shutdown");
        Objects.requireNonNull(close, "close");
        if (state != State.ACTIVE) return;

        state = State.STOPPING;
        shutdownRunning = true;
        Throwable failure = null;
        try {
            shutdown.run();
        } catch (RuntimeException | Error shutdownFailure) {
            failure = shutdownFailure;
            throw shutdownFailure;
        } finally {
            shutdownRunning = false;
            try {
                closeOnce(close);
            } catch (RuntimeException | Error closeFailure) {
                if (failure == null) throw closeFailure;
                if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
        }
    }

    void stopped(Runnable close) {
        requireOwner();
        Objects.requireNonNull(close, "close");
        state = State.STOPPED;
        // Stopped may reenter a final-save callback. Let its outer finally close.
        if (!shutdownRunning) closeOnce(close);
    }

    private void closeOnce(Runnable close) {
        if (closeAttempted) return;
        closeAttempted = true;
        closeRunning = true;
        try {
            close.run();
        } finally {
            closeRunning = false;
        }
    }
}
