package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.Objects;

/** Per-view detail availability; resuming cached data never grants write authority. */
public final class RoomViewState {

    private final String gameType;
    private final String mapName;
    private boolean fullDetailAvailable = true;

    /** A newly constructed room view starts from its received full detail. */
    public RoomViewState(String gameType, String mapName) {
        this.gameType = Objects.requireNonNull(gameType, "gameType");
        this.mapName = Objects.requireNonNull(mapName, "mapName");
    }

    public boolean accepts(String type, String map) {
        return gameType.equals(type) && mapName.equals(map);
    }

    public boolean fullDetailAvailable() {
        return fullDetailAvailable;
    }

    public boolean canMutate(RoomActionIntent intent) {
        return fullDetailAvailable && intent.idle();
    }

    public void awaitFullDetail() {
        fullDetailAvailable = false;
    }

    public boolean acceptFullDetail(String type, String map) {
        if (!accepts(type, map)) return false;
        fullDetailAvailable = true;
        return true;
    }

    /** Partial updates may refresh readiness, but cannot validate the cached roster. */
    public boolean acceptReady(String type, String map) {
        return accepts(type, map);
    }
}
