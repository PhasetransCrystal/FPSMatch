package net.ptcrys.fpsmatch.editor.core.storage;

import java.util.Objects;

/** A fixed byte version, distinct from the stable identity of a document or asset. */
public record BlobRef(Kind kind, String sha256, long size) {

    public enum Kind {

        DOCUMENT("documents"),
        ASSET("assets");

        private final String directory;

        Kind(String directory) {
            this.directory = directory;
        }

        String directory() {
            return directory;
        }
    }

    public BlobRef {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sha256, "sha256");
        if (!sha256.matches("[0-9a-f]{64}") || size < 0) {
            throw new IllegalArgumentException("invalid content reference");
        }
    }

    String relativePath() {
        return kind.directory() + "/" + sha256 + ".blob";
    }
}
