package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MapViewIntentTest {
    private static final MapViewIntent.Room A = new MapViewIntent.Room("cs", "alpha");
    private static final MapViewIntent.Room B = new MapViewIntent.Room("cs", "beta");

    @Test
    void unsolicitedAndDuplicateDetailsNeverCreateNavigation() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, A));
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        assertEquals(MapViewIntent.Request.DETAIL, state.consumeDetail(view, A));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, A));
    }

    @Test
    void passiveListDoesNotAcknowledgeAnActiveRefresh() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        state.openSelection(view);
        state.applySelection(view, true);
        assertTrue(state.waiting(view));
        state.applySelection(view, false);
        assertFalse(state.waiting(view));
        assertTrue(state.ownsSelection(view));
    }

    @Test
    void roomIdentityIncludesBothModeAndInternalName() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, new MapViewIntent.Room("csdm", "alpha")));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, B));
        assertTrue(state.waiting(view));
        assertEquals(MapViewIntent.Request.DETAIL, state.consumeDetail(view, new MapViewIntent.Room("cs", "alpha")));
    }

    @Test
    void screenIdentityIsNotObjectEquality() {
        MapViewIntent state = new MapViewIntent();
        Object view = new String("same"), other = new String("same");
        openReadyList(state, view);
        assertFalse(state.ownsSelection(other));
        assertFalse(state.requestDetail(other, A));
        assertTrue(state.requestDetail(view, A));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(other, A));
        assertTrue(state.waiting(view));
    }

    @Test
    void closingAnOwnerPreventsLateRepliesAndRefreshes() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        state.close(view);
        assertFalse(state.waiting(view));
        assertFalse(state.ownsSelection(view));
        assertFalse(state.refreshSelection(view));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, A));
    }

    @Test
    void reopeningCreatesNewOwnershipAndDropsPreviousRequest() {
        MapViewIntent state = new MapViewIntent();
        Object oldView = new Object(), nextView = new Object();
        openReadyList(state, oldView);
        assertTrue(state.requestDetail(oldView, A));
        state.openSelection(nextView);
        assertFalse(state.ownsSelection(oldView));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(oldView, A));
        state.applySelection(oldView, false);
        assertTrue(state.waiting(nextView));
        state.applySelection(nextView, false);
        assertTrue(state.requestDetail(nextView, B));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(nextView, A));
        assertEquals(MapViewIntent.Request.DETAIL, state.consumeDetail(nextView, B));
    }

    @Test
    void cancellingReadRetainsListAndAllowsExplicitRefresh() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        assertTrue(state.cancel(view));
        assertTrue(state.ownsSelection(view));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, A));
        assertTrue(state.refreshSelection(view));
    }

    @Test
    void concurrentRequestsAreRejectedInsteadOfOverwritingIntent() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        assertFalse(state.requestDetail(view, B));
        assertFalse(state.refreshSelection(view));
        assertFalse(state.acceptInvitation(new Object(), B));
        assertEquals(A, state.pendingRoom(view));
    }

    @Test
    void timeoutIsBoundedByOwnerTicksAndReportedOnlyOnce() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object(), other = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        for (int i = 0; i < 500; i++) assertFalse(state.tick(other));
        for (int i = 1; i < MapViewIntent.TIMEOUT_TICKS; i++) assertFalse(state.tick(view));
        assertTrue(state.tick(view));
        assertFalse(state.tick(view));
        assertFalse(state.waiting(view));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(view, A));
    }

    @Test
    void invitationAcceptanceRequiresItsOwnOwnerAndRoom() {
        MapViewIntent state = new MapViewIntent();
        Object invitation = new Object();
        assertTrue(state.acceptInvitation(invitation, A));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(new Object(), A));
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(invitation, B));
        assertEquals(MapViewIntent.Request.ACCEPT_INVITATION, state.consumeDetail(invitation, A));
        assertFalse(state.waiting(invitation));
    }

    @Test
    void invitationCloseAndSessionResetInvalidateNavigation() {
        MapViewIntent state = new MapViewIntent();
        Object invitation = new Object();
        assertTrue(state.acceptInvitation(invitation, A));
        state.close(invitation);
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(invitation, A));
        assertTrue(state.acceptInvitation(invitation, B));
        state.clear();
        assertEquals(MapViewIntent.Request.NONE, state.consumeDetail(invitation, B));
    }

    @Test
    void sameRoomRetryHasOnlyLocalIntentNotWireRequestCorrelation() {
        MapViewIntent state = new MapViewIntent();
        Object view = new Object();
        openReadyList(state, view);
        assertTrue(state.requestDetail(view, A));
        state.cancel(view);
        assertTrue(state.requestDetail(view, A));
        // There is no wire request id: an A reply satisfies the current A intent.
        // This assertion records the boundary rather than claiming stale detection.
        assertEquals(MapViewIntent.Request.DETAIL, state.consumeDetail(view, A));
    }

    @Test
    void sameRoomInANewViewStillCannotIdentifyWhichRequestReplied() {
        MapViewIntent state = new MapViewIntent();
        Object oldView = new Object(), nextView = new Object();
        openReadyList(state, oldView);
        assertTrue(state.requestDetail(oldView, A));
        state.close(oldView);
        openReadyList(state, nextView);
        assertTrue(state.requestDetail(nextView, A));
        // The router only knows the currently displayed owner; packets carry
        // no oldView/nextView identity, so either A response satisfies this intent.
        assertEquals(MapViewIntent.Request.DETAIL, state.consumeDetail(nextView, A));
    }

    private static void openReadyList(MapViewIntent state, Object owner) {
        state.openSelection(owner);
        state.applySelection(owner, false);
    }
}
