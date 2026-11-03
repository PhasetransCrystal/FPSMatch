package net.ptcrys.fpsmatch.core;

import java.util.List;
import java.util.Objects;

/** Attempts persistence and every captured cleanup before propagating failures. */
public final class ShutdownCleanup {

    private ShutdownCleanup() {}

    public static void run(Runnable persistence, List<Runnable> cleanup) {
        Objects.requireNonNull(persistence, "persistence");
        List<Runnable> capturedCleanup = List.copyOf(Objects.requireNonNull(cleanup, "cleanup"));

        Throwable failure = null;
        try {
            persistence.run();
        } catch (RuntimeException | Error persistenceFailure) {
            failure = persistenceFailure;
        }

        for (Runnable action : capturedCleanup) {
            try {
                action.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
        }

        if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
        if (failure instanceof Error errorFailure) throw errorFailure;
    }
}
