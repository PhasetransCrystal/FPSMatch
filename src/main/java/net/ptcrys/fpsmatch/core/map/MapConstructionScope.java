package net.ptcrys.fpsmatch.core.map;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Tracks synchronous construction resources until ownership can be transferred. */
public final class MapConstructionScope {

    private static final int MAX_ACQUISITIONS = 4096;
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();

    private MapConstructionScope() {}

    public static <T> T construct(Supplier<T> constructor) {
        Objects.requireNonNull(constructor, "constructor");
        if (CURRENT.get() != null) {
            throw new IllegalStateException("A map construction scope is already active on this thread");
        }
        Frame frame = new Frame();
        CURRENT.set(frame);
        try {
            return Objects.requireNonNull(constructor.get(), "constructor result");
        } catch (RuntimeException | Error failure) {
            frame.phase = Phase.ROLLING_BACK;
            frame.rollback(failure);
            throw failure;
        } finally {
            try {
                frame.clear();
            } finally {
                CURRENT.remove();
            }
        }
    }

    public static <T> T acquire(Supplier<T> acquisition, Consumer<? super T> rollback) {
        Objects.requireNonNull(acquisition, "acquisition");
        Objects.requireNonNull(rollback, "rollback");
        Frame frame = CURRENT.get();
        if (frame == null) {
            return Objects.requireNonNull(acquisition.get(), "acquisition result");
        }
        if (frame.phase != Phase.CONSTRUCTING) {
            throw new IllegalStateException("Cannot acquire resources while construction is rolling back");
        }
        if (frame.size == MAX_ACQUISITIONS) {
            throw new IllegalStateException("Map construction acquisition limit exceeded");
        }
        Entry<T> entry = new Entry<>(rollback);
        // Reserve before invoking the supplier, including when it acquires nested resources.
        frame.entries[frame.size++] = entry;
        T resource = Objects.requireNonNull(acquisition.get(), "acquisition result");
        entry.resource = resource;
        return resource;
    }

    public static boolean isConstructing() {
        Frame frame = CURRENT.get();
        return frame != null && frame.phase == Phase.CONSTRUCTING;
    }

    private enum Phase {
        CONSTRUCTING,
        ROLLING_BACK
    }

    private static final class Frame {

        private final Entry<?>[] entries = new Entry<?>[MAX_ACQUISITIONS];
        private int size;
        private Phase phase = Phase.CONSTRUCTING;

        private void rollback(Throwable failure) {
            for (int index = size - 1; index >= 0; index--) {
                try {
                    entries[index].rollback();
                } catch (RuntimeException | Error cleanupFailure) {
                    if (failure != cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }

        private void clear() {
            for (int index = 0; index < size; index++) {
                entries[index].clear();
                entries[index] = null;
            }
            size = 0;
        }
    }

    private static final class Entry<T> {

        private Consumer<? super T> rollback;
        private T resource;

        private Entry(Consumer<? super T> rollback) {
            this.rollback = rollback;
        }

        private void rollback() {
            if (resource != null) {
                rollback.accept(resource);
            }
        }

        private void clear() {
            rollback = null;
            resource = null;
        }
    }
}
