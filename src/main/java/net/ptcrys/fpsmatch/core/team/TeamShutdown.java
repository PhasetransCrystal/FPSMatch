package net.ptcrys.fpsmatch.core.team;

import net.ptcrys.fpsmatch.core.ShutdownCleanup;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.List;
import java.util.Objects;

/** Final team cleanup preserves a replacement scoreboard object owned by someone else. */
final class TeamShutdown {

    private TeamShutdown() {}

    static void run(Runnable closeCapabilities, Scoreboard scoreboard, PlayerTeam owned) {
        Objects.requireNonNull(closeCapabilities, "closeCapabilities");
        Objects.requireNonNull(scoreboard, "scoreboard");
        Objects.requireNonNull(owned, "owned");
        String name = owned.getName();
        ShutdownCleanup.run(closeCapabilities, List.of(() -> {
            PlayerTeam current = scoreboard.getPlayerTeam(name);
            if (current == owned) {
                scoreboard.removePlayerTeam(owned);
            } else if (current != null) {
                throw new IllegalStateException("Scoreboard team was replaced before cleanup: " + name);
            }
        }));
    }
}
