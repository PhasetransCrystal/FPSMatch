package net.ptcrys.fpsmatch.common.client.screen;

import java.util.Objects;
import java.util.Optional;

/** Pure local view intent. Replies have no authority to create or revive a view. */
public final class MatchConfigToolState<S> {

    public static final long TIMEOUT_MILLIS = 10_000L;

    public enum Operation {
        OPEN,
        SELECT,
        REFRESH
    }

    public enum Phase {
        IDLE,
        WAITING,
        FAILED,
        TIMED_OUT,
        CLOSED
    }

    public record Target(String gameType, String mapName) {

        public static final Target NONE = new Target("", "");

        public Target {
            Objects.requireNonNull(gameType);
            Objects.requireNonNull(mapName);
        }
    }

    public record Ticket(long session, long request, Operation operation, Target target) {}

    private final Object connection;
    private final long session;
    private long sequence, startedAt;
    private Ticket pending, lastRequest;
    private Phase phase = Phase.IDLE;
    private S snapshot;
    private Target selected = Target.NONE;
    private String failure = "";
    private boolean disconnected;

    public MatchConfigToolState(Object connection, long session) {
        this.connection = Objects.requireNonNull(connection);
        if (session <= 0) throw new IllegalArgumentException("session must be positive");
        this.session = session;
    }

    public Phase phase() {
        return phase;
    }

    public S snapshot() {
        return snapshot;
    }

    public Target selected() {
        return selected;
    }

    public Ticket pending() {
        return pending;
    }

    public String failure() {
        return failure;
    }

    public boolean canRequest() {
        return phase != Phase.CLOSED && pending == null && !disconnected;
    }

    public Optional<Ticket> begin(Operation operation, Target target, long now) {
        Objects.requireNonNull(operation);
        Objects.requireNonNull(target);
        if (!canRequest()) return Optional.empty();
        if (operation != Operation.OPEN && snapshot == null) return Optional.empty();
        if (operation == Operation.OPEN && snapshot != null) return Optional.empty();
        if (operation == Operation.SELECT && target.equals(Target.NONE)) return Optional.empty();
        pending = new Ticket(session, ++sequence, operation, target);
        lastRequest = pending;
        startedAt = now;
        phase = Phase.WAITING;
        failure = "";
        return Optional.of(pending);
    }

    public Optional<Ticket> retry(long now) {
        return lastRequest == null ? Optional.empty() : begin(lastRequest.operation(), lastRequest.target(), now);
    }

    public boolean complete(Object sourceConnection, long responseSession, long request, Operation operation,
                            Target target, S nextSnapshot, String error) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(error);
        if (sourceConnection != connection || pending == null || pending.session() != responseSession || pending.request() != request || pending.operation() != operation) return false;
        if (operation != Operation.OPEN && !pending.target().equals(target)) return false;
        if (!error.isEmpty()) {
            failure = error;
            pending = null;
            phase = Phase.FAILED;
            return true;
        }
        if (nextSnapshot == null) return false;
        snapshot = nextSnapshot;
        selected = target;
        pending = null;
        phase = Phase.IDLE;
        failure = "";
        return true;
    }

    public boolean tick(Object currentConnection, long now) {
        if (phase == Phase.CLOSED || disconnected) return false;
        if (currentConnection != connection) {
            pending = null;
            disconnected = true;
            phase = Phase.FAILED;
            failure = "disconnected";
            return true;
        }
        if (pending != null && now - startedAt >= TIMEOUT_MILLIS) {
            pending = null;
            phase = Phase.TIMED_OUT;
            failure = "timeout";
            return true;
        }
        return false;
    }

    public boolean cancel() {
        if (pending == null) return false;
        pending = null;
        phase = snapshot == null ? Phase.FAILED : Phase.IDLE;
        failure = "cancelled";
        return true;
    }

    public void close() {
        pending = null;
        phase = Phase.CLOSED;
    }
}
