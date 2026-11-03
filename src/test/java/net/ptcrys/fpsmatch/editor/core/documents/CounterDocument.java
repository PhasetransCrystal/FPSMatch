package net.ptcrys.fpsmatch.editor.core.documents;

import java.util.List;
import java.util.Objects;

/**
 * The second domain-neutral immutable value used by the shared fixtures (see {@link TextDocument}).
 */
public record CounterDocument(List<Long> deltas, String note) {
    public CounterDocument {
        Objects.requireNonNull(deltas, "deltas");
        Objects.requireNonNull(note, "note");
        deltas = List.copyOf(deltas);
    }

    public long total() {
        long total = 0L;
        for (long delta : deltas) {
            total += delta;
        }
        return total;
    }

    public CounterDocument normalized() {
        if (deltas.isEmpty() || deltas.get(deltas.size() - 1) != 0L) {
            return this;
        }
        return new CounterDocument(deltas.subList(0, deltas.size() - 1), note);
    }
}
