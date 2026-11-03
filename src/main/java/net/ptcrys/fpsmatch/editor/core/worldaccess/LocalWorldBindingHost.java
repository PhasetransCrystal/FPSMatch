package net.ptcrys.fpsmatch.editor.core.worldaccess;

import net.ptcrys.fpsmatch.editor.api.EditorId;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Trusted, explicitly selected local host. Implementations run on their owner. */
public interface LocalWorldBindingHost {

    void requireOwner();

    boolean active();

    Selection capture(UUID actorId, Map<EditorId, EditorId> dimensions);

    interface Selection {

        Path root();

        void verify();
    }
}
