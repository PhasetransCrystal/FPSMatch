package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.RoomActionIntent.Operation.*;
import static net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.RoomActionIntent.Phase.*;

class RoomActionIntentTest {
    private static final UUID SELF = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test void delayedReadyConfirmationPreventsASecondToggleAfterTimeout() {
        var state = new RoomActionIntent();
        assertTrue(state.begin(READY, SELF, "", true));
        expire(state);
        assertEquals(UNCONFIRMED, state.phase());
        assertFalse(state.idle());
        assertFalse(state.begin(READY, SELF, "", true));
        assertFalse(state.begin(LEAVE, SELF, "", false));
        assertFalse(state.observeReady(false));
        assertTrue(state.observeReady(true));
        assertEquals(IDLE, state.phase());
        // A new toggle is permitted only after the desired first state is seen.
        assertTrue(state.begin(READY, SELF, "", false));
        assertFalse(state.observeReady(true));
        assertTrue(state.observeReady(false));
    }

    @Test void timeoutOccursOnceAndFurtherTimeCannotReleaseTheOperation() {
        var state = new RoomActionIntent();
        state.begin(JOIN, SELF, "", false);
        for (int i = 1; i < RoomActionIntent.TIMEOUT_TICKS; i++) assertFalse(state.tick());
        assertTrue(state.tick());
        for (int i = 0; i < 1000; i++) assertFalse(state.tick());
        assertEquals(UNCONFIRMED, state.phase());
        assertTrue(state.is(JOIN));
        assertFalse(state.begin(JOIN, SELF, "", false));
    }

    @Test void anUncorrelatedErrorDoesNotProveRejectionOrPermitRetry() {
        var state = new RoomActionIntent();
        state.begin(READY, SELF, "", true);
        assertTrue(state.markUnconfirmed());
        assertFalse(state.markUnconfirmed());
        assertTrue(state.is(READY));
        assertFalse(state.begin(READY, SELF, "", true));
        assertFalse(state.observeReady(false));
        assertTrue(state.observeDetail(true, true, Map.of(SELF, "ct")));
        assertEquals(IDLE, state.phase());
    }

    @Test void unrelatedRosterChangesCannotCompleteTheRequestedTeamSwitch() {
        var state = new RoomActionIntent();
        state.begin(SWITCH_TEAM, SELF, "t", false);
        expire(state);
        assertFalse(state.observeDetail(true, false, Map.of(SELF, "ct", OTHER, "t")));
        assertFalse(state.observeDetail(true, false, Map.of(OTHER, "t")));
        assertFalse(state.begin(SWITCH_TEAM, OTHER, "ct", false));
        assertTrue(state.observeDetail(true, false, Map.of(SELF, "t", OTHER, "ct")));
        assertEquals(IDLE, state.phase());
    }

    @Test void kickRequiresTheTargetToBeAbsentFromAFullRoster() {
        var state = new RoomActionIntent();
        state.begin(KICK, OTHER, "", false);
        state.markUnconfirmed();
        assertFalse(state.observeDetail(true, false, Map.of(OTHER, "ct")));
        assertTrue(state.observeDetail(true, false, Map.of(SELF, "ct")));
        assertFalse(state.observeDetail(true, false, Map.of(SELF, "ct")));
    }

    @Test void partialReadinessPacketsCannotCompleteMembershipOrRosterWork() {
        for (var operation : new RoomActionIntent.Operation[] { JOIN, LEAVE, SWITCH_TEAM, KICK }) {
            var state = new RoomActionIntent();
            state.begin(operation, SELF, "ct", false);
            assertFalse(state.observeReady(false));
            assertFalse(state.observeReady(true));
            assertEquals(WAITING, state.phase());
        }
    }

    @Test void joinAndLeaveCompleteOnlyWhenTheirOwnMembershipGoalIsObserved() {
        var state = new RoomActionIntent();
        state.begin(JOIN, SELF, "", false);
        expire(state);
        assertFalse(state.observeDetail(false, false, Map.of()));
        assertTrue(state.observeDetail(true, false, Map.of(SELF, "spectator")));
        state.begin(LEAVE, SELF, "", false);
        assertFalse(state.observeDetail(true, false, Map.of(SELF, "spectator")));
        assertTrue(state.observeDetail(false, false, Map.of()));
    }

    @Test void aDuplicateReplyOrErrorWhileIdleDoesNotStartAnotherOperation() {
        var state = new RoomActionIntent();
        state.begin(READY, SELF, "", true);
        assertTrue(state.observeReady(true));
        assertFalse(state.observeReady(true));
        assertFalse(state.markUnconfirmed());
        assertFalse(state.tick());
        assertEquals(IDLE, state.phase());
    }

    private static void expire(RoomActionIntent state) {
        for (int i = 0; i < RoomActionIntent.TIMEOUT_TICKS; i++) state.tick();
    }
}
