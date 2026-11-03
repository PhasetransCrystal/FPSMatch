package net.ptcrys.fpsmatch.editor.core.session;

import net.ptcrys.fpsmatch.editor.api.extensions.ExtensionRegistry;
import net.ptcrys.fpsmatch.editor.core.resources.ResourceCatalog;

import java.util.Objects;
import java.util.Optional;

/**
 * Session-issued editing directory of the content-validated opening snapshot. It carries no I/O
 * or runtime installation authority. Newly added resources require a validated reopen before they
 * enter this directory; immutable versions already captured here never follow currentVersion.
 */
public final class EditorResourceContext {

    private final SessionOwner owner;
    private final ExtensionRegistry registry;
    private final Optional<ResourceCatalog> catalog;

    EditorResourceContext(SessionOwner owner, ExtensionRegistry registry, LoadedProject source) {
        this.owner = Objects.requireNonNull(owner);
        this.registry = Objects.requireNonNull(registry);
        catalog = source.validatedWith(registry) && source.resourceFailure().isEmpty() ? source.resources() : Optional.empty();
    }

    /** Empty means unavailable or unvalidated, never an authenticated empty resource directory. */
    public Optional<ResourceCatalog> catalog() {
        owner.requireReadable();
        return catalog;
    }

    /** Rejects an identically configured but distinct registry, as well as an expired session. */
    public void requireRegistry(ExtensionRegistry expected) {
        owner.requireReadable();
        if (registry != Objects.requireNonNull(expected))
            throw new IllegalArgumentException("editor resource context belongs to another registry");
    }
}
