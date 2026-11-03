package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Objects;

/**
 * Client-local navigation intent. With no current intent, a late reply cannot
 * reopen a closed view. The legacy wire has no request sequence: a new intent
 * for the same room cannot distinguish an earlier reply, even in a new view.
 * Closing or timing out only cancels navigation, never a server-side operation.
 */
public final class MapViewIntent {

    public static final int TIMEOUT_TICKS = 200;

    public enum Request {
        NONE,
        LIST,
        DETAIL,
        ACCEPT_INVITATION
    }

    public record Room(String gameType, String mapName) {

        public Room {
            Objects.requireNonNull(gameType, "gameType");
            Objects.requireNonNull(mapName, "mapName");
        }
    }

    private Object selectionOwner;
    private Object requestOwner;
    private Request request = Request.NONE;
    private Room room;
    private int ticks;

    public void openSelection(Object owner) {
        clear();
        selectionOwner = Objects.requireNonNull(owner, "owner");
        begin(owner, Request.LIST, null);
    }

    public boolean ownsSelection(Object owner) {
        return owner != null && owner == selectionOwner;
    }

    public boolean refreshSelection(Object owner) {
        return ownsSelection(owner) && begin(owner, Request.LIST, null);
    }

    public boolean requestDetail(Object owner, Room target) {
        return ownsSelection(owner) && begin(owner, Request.DETAIL, Objects.requireNonNull(target, "target"));
    }

    public boolean acceptInvitation(Object owner, Room target) {
        return begin(Objects.requireNonNull(owner, "owner"), Request.ACCEPT_INVITATION,
                Objects.requireNonNull(target, "target"));
    }

    private boolean begin(Object owner, Request next, Room target) {
        if (request != Request.NONE) return false;
        requestOwner = owner;
        request = next;
        room = target;
        ticks = 0;
        return true;
    }

    public boolean waiting(Object owner) {
        return owner != null && owner == requestOwner && request != Request.NONE;
    }

    public Request request(Object owner) {
        return waiting(owner) ? request : Request.NONE;
    }

    public void applySelection(Object owner, boolean passive) {
        if (!passive && ownsSelection(owner) && waiting(owner) && request == Request.LIST) cancel(owner);
    }

    public Request consumeDetail(Object owner, Room target) {
        if (!waiting(owner) || !Objects.equals(room, target) || request != Request.DETAIL && request != Request.ACCEPT_INVITATION) return Request.NONE;
        Request accepted = request;
        cancel(owner);
        return accepted;
    }

    public Room pendingRoom(Object owner) {
        return waiting(owner) ? room : null;
    }

    public boolean tick(Object owner) {
        if (!waiting(owner) || ++ticks < TIMEOUT_TICKS) return false;
        cancel(owner);
        return true;
    }

    public boolean cancel(Object owner) {
        if (!waiting(owner)) return false;
        requestOwner = null;
        request = Request.NONE;
        room = null;
        ticks = 0;
        return true;
    }

    public void close(Object owner) {
        cancel(owner);
        if (ownsSelection(owner)) selectionOwner = null;
    }

    public void clear() {
        selectionOwner = null;
        requestOwner = null;
        request = Request.NONE;
        room = null;
        ticks = 0;
    }
}
