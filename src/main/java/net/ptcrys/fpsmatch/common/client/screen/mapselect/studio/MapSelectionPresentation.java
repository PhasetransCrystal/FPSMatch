package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Objects;

/** Local list presentation; loading is derived from navigation, never saved as a notice. */
public final class MapSelectionPresentation {

    public enum Status {
        IDLE,
        LOADING_MAPS,
        LOADING_ROOM,
        NOTICE
    }

    public enum EmptyState {
        NONE,
        LOADING,
        NO_MAPS,
        NO_MATCHES
    }

    private MapViewIntent.Request request = MapViewIntent.Request.NONE;
    private String notice = "";
    private boolean error;

    public void navigationChanged(MapViewIntent.Request next) {
        Objects.requireNonNull(next, "next");
        if (next != MapViewIntent.Request.NONE && next != request) {
            notice = "";
            error = false;
        }
        request = next;
    }

    public void notice(String text, boolean isError) {
        notice = Objects.requireNonNull(text, "text");
        error = isError;
    }

    public Status status() {
        if (request == MapViewIntent.Request.LIST) return Status.LOADING_MAPS;
        if (request == MapViewIntent.Request.DETAIL) return Status.LOADING_ROOM;
        return notice.isBlank() ? Status.IDLE : Status.NOTICE;
    }

    public String notice() {
        return notice;
    }

    public boolean error() {
        return status() == Status.NOTICE && error;
    }

    public EmptyState emptyState(int totalMaps, int visibleMaps) {
        if (totalMaps < 0 || visibleMaps < 0 || visibleMaps > totalMaps)
            throw new IllegalArgumentException("invalid map counts");
        if (visibleMaps > 0) return EmptyState.NONE;
        if (totalMaps > 0) return EmptyState.NO_MATCHES;
        return request == MapViewIntent.Request.LIST ? EmptyState.LOADING : EmptyState.NO_MAPS;
    }
}
