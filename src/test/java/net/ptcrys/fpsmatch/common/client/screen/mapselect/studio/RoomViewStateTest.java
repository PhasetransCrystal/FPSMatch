package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static net.ptcrys.fpsmatch.common.client.screen.mapselect.studio.RoomActionIntent.Operation.READY;

class RoomViewStateTest {
    private static final UUID SELF = new UUID(0, 1);

    @Test void aNewViewBuiltFromFullDetailCanActOnlyWhenItsSharedIntentIsIdle() {
        var view = new RoomViewState("cs", "dust");
        var intent = new RoomActionIntent();
        assertTrue(view.fullDetailAvailable());
        assertTrue(view.canMutate(intent));
        intent.begin(READY, SELF, "", true);
        assertFalse(view.canMutate(intent));
        assertFalse(new RoomViewState("cs", "dust").canMutate(intent));
    }

    @Test void backgroundCompletionCannotAuthorizeACachedViewUntilFullDetailArrives() {
        var session = new RoomActionSession();
        var intent = session.intent("cs", "dust");
        var cachedView = new RoomViewState("cs", "dust");
        intent.begin(READY, SELF, "", true);
        assertTrue(session.observeReady("cs", "dust", true));
        cachedView.awaitFullDetail();
        assertTrue(intent.idle());
        assertFalse(cachedView.canMutate(intent));
        assertTrue(cachedView.acceptReady("cs", "dust"));
        assertFalse(cachedView.canMutate(intent));
        assertTrue(cachedView.acceptFullDetail("cs", "dust"));
        assertTrue(cachedView.canMutate(intent));
    }

    @Test void onlyAnExactRoomFullDetailCanReleaseTheResumeWait() {
        var view = new RoomViewState("cs", "dust");
        var intent = new RoomActionIntent();
        view.awaitFullDetail();
        assertFalse(view.acceptFullDetail("dm", "dust"));
        assertFalse(view.acceptFullDetail("cs", "Dust"));
        assertFalse(view.acceptReady("cs", "other"));
        assertTrue(view.acceptReady("cs", "dust"));
        assertFalse(view.fullDetailAvailable());
        assertFalse(view.canMutate(intent));
        assertTrue(view.acceptFullDetail("cs", "dust"));
        assertTrue(view.canMutate(intent));
    }

    @Test void eachCachedViewNeedsItsOwnFullDetailWhenRestored() {
        var intent = new RoomActionIntent();
        var first = new RoomViewState("cs", "dust");
        var second = new RoomViewState("cs", "dust");
        first.awaitFullDetail();
        second.awaitFullDetail();
        first.acceptFullDetail("cs", "dust");
        assertTrue(first.canMutate(intent));
        assertFalse(second.canMutate(intent));
        first.awaitFullDetail();
        assertFalse(first.canMutate(intent));
    }

    @Test void refreshingFullDetailDoesNotOverrideAnUnconfirmedOperation() {
        var view = new RoomViewState("cs", "dust");
        var intent = new RoomActionIntent();
        intent.begin(READY, SELF, "", true);
        intent.markUnconfirmed();
        view.awaitFullDetail();
        view.acceptFullDetail("cs", "dust");
        assertTrue(view.fullDetailAvailable());
        assertFalse(view.canMutate(intent));
        assertTrue(intent.observeReady(true));
        assertTrue(view.canMutate(intent));
    }

    @Test void fullDetailCannotReauthorizeAViewFromAResetSession() {
        var session = new RoomActionSession();
        var oldIntent = session.intent("cs", "dust");
        var oldView = new RoomViewState("cs", "dust");
        session.reset();
        oldView.awaitFullDetail();
        oldView.acceptFullDetail("cs", "dust");
        assertFalse(oldView.canMutate(oldIntent));
    }
}
