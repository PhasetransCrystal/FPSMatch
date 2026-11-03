package net.ptcrys.fpsmatch.common.client.screen.mapselect.studio;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-thread state for one server session. View lifetime has no effect on
 * outstanding operations. Only observed room state or session reset ends them.
 * A matching observation is not request correlation on the legacy protocol.
 */
public final class RoomActionSession {

    private record Room(String gameType, String mapName) {

        private Room {
            Objects.requireNonNull(gameType, "gameType");
            Objects.requireNonNull(mapName, "mapName");
        }
    }

    private final Map<Room, RoomActionIntent> rooms = new HashMap<>();

    public RoomActionIntent intent(String gameType, String mapName) {
        return rooms.computeIfAbsent(new Room(gameType, mapName), ignored -> new RoomActionIntent());
    }

    /** Observe an existing intent without creating one or asking any view to open. */
    public boolean observeDetail(String gameType, String mapName, boolean member, boolean ready, Map<UUID, String> roster) {
        RoomActionIntent intent = rooms.get(new Room(gameType, mapName));
        return intent != null && intent.observeDetail(member, ready, roster);
    }

    public boolean observeReady(String gameType, String mapName, boolean ready) {
        RoomActionIntent intent = rooms.get(new Room(gameType, mapName));
        return intent != null && intent.observeReady(ready);
    }

    /** Time advances once per client tick, including while no room view is open. */
    public void tick() {
        rooms.values().forEach(RoomActionIntent::tick);
    }

    public void reset() {
        rooms.values().forEach(RoomActionIntent::invalidate);
        rooms.clear();
    }
}
