package net.ptcrys.fpsmatch.editor.api.extensions;

import net.ptcrys.fpsmatch.editor.api.EditorId;
import net.ptcrys.fpsmatch.editor.api.registry.ApiVersion;
import net.ptcrys.fpsmatch.editor.api.registry.Contribution;
import net.ptcrys.fpsmatch.editor.api.registry.ContributionKey;
import net.ptcrys.fpsmatch.editor.api.registry.ModuleSpec;
import net.ptcrys.fpsmatch.editor.api.registry.RegistryBuilder;
import net.ptcrys.fpsmatch.editor.api.registry.RegistrySnapshot;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Collects extension kinds, modules, contributions and their real implementations, then freezes them
 * into one {@link ExtensionRegistry}.
 *
 * <p>
 * The builder owns exactly one {@link RegistryBuilder}: module and contribution budgets,
 * duplicate detection, dependency resolution, side filtering and diagnostics stay owned by the
 * registry, and no already frozen snapshot can be spliced in. There is deliberately no way to add a
 * contribution without an implementation, so every contribution the registry accepts at freeze time
 * has a real implementation attached to it.
 * </p>
 *
 * <p>
 * Developer contract errors (an undefined kind, a kind whose value type disagrees with the
 * definition, a contribution whose metadata names another kind, a null argument, too many kinds)
 * are rejected atomically with {@link IllegalArgumentException} or {@link NullPointerException}
 * before anything is handed to the registry. Availability problems of already collected
 * contributions are reported by the registry's own problems and language keys instead.
 * </p>
 */
public final class ExtensionRegistryBuilder {

    /** Hard bound on distinct extension kinds; checked before a new kind is stored. */
    static final int MAX_KINDS = 256;

    private final RegistryBuilder registry;
    private final Map<EditorId, ExtensionKind<?>> kinds = new TreeMap<>();
    private final Map<ContributionKey, Object> implementations = new TreeMap<>();
    private boolean frozen;

    /**
     * @param api         the API level this registry provides
     * @param environment the publication side of this registry, {@code COMMON} on a dedicated server
     */
    public ExtensionRegistryBuilder(ApiVersion api, Contribution.Side environment) {
        this.registry = new RegistryBuilder(api, environment);
    }

    /**
     * Declares one extension point contract.
     *
     * <p>
     * Defining the same id with an identical {@link Class} is idempotent; defining it with a
     * different class is a programming error that rejects this call alone, keeps the first contract
     * intact and never silently selects the last declaration. At most {@value #MAX_KINDS} distinct
     * kinds are accepted, and the bound is checked before the kind is stored.
     * </p>
     */
    public void define(ExtensionKind<?> kind) {
        requireOpen();
        Objects.requireNonNull(kind, "kind");
        ExtensionKind<?> existing = kinds.get(kind.id());
        if (existing != null) {
            if (existing.valueType() == kind.valueType()) {
                return;
            }
            throw new IllegalArgumentException("extension kind " + kind.id() + " is already defined for " + existing.valueType().getName());
        }
        if (kinds.size() >= MAX_KINDS) {
            throw new IllegalArgumentException(
                    "extension registry is limited to " + MAX_KINDS + " distinct kinds");
        }
        kinds.put(kind.id(), kind);
    }

    /** Offers one module to the underlying registry, which owns every module rule. */
    public void addModule(ModuleSpec module) {
        requireOpen();
        Objects.requireNonNull(module, "module");
        registry.addModule(module);
    }

    /**
     * Publishes one real implementation under the metadata key of {@code metadata}.
     *
     * <p>
     * The kind must already be defined, must still carry the identical value type class, and
     * {@code metadata.key().kind()} must name that kind; {@code implementation} must be non-null and
     * an instance of the value type. Only after every check passed is the metadata handed to the
     * registry and the implementation stored, so a rejected call never leaves a half-registered
     * contribution behind. No plugin callback runs here or later.
     * </p>
     */
    public <T> void add(ExtensionKind<T> kind, Contribution metadata, T implementation) {
        requireOpen();
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(implementation, "implementation");
        ExtensionKind<?> defined = kinds.get(kind.id());
        if (defined == null) {
            throw new IllegalArgumentException("extension kind is not defined: " + kind.id());
        }
        if (defined.valueType() != kind.valueType()) {
            throw new IllegalArgumentException("extension kind " + kind.id() + " is defined for " + defined.valueType().getName() + ", not " + kind.valueType().getName());
        }
        if (!kind.id().equals(metadata.key().kind())) {
            throw new IllegalArgumentException("contribution " + metadata.key() + " does not belong to extension kind " + kind.id());
        }
        if (!kind.valueType().isInstance(implementation)) {
            throw new IllegalArgumentException("implementation for " + metadata.key() + " is not an instance of " + kind.valueType().getName());
        }
        registry.add(metadata);
        implementations.put(metadata.key(), implementation);
    }

    /**
     * Freezes the metadata once and binds implementations only for the contributions the registry
     * reports as usable, so a duplicate contribution makes every record of that conflict unavailable
     * instead of quietly publishing whichever implementation happened to be stored first. The
     * returned registry keeps its own copies and never observes later builder or input changes.
     *
     * @throws IllegalStateException if this builder was already frozen
     */
    public ExtensionRegistry freeze() {
        requireOpen();
        frozen = true;
        RegistrySnapshot metadata = registry.freeze();
        Map<ContributionKey, Object> published = new TreeMap<>();
        for (ContributionKey key : metadata.contributions().keySet()) {
            // Every accepted contribution arrived through add(...), which stores the implementation
            // only after the metadata was collected; a missing one would be an internal fault.
            published.put(key, Objects.requireNonNull(implementations.get(key),
                    "accepted contribution without an implementation: " + key));
        }
        return new ExtensionRegistry(metadata, kinds, published);
    }

    /**
     * Permanently closes this builder without freezing or invoking extensions. This operation is
     * idempotent and releases collected kind and implementation references. A registry already
     * returned by {@link #freeze()} owns its copies and is unaffected.
     */
    public void discard() {
        frozen = true;
        kinds.clear();
        implementations.clear();
    }

    private void requireOpen() {
        if (frozen) {
            throw new IllegalStateException("extension registry builder is already frozen");
        }
    }
}
