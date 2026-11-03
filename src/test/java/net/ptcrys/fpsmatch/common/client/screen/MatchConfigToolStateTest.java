package net.ptcrys.fpsmatch.common.client.screen;

import org.junit.jupiter.api.Test;
import static net.ptcrys.fpsmatch.common.client.screen.MatchConfigToolState.*;
import static org.junit.jupiter.api.Assertions.*;

class MatchConfigToolStateTest {
    private final Object connection = new Object();
    private final MatchConfigToolState<String> state = new MatchConfigToolState<>(connection, 19);
    private final Target alpha = new Target("mode", "alpha");
    private final Target beta = new Target("mode", "beta");
    private Ticket open() { return state.begin(Operation.OPEN, Target.NONE, 100).orElseThrow(); }
    private boolean reply(Ticket ticket, Target target, String snapshot, String failure) {
        return state.complete(connection, ticket.session(), ticket.request(), ticket.operation(), target, snapshot, failure);
    }
    private void opened() { assertTrue(reply(open(), alpha, "original", "")); }

    @Test void localOpenWaitsAndDuplicateRequestsDoNotReplaceItsTicket() {
        Ticket ticket = open();
        assertEquals(Phase.WAITING, state.phase());
        assertNull(state.snapshot());
        assertTrue(state.begin(Operation.OPEN, Target.NONE, 101).isEmpty());
        assertTrue(state.begin(Operation.SELECT, beta, 102).isEmpty());
        assertSame(ticket, state.pending());
    }
    @Test void emptyInitialSnapshotIsACompletedResponse() {
        assertTrue(reply(open(), Target.NONE, "empty inventory", ""));
        assertEquals(Phase.IDLE, state.phase());
        assertEquals(Target.NONE, state.selected());
        assertNotNull(state.snapshot());
    }
    @Test void selectionRemainsOriginalUntilMatchingSuccess() {
        opened();
        Ticket ticket = state.begin(Operation.SELECT, beta, 200).orElseThrow();
        assertEquals(alpha, state.selected());
        assertEquals("original", state.snapshot());
        assertTrue(reply(ticket, beta, "second", ""));
        assertEquals(beta, state.selected());
        assertEquals("second", state.snapshot());
    }
    @Test void failedSelectionPreservesTheOriginalSnapshotAndTarget() {
        opened();
        Ticket ticket = state.begin(Operation.SELECT, beta, 200).orElseThrow();
        assertTrue(reply(ticket, beta, "must not apply", "removed"));
        assertEquals(Phase.FAILED, state.phase());
        assertEquals("removed", state.failure());
        assertEquals(alpha, state.selected());
        assertEquals("original", state.snapshot());
    }
    @Test void refreshFailureNeverSwitchesToAnotherMap() {
        opened();
        Ticket ticket = state.begin(Operation.REFRESH, alpha, 200).orElseThrow();
        assertFalse(reply(ticket, beta, "fallback", ""));
        assertFalse(reply(ticket, beta, null, "removed"));
        assertSame(ticket, state.pending());
        assertTrue(reply(ticket, alpha, null, "removed"));
        assertEquals(alpha, state.selected());
        assertEquals("original", state.snapshot());
    }
    @Test void refreshReplacesOnlyWithMatchingTarget() {
        opened();
        Ticket ticket = state.begin(Operation.REFRESH, alpha, 200).orElseThrow();
        assertTrue(reply(ticket, alpha, "fresh", ""));
        assertEquals("fresh", state.snapshot());
    }
    @Test void wrongSessionRequestOperationAndConnectionCannotCompleteOpen() {
        Ticket ticket = open();
        assertFalse(state.complete(connection, 18, ticket.request(), Operation.OPEN, alpha, "bad", ""));
        assertFalse(state.complete(connection, 19, ticket.request() + 1, Operation.OPEN, alpha, "bad", ""));
        assertFalse(state.complete(connection, 19, ticket.request(), Operation.REFRESH, alpha, "bad", ""));
        assertFalse(state.complete(new Object(), 19, ticket.request(), Operation.OPEN, alpha, "bad", ""));
        assertSame(ticket, state.pending());
        assertNull(state.snapshot());
    }
    @Test void duplicateReplyCannotOverwriteACompletedSnapshot() {
        Ticket ticket = open();
        assertTrue(reply(ticket, alpha, "first", ""));
        assertFalse(reply(ticket, beta, "duplicate", ""));
        assertEquals("first", state.snapshot());
    }
    @Test void cancellingFirstOpenRetiresReplyAndRetryUsesANewRequest() {
        Ticket old = open();
        assertTrue(state.cancel());
        assertFalse(reply(old, alpha, "late", ""));
        Ticket retry = state.retry(200).orElseThrow();
        assertTrue(retry.request() > old.request());
        assertFalse(reply(old, alpha, "late", ""));
        assertTrue(reply(retry, alpha, "new", ""));
    }
    @Test void cancellingSelectionKeepsPriorSnapshotAndAllowsFreshChoice() {
        opened();
        Ticket old = state.begin(Operation.SELECT, beta, 200).orElseThrow();
        assertTrue(state.cancel());
        assertEquals("original", state.snapshot());
        assertEquals(alpha, state.selected());
        assertFalse(reply(old, beta, "late", ""));
        assertTrue(state.begin(Operation.REFRESH, alpha, 300).isPresent());
    }
    @Test void timeoutBoundaryAndRetryCannotBeCompletedByLateResponse() {
        opened();
        Ticket old = state.begin(Operation.REFRESH, alpha, 200).orElseThrow();
        assertFalse(state.tick(connection, 200 + TIMEOUT_MILLIS - 1));
        assertTrue(state.tick(connection, 200 + TIMEOUT_MILLIS));
        assertEquals(Phase.TIMED_OUT, state.phase());
        assertEquals("original", state.snapshot());
        Ticket retry = state.retry(20_000).orElseThrow();
        assertFalse(reply(old, alpha, "late", ""));
        assertSame(retry, state.pending());
        assertTrue(reply(retry, alpha, "fresh", ""));
    }
    @Test void closeRejectsRepliesRetriesAndNewRequests() {
        Ticket ticket = open();
        state.close();
        assertEquals(Phase.CLOSED, state.phase());
        assertFalse(reply(ticket, alpha, "late", ""));
        assertTrue(state.retry(300).isEmpty());
        assertTrue(state.begin(Operation.OPEN, Target.NONE, 400).isEmpty());
    }
    @Test void disconnectedOrReplacedConnectionRetiresViewAuthority() {
        for (Object replacement : new Object[] {null, new Object()}) {
            MatchConfigToolState<String> separate = new MatchConfigToolState<>(connection, 22);
            Ticket ticket = separate.begin(Operation.OPEN, Target.NONE, 0).orElseThrow();
            assertTrue(separate.tick(replacement, 1));
            assertEquals("disconnected", separate.failure());
            assertFalse(separate.complete(connection, 22, ticket.request(), Operation.OPEN, alpha, "late", ""));
            assertTrue(separate.retry(2).isEmpty());
        }
    }
    @Test void freshSessionRejectsOldSessionReplyEvenWithSameSequence() {
        Ticket old = open();
        state.close();
        MatchConfigToolState<String> fresh = new MatchConfigToolState<>(connection, 20);
        Ticket current = fresh.begin(Operation.OPEN, Target.NONE, 200).orElseThrow();
        assertFalse(fresh.complete(connection, old.session(), old.request(), Operation.OPEN, alpha, "late", ""));
        assertSame(current, fresh.pending());
    }
}
