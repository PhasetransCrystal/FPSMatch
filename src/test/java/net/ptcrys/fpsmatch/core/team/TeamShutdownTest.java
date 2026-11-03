package net.ptcrys.fpsmatch.core.team;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TeamShutdownTest {
    @Test
    void closesCapabilitiesBeforeRemovingTheActualOwnedTeamAndAllowsRepeatedRelease() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("owned");
        PlayerTeam unrelated = scoreboard.addPlayerTeam("unrelated");
        AtomicInteger closes = new AtomicInteger();

        TeamShutdown.run(() -> {
            assertSame(owned, scoreboard.getPlayerTeam("owned"));
            closes.incrementAndGet();
        }, scoreboard, owned);

        assertEquals(1, closes.get());
        assertNull(scoreboard.getPlayerTeam("owned"));
        assertSame(unrelated, scoreboard.getPlayerTeam("unrelated"));
        assertDoesNotThrow(() -> TeamShutdown.run(closes::incrementAndGet, scoreboard, owned));
        assertEquals(2, closes.get(), "the capability container owns its own close idempotence");
    }

    @Test
    void anAlreadyMissingTeamStillClosesCapabilities() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("missing");
        scoreboard.removePlayerTeam(owned);
        AtomicInteger closes = new AtomicInteger();

        assertDoesNotThrow(() -> TeamShutdown.run(closes::incrementAndGet, scoreboard, owned));
        assertEquals(1, closes.get());
        assertNull(scoreboard.getPlayerTeam("missing"));
    }

    @Test
    void anExternalSameNameReplacementIsPreservedAndReported() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("replaced");
        scoreboard.removePlayerTeam(owned);
        PlayerTeam replacement = scoreboard.addPlayerTeam("replaced");
        scoreboard.addPlayerToTeam("external-member", replacement);
        AtomicInteger closes = new AtomicInteger();

        assertThrows(IllegalStateException.class,
                () -> TeamShutdown.run(closes::incrementAndGet, scoreboard, owned));

        assertEquals(1, closes.get());
        assertSame(replacement, scoreboard.getPlayerTeam("replaced"));
        assertSame(replacement, scoreboard.getPlayersTeam("external-member"));
    }

    @Test
    void replacementCreatedByTheCapabilityCallbackIsAlsoPreserved() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("callback-swap");
        PlayerTeam[] replacement = new PlayerTeam[1];

        assertThrows(IllegalStateException.class, () -> TeamShutdown.run(() -> {
            scoreboard.removePlayerTeam(owned);
            replacement[0] = scoreboard.addPlayerTeam(owned.getName());
        }, scoreboard, owned));

        assertNotSame(owned, replacement[0]);
        assertSame(replacement[0], scoreboard.getPlayerTeam(owned.getName()));
    }

    @Test
    void runtimeOrErrorFromCapabilityCloseDoesNotSkipOwnedTeamRemoval() {
        assertCloseFailureStillRemoves(new IllegalStateException("capability"));
        assertCloseFailureStillRemoves(new AssertionError("capability"));
    }

    private static void assertCloseFailureStillRemoves(Throwable failure) {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("failed-close");
        assertSame(failure, assertThrows(Throwable.class, () -> TeamShutdown.run(
                () -> throwUnchecked(failure), scoreboard, owned)));
        assertNull(scoreboard.getPlayerTeam(owned.getName()));
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void capabilityFailureRemainsPrimaryWhenScoreboardOwnershipAlsoFails() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("both-fail");
        scoreboard.removePlayerTeam(owned);
        PlayerTeam replacement = scoreboard.addPlayerTeam(owned.getName());
        AssertionError primary = new AssertionError("capability");

        assertSame(primary, assertThrows(AssertionError.class,
                () -> TeamShutdown.run(() -> { throw primary; }, scoreboard, owned)));

        assertSame(replacement, scoreboard.getPlayerTeam(owned.getName()));
        assertEquals(1, primary.getSuppressed().length);
        assertInstanceOf(IllegalStateException.class, primary.getSuppressed()[0]);
    }

    @Test
    void allNullParametersAreValidatedBeforeAnyCleanupOrScoreboardMutation() {
        Scoreboard scoreboard = new Scoreboard();
        PlayerTeam owned = scoreboard.addPlayerTeam("validate-first");
        AtomicInteger closes = new AtomicInteger();

        assertThrows(NullPointerException.class, () -> TeamShutdown.run(null, scoreboard, owned));
        assertThrows(NullPointerException.class,
                () -> TeamShutdown.run(closes::incrementAndGet, null, owned));
        assertThrows(NullPointerException.class,
                () -> TeamShutdown.run(closes::incrementAndGet, scoreboard, null));

        assertEquals(0, closes.get());
        assertSame(owned, scoreboard.getPlayerTeam(owned.getName()));
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError(failure);
    }
}
