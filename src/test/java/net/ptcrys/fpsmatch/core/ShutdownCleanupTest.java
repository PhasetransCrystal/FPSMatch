package net.ptcrys.fpsmatch.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShutdownCleanupTest {
    @Test
    void successfulPersistencePrecedesEveryCleanupAndEmptyCleanupIsAllowed() {
        List<String> calls = new ArrayList<>();
        assertDoesNotThrow(() -> ShutdownCleanup.run(() -> calls.add("save"), List.of(
                () -> calls.add("first-map"),
                () -> calls.add("second-map"))));
        assertEquals(List.of("save", "first-map", "second-map"), calls);

        calls.clear();
        assertDoesNotThrow(() -> ShutdownCleanup.run(() -> calls.add("save"), List.of()));
        assertEquals(List.of("save"), calls);
    }

    @Test
    void runtimePersistenceFailureStillAttemptsAllMapsAndRetainsFailureOrder() {
        assertPersistenceFailure(new IllegalStateException("save"));
    }

    @Test
    void persistenceErrorStillAttemptsAllMapsAndRetainsTheOriginalError() {
        assertPersistenceFailure(new AssertionError("save"));
    }

    private static void assertPersistenceFailure(Throwable primary) {
        List<String> calls = new ArrayList<>();
        var firstCleanupFailure = new AssertionError("first map");
        var lastCleanupFailure = new IllegalArgumentException("last map");

        Throwable caught = assertThrows(Throwable.class, () -> ShutdownCleanup.run(() -> {
            calls.add("save");
            throwUnchecked(primary);
        }, List.of(() -> {
            calls.add("first-map");
            throw firstCleanupFailure;
        }, () -> calls.add("middle-map"), () -> {
            calls.add("last-map");
            throw lastCleanupFailure;
        })));

        assertSame(primary, caught);
        assertEquals(List.of("save", "first-map", "middle-map", "last-map"), calls);
        assertArrayEquals(new Throwable[]{firstCleanupFailure, lastCleanupFailure}, caught.getSuppressed());
    }

    @Test
    void firstCleanupFailureBecomesPrimaryAfterSuccessfulPersistenceAndLaterMapsStillRun() {
        assertCleanupFailure(new IllegalStateException("first map"));
        assertCleanupFailure(new AssertionError("first map"));
    }

    private static void assertCleanupFailure(Throwable primary) {
        List<String> calls = new ArrayList<>();
        var laterFailure = new AssertionError("later map");
        Throwable caught = assertThrows(Throwable.class, () -> ShutdownCleanup.run(
                () -> calls.add("save"), List.of(() -> {
                    calls.add("first-map");
                    throwUnchecked(primary);
                }, () -> {
                    calls.add("second-map");
                    throw laterFailure;
                }, () -> calls.add("last-map"))));

        assertSame(primary, caught);
        assertEquals(List.of("save", "first-map", "second-map", "last-map"), calls);
        assertArrayEquals(new Throwable[]{laterFailure}, caught.getSuppressed());
    }

    @Test
    void repeatedFailureInstanceIsNotSuppressedOntoItselfOrAllowedToAbortLaterCleanup() {
        var shared = new AssertionError("shared failure");
        var distinct = new IllegalStateException("other map");
        List<String> calls = new ArrayList<>();
        assertSame(shared, assertThrows(AssertionError.class, () -> ShutdownCleanup.run(() -> {
            calls.add("save");
            throw shared;
        }, List.of(() -> {
            calls.add("shared-map");
            throw shared;
        }, () -> {
            calls.add("distinct-map");
            throw distinct;
        }, () -> calls.add("last-map")))));

        assertEquals(List.of("save", "shared-map", "distinct-map", "last-map"), calls);
        assertArrayEquals(new Throwable[]{distinct}, shared.getSuppressed());
    }

    @Test
    void persistenceAndCleanupMutationsCannotReplaceTheCapturedMapCallbacks() {
        List<String> calls = new ArrayList<>();
        List<Runnable> cleanup = new ArrayList<>();
        cleanup.add(() -> {
            calls.add("first-map");
            cleanup.clear();
            cleanup.add(() -> calls.add("cleanup-replacement"));
        });
        cleanup.add(() -> calls.add("second-map"));

        assertDoesNotThrow(() -> ShutdownCleanup.run(() -> {
            calls.add("save");
            cleanup.clear();
            cleanup.add(() -> calls.add("save-replacement"));
        }, cleanup));

        assertEquals(List.of("save", "first-map", "second-map"), calls);
        assertEquals(1, cleanup.size(), "the callbacks really did mutate the caller's list");
    }

    @Test
    void nullParametersAndALateNullMapAreRejectedBeforeAnyCallbackRuns() {
        List<String> calls = new ArrayList<>();
        Runnable save = () -> calls.add("save");
        Runnable map = () -> calls.add("map");

        assertThrows(NullPointerException.class, () -> ShutdownCleanup.run(null, List.of(map)));
        assertThrows(NullPointerException.class, () -> ShutdownCleanup.run(save, null));
        assertThrows(NullPointerException.class,
                () -> ShutdownCleanup.run(save, Arrays.asList(map, map, null)));

        assertTrue(calls.isEmpty(), "validation must finish before persistence or any map cleanup");
    }

    @Test
    void lifetimeClosesLastAfterCombinedFailuresAndNeverRepeatsActionsDuringOrAfterReentry() {
        var lifetime = new ServerLifetime<>(new Object(), () -> true);
        List<String> calls = new ArrayList<>();
        var saveFailure = new IllegalStateException("save");
        var firstMapFailure = new AssertionError("first map");
        var secondMapFailure = new IllegalArgumentException("second map");
        var closeFailure = new AssertionError("close");
        Runnable forbiddenSave = () -> calls.add("unexpected-save");
        Runnable forbiddenClose = () -> calls.add("unexpected-close");
        Runnable close = () -> {
            calls.add("close");
            lifetime.stopping(forbiddenSave, forbiddenClose);
            lifetime.stopped(forbiddenClose);
            assertFalse(lifetime.terminalCleanupFinished(), "close is still running");
            throw closeFailure;
        };
        Runnable shutdown = () -> ShutdownCleanup.run(() -> {
            calls.add("save");
            assertFalse(lifetime.active());
            lifetime.stopping(forbiddenSave, forbiddenClose);
            lifetime.stopped(forbiddenClose);
            assertEquals(List.of("save"), calls, "reentrant Stopped must defer close");
            throw saveFailure;
        }, List.of(() -> {
            calls.add("first-map");
            lifetime.stopping(forbiddenSave, forbiddenClose);
            lifetime.stopped(forbiddenClose);
            assertFalse(lifetime.terminalCleanupFinished(), "map cleanup is still running");
            throw firstMapFailure;
        }, () -> {
            calls.add("second-map");
            throw secondMapFailure;
        }, () -> calls.add("last-map")));

        assertSame(saveFailure,
                assertThrows(IllegalStateException.class, () -> lifetime.stopping(shutdown, close)));
        assertEquals(List.of("save", "first-map", "second-map", "last-map", "close"), calls);
        assertArrayEquals(new Throwable[]{firstMapFailure, secondMapFailure, closeFailure},
                saveFailure.getSuppressed());
        assertFalse(lifetime.active());
        assertTrue(lifetime.terminalCleanupFinished());

        assertDoesNotThrow(() -> {
            lifetime.stopping(shutdown, close);
            lifetime.stopped(close);
            lifetime.stopping(shutdown, close);
            lifetime.stopped(close);
        });
        assertEquals(List.of("save", "first-map", "second-map", "last-map", "close"), calls);
        assertArrayEquals(new Throwable[]{firstMapFailure, secondMapFailure, closeFailure},
                saveFailure.getSuppressed());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        throw new AssertionError("test failure must be unchecked", failure);
    }
}
