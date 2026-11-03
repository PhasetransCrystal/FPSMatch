package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapSelectionPresentationTest {
    private static final MapViewIntent.Room ROOM = new MapViewIntent.Room("cs", "alpha");

    @Test void returningFromARoomCannotRetainRoomLoading() {
        var intent = new MapViewIntent();
        var view = new MapSelectionPresentation();
        Object owner = new Object();
        intent.openSelection(owner);
        intent.applySelection(owner, false);
        intent.requestDetail(owner, ROOM);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.LOADING_ROOM, view.status());
        assertEquals(MapViewIntent.Request.DETAIL, intent.consumeDetail(owner, ROOM));
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.IDLE, view.status());
        intent.clear();
        intent.openSelection(owner);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.LOADING_MAPS, view.status());
        intent.applySelection(owner, false);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.IDLE, view.status());
    }

    @Test void explicitRefreshResolvesOnlyWhenTheActiveListReplyArrives() {
        var intent = new MapViewIntent();
        var view = new MapSelectionPresentation();
        Object owner = new Object();
        intent.openSelection(owner);
        intent.applySelection(owner, false);
        assertTrue(intent.refreshSelection(owner));
        view.navigationChanged(intent.request(owner));
        intent.applySelection(owner, true);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.LOADING_MAPS, view.status());
        intent.applySelection(owner, false);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.IDLE, view.status());
        assertEquals("", view.notice());
    }

    @Test void aPassiveSnapshotDoesNotEraseTheCancellationNotice() {
        var intent = new MapViewIntent();
        var view = new MapSelectionPresentation();
        Object owner = new Object();
        intent.openSelection(owner);
        view.navigationChanged(intent.request(owner));
        intent.cancel(owner);
        view.navigationChanged(intent.request(owner));
        view.notice("Stopped waiting", false);
        intent.applySelection(owner, true);
        view.navigationChanged(intent.request(owner));
        assertEquals(MapSelectionPresentation.Status.NOTICE, view.status());
        assertEquals("Stopped waiting", view.notice());
        assertFalse(view.error());
    }

    @Test void retryOwnsItsLoadingAndClearsThePreviousError() {
        var view = new MapSelectionPresentation();
        view.notice("Previous request timed out", true);
        assertTrue(view.error());
        view.navigationChanged(MapViewIntent.Request.LIST);
        assertEquals(MapSelectionPresentation.Status.LOADING_MAPS, view.status());
        assertFalse(view.error());
        view.navigationChanged(MapViewIntent.Request.NONE);
        assertEquals(MapSelectionPresentation.Status.IDLE, view.status());
        assertEquals("", view.notice());
    }

    @Test void timeoutCannotRemainALoadingState() {
        var intent = new MapViewIntent();
        var view = new MapSelectionPresentation();
        Object owner = new Object();
        intent.openSelection(owner);
        view.navigationChanged(intent.request(owner));
        for (int i = 0; i < MapViewIntent.TIMEOUT_TICKS; i++) intent.tick(owner);
        view.navigationChanged(intent.request(owner));
        view.notice("The list did not respond", true);
        assertEquals(MapSelectionPresentation.Status.NOTICE, view.status());
        assertTrue(view.error());
        assertEquals(MapViewIntent.Request.NONE, intent.request(owner));
    }

    @Test void filteredEmptyAndServerEmptyRemainDifferentStates() {
        var view = new MapSelectionPresentation();
        assertEquals(MapSelectionPresentation.EmptyState.NO_MAPS, view.emptyState(0, 0));
        assertEquals(MapSelectionPresentation.EmptyState.NO_MATCHES, view.emptyState(3, 0));
        assertEquals(MapSelectionPresentation.EmptyState.NONE, view.emptyState(3, 1));
        view.navigationChanged(MapViewIntent.Request.LIST);
        assertEquals(MapSelectionPresentation.EmptyState.LOADING, view.emptyState(0, 0));
        assertEquals(MapSelectionPresentation.EmptyState.NO_MATCHES, view.emptyState(3, 0));
        view.navigationChanged(MapViewIntent.Request.NONE);
        assertEquals(MapSelectionPresentation.EmptyState.NO_MAPS, view.emptyState(0, 0));
    }
}
