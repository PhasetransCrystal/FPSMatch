package net.ptcrys.fpsmatch.editor.api.registry;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.util.Objects;

/** Identifies one contribution; the pair (kind, id) is unique across the registry. */
public record ContributionKey(EditorId kind, EditorId id) implements Comparable<ContributionKey> {

    public ContributionKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
    }

    @Override
    public String toString() {
        return kind + "#" + id;
    }

    @Override
    public int compareTo(ContributionKey other) {
        int byKind = kind.compareTo(other.kind);
        return byKind != 0 ? byKind : id.compareTo(other.id);
    }
}
