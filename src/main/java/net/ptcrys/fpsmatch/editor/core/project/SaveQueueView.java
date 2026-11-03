package net.ptcrys.fpsmatch.editor.core.project;

import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.util.Objects;
import java.util.Optional;

/**
 * An immutable reading of the queue. The engine publishes a new view only after a public call
 * returns, so a view taken from inside a validator callback describes the last published state:
 * the dispatcher updates the view only after the validator returns.
 *
 * <p>
 * {@code confirmed} is always the latest locally confirmed manifest; it is never the in-flight
 * candidate. {@code queued} counts requests waiting in line and excludes the active request.
 */
public record SaveQueueView(State state, int queued, Optional<SaveAttempt> active, SavedProject confirmed) {

    public enum State {
        /** Nothing is in flight; a queued request may be dispatched. */
        READY,
        /** One request is publishing; its result is still unknown to the queue. */
        PUBLISHING,
        /** The publish result could not be determined; only the same request may resolve it. */
        UNKNOWN,
        /** The request conflicted with a newer saved state; the engine is frozen for this session. */
        RECOVERY,
        /** The queue was closed; pending requests were cancelled. */
        CLOSED
    }

    public SaveQueueView {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(confirmed, "confirmed");
        if (queued < 0) {
            throw new IllegalArgumentException("queued request count cannot be negative");
        }
        if (active.isPresent() && (state == State.READY || state == State.CLOSED)) {
            throw new IllegalArgumentException(state + " cannot carry an active attempt");
        }
    }
}
