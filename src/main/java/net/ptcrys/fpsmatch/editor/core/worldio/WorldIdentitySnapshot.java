package net.ptcrys.fpsmatch.editor.core.worldio;

import java.nio.file.Path;
import java.util.UUID;

/** An immutable authenticated local capture, without an open channel or lock. */
public final class WorldIdentitySnapshot {

    private final UUID worldId;
    private final Path root;
    final FileIdentityProvider identities;
    final WorldIdentities.Stamp rootStamp;
    final WorldIdentities.Stamp directoryStamp;
    final WorldIdentities.Stamp fileStamp;
    private final byte[] bytes;

    WorldIdentitySnapshot(UUID worldId, Path root, FileIdentityProvider identities, WorldIdentities.Stamp rootStamp,
                          WorldIdentities.Stamp directoryStamp, WorldIdentities.Stamp fileStamp, byte[] bytes) {
        this.worldId = worldId;
        this.root = root;
        this.identities = identities;
        this.rootStamp = rootStamp;
        this.directoryStamp = directoryStamp;
        this.fileStamp = fileStamp;
        this.bytes = bytes.clone();
    }

    public UUID worldId() {
        return worldId;
    }

    public Path root() {
        return root;
    }

    byte[] bytes() {
        return bytes.clone();
    }
}
