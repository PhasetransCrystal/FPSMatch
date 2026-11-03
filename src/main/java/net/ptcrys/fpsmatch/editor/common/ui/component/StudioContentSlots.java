package net.ptcrys.fpsmatch.editor.common.ui.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded owner-thread declarations retained only by a successful frame publication.
 * Keys must cover content, bindings and presentation, but may exclude scroll transforms.
 * Values must not perform domain mutations or retain delayed projection reads.
 */
public final class StudioContentSlots<T> {

    public record Stats(int retained, long builds, long reuses, long publications) {}

    private record Entry<T>(Object revision, T value) {}

    private final int limit;
    private Object scope;
    private Map<String, Entry<T>> committed = Map.of();
    private Publication active;
    private long builds, reuses, publications;

    public StudioContentSlots(int limit) {
        if (limit < 1) throw new IllegalArgumentException("positive content slot limit required");
        this.limit = limit;
    }

    public Publication begin(Object nextScope) {
        if (active != null) throw new IllegalStateException("content publication active");
        active = new Publication(Objects.requireNonNull(nextScope));
        return active;
    }

    public final class Publication implements AutoCloseable {

        private final Object nextScope;
        private final Map<String, Entry<T>> base;
        private final LinkedHashMap<String, Entry<T>> next = new LinkedHashMap<>();
        private boolean closed, failed;

        private Publication(Object nextScope) {
            this.nextScope = nextScope;
            base = nextScope.equals(scope) ? committed : Map.of();
        }

        public T content(String slot, Object revision, Supplier<T> builder) {
            requireActive();
            Objects.requireNonNull(slot);
            Objects.requireNonNull(revision);
            Objects.requireNonNull(builder);
            var prior = next.containsKey(slot) ? next.get(slot) : base.get(slot);
            Entry<T> result;
            if (prior != null && prior.revision().equals(revision)) {
                result = prior;
                reuses++;
            } else {
                try {
                    result = new Entry<>(revision, Objects.requireNonNull(builder.get()));
                    builds++;
                } catch (RuntimeException | Error failure) {
                    failed = true;
                    throw failure;
                }
            }
            next.remove(slot);
            next.put(slot, result);
            while (next.size() > limit) next.remove(next.keySet().iterator().next());
            return result.value();
        }

        public void commit() {
            requireActive();
            // All fallible content preparation has already completed.
            committed = next;
            scope = nextScope;
            publications++;
            closed = true;
            active = null;
        }

        private void requireActive() {
            if (closed || active != this || failed) throw new IllegalStateException("content publication is not usable");
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (active == this) active = null;
            }
        }
    }

    public void clear() {
        if (active != null) throw new IllegalStateException("content publication active");
        committed = Map.of();
        scope = null;
    }

    public Stats stats() {
        return new Stats(committed.size(), builds, reuses, publications);
    }
}
