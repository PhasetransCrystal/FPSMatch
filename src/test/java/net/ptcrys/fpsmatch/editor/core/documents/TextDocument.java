package net.ptcrys.fpsmatch.editor.core.documents;

import java.util.List;
import java.util.Objects;

/** The first domain-neutral immutable value used by the shared fixtures. It has no map fields. */
public record TextDocument(List<String> lines, int caret) {
    public TextDocument {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
        if (caret < 0) {
            throw new IllegalArgumentException("caret must be non-negative");
        }
    }

    public String joined() {
        return String.join("\n", lines);
    }
}
