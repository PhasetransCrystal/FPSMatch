package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.RoomActionIntent.Operation.*;
import static net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.RoomActionIntent.Phase.*;

class RoomActionSessionTest {
    private static final UUID SELF = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test void reopeningTheSameRoomCannotBypassAnUnconfirmedToggle() {
        var session = new RoomActionSession();
        var firstView = session.intent("cs", "dust");
        assertTrue(firstView.begin(READY, SELF, "", true));
        expire(session);
        var reopenedView = session.intent("cs", "dust");
        assertSame(firstView, reopenedView);
        assertEquals(UNCONFIRMED, reopenedView.phase());
        assertFalse(reopenedView.begin(READY, SELF, "", true));
        assertFalse(reopenedView.begin(LEAVE, SELF, "", false));
        assertFalse(session.observeReady("cs", "dust", false));
        assertEquals(UNCONFIRMED, reopenedView.phase());
    }

    @Test void aLateReadyUpdateCompletesTheIntentWhileItsViewIsClosed() {
        var session = new RoomActionSession();
        session.intent("cs", "dust").begin(READY, SELF, "", true);
        expire(session);
        // No view is required for packet observation or completion.
        assertTrue(session.observeReady("cs", "dust", true));
        assertFalse(session.observeReady("cs", "dust", true));
        var reopenedView = session.intent("cs", "dust");
        assertEquals(IDLE, reopenedView.phase());
        assertTrue(reopenedView.begin(READY, SELF, "", false));
        assertFalse(session.observeReady("cs", "dust", true));
        assertTrue(session.observeReady("cs", "dust", false));
    }

    @Test void aLateFullDetailCompletesMembershipWithoutReopeningTheView() {
        var session = new RoomActionSession();
        var closedView = session.intent("cs", "dust");
        closedView.begin(JOIN, SELF, "", false);
        expire(session);
        assertFalse(session.observeDetail("cs", "dust", false, false, Map.of()));
        assertTrue(session.observeDetail("cs", "dust", true, false, Map.of(SELF, "spectator")));
        assertTrue(closedView.idle());
        assertSame(closedView, session.intent("cs", "dust"));
    }

    @Test void roomIdentityIncludesBothExactGameTypeAndInternalMapName() {
        var session = new RoomActionSession();
        var target = session.intent("cs", "dust");
        target.begin(READY, SELF, "", true);
        assertNotSame(target, session.intent("dm", "dust"));
        assertNotSame(target, session.intent("cs", "Dust"));
        assertNotSame(session.intent("a:b", "c"), session.intent("a", "b:c"));
        assertFalse(session.observeReady("dm", "dust", true));
        assertFalse(session.observeDetail("cs", "Dust", true, true, Map.of(SELF, "ct")));
        assertEquals(WAITING, target.phase());
        assertTrue(session.observeReady("cs", "dust", true));
    }

    @Test void resetPermanentlyInvalidatesPendingAndIdleReferencesHeldByOldViews() {
        var session = new RoomActionSession();
        var pendingView = session.intent("cs", "dust");
        var idleView = session.intent("dm", "arena");
        pendingView.begin(READY, SELF, "", true);
        session.reset();
        for (var oldView : new RoomActionIntent[] { pendingView, idleView }) {
            assertEquals(INVALIDATED, oldView.phase());
            assertFalse(oldView.idle());
            assertFalse(oldView.pending());
            assertFalse(oldView.begin(READY, SELF, "", false));
            assertFalse(oldView.observeReady(true));
            assertFalse(oldView.observeDetail(true, true, Map.of(SELF, "ct")));
            assertFalse(oldView.markUnconfirmed());
            assertFalse(oldView.tick());
        }
        var newView = session.intent("cs", "dust");
        assertNotSame(pendingView, newView);
        assertTrue(newView.begin(READY, SELF, "", false));
        assertFalse(pendingView.begin(LEAVE, SELF, "", false));
        assertTrue(newView.is(READY));
    }

    @Test void latePacketsAfterResetDoNotResurrectTheRemovedIntent() {
        var session = new RoomActionSession();
        var oldView = session.intent("cs", "dust");
        oldView.begin(READY, SELF, "", true);
        session.reset();
        assertFalse(session.observeReady("cs", "dust", true));
        assertFalse(session.observeDetail("cs", "dust", true, true, Map.of(SELF, "ct")));
        session.tick();
        assertEquals(INVALIDATED, oldView.phase());
        assertEquals(IDLE, session.intent("cs", "dust").phase());
    }

    @Test void readinessOnlyPacketsCannotReleaseAClosedViewsRosterOperation() {
        var session = new RoomActionSession();
        var closedView = session.intent("cs", "dust");
        closedView.begin(KICK, OTHER, "", false);
        expire(session);
        assertFalse(session.observeReady("cs", "dust", false));
        assertFalse(session.observeReady("cs", "dust", true));
        assertFalse(session.observeDetail("cs", "dust", true, false, Map.of(OTHER, "ct")));
        assertEquals(UNCONFIRMED, closedView.phase());
        assertTrue(session.observeDetail("cs", "dust", true, false, Map.of(SELF, "ct")));
    }

    @Test void allRoomWaitsAgeWhileNoViewsAreOpen() {
        var session = new RoomActionSession();
        var first = session.intent("cs", "dust");
        var second = session.intent("dm", "arena");
        first.begin(JOIN, SELF, "", false);
        second.begin(LEAVE, SELF, "", false);
        for (int tick = 1; tick < RoomActionIntent.TIMEOUT_TICKS; tick++) session.tick();
        assertEquals(WAITING, first.phase());
        assertEquals(WAITING, second.phase());
        session.tick();
        assertEquals(UNCONFIRMED, first.phase());
        assertEquals(UNCONFIRMED, second.phase());
        assertTrue(session.observeDetail("dm", "arena", false, false, Map.of()));
        assertEquals(UNCONFIRMED, first.phase());
        assertTrue(second.idle());
    }

    private static void expire(RoomActionSession session) {
        for (int tick = 0; tick < RoomActionIntent.TIMEOUT_TICKS; tick++) session.tick();
    }
}
