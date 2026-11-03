package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One observable room operation. Timeout and uncorrelated errors preserve its
 * target because the server may already have acted. Matching state establishes
 * the desired result, not packet/request correlation: the legacy wire has no id.
 */
public final class RoomActionIntent {

    public static final int TIMEOUT_TICKS = 200;

    public enum Phase {
        IDLE,
        WAITING,
        UNCONFIRMED,
        INVALIDATED
    }

    public enum Operation {
        JOIN,
        LEAVE,
        READY,
        SWITCH_TEAM,
        KICK
    }

    private Phase phase = Phase.IDLE;
    private Operation operation;
    private UUID target;
    private String team;
    private boolean expectedReady;
    private int ticks;

    public boolean begin(Operation next, UUID player, String targetTeam, boolean desiredReady) {
        Objects.requireNonNull(next, "next");
        if (!idle()) return false;
        if (next == Operation.SWITCH_TEAM || next == Operation.KICK) Objects.requireNonNull(player, "player");
        if (next == Operation.SWITCH_TEAM) Objects.requireNonNull(targetTeam, "targetTeam");
        operation = next;
        target = player;
        team = targetTeam;
        expectedReady = desiredReady;
        ticks = 0;
        phase = Phase.WAITING;
        return true;
    }

    public Phase phase() {
        return phase;
    }

    public boolean idle() {
        return phase == Phase.IDLE;
    }

    public boolean pending() {
        return phase == Phase.WAITING || phase == Phase.UNCONFIRMED;
    }

    public boolean is(Operation candidate) {
        return pending() && operation == candidate;
    }

    /** End this session's authority permanently, including references held by old views. */
    void invalidate() {
        phase = Phase.INVALIDATED;
        operation = null;
        target = null;
        team = null;
        ticks = 0;
    }

    /** Reports the transition once; elapsed time never authorizes resubmission. */
    public boolean tick() {
        return phase == Phase.WAITING && ++ticks >= TIMEOUT_TICKS && markUnconfirmed();
    }

    /** An error without correlation cannot prove this operation was rejected. */
    public boolean markUnconfirmed() {
        if (phase != Phase.WAITING) return false;
        phase = Phase.UNCONFIRMED;
        return true;
    }

    /** The caller must first verify the full detail belongs to this screen's room. */
    public boolean observeDetail(boolean member, boolean ready, Map<UUID, String> roster) {
        Objects.requireNonNull(roster, "roster");
        if (!pending()) return false;
        boolean satisfied = switch (operation) {
            case JOIN -> member;
            case LEAVE -> !member;
            case READY -> ready == expectedReady;
            case SWITCH_TEAM -> team.equals(roster.get(target));
            case KICK -> !roster.containsKey(target);
        };
        return satisfied && complete();
    }

    /** A readiness-only packet cannot complete membership, team or kick work. */
    public boolean observeReady(boolean ready) {
        return is(Operation.READY) && ready == expectedReady && complete();
    }

    private boolean complete() {
        phase = Phase.IDLE;
        operation = null;
        target = null;
        team = null;
        ticks = 0;
        return true;
    }
}
