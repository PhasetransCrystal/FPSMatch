package net.ptcrys.fpsmatch.editor.core.packio;

import java.util.List;
import java.util.Objects;

/** The digest identifies all archive bytes, including the metadata. */
public record PackIndex(List<PackEntry> entries, long contentBytes, String sha256) {

    public PackIndex {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > PackIo.COUNT_LIMIT) {
            throw new IllegalArgumentException("Invalid index count");
        }
        entries = List.copyOf(entries);
        Objects.requireNonNull(sha256, "sha256");
        PackIo.validateHash(sha256);
        PackIo.validateEntries(entries, contentBytes);
    }
}
