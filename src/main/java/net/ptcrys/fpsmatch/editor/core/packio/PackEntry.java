package net.ptcrys.fpsmatch.editor.core.packio;

import java.util.Objects;

/** A validated, immutable file descriptor. */
public record PackEntry(String path, long size, String sha256) {

    public PackEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        PackIo.validatePath(path);
        if (size < 0 || size > PackIo.LIMIT) throw new IllegalArgumentException("Invalid size");
        PackIo.validateHash(sha256);
    }
}
