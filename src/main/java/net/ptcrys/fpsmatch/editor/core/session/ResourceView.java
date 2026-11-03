package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;

import java.util.Objects;

/** Immutable resource content and the state of its independent editing history. */
public record ResourceView(ResourceToken token, ResourceCatalog content, boolean dirty,
                           boolean canUndo, boolean canRedo, boolean historyTruncated) {

    public ResourceView {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(content, "content");
    }
}
