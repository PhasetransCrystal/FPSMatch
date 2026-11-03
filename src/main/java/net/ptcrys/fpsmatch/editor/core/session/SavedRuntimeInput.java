package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;
import net.ptcrys.fpsmatch.editor.core.storage.SavedProject;

import java.util.Objects;
import java.util.UUID;

/** Original fixed saved metadata and one decoded, isolated document value. */
public record SavedRuntimeInput<T>(UUID documentId, SavedProject saved,
                                   ResourceCatalog resources, T content) {

    public SavedRuntimeInput {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(content, "content");
    }
}
