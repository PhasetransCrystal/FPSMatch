package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.core.worldio.WorldIdentitySnapshot;

/** Opaque successful read. Only the issuing controller can accept it. */
public final class WorldBindingRead {

    final WorldBindingRequest request;
    final WorldIdentitySnapshot snapshot;

    WorldBindingRead(WorldBindingRequest request, WorldIdentitySnapshot snapshot) {
        this.request = request;
        this.snapshot = snapshot;
    }
}
